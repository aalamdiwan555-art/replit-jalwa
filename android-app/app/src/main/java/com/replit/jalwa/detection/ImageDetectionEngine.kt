package com.replit.jalwa.detection

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Local-only, bounded template matcher. It never persists the input frame or the
 * reference bitmap. Matching is deliberately conservative and is intended for
 * controlled testing of software the user owns or is authorized to test.
 */
class ImageDetectionEngine(
    private val scope: CoroutineScope,
    private val onStatus: (DetectionStatus) -> Unit,
    private val onMatch: (DetectionResult) -> Unit,
) {
    data class Configuration(
        val threshold: Float,
        val processingIntervalMs: Long = 250L,
        val cooldownMs: Long = 2_000L,
        val regionOfInterest: Rect? = null,
        val action: TestAction = TestAction.NONE,
    )

    @Volatile
    private var running = false
    @Volatile
    private var paused = false
    private var lastProcessedAt = 0L
    private var lastMatchAt = 0L
    private var cooldownJob: Job? = null
    @Volatile
    private var configuration = Configuration(0.9f)
    private var reference: Bitmap? = null

    fun start(referenceBitmap: Bitmap, config: Configuration) {
        stop(recycleReference = true, emitStopped = false)
        reference = referenceBitmap
        configuration = config.copy(threshold = config.threshold.coerceIn(0f, 1f))
        running = true
        paused = false
        lastProcessedAt = 0L
        lastMatchAt = 0L
        onStatus(DetectionStatus(DetectionState.SEARCHING, message = "Searching in memory"))
    }

    fun pause() {
        if (!running) return
        paused = true
        cooldownJob?.cancel()
        onStatus(DetectionStatus(DetectionState.PAUSED, message = "Paused"))
    }

    fun resume() {
        if (!running) return
        paused = false
        onStatus(DetectionStatus(DetectionState.SEARCHING, message = "Searching in memory"))
    }

    fun updateRegionOfInterest(region: Rect?) {
        configuration = configuration.copy(regionOfInterest = region)
    }

    fun stop(recycleReference: Boolean = true, emitStopped: Boolean = true) {
        running = false
        paused = false
        cooldownJob?.cancel()
        cooldownJob = null
        if (recycleReference) {
            reference?.takeIf { !it.isRecycled }?.recycle()
            reference = null
        }
        if (emitStopped) onStatus(DetectionStatus(DetectionState.STOPPED, message = "Stopped by the user"))
    }

    fun process(frame: Bitmap) {
        if (!running || paused) return
        val now = System.currentTimeMillis()
        if (now - lastProcessedAt < configuration.processingIntervalMs) return
        lastProcessedAt = now

        val referenceBitmap = reference ?: run {
            onStatus(DetectionStatus(DetectionState.ERROR, message = "Template is unavailable"))
            stop(recycleReference = false, emitStopped = false)
            return
        }

        onStatus(DetectionStatus(DetectionState.PROCESSING, message = "Processing"))
        val result = runCatching {
            findBestMatch(referenceBitmap, frame, configuration.regionOfInterest)
        }.getOrElse {
            onStatus(DetectionStatus(DetectionState.ERROR, message = "Detection failed safely"))
            return
        }
        if (!running || paused) return

        onStatus(
            DetectionStatus(
                state = if (result.matched) DetectionState.MATCH_FOUND else DetectionState.SEARCHING,
                confidence = result.confidence,
                message = if (result.matched) "Template found" else "Searching in memory",
                result = result,
            ),
        )
        if (result.matched && now - lastMatchAt >= configuration.cooldownMs) {
            lastMatchAt = now
            onMatch(result)
            cooldownJob?.cancel()
            cooldownJob = scope.launch {
                onStatus(
                    DetectionStatus(
                        DetectionState.COOLDOWN,
                        result.confidence,
                        configuration.action,
                        "Cooldown active",
                        result,
                    ),
                )
                delay(configuration.cooldownMs)
                if (running && !paused) {
                    onStatus(
                        DetectionStatus(
                            DetectionState.SEARCHING,
                            result.confidence,
                            configuration.action,
                            "Searching in memory",
                            result,
                        ),
                    )
                }
            }
        }
    }

    private fun findBestMatch(reference: Bitmap, frame: Bitmap, roi: Rect?): DetectionResult {
        if (reference.width <= 0 || reference.height <= 0 || frame.width <= 0 || frame.height <= 0) {
            return DetectionResult(false, 0f)
        }
        val bounds = Rect(0, 0, frame.width, frame.height)
        if (roi != null && !bounds.intersect(roi)) {
            return DetectionResult(false, 0f)
        }
        if (bounds.width() < reference.width || bounds.height() < reference.height) {
            return DetectionResult(false, 0f)
        }

        val scanStepX = max(1, (bounds.width() - reference.width) / 28)
        val scanStepY = max(1, (bounds.height() - reference.height) / 28)
        var bestConfidence = 0f
        var bestLeft = bounds.left
        var bestTop = bounds.top
        var y = bounds.top
        while (y <= bounds.bottom - reference.height) {
            var x = bounds.left
            while (x <= bounds.right - reference.width) {
                val confidence = patchConfidence(reference, frame, x, y)
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestLeft = x
                    bestTop = y
                }
                x += scanStepX
            }
            y += scanStepY
        }
        val matched = bestConfidence >= configuration.threshold
        return DetectionResult(
            matched = matched,
            confidence = bestConfidence,
            left = bestLeft,
            top = bestTop,
            right = bestLeft + reference.width,
            bottom = bestTop + reference.height,
        )
    }

    private fun patchConfidence(reference: Bitmap, frame: Bitmap, left: Int, top: Int): Float {
        val sampleCount = min(2_500, max(64, reference.width * reference.height))
        val stride = max(1, (reference.width * reference.height) / sampleCount)
        var index = 0
        var totalDifference = 0L
        var samples = 0
        while (index < reference.width * reference.height) {
            val x = index % reference.width
            val y = index / reference.width
            val a = reference.getPixel(x, y)
            val b = frame.getPixel(left + x, top + y)
            totalDifference += abs(android.graphics.Color.red(a) - android.graphics.Color.red(b))
            totalDifference += abs(android.graphics.Color.green(a) - android.graphics.Color.green(b))
            totalDifference += abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
            samples += 3
            index += stride
        }
        return (1f - totalDifference.toFloat() / (samples * 255f)).coerceIn(0f, 1f)
    }
}