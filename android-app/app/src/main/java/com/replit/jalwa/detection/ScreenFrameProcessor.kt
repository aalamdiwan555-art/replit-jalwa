package com.replit.jalwa.detection

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Provides one bounded in-memory processing slot. Frames arriving while the
 * slot is busy are dropped and immediately recycled by the caller.
 */
class ScreenFrameProcessor(
    private val engine: ImageDetectionEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val processing = AtomicBoolean(false)

    fun submit(frame: Bitmap) {
        if (!processing.compareAndSet(false, true)) {
            frame.recycleIfNeeded()
            return
        }
        scope.launch {
            try {
                engine.process(frame)
            } finally {
                frame.recycleIfNeeded()
                processing.set(false)
            }
        }
    }

    fun pause() = engine.pause()

    fun resume() = engine.resume()

    fun stop() {
        engine.stop()
        scope.coroutineContext.cancel()
    }

    private fun Bitmap.recycleIfNeeded() {
        if (!isRecycled && config != Bitmap.Config.HARDWARE) recycle()
    }
}