package com.replit.jalwa.accessibility

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.replit.jalwa.data.AppDatabase
import com.replit.jalwa.data.ClickHistoryEntity
import com.replit.jalwa.detection.ImageMatcher
import com.replit.jalwa.detection.TestAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TestAutomationService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captureJob: Job? = null
    private var reference: Bitmap? = null
    private var threshold = 0.90f
    private var action = TestAction.NONE
    private var auditUserId: Long? = null
    private var auditTemplateName = ""
    private var lastTriggerAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally do not inspect or log unrelated window contents.
    }

    override fun onInterrupt() {
        running = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        if (running) beginProcessing()
    }

    override fun onDestroy() {
        stopProcessing()
        connected = false
        running = false
        instance = null
        super.onDestroy()
    }

    @android.annotation.SuppressLint("ObsoleteSdkInt")
    private fun beginProcessing() {
        captureJob?.cancel()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || reference == null) return
        captureJob = serviceScope.launch {
            while (isActive && running) {
                requestScreenshot()
                delay(750)
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun requestScreenshot() {
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardware = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardware, screenshot.colorSpace)
                    hardware.close()
                    val frame = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    bitmap?.recycle()
                    if (frame != null) {
                        val confidence = reference?.let { ImageMatcher.confidence(it, frame) } ?: 0f
                        frame.recycle()
                        val now = System.currentTimeMillis()
                        if (confidence >= threshold && now - lastTriggerAt >= COOLDOWN_MS) {
                            lastTriggerAt = now
                            executeInternal(action)
                            recordTargetAction(confidence)
                        }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // Do not log or persist screenshot failures or screen contents.
                }
            },
        )
    }

    private fun stopProcessing() {
        captureJob?.cancel()
        captureJob = null
        reference?.recycle()
        reference = null
        auditUserId = null
        auditTemplateName = ""
    }

    private fun executeInternal(action: TestAction) {
        if (!running) return
        when (action) {
            TestAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            TestAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            TestAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            TestAction.NONE -> Unit
        }
    }

    companion object {
        @Volatile var connected: Boolean = false
            private set
        @Volatile var running: Boolean = false
            private set

        fun setRunning(value: Boolean) {
            running = value
            if (value) instance?.beginProcessing() else instance?.stopProcessing()
        }

        fun configure(
            templatePath: String,
            thresholdValue: Float,
            testAction: TestAction,
            userId: Long,
            templateName: String,
        ) {
            instance?.configureInternal(templatePath, thresholdValue, testAction, userId, templateName)
        }

        fun execute(action: TestAction) {
            instance?.executeInternal(action)
        }

        private var instance: TestAutomationService? = null

        init {
            // The Android service lifecycle is authoritative; this field is only a transient bridge.
        }

        private const val COOLDOWN_MS = 2_000L
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    private fun configureInternal(
        templatePath: String,
        thresholdValue: Float,
        testAction: TestAction,
        userId: Long,
        templateName: String,
    ) {
        reference?.recycle()
        reference = BitmapFactory.decodeFile(templatePath)
        threshold = thresholdValue.coerceIn(0f, 1f)
        action = testAction
        auditUserId = userId
        auditTemplateName = templateName
    }

    private fun recordTargetAction(confidence: Float) {
        val userId = auditUserId ?: return
        serviceScope.launch(Dispatchers.IO) {
            AppDatabase.get(applicationContext).clickHistoryDao().insert(
                ClickHistoryEntity(
                    userId = userId,
                    templateName = auditTemplateName,
                    action = action.name,
                    confidence = confidence,
                ),
            )
        }
    }
}