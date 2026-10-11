package noline.zhouzhou.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

data class CompressedImage(val name: String, val mimeType: String, val bytes: ByteArray)

/**
 * 相册里的原图动辄十几 MB，XML-RPC 走 base64 还要再膨胀三分之一，
 * 很容易撞上 PHP 的 post_max_size，所以上传前先等比压到 MaxEdge 以内。
 */
object ImageCompressor {

    private const val MaxEdge = 1600
    private const val Quality = 82
    private const val MaxBytes = 2_500_000

    fun compress(resolver: ContentResolver, uri: Uri): CompressedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) throw IllegalStateException("无法读取这张图片")

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(srcW, srcH)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("无法读取这张图片")

        val oriented = rotate(decoded, orientation(resolver, uri))
        if (oriented !== decoded) decoded.recycle()
        val scaled = scaleToFit(oriented)
        if (scaled !== oriented) oriented.recycle()

        val bytes = encode(scaled)
        scaled.recycle()
        return CompressedImage(displayName(resolver, uri), "image/jpeg", bytes)
    }

    private fun sampleSize(w: Int, h: Int): Int {
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MaxEdge) sample *= 2
        return sample
    }

    private fun orientation(resolver: android.content.ContentResolver, uri: Uri): Int = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun rotate(bitmap: Bitmap, exif: Int): Bitmap {
        val degrees = when (exif) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val flip = exif == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
            exif == ExifInterface.ORIENTATION_TRANSVERSE ||
            exif == ExifInterface.ORIENTATION_FLIP_VERTICAL ||
            exif == ExifInterface.ORIENTATION_TRANSPOSE
        if (degrees == 0f && !flip) return bitmap
        val matrix = Matrix().apply {
            if (degrees != 0f) postRotate(degrees)
            if (flip) postScale(-1f, 1f)
        }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        return out
    }

    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= MaxEdge) return bitmap
        val ratio = MaxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1), true
        )
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        var quality = Quality
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= MaxBytes || quality <= 50) return bytes
            quality -= 10
        }
    }

    private fun displayName(resolver: android.content.ContentResolver, uri: Uri): String {
        val raw = queryName(resolver, uri) ?: uri.lastPathSegment ?: "image"
        val stem = raw.substringBeforeLast('.').ifBlank { "image" }
        // 服务端会拿这个名字做附件标题，尖括号会打乱 Markdown 和 HTML。
        val safe = stem.replace(Regex("[<>\"'\\[\\]\\\\/|?*:\\s]+"), "-").trim('-')
        return (safe.ifBlank { "image" }.take(40)) + ".jpg"
    }

    private fun queryName(resolver: android.content.ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
