package com.memora.app.local

import java.io.File
import java.util.zip.ZipFile

/** Text pulled out of a stored file. [unsupported] explains why nothing was extracted; it is never silently empty. */
data class Extraction(val text: String?, val extractor: String, val unsupported: String? = null)

interface ContentExtractor {
    suspend fun extract(file: File, name: String, mime: String): Extraction
}

/** Reads at most [limit] bytes (InputStream.readNBytes needs Android 13, but the app supports Android 9+). */
internal fun readUpTo(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var left = limit
    while (left > 0) {
        val n = input.read(buf, 0, minOf(buf.size, left))
        if (n < 0) break
        out.write(buf, 0, n)
        left -= n
    }
    return out.toByteArray()
}

/** Offline OCR for photos and PDF pages (ML Kit on Android, a fake in tests). */
interface OcrEngine {
    suspend fun recognizeImage(file: File): String
    suspend fun recognizePdf(file: File, maxPages: Int): String
}

/**
 * Plain text, CSV, JSON and DOCX are read directly. Images and PDFs go through [ocr] when one is available;
 * PDFs are rendered and recognised page by page, so scanned and text PDFs both work (slowly and approximately).
 */
class DefaultExtractor(private val ocr: OcrEngine? = null, private val maxPdfPages: Int = 20) : ContentExtractor {
    override suspend fun extract(file: File, name: String, mime: String): Extraction {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            // The file extension decides first: a .docx is a zip file whatever MIME type a picker reported for it.
            ext == "docx" -> Extraction(Docx.text(file), "docx")
            ext in TEXT_EXTS || (ext !in BINARY_EXTS && (mime.startsWith("text/") || mime == "application/json")) ->
                Extraction(readCapped(file), "text")
            ext in IMAGE_EXTS || mime.startsWith("image/") ->
                if (ocr == null) Extraction(null, "none", "No offline text recognition is available on this phone, so this photo can't be searched.")
                else Extraction(ocr.recognizeImage(file), "ocr")
            ext == "pdf" || mime == "application/pdf" ->
                if (ocr == null) Extraction(null, "none", "No offline text recognition is available on this phone, so this PDF can't be searched.")
                else Extraction(ocr.recognizePdf(file, maxPdfPages), "pdf-ocr")
            else -> Extraction(null, "none", "This file type can't be read on the phone. The file is stored but its contents are not searchable.")
        }
    }

    private fun readCapped(file: File): String {
        val limit = 5 * 1024 * 1024 // 5 MB of text is far more than a person asks questions about
        return file.inputStream().use { readUpTo(it, limit) }.toString(Charsets.UTF_8)
    }

    companion object {
        val TEXT_EXTS = setOf("txt", "md", "csv", "json", "log", "tsv", "xml", "html")
        val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        private val BINARY_EXTS = IMAGE_EXTS + setOf("pdf", "docx", "zip", "bin")
    }
}

/** Minimal DOCX reader: text runs from word/document.xml, with paragraph and table-cell breaks kept. */
object Docx {
    private const val MAX_XML = 20 * 1024 * 1024 // guards against zip bombs

    fun text(file: File): String = ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: throw IllegalArgumentException("Not a valid .docx file")
        if (entry.size > MAX_XML) throw IllegalArgumentException("The document is too large to read")
        parse(zip.getInputStream(entry).use { readUpTo(it, MAX_XML) }.toString(Charsets.UTF_8))
    }

    private val TOKEN = Regex("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>|</w:p>|</w:tc>|<w:tab/>|<w:br/>")

    fun parse(xml: String): String {
        val sb = StringBuilder()
        TOKEN.findAll(xml).forEach { m ->
            when {
                m.value.startsWith("<w:t>") || m.value.startsWith("<w:t ") -> sb.append(unescape(m.groupValues[1]))
                m.value == "</w:p>" || m.value == "<w:br/>" -> sb.append('\n')
                m.value == "</w:tc>" || m.value == "<w:tab/>" -> sb.append(" | ")
            }
        }
        return sb.toString().replace(Regex("[ ]*\\|[ ]*\n"), "\n").trim()
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
