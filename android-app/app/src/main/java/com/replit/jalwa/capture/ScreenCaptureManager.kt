package com.replit.jalwa.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import com.replit.jalwa.detection.DetectionState
import com.replit.jalwa.detection.ScreenFrameProcessor
import java.nio.ByteBuffer

/**
 * Owns the user-approved MediaProjection session. Frames are copied to a
 * bounded software bitmap, submitted once, and released immediately.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val processor: ScreenFrameProcessor,
    private val onState: (DetectionState, String) -> Unit,
    private val onCaptureSize: (sourceWidth: Int, sourceHeight: Int, captureWidth: Int, captureHeight: Int) -> Unit,
) {
    private val projectionManager =
        context.getSystemService(MediaProjectionManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val thread = HandlerThread("screen-capture-frames")
    private var frameHandler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var resultCode: Int? = null
    private var projectionData: Intent? = null
    private var paused = false
    private var released = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            onState(DetectionState.ERROR, "Screen-capture permission was revoked")
            release()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY && !released && !paused) {
                frameHandler?.post { recreateVirtualDisplay() }
            }
        }
    }

    fun startCapture(permissionResultCode: Int, permissionData: Intent) {
        runCatching {
            check(permissionResultCode == android.app.Activity.RESULT_OK) {
                "Screen capture permission was denied"
            }
            released = false
            resultCode = permissionResultCode
            projectionData = permissionData
            if (!thread.isAlive) {
                thread.start()
                frameHandler = Handler(thread.looper)
            }
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection = projectionManager.getMediaProjection(permissionResultCode, permissionData)
                ?: error("Unable to create screen projection")
            mediaProjection?.registerCallback(projectionCallback, frameHandler)
            displayManager.registerDisplayListener(displayListener, frameHandler)
            recreateVirtualDisplay()
        }.onFailure {
            onState(DetectionState.ERROR, "Screen capture could not start")
            release()
        }
    }

    fun pauseCapture() {
        paused = true
        // Keep draining the reader while paused so the virtual display cannot
        // build an unbounded backlog or block on a full buffer queue.
        imageReader?.setOnImageAvailableListener(::onImageAvailable, frameHandler)
        onState(DetectionState.PAUSED, "Paused")
    }

    fun resumeCapture() {
        if (released) return
        paused = false
        imageReader?.setOnImageAvailableListener(::onImageAvailable, frameHandler)
        onState(DetectionState.SEARCHING, "Searching in memory")
    }

    fun stopCapture() {
        onState(DetectionState.STOPPED, "Stopped by the user")
        release()
    }

    fun release() {
        if (released) return
        released = true
        paused = true
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        projectionData = null
        resultCode = null
        frameHandler = null
        if (thread.isAlive) thread.quitSafely()
    }

    private fun recreateVirtualDisplay() {
        val projection = mediaProjection ?: return
        val metrics = screenMetrics()
        val scale = (MAX_CAPTURE_DIMENSION.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels))
            .coerceAtMost(1f)
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener(::onImageAvailable, frameHandler)
        onCaptureSize(metrics.widthPixels, metrics.heightPixels, width, height)
        virtualDisplay?.release()
        virtualDisplay = projection.createVirtualDisplay(
            "template-detection-capture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            frameHandler,
        )
        if (virtualDisplay == null) {
            onState(DetectionState.ERROR, "Unable to create the virtual display")
        } else {
            onState(DetectionState.CAPTURING, "Screen Capture Active")
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        if (paused || released) {
            reader.acquireLatestImage()?.close()
            return
        }
        var image: Image? = null
        var bitmap: Bitmap? = null
        try {
            image = reader.acquireLatestImage() ?: return
            bitmap = imageToBitmap(image)
            if (bitmap != null) processor.submit(bitmap) else onState(
                DetectionState.ERROR,
                "The screen frame could not be read",
            )
            bitmap = null
        } catch (_: OutOfMemoryError) {
            onState(DetectionState.ERROR, "Not enough memory to process the frame")
        } catch (_: RuntimeException) {
            onState(DetectionState.ERROR, "Screen frame processing stopped safely")
        } finally {
            image?.close()
            bitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer: ByteBuffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmapWidth = image.width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return if (bitmapWidth == image.width) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also {
                bitmap.recycle()
            }
        }
    }

    private fun screenMetrics(): DisplayMetrics =
        DisplayMetrics().also { metrics ->
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && metrics.widthPixels == 0) {
                val bounds = windowManager.currentWindowMetrics.bounds
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
            }
        }

    companion object {
        private const val MAX_CAPTURE_DIMENSION = 1_920
    }
}