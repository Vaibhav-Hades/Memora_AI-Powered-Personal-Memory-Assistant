package com.memora.app.local

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

sealed interface ImportResult {
    val file: LocalFile

    data class Added(override val file: LocalFile) : ImportResult

    /** The same bytes are already stored (possibly under another name); nothing was copied. */
    data class Duplicate(override val file: LocalFile) : ImportResult
}

class ImportException(message: String) : Exception(message)

/**
 * The phone's own copy of the user's files: original bytes in app-private storage, metadata and extracted text in the
 * database. Files become searchable (READY) only after extraction and indexing both succeeded.
 */
class LocalLibrary(
    private val db: LocalDb,
    private val vaultDir: File,
    private val extractor: ContentExtractor,
    private val maxFileBytes: Long = 200L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val tmpDir = File(vaultDir, "tmp")

    init {
        vaultDir.mkdirs()
        tmpDir.mkdirs()
        // Leftovers of an import killed with the app; these were never completed files.
        tmpDir.listFiles()?.forEach { it.delete() }
    }

    fun files(): List<LocalFile> = db.allFiles()

    /** The stored original, or null if it has gone missing. */
    fun fileOnDisk(f: LocalFile): File? = File(vaultDir, f.storedName).takeIf { it.isFile }

    /** Copies a stream into the vault, de-duplicating by content. Does not index. */
    fun import(
        name: String, mime: String, input: InputStream, origin: String,
        remoteId: Long? = null, remoteSha256: String? = null,
    ): ImportResult {
        val tmp = File(tmpDir, UUID.randomUUID().toString())
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > maxFileBytes) throw ImportException("This file is larger than ${maxFileBytes / (1024 * 1024)} MB.")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            if (size == 0L) throw ImportException("The file is empty.")
            return adopt(tmp, size, hex(digest.digest()), name, mime, origin, remoteId, remoteSha256)
        } finally {
            tmp.delete()
        }
    }

    /** Moves an already-verified file (e.g. a finished download) into the vault. The source file is consumed. */
    fun adopt(
        source: File, size: Long, sha256: String, name: String, mime: String, origin: String,
        remoteId: Long? = null, remoteSha256: String? = null,
    ): ImportResult {
        db.findBySha(sha256)?.let { existing ->
            if (remoteId != null && remoteSha256 != null) db.updateRemote(existing.id, remoteId, remoteSha256)
            source.delete()
            return ImportResult.Duplicate(db.file(existing.id) ?: existing)
        }
        val safeName = displayName(name)
        val extension = safeName.substringAfterLast('.', "").lowercase().filter(Char::isLetterOrDigit)
        val stored = if (extension.isEmpty() || extension.length > 8) sha256 else "$sha256.$extension"
        val target = File(vaultDir, stored)
        // A rename inside one filesystem is atomic: a half-written file never appears under its final name.
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
        val id = db.insertFile(
            LocalFile(0, safeName, mime, size, sha256, stored, clock(), origin, remoteId, remoteSha256, IndexState.PENDING),
        )
        return ImportResult.Added(db.file(id)!!)
    }

    /** Extracts and indexes one file. Failures are recorded on the file (and can be retried), never hidden. */
    suspend fun index(id: Long): LocalFile {
        val f = db.file(id) ?: throw ImportException("File not found")
        val disk = fileOnDisk(f)
        if (disk == null) {
            db.updateState(id, IndexState.FAILED, "The stored copy is missing. Download or import the file again.", null)
            return db.file(id)!!
        }
        db.updateState(id, IndexState.EXTRACTING, null, null)
        try {
            val result = extractor.extract(disk, f.name, f.mime)
            if (result.text == null) {
                db.updateState(id, IndexState.UNSUPPORTED, result.unsupported, result.extractor)
                return db.file(id)!!
            }
            db.updateState(id, IndexState.INDEXING, null, result.extractor)
            val photo = isPhoto(f)
            // A photo is searchable by what the user wrote and what the laptop described, as well as by the text in it.
            val text = if (photo) {
                db.saveDerived(id, result.text.trim().take(MAX_OCR_CHARS), null, null)
                PhotoText.compose(db.photoNote(id), null)
            } else result.text
            val chunks = Chunker.split(text)
            if (chunks.isEmpty()) {
                db.updateState(id, IndexState.FAILED, "No readable text was found in this file.", result.extractor)
                return db.file(id)!!
            }
            db.replaceChunks(id, chunks)
            db.updateState(id, IndexState.READY, null, result.extractor)
        } catch (e: kotlinx.coroutines.CancellationException) {
            db.updateState(id, IndexState.PENDING, null, null) // cancelled work is neither "ready" nor "failed"
            throw e
        } catch (e: Exception) {
            db.updateState(id, IndexState.FAILED, "Couldn't read this file: ${e.message ?: e.javaClass.simpleName}", null)
        }
        return db.file(id)!!
    }

    /**
     * Saves the user's own caption, notes, tags, event, place and favourite, then rebuilds the photo's search text from
     * the stored OCR text (no new OCR run). A photo that already has an index stays searchable throughout.
     */
    fun saveUserFields(id: Long, fields: PhotoUserFields): PhotoUserFields {
        val f = db.file(id) ?: throw ImportException("File not found")
        if (!isPhoto(f)) throw ImportException("Only photos have captions and notes.")
        val clean = PhotoText.clean(fields)
        if (!PhotoText.validDate(clean.eventDate)) throw ImportException("The event date must look like 2025-10-14.")
        backfillOcr(id)
        val before = db.photoNote(id)?.user
        db.saveUserFields(id, clean, clock())
        // A favourite toggle changes nothing that is searched.
        if (before == null || before.copy(favorite = clean.favorite) != clean) rebuildPhoto(id)
        return clean
    }

    /** The laptop's description of a photo. Stored apart from the user's words, which it never replaces. */
    fun recordLaptopDescription(id: Long, caption: String?, summary: String?) {
        if (caption.isNullOrBlank() && summary.isNullOrBlank()) return
        backfillOcr(id)
        db.saveDerived(id, null, caption.orEmpty().trim().take(300), summary.orEmpty().trim().take(MAX_FIELD_CHARS))
        if (db.file(id)?.state == IndexState.READY) rebuildPhoto(id)
    }

    /** Rewrites a photo's chunks from stored text only. If nothing is left to search, the old index is kept. */
    private fun rebuildPhoto(id: Long) {
        val f = db.file(id) ?: return
        val chunks = Chunker.split(PhotoText.compose(db.photoNote(id), null))
        when (f.state) {
            IndexState.READY -> db.replaceChunks(id, chunks)
            // A photo with no readable text becomes searchable once the user describes it.
            IndexState.FAILED, IndexState.UNSUPPORTED -> if (chunks.isNotEmpty() && fileOnDisk(f) != null) {
                db.replaceChunks(id, chunks)
                db.updateState(id, IndexState.READY, null, f.extractor)
            }
            else -> Unit // indexing is in progress and will pick the notes up itself
        }
    }

    private fun isPhoto(f: LocalFile) = f.mime.startsWith("image/") || f.name.substringAfterLast('.', "").lowercase() in DefaultExtractor.IMAGE_EXTS

    fun photoNote(id: Long): PhotoNote? {
        backfillOcr(id)
        return db.photoNote(id)
    }

    /**
     * A photo indexed before photo notes existed has its OCR text only inside its chunks. While the user has written
     * nothing about it, those chunks are exactly the OCR text, so copy them over once. Otherwise a later edit would
     * rebuild the index without that text.
     */
    private fun backfillOcr(id: Long) {
        val f = db.file(id) ?: return
        if (!isPhoto(f) || f.state != IndexState.READY) return
        val note = db.photoNote(id)
        if (note != null && (note.derived.ocrText.isNotBlank() || !note.user.isBlank || note.derived.laptopCaption.isNotBlank() || note.derived.laptopSummary.isNotBlank())) return
        val text = Chunker.join(db.allChunks().filter { it.fileId == id }.sortedBy { it.index }.map { it.text })
        if (text.isNotBlank() && !text.contains("(written by you)") && !text.contains("from the laptop's AI")) db.saveDerived(id, text.take(MAX_OCR_CHARS), null, null)
    }

    /** Re-runs indexing for everything that failed or was left unfinished (e.g. the app was killed mid-way). */
    suspend fun retryFailed(): Int {
        val pending = setOf(IndexState.FAILED, IndexState.PENDING, IndexState.EXTRACTING, IndexState.INDEXING)
        val todo = db.allFiles().filter { it.state in pending }
        todo.forEach { index(it.id) }
        return todo.size
    }

    /** Removes the phone's copy only (never anything on the laptop). */
    fun remove(id: Long) {
        val f = db.file(id) ?: return
        db.deleteFile(id)
        File(vaultDir, f.storedName).delete()
    }

    companion object {
        private const val MAX_OCR_CHARS = 20_000
        private const val MAX_FIELD_CHARS = 2000

        /** A name safe to show and to export: no directories, no control characters. */
        fun displayName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it != '\u007f' }.trim().trim('.')
            return base.ifEmpty { "file" }.take(120)
        }

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
