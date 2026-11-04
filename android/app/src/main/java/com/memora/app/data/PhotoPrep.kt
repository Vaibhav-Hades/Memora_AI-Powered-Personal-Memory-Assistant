package com.memora.app.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Reading photos chosen in the Photo Picker and turning server bytes into bitmaps. */
object PhotoPrep {
    /** Long edge sent to the laptop: plenty for the AI to read receipts and labels, small enough for Wi-Fi. */
    private const val UPLOAD_MAX_SIDE = 2048

    fun displayName(resolver: ContentResolver, uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "photo.jpg"

    /**
     * Decodes any format the phone supports (including HEIC), applies the EXIF rotation, scales down
     * and re-encodes as JPEG. Re-encoding also drops EXIF metadata such as GPS location.
     */
    fun prepareForUpload(resolver: ContentResolver, uri: Uri): ByteArray {
        val bitmap = decode(resolver, uri, UPLOAD_MAX_SIDE)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** Small local preview of a picked photo. */
    fun localThumbnail(resolver: ContentResolver, uri: Uri, size: Int): Bitmap = decode(resolver, uri, size)

    private fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** Decodes a stored image straight from disk at a reduced size, so a grid of photos never loads the originals whole. */
    fun decodeFile(file: java.io.File, maxSide: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null // not an image the phone can decode
    }

    /** Decodes image bytes from the server, sampling down so large photos don't exhaust memory. */
    fun decodeBytes(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
