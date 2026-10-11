package org.fossify.camera.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Adds a timestamp watermark (yyyy-MM-dd HH:mm:ss) to captured photos.
 */
object WatermarkUtils {

    private const val TAG = "WatermarkUtils"

    private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // The watermark size and offset are relative to the image width so that the text scales
    // proportionally on any resolution.
    private const val TEXT_SIZE_RATIO = 0.03f
    private const val MARGIN_RATIO = 0.03f
    private const val SHADOW_RADIUS_RATIO = 0.25f
    private const val SHADOW_OFFSET_RATIO = 0.05f
    private const val OUTLINE_RATIO = 0.14f

    /**
     * Decodes the given JPEG, rotates it to an upright position, draws the current timestamp in
     * the bottom-right corner and re-encodes it.
     *
     * Because the rotation is baked into the pixels, the caller must persist the EXIF orientation
     * as [ExifInterface.ORIENTATION_NORMAL] to avoid rotating the image a second time.
     *
     * If anything goes wrong (for example the image is too large to be decoded into memory), the
     * original bytes are returned unchanged so that the capture is never lost.
     *
     * @param jpegData the raw JPEG bytes produced by the camera
     * @param jpegQuality the JPEG compression quality to use when re-encoding
     * @param fallbackRotation rotation in degrees to apply when the JPEG carries no usable EXIF
     * orientation, typically [androidx.camera.core.ImageInfo.getRotationDegrees]
     */
    fun addTimestampWatermark(
        jpegData: ByteArray,
        jpegQuality: Int,
        fallbackRotation: Int,
    ): ByteArray {
        return try {
            addTimestampWatermarkInternal(jpegData, jpegQuality, fallbackRotation)
        } catch (e: OutOfMemoryError) {
            // Not enough memory to hold the decoded, full resolution image. Save the photo
            // as-is rather than crashing the capture.
            jpegData
        } catch (e: Exception) {
            jpegData
        }
    }

    private fun addTimestampWatermarkInternal(
        jpegData: ByteArray,
        jpegQuality: Int,
        fallbackRotation: Int,
    ): ByteArray {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size, options)
            ?: return jpegData

        if (!bitmap.isMutable) {
            val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            bitmap.recycle()
            bitmap = mutable ?: return jpegData
        }

        val exifRotation = getExifRotation(jpegData)
        val totalRotation = resolveRotation(exifRotation, fallbackRotation)
        Log.d(
            TAG,
            "watermark: exifRotation=$exifRotation fallbackRotation=$fallbackRotation " +
                "appliedRotation=$totalRotation source=${bitmap.width}x${bitmap.height}"
        )
        if (totalRotation != 0) {
            rotate(bitmap, totalRotation.toFloat())?.let { rotated ->
                bitmap.recycle()
                bitmap = rotated
            }
        }
        Log.d(TAG, "watermark: finalSize=${bitmap.width}x${bitmap.height}")

        val text = LocalDateTime.now().format(TIMESTAMP_FORMATTER)
        val canvas = Canvas(bitmap)
        val textSize = bitmap.width * TEXT_SIZE_RATIO

        // A soft black drop shadow alone is not enough on a very bright photo, where white text
        // on a white background can only be told apart by the shadow. The black outline drawn
        // underneath the white fill guarantees contrast on any background.
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            this.textSize = textSize
            style = Paint.Style.STROKE
            strokeWidth = textSize * OUTLINE_RATIO
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(
                textSize * SHADOW_RADIUS_RATIO,
                0f,
                textSize * SHADOW_OFFSET_RATIO,
                Color.BLACK
            )
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            this.textSize = textSize
        }

        val margin = bitmap.width * MARGIN_RATIO
        val x = bitmap.width - outlinePaint.measureText(text) - margin
        val y = bitmap.height - margin
        canvas.drawText(text, x, y, outlinePaint)
        canvas.drawText(text, x, y, textPaint)

        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    /**
     * Returns the rotation that still has to be applied to the decoded pixels.
     *
     * The EXIF orientation stored inside the JPEG is preferred. It is missing whenever the image
     * was re-encoded (for example while cropping), and on some devices the rotation is only
     * reported through [androidx.camera.core.ImageInfo.getRotationDegrees], so the caller supplied
     * value is used as a fallback. Using only one of the two also prevents rotating twice.
     */
    private fun resolveRotation(exifRotation: Int, fallbackRotation: Int): Int {
        val rotation = if (exifRotation != 0) exifRotation else fallbackRotation
        return ((rotation % 360) + 360) % 360
    }

    private fun getExifRotation(jpegData: ByteArray): Int {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(jpegData))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun rotate(source: Bitmap, degrees: Float): Bitmap? {
        val matrix = Matrix().apply {
            postRotate(degrees)
        }
        return try {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
