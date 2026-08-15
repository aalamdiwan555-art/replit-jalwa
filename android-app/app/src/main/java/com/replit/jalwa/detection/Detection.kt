package com.replit.jalwa.detection

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class DetectionState { IDLE, SEARCHING, MATCH_FOUND, ACTION_PENDING, COOLDOWN, STOPPED, ERROR }
enum class TestAction { NONE, BACK, HOME, NOTIFICATIONS }

data class DetectionStatus(
    val state: DetectionState = DetectionState.IDLE,
    val confidence: Float = 0f,
    val lastAction: TestAction = TestAction.NONE,
    val message: String = "Ready for a user-started test",
)

/**
 * A small, dependency-free in-memory matcher for controlled test fixtures.
 * It deliberately never writes frames to disk and samples pixels to keep CPU use bounded.
 */
object ImageMatcher {
    fun confidence(reference: Bitmap, frame: Bitmap, maxSamples: Int = 4_000): Float {
        if (reference.width == 0 || reference.height == 0 || frame.width == 0 || frame.height == 0) {
            return 0f
        }
        val scale = minOf(frame.width.toFloat() / reference.width, frame.height.toFloat() / reference.height)
        val width = (reference.width * scale).toInt().coerceAtLeast(1)
        val height = (reference.height * scale).toInt().coerceAtLeast(1)
        val step = ((width * height) / maxSamples).coerceAtLeast(1)
        var totalDifference = 0L
        var count = 0
        var index = 0
        while (index < width * height) {
            val x = index % width
            val y = index / width
            val referenceX = (x / scale).toInt().coerceIn(0, reference.width - 1)
            val referenceY = (y / scale).toInt().coerceIn(0, reference.height - 1)
            val frameX = x.coerceIn(0, frame.width - 1)
            val frameY = y.coerceIn(0, frame.height - 1)
            val a = reference.getPixel(referenceX, referenceY)
            val b = frame.getPixel(frameX, frameY)
            totalDifference += kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b))
            totalDifference += kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b))
            totalDifference += kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
            count += 3
            index += step
        }
        return (1f - (totalDifference.toFloat() / (count * 255f))).coerceIn(0f, 1f)
    }
}

class DetectionController(
    private val scope: CoroutineScope,
    private val onStatus: (DetectionStatus) -> Unit,
    private val onAction: (TestAction) -> Unit,
) {
    private var job: Job? = null
    private var lastTriggerAt = 0L

    fun start(action: TestAction, cooldownMillis: Long = 2_000L) {
        stop()
        job = scope.launch(Dispatchers.Default) {
            onStatus(DetectionStatus(DetectionState.SEARCHING, message = "Waiting for a controlled test frame"))
            // Real frame sources must be provided by an explicit, user-approved Android API.
            // This loop is intentionally idle until submitFrame is called by the service.
        }
    }

    fun submitFrame(reference: Bitmap, frame: Bitmap, threshold: Float, action: TestAction, cooldownMillis: Long) {
        if (job?.isActive != true) return
        val confidence = ImageMatcher.confidence(reference, frame)
        onStatus(DetectionStatus(DetectionState.SEARCHING, confidence, message = "Analyzing in memory"))
        val now = System.currentTimeMillis()
        if (confidence >= threshold && now - lastTriggerAt >= cooldownMillis) {
            lastTriggerAt = now
            scope.launch {
                onStatus(DetectionStatus(DetectionState.MATCH_FOUND, confidence, message = "Match found"))
                delay(150)
                onStatus(DetectionStatus(DetectionState.ACTION_PENDING, confidence, action, "Executing one configured test action"))
                onAction(action)
                onStatus(DetectionStatus(DetectionState.COOLDOWN, confidence, action, "Cooldown active"))
                delay(cooldownMillis)
                onStatus(DetectionStatus(DetectionState.SEARCHING, confidence, action, "Searching"))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        onStatus(DetectionStatus(DetectionState.STOPPED, message = "Stopped by the user"))
    }
}