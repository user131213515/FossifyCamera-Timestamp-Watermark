package org.fossify.camera.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Verifies that [WatermarkUtils] really draws a legible timestamp into the
 * bottom right corner and that it rotates the photo upright first.
 *
 * The rendered results are also written as PNG files to
 * app/build/watermark-verification so they can be inspected by eye.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkUtilsTest {

    private val outDir = File("build/watermark-verification").apply { mkdirs() }

    private val width = 800
    private val height = 600

    @Test
    fun rotatesByExifOrientationAndDrawsTimestampInBottomRightCorner() {
        val tagged = withExifOrientation(
            gradientJpeg(width, height),
            ExifInterface.ORIENTATION_ROTATE_90
        )

        val decoded = decode(WatermarkUtils.addTimestampWatermark(tagged, 95, 0))
        save(decoded, "1-exif-rotate-90.png")

        // EXIF orientation 6 means the stored landscape pixels must come out as portrait.
        assertEquals(600, decoded.width)
        assertEquals(800, decoded.height)

        val bottomRight = countPixels(decoded, decoded.width / 2, decoded.height / 2, decoded.width, decoded.height) { isWhitish(it) }
        val topLeft = countPixels(decoded, 0, 0, decoded.width / 2, decoded.height / 2) { isWhitish(it) }

        assertTrue("expected white timestamp pixels in the bottom right, found $bottomRight", bottomRight > 40)
        assertEquals("nothing should be drawn outside the bottom right corner", 0, topLeft)
    }

    @Test
    fun usesFallbackRotationWhenTheJpegHasNoExifOrientation() {
        // No EXIF at all: the rotation reported by CameraX is the only source of truth.
        val decoded = decode(WatermarkUtils.addTimestampWatermark(gradientJpeg(width, height), 95, 90))
        save(decoded, "2-fallback-rotation-90.png")

        assertEquals(600, decoded.width)
        assertEquals(800, decoded.height)

        val bottomRight = countPixels(decoded, decoded.width / 2, decoded.height / 2, decoded.width, decoded.height) { isWhitish(it) }
        assertTrue("expected white timestamp pixels in the bottom right, found $bottomRight", bottomRight > 40)
    }

    @Test
    fun leavesAnUprightPhotoUntouchedApartFromTheTimestamp() {
        val decoded = decode(WatermarkUtils.addTimestampWatermark(gradientJpeg(width, height), 95, 0))
        save(decoded, "3-upright-no-rotation.png")

        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)

        val bottomRight = countPixels(decoded, decoded.width / 2, decoded.height / 2, decoded.width, decoded.height) { isWhitish(it) }
        assertTrue("expected white timestamp pixels in the bottom right, found $bottomRight", bottomRight > 40)
    }

    @Test
    fun timestampStaysLegibleOnBothBrightAndDarkPhotos() {
        val onWhite = decode(WatermarkUtils.addTimestampWatermark(solidJpeg(width, height, Color.WHITE), 95, 0))
        save(onWhite, "4-on-white-background.png")
        val darkPixels = countPixels(onWhite, 0, 0, onWhite.width, onWhite.height) { isDarkish(it) }
        assertTrue("black shadow must be visible on a bright photo, found $darkPixels dark pixels", darkPixels > 40)

        val onBlack = decode(WatermarkUtils.addTimestampWatermark(solidJpeg(width, height, Color.BLACK), 95, 0))
        save(onBlack, "5-on-black-background.png")
        val whitePixels = countPixels(onBlack, 0, 0, onBlack.width, onBlack.height) { isWhitish(it) }
        assertTrue("white text must be visible on a dark photo, found $whitePixels white pixels", whitePixels > 40)
    }

    @Test
    fun returnsTheOriginalBytesWhenTheImageCannotBeDecoded() {
        // A capture must never be lost just because the watermark could not be applied.
        val garbage = ByteArray(64) { 0x11 }
        assertArrayEquals(garbage, WatermarkUtils.addTimestampWatermark(garbage, 95, 90))
    }

    private fun solidJpeg(w: Int, h: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        return compress(bitmap)
    }

    private fun gradientJpeg(w: Int, h: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            // Stays well below pure white so that only the drawn text can be whitish.
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF202020.toInt(), 0xFFB0B0B0.toInt(), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return compress(bitmap)
    }

    private fun compress(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val file = File.createTempFile("watermark-source", ".jpg")
        FileOutputStream(file).use { it.write(jpeg) }
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.saveAttributes()
        return file.readBytes().also { file.delete() }
    }

    private fun decode(bytes: ByteArray): Bitmap =
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "watermarked jpeg could not be decoded" }

    private fun isWhitish(pixel: Int): Boolean =
        Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200

    private fun isDarkish(pixel: Int): Boolean =
        Color.red(pixel) < 90 && Color.green(pixel) < 90 && Color.blue(pixel) < 90

    private fun countPixels(
        bitmap: Bitmap,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        predicate: (Int) -> Boolean,
    ): Int {
        var count = 0
        for (y in fromY until toY) {
            for (x in fromX until toX) {
                if (predicate(bitmap.getPixel(x, y))) {
                    count++
                }
            }
        }
        return count
    }

    private fun save(bitmap: Bitmap, name: String) {
        FileOutputStream(File(outDir, name)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
