package com.memora.app.local

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Reads the metadata a photo carries (capture time, location, size) from the stored original. Offline; no network. */
object MetadataReader {
    fun read(file: File): PhotoMetadata = try {
        val exif = ExifInterface(file.absolutePath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val rotated = exif.rotationDegrees % 180 != 0
        val (w, h) = if (bounds.outWidth > 0) {
            if (rotated) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        } else null to null
        val taken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
        val latLong = exif.latLong
        PhotoMetadata(
            capture = ExifDates.parse(taken, exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)),
            width = w, height = h,
            latitude = latLong?.get(0), longitude = latLong?.get(1),
        )
    } catch (_: Exception) {
        PhotoMetadata() // not an image, or unreadable: every field stays "not available"
    }
}
