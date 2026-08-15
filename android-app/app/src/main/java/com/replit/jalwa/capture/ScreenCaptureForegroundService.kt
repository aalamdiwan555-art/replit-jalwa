package com.replit.jalwa.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.graphics.Rect
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.replit.jalwa.MainActivity
import com.replit.jalwa.R
import com.replit.jalwa.actions.ActionController
import com.replit.jalwa.data.AppDatabase
import com.replit.jalwa.data.ClickHistoryEntity
import com.replit.jalwa.data.SubscriptionManager
import com.replit.jalwa.data.TemplateEntity
import com.replit.jalwa.data.TemplateStore
import com.replit.jalwa.detection.DetectionState
import com.replit.jalwa.detection.DetectionStatus
import com.replit.jalwa.detection.DetectionRuntime
import com.replit.jalwa.detection.ImageDetectionEngine
import com.replit.jalwa.detection.ScreenFrameProcessor
import com.replit.jalwa.detection.TestAction
import com.replit.jalwa.overlay.FloatingOverlayService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenCaptureForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var processor: ScreenFrameProcessor? = null
    private var captureManager: ScreenCaptureManager? = null
    private var engine: ImageDetectionEngine? = null
    private var actionController: ActionController? = null
    private var actionJob: Job? = null
    private var startJob: Job? = null
    private var sessionGeneration = 0L
    private var currentStatus = DetectionStatus()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_PAUSE -> pauseSession()
            ACTION_RESUME -> resumeSession()
            ACTION_STOP -> {
                stopSession()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession()
        serviceScope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(intent: Intent) {
        stopSession()
        val generation = ++sessionGeneration
        startJob = serviceScope.launch {
            val request = withContext(Dispatchers.IO) { authorizeStart(intent) }
            if (generation != sessionGeneration || request == null) {
                request?.reference?.recycleIfNeeded()
                if (generation == sessionGeneration) publishError("Capture request was not authorized")
                return@launch
            }
            initializeSession(request, generation)
        }
    }

    private suspend fun authorizeStart(intent: Intent): AuthorizedSession? {
        val permissionCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val permissionData = intent.intentExtra(EXTRA_RESULT_DATA) ?: return null
        if (permissionCode != android.app.Activity.RESULT_OK) return null

        val userId = intent.getLongExtra(EXTRA_USER_ID, 0L)
        val templateId = intent.getLongExtra(EXTRA_TEMPLATE_ID, 0L)
        val database = AppDatabase.get(applicationContext)
        val user = database.userDao().findById(userId) ?: return null
        if (!SubscriptionManager.isSubscriptionActive(user)) return null

        val template = database.templateDao().findById(templateId) ?: return null
        if (!template.enabled) return null
        val store = TemplateStore(applicationContext)
        val templateFile = runCatching { store.open(template.internalFilename) }.getOrNull()
            ?: return null
        if (!templateFile.isFile) return null
        val reference = android.graphics.BitmapFactory.decodeFile(templateFile.absolutePath)
            ?: return null
        val action = intent.getStringExtra(EXTRA_ACTION)
            ?.let { runCatching { TestAction.valueOf(it) }.getOrDefault(TestAction.NONE) }
            ?: TestAction.NONE
        val detectionRegion = if (template.detectionRegion.isNullOrBlank()) {
            null
        } else {
            parseRegion(template.detectionRegion) ?: run {
                reference.recycleIfNeeded()
                return null
            }
        }
        return AuthorizedSession(
            permissionCode = permissionCode,
            permissionData = permissionData,
            reference = reference,
            template = template,
            detectionRegion = detectionRegion,
            action = action,
            userId = user.id,
        )
    }

    private fun initializeSession(request: AuthorizedSession, generation: Long) {
        if (generation != sessionGeneration) {
            request.reference.recycleIfNeeded()
            return
        }
        val action = request.action
        val userId = request.userId
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                0
            },
        )
        FloatingOverlayService.start(this)

        actionController = ActionController(
            onStatus = { message -> publishStatus(DetectionStatus(DetectionState.ACTION_PENDING, message = message)) },
            onRecorded = { result, triggeredAction ->
                serviceScope.launch(Dispatchers.IO) {
                    AppDatabase.get(applicationContext).clickHistoryDao().insert(
                        ClickHistoryEntity(
                            userId = userId,
                            templateName = request.template.name,
                            action = triggeredAction.name,
                            confidence = result.confidence,
                        ),
                    )
                }
            },
        )
        val detectionEngine = ImageDetectionEngine(
            scope = serviceScope,
            onStatus = ::publishStatus,
            onMatch = { result ->
                actionJob?.cancel()
                actionJob = serviceScope.launch {
                    actionController?.execute(result, action)
                }
            },
        )
        engine = detectionEngine
        detectionEngine.start(
            request.reference,
            ImageDetectionEngine.Configuration(
                threshold = request.template.threshold,
                regionOfInterest = null,
                action = action,
            ),
        )
        val frameProcessor = ScreenFrameProcessor(detectionEngine)
        processor = frameProcessor
        captureManager = ScreenCaptureManager(
            context = applicationContext,
            processor = frameProcessor,
            onState = { state, message ->
                publishStatus(DetectionStatus(state, message = message))
            },
            onCaptureSize = { sourceWidth, sourceHeight, captureWidth, captureHeight ->
                detectionEngine.updateRegionOfInterest(
                    scaleRegion(
                        request.detectionRegion,
                        sourceWidth,
                        sourceHeight,
                        captureWidth,
                        captureHeight,
                    ),
                )
            },
        ).also { it.startCapture(request.permissionCode, request.permissionData) }
    }

    private fun pauseSession() {
        processor?.pause()
        captureManager?.pauseCapture()
        publishStatus(DetectionStatus(DetectionState.PAUSED, message = "Paused"))
    }

    private fun resumeSession() {
        processor?.resume()
        captureManager?.resumeCapture()
        publishStatus(DetectionStatus(DetectionState.SEARCHING, message = "Searching in memory"))
    }

    private fun stopSession() {
        startJob?.cancel()
        startJob = null
        actionJob?.cancel()
        actionJob = null
        processor?.stop()
        processor = null
        captureManager?.stopCapture()
        captureManager = null
        engine = null
        actionController = null
        FloatingOverlayService.stop(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        currentStatus = DetectionStatus(DetectionState.STOPPED, message = "Stopped by the user")
    }

    private fun publishError(message: String) {
        publishStatus(DetectionStatus(DetectionState.ERROR, message = message))
        stopSession()
        stopSelf()
    }

    private fun publishStatus(status: DetectionStatus) {
        currentStatus = status
        DetectionRuntime.publish(status)
        FloatingOverlayService.publish(status)
    }

    private data class AuthorizedSession(
        val permissionCode: Int,
        val permissionData: Intent,
        val reference: android.graphics.Bitmap,
        val template: TemplateEntity,
        val detectionRegion: Rect?,
        val action: TestAction,
        val userId: Long,
    )

    private fun parseRegion(value: String?): Rect? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(",").map { it.trim().toIntOrNull() }
        if (parts.size != 4 || parts.any { it == null }) return null
        val (left, top, right, bottom) = parts.filterNotNull()
        return Rect(left, top, right, bottom).takeIf {
            left >= 0 && top >= 0 && right > left && bottom > top
        }
    }

    private fun scaleRegion(
        source: Rect?,
        sourceWidth: Int,
        sourceHeight: Int,
        captureWidth: Int,
        captureHeight: Int,
    ): Rect? {
        if (source == null) return null
        if (
            source.left < 0 || source.top < 0 ||
            source.right > sourceWidth || source.bottom > sourceHeight ||
            source.right <= source.left || source.bottom <= source.top
        ) return null
        val clipped = Rect(source)
        return Rect(
            clipped.left * captureWidth / sourceWidth,
            clipped.top * captureHeight / sourceHeight,
            (clipped.right * captureWidth / sourceWidth).coerceAtLeast(1),
            (clipped.bottom * captureHeight / sourceHeight).coerceAtLeast(1),
        ).takeIf { it.right > it.left && it.bottom > it.top }
    }

    private fun android.graphics.Bitmap.recycleIfNeeded() {
        if (!isRecycled && config != android.graphics.Bitmap.Config.HARDWARE) recycle()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Template Detection Active")
            .setContentText("Screen capture is visible and user-controlled")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_launcher,
                "Pause",
                commandPendingIntent(ACTION_PAUSE),
            )
            .addAction(
                R.drawable.ic_launcher,
                "Stop",
                commandPendingIntent(ACTION_STOP),
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    50,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
                ),
            )
            .build()

    private fun commandPendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, ScreenCaptureForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag(),
        )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Template detection",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    @Suppress("DEPRECATION")
    private fun Intent.intentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            getParcelableExtra(key)
        }

    companion object {
        const val ACTION_START = "com.replit.jalwa.capture.START"
        const val ACTION_PAUSE = "com.replit.jalwa.capture.PAUSE"
        const val ACTION_RESUME = "com.replit.jalwa.capture.RESUME"
        const val ACTION_STOP = "com.replit.jalwa.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TEMPLATE_PATH = "template_path"
        const val EXTRA_TEMPLATE_ID = "template_id"
        const val EXTRA_THRESHOLD = "threshold"
        const val EXTRA_DETECTION_REGION = "detection_region"
        const val EXTRA_ACTION = "action"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_TEMPLATE_NAME = "template_name"
        private const val NOTIFICATION_CHANNEL = "template_detection"
        private const val NOTIFICATION_ID = 4201

        fun start(
            context: Context,
            permissionResultCode: Int,
            permissionData: Intent,
            templatePath: String,
            templateId: Long,
            threshold: Float,
            detectionRegion: String?,
            action: TestAction,
            userId: Long,
            templateName: String,
        ) {
            val intent = Intent(context, ScreenCaptureForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, permissionResultCode)
                .putExtra(EXTRA_RESULT_DATA, permissionData)
                .putExtra(EXTRA_TEMPLATE_PATH, templatePath)
                .putExtra(EXTRA_TEMPLATE_ID, templateId)
                .putExtra(EXTRA_THRESHOLD, threshold)
                .putExtra(EXTRA_DETECTION_REGION, detectionRegion)
                .putExtra(EXTRA_ACTION, action.name)
                .putExtra(EXTRA_USER_ID, userId)
                .putExtra(EXTRA_TEMPLATE_NAME, templateName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) =
            context.startService(Intent(context, ScreenCaptureForegroundService::class.java).setAction(ACTION_PAUSE))

        fun resume(context: Context) =
            context.startService(Intent(context, ScreenCaptureForegroundService::class.java).setAction(ACTION_RESUME))

        fun stop(context: Context) =
            context.startService(Intent(context, ScreenCaptureForegroundService::class.java).setAction(ACTION_STOP))
    }
}