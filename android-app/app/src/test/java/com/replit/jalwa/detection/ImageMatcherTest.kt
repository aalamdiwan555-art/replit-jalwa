package com.replit.jalwa.detection

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMatcherTest {
    @Test
    fun identical_bitmaps_have_high_confidence() {
        val reference = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val frame = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        reference.eraseColor(Color.BLUE)
        frame.eraseColor(Color.BLUE)
        assertTrue(ImageMatcher.confidence(reference, frame) > 0.99f)
    }
}