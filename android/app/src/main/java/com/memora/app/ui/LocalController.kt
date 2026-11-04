package com.memora.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.memora.app.data.Answer
import com.memora.app.data.MemoraApi
import com.memora.app.data.SearchHit
import com.memora.app.local.Chunker
import com.memora.app.local.MetadataReader
import com.memora.app.local.PhotoMetadata
import com.memora.app.local.DownloadResult
import com.memora.app.local.ImportException
import com.memora.app.local.ImportResult
import com.memora.app.local.IndexState
import com.memora.app.local.LocalFile
import com.memora.app.local.LocalMemory
import com.memora.app.local.ModeResolver
import com.memora.app.local.ModelCatalog
import com.memora.app.local.ModelException
import com.memora.app.local.ModelSpec
import com.memora.app.local.ModelStatus
import com.memora.app.local.ModePrefs
import com.memora.app.local.OperatingMode
import com.memora.app.local.RemoteFile
import com.memora.app.local.Route
import com.memora.app.local.SyncEntry
import com.memora.app.local.SyncPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Progress of one laptop file being brought to the phone, keyed by the laptop's file id. */
data class TransferUi(
    val done: Long = 0,
    val total: Long = 0,
    val message: String? = null,
    val failed: Boolean = false,
    val finished: Boolean = false,
    val localId: Long? = null,
)

data class SyncUi(val entries: List<SyncEntry> = emptyList(), val loading: Boolean = false, val error: String? = null)

data class ModelUi(
    val status: ModelStatus = ModelStatus.NotInstalled,
    val busy: Boolean = false,
    val done: Long = 0,
    val total: Long = 0,
    val message: String? = null,
)

/**
 * Everything the phone does on its own: its library, offline answers, downloads from the laptop and the AI model.
 * It only touches the laptop (through [api]) when the user asks for a download or sync.
 */
class LocalController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val api: () -> MemoraApi?,
) {
    private val memory = LocalMemory(app)
    private val prefs = ModePrefs(app)
    private val resolver = app.contentResolver

    private val _mode = MutableStateFlow(prefs.mode)
    val mode: StateFlow<OperatingMode> = _mode

    private val _files = MutableStateFlow<List<LocalFile>>(emptyList())
    val files: StateFlow<List<LocalFile>> = _files

    private val _sync = MutableStateFlow(SyncUi())
    val sync: StateFlow<SyncUi> = _sync

    private val _transfers = MutableStateFlow<Map<Long, TransferUi>>(emptyMap())
    val transfers: StateFlow<Map<Long, TransferUi>> = _transfers

    private val _model = MutableStateFlow(ModelUi(memory.models.status()))
    val model: StateFlow<ModelUi> = _model

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private var indexJob: Job? = null

    init {
        refresh()
        resumeUnfinished()
    }

    fun setMode(m: OperatingMode) {
        prefs.mode = m
        _mode.value = m
    }

    fun route(laptopConfigured: Boolean, laptopReachable: Boolean): Route = ModeResolver.resolve(_mode.value, laptopConfigured, laptopReachable)

    fun dismissNotice() { _notice.value = null }

    fun refresh() { _files.value = memory.library.files() }

    val readyCount: Int get() = _files.value.count { it.state == IndexState.READY }

    // Offline answers

    /** Answers from the phone's own files with the on-device model. Never contacts the laptop. */
    suspend fun answer(question: String): Pair<Answer, List<SearchHit>> {
        val answer = withContext(Dispatchers.Default) { memory.answerer().answer(question) }
        val hits = answer.sources.map { SearchHit(it.fileId, it.name, "On this phone", it.kind, it.ext, it.snippet, local = true) }
        return answer to hits
    }

    /** Keeps the question and answer text on the phone (app-private database) so it can be reviewed later, offline. */
    fun recordAnswer(question: String, answer: Answer, mode: String) {
        runCatching { memory.db.addQa(question, answer.text, answer.sufficient, mode) }
    }

    fun history() = runCatching { memory.db.history(20) }.getOrDefault(emptyList())

    // Import

    fun importFiles(uris: List<Uri>) {
        scope.launch {
            var added = 0
            var duplicates = 0
            val problems = ArrayList<String>()
            for (uri in uris) {
                try {
                    val (name, size) = describe(uri)
                    val mime = resolver.getType(uri) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                    val result = withContext(Dispatchers.IO) {
                        val input = resolver.openInputStream(uri) ?: throw ImportException("Couldn't open $name")
                        input.use { memory.library.import(name, mime, it, origin = "phone-import") }
                    }
                    if (result is ImportResult.Added) added++ else duplicates++
                    if (size == 0L) problems += "$name is empty"
                } catch (e: ImportException) {
                    problems += e.message ?: "Import failed"
                } catch (e: Exception) {
                    problems += "Couldn't import a file: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            refresh()
            _notice.value = buildString {
                append("Imported $added file${if (added == 1) "" else "s"}")
                if (duplicates > 0) append(", $duplicates already on the phone")
                if (problems.isNotEmpty()) append(". ").append(problems.joinToString("; "))
                if (added > 0) append(". Making them searchable…")
            }
            indexPending()
        }
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return (c.getString(0) ?: "file") to c.getLong(1)
        }
        return (uri.lastPathSegment ?: "file") to -1L
    }

    /** Indexes everything not yet searchable, one file at a time so the UI and memory stay healthy. */
    fun indexPending() {
        if (indexJob?.isActive == true) return
        indexJob = scope.launch(Dispatchers.Default) {
            for (f in memory.library.files().filter { it.state == IndexState.PENDING }) {
                memory.library.index(f.id)
                refresh()
            }
        }
    }

    fun indexOne(id: Long) {
        scope.launch(Dispatchers.Default) {
            memory.library.index(id)
            refresh()
        }
    }

    /** After a crash or process death, files left mid-way go back through indexing automatically. */
    private fun resumeUnfinished() {
        if (memory.library.files().any { it.state == IndexState.PENDING || it.state == IndexState.EXTRACTING || it.state == IndexState.INDEXING }) {
            scope.launch(Dispatchers.Default) {
                memory.library.files()
                    .filter { it.state == IndexState.EXTRACTING || it.state == IndexState.INDEXING }
                    .forEach { memory.library.index(it.id) }
                refresh()
                indexPending()
            }
        }
    }

    fun retryFailed() {
        scope.launch(Dispatchers.Default) {
            memory.library.retryFailed()
            refresh()
        }
    }

    fun remove(id: Long) {
        memory.library.remove(id)
        refresh()
    }

    /** A content:// address other apps can open (read-only, temporary) for a file stored on the phone. */
    fun shareUri(f: LocalFile): Uri? = memory.library.fileOnDisk(f)?.let {
        FileProvider.getUriForFile(app, "${app.packageName}.files", it)
    }

    /** Copies the stored original to a place the user picked with the system file dialog (which also avoids name clashes). */
    fun export(f: LocalFile, destination: Uri) {
        scope.launch(Dispatchers.IO) {
            val source = memory.library.fileOnDisk(f)
            _notice.value = try {
                if (source == null) throw ImportException("The stored copy is missing.")
                val out = resolver.openOutputStream(destination) ?: throw ImportException("Couldn't write to that location.")
                out.use { o -> source.inputStream().use { it.copyTo(o) } }
                "Saved a copy of ${f.name}."
            } catch (e: Exception) {
                "Couldn't save a copy: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun file(id: Long): LocalFile? = _files.value.firstOrNull { it.id == id }

    fun readBytes(f: LocalFile): ByteArray? = memory.library.fileOnDisk(f)?.readBytes()

    fun diskFile(f: LocalFile): File? = memory.library.fileOnDisk(f)

    /** The text Memora extracted from a stored file (OCR or document text), reassembled from its indexed chunks. */
    fun extractedText(id: Long): String =
        runCatching { Chunker.join(memory.db.allChunks().filter { it.fileId == id }.sortedBy { it.index }.map { it.text }) }.getOrDefault("")

    /** What the user wrote about a photo plus the machine-made text, both read from the phone's database. */
    fun photoNote(id: Long): com.memora.app.local.PhotoNote? = runCatching { memory.library.photoNote(id) }.getOrNull()

    /** Saves the user's own fields. Returns an error message, or null when saved. */
    suspend fun savePhotoFields(id: Long, fields: com.memora.app.local.PhotoUserFields): String? = withContext(Dispatchers.IO) {
        try {
            memory.library.saveUserFields(id, fields)
            refresh()
            null
        } catch (e: ImportException) {
            e.message
        } catch (e: Exception) {
            "Couldn't save: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun setFavorite(id: Long, favorite: Boolean) {
        scope.launch(Dispatchers.IO) {
            val current = memory.library.photoNote(id)?.user ?: com.memora.app.local.PhotoUserFields()
            runCatching { memory.library.saveUserFields(id, current.copy(favorite = favorite)) }
            refresh()
        }
    }

    /** Ids of photos the user marked as favourites. */
    fun favoriteIds(): Set<Long> = runCatching { memory.db.allPhotoNotes().filter { it.user.favorite }.map { it.fileId }.toSet() }.getOrDefault(emptySet())

    private val captureCache =HashMap<String, com.memora.app.local.CaptureTime?>()

    /** Capture time from the photo's metadata, read once per file content and remembered. Null when the file has none. */
    private fun captureOf(f: LocalFile): com.memora.app.local.CaptureTime? = synchronized(captureCache) {
        if (captureCache.containsKey(f.sha256)) captureCache[f.sha256]
        else (if (f.mime.startsWith("image/") || f.ext in com.memora.app.local.DefaultExtractor.IMAGE_EXTS) metadata(f).capture else null)
            .also { captureCache[f.sha256] = it }
    }

    /** One dated entry per stored file, for the timeline and the home screen. Reads metadata off the main thread. */
    suspend fun timeline(files: List<LocalFile>): List<com.memora.app.local.TimelineEntry> = withContext(Dispatchers.IO) {
        files.map { com.memora.app.local.Timeline.entry(it, captureOf(it)) }
    }

    fun related(f: LocalFile): List<com.memora.app.local.RelatedMemory> = runCatching {
        com.memora.app.local.Related.find(f, extractedText(f.id), memory.index)
    }.getOrDefault(emptyList())

    /** Capture time, location and size read from the stored photo itself. */
    fun metadata(f: LocalFile): PhotoMetadata = diskFile(f)?.let { MetadataReader.read(it) } ?: PhotoMetadata()

    // Sync and download from the laptop

    fun loadSync() {
        val remote = api() ?: run { _sync.value = SyncUi(error = "No laptop is set up."); return }
        scope.launch {
            _sync.update { it.copy(loading = true, error = null) }
            try {
                val manifest = remote.manifest()
                _sync.value = SyncUi(SyncPlanner.plan(manifest, memory.library.files()))
            } catch (e: Exception) {
                _sync.update { it.copy(loading = false, error = e.message ?: "Couldn't reach the laptop.") }
            }
        }
    }

    /** Downloads the laptop's original file; verified before it is stored. Making it searchable is a separate, offered step. */
    fun download(file: RemoteFile, thenIndex: Boolean = false) {
        val remote = api() ?: return
        val downloader = memory.downloader(remote)
        if (downloader.isRunning(file.id)) return
        scope.launch {
            _transfers.update { it + (file.id to TransferUi(total = file.size)) }
            val result = withContext(Dispatchers.IO) {
                downloader.download(file) { done, total ->
                    _transfers.update { it + (file.id to TransferUi(done, total)) }
                }
            }
            when (result) {
                is DownloadResult.Done -> {
                    _transfers.update { it + (file.id to TransferUi(file.size, file.size, finished = true, localId = result.file.id)) }
                    refresh()
                    if (thenIndex && result.file.state != IndexState.READY) indexOne(result.file.id)
                    refreshSyncQuietly()
                }
                is DownloadResult.Failed -> _transfers.update { it + (file.id to TransferUi(message = result.message, failed = true)) }
                DownloadResult.AlreadyRunning -> Unit
            }
        }
    }

    /** Download triggered from an evidence card: only the laptop's file id is known there. */
    fun downloadById(remoteId: Long, thenIndex: Boolean = false) {
        val remote = api() ?: return
        scope.launch {
            try {
                val entry = remote.manifest().firstOrNull { it.id == remoteId }
                    ?: throw IllegalStateException("The laptop no longer lists this file.")
                download(entry, thenIndex)
            } catch (e: Exception) {
                _transfers.update { it + (remoteId to TransferUi(message = e.message ?: "Couldn't start the download.", failed = true)) }
            }
        }
    }

    private fun refreshSyncQuietly() {
        if (_sync.value.entries.isNotEmpty()) loadSync()
    }

    // On-device AI model

    fun refreshModel() { _model.update { it.copy(status = memory.models.status()) } }

    /** Downloads a model after the user confirmed in the UI (requires internet this one time). */
    fun downloadModel(spec: ModelSpec) {
        if (_model.value.busy) return
        scope.launch(Dispatchers.IO) {
            _model.value = ModelUi(memory.models.status(), busy = true, total = spec.sizeBytes, message = "Downloading ${spec.label}…")
            try {
                memory.models.download(spec) { done, total -> _model.update { it.copy(done = done, total = total) } }
                memory.engine.release()
                _model.value = ModelUi(memory.models.status(), message = "${spec.label} is installed. It stays on this phone.")
            } catch (e: ModelException) {
                _model.value = ModelUi(memory.models.status(), message = e.message)
            } catch (e: Exception) {
                _model.value = ModelUi(memory.models.status(), message = "Download failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun importModel(uri: Uri) {
        if (_model.value.busy) return
        scope.launch(Dispatchers.IO) {
            _model.value = ModelUi(memory.models.status(), busy = true, message = "Copying the model…")
            try {
                val (name, size) = describe(uri)
                val input = resolver.openInputStream(uri) ?: throw ModelException("Couldn't open that file.")
                input.use { memory.models.importFrom(name, it, size) }
                memory.engine.release()
                _model.value = ModelUi(memory.models.status(), message = "Model imported. It will be checked the first time it answers.")
            } catch (e: ModelException) {
                _model.value = ModelUi(memory.models.status(), message = e.message)
            } catch (e: Exception) {
                _model.value = ModelUi(memory.models.status(), message = "Import failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun removeModel() {
        memory.models.active()?.let { memory.engine.release(); memory.models.remove(it) }
        refreshModel()
    }

    val modelProblem: String? get() = memory.engine.lastError

    val catalog: List<ModelSpec> get() = ModelCatalog.models

    /** Frees the model's memory (called when the system is low on memory). */
    fun releaseModel() = memory.engine.release()

    fun modelFile(): File? = memory.models.active()
}
