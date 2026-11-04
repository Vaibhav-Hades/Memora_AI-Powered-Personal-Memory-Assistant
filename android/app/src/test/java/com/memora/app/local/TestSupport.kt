package com.memora.app.local

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/** In-memory stand-in for the phone's SQLite database. */
class InMemoryLocalDb : LocalDb {
    override var version = 0L
        private set
    private val files = LinkedHashMap<Long, LocalFile>()
    private val chunks = LinkedHashMap<Long, MutableList<LocalChunk>>()
    private val history = ArrayList<QaRecord>()
    private var nextId = 1L
    private var nextChunk = 1L

    override fun allFiles() = files.values.toList()
    override fun file(id: Long) = files[id]
    override fun findBySha(sha256: String) = files.values.firstOrNull { it.sha256 == sha256 }
    override fun findByRemote(origin: String, remoteId: Long) = files.values.firstOrNull { it.origin == origin && it.remoteId == remoteId }
    override fun insertFile(f: LocalFile): Long {
        require(findBySha(f.sha256) == null) { "sha256 must be unique" }
        val id = nextId++
        files[id] = f.copy(id = id)
        version++
        return id
    }
    override fun updateState(id: Long, state: IndexState, error: String?, extractor: String?) {
        files[id] = files.getValue(id).copy(state = state, error = error, extractor = extractor)
        version++
    }
    override fun updateRemote(id: Long, remoteId: Long, remoteSha256: String) {
        files[id] = files.getValue(id).copy(remoteId = remoteId, remoteSha256 = remoteSha256)
        version++
    }
    override fun deleteFile(id: Long) {
        files.remove(id)
        chunks.remove(id)
        notes.remove(id)
        version++
    }
    override fun replaceChunks(fileId: Long, texts: List<String>) {
        chunks[fileId] = texts.mapIndexed { i, t -> LocalChunk(nextChunk++, fileId, i, t) }.toMutableList()
        version++
    }
    override fun allChunks() = chunks.values.flatten()
    override fun addQa(question: String, answer: String, sufficient: Boolean, mode: String) {
        history += QaRecord(history.size + 1L, question, answer, sufficient, mode, 0)
    }
    override fun history(limit: Int) = history.takeLast(limit).reversed()

    private val notes = LinkedHashMap<Long, PhotoNote>()
    override fun photoNote(fileId: Long) = notes[fileId]
    override fun allPhotoNotes() = notes.values.toList()
    override fun saveUserFields(fileId: Long, user: PhotoUserFields, now: Long) {
        notes[fileId] = (notes[fileId] ?: PhotoNote(fileId)).copy(user = user, updatedAt = now)
        version++
    }
    override fun saveDerived(fileId: Long, ocrText: String?, laptopCaption: String?, laptopSummary: String?) {
        val n = notes[fileId] ?: PhotoNote(fileId)
        notes[fileId] = n.copy(derived = PhotoDerived(ocrText ?: n.derived.ocrText, laptopCaption ?: n.derived.laptopCaption, laptopSummary ?: n.derived.laptopSummary))
        version++
    }
}

fun tempDir(): File = Files.createTempDirectory("memora-test").toFile()

fun sha256Of(bytes: ByteArray): String = LocalLibrary.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun newLibrary(db: LocalDb = InMemoryLocalDb(), ocr: OcrEngine? = null, dir: File = tempDir()) =
    LocalLibrary(db, File(dir, "vault"), DefaultExtractor(ocr))

/** Pretends to be OCR for photos: returns the text it was told the image contains. */
class FakeOcr(private val text: String = "ZENBREW WARRANTY CARD Warranty period: 2 years from purchase") : OcrEngine {
    var calls = 0
    override suspend fun recognizeImage(file: File): String { calls++; return text }
    override suspend fun recognizePdf(file: File, maxPages: Int): String { calls++; return text }
}

/** A fake laptop: serves bytes by file id, optionally dropping the connection part-way. */
class FakeRemote(private val files: Map<Long, ByteArray>) : RemoteSource {
    var failAfterBytes: Int? = null
    var opens = 0
    var lastStart = -1L
    var ignoreRange = false
    var error: Exception? = null
    /** When set, open() waits for it, so a test can hold one download in flight. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun entry(
        id: Long, name: String, mime: String = "application/octet-stream", sha: String? = null, available: Boolean = true,
        photoCaption: String? = null, photoSummary: String? = null,
    ) = RemoteFile(
        id, name, name.substringAfterLast('.', ""), "", mime, files.getValue(id).size.toLong(), sha ?: sha256Of(files.getValue(id)), available, "indexed",
        photoCaption, photoSummary,
    )

    override suspend fun manifest(): List<RemoteFile> = files.keys.map { entry(it, "f$it") }

    override suspend fun open(id: Long, startAt: Long): RemoteStream {
        opens++
        lastStart = startAt
        error?.let { throw it }
        gate?.await()
        val data = files.getValue(id)
        val start = if (ignoreRange) 0 else startAt.toInt()
        val body = data.copyOfRange(start, data.size)
        val limit = failAfterBytes
        val input: InputStream = if (limit != null) {
            object : InputStream() {
                private val inner = ByteArrayInputStream(body)
                private var served = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served >= limit) throw IOException("connection reset")
                    val n = inner.read(b, off, minOf(len, limit - served))
                    if (n > 0) served += n
                    return n
                }
            }
        } else ByteArrayInputStream(body)
        return RemoteStream(data.size.toLong(), sha256Of(data), start.toLong(), input)
    }
}

/** An on-device model that returns a canned reply and records what it was asked. */
class FakeEngine(var reply: String = "", var failWith: LocalAiException? = null) : LocalAiEngine {
    var prompts = mutableListOf<String>()
    override suspend fun generate(system: String, user: String): String {
        prompts += user
        failWith?.let { throw it }
        return reply
    }
}
