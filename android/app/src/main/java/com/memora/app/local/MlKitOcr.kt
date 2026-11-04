package com.memora.app.local

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Offline text recognition with ML Kit's bundled Latin model (no network, no Play Services download). */
class MlKitOcr : OcrEngine {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognizeImage(file: File): String {
        val bitmap = withContext(Dispatchers.IO) { decodeScaled(file, MAX_SIDE) } ?: throw IllegalArgumentException("Couldn't decode this image")
        return try { recognize(bitmap) } finally { bitmap.recycle() }
    }

    override suspend fun recognizePdf(file: File, maxPages: Int): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until minOf(renderer.pageCount, maxPages)) {
                    renderer.openPage(i).use { page ->
                        val scale = 2f
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        try { out.append("[Page ${i + 1}]\n").append(recognize(bmp)).append("\n\n") } finally { bmp.recycle() }
                    }
                }
            }
        }
        out.toString()
    }

    private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** ImageDecoder applies the photo's EXIF rotation, which OCR needs to read text the right way up. */
    private fun decodeScaled(file: File, maxSide: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val f = maxSide.toFloat() / longest
                decoder.setTargetSize((info.size.width * f).toInt().coerceAtLeast(1), (info.size.height * f).toInt().coerceAtLeast(1))
            }
        }
    } catch (_: Exception) {
        null
    }

    private companion object { const val MAX_SIDE = 2000 }
}
