package com.memora.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.memora.app.data.Answer
import com.memora.app.data.ConnectionRouter
import com.memora.app.data.LaptopRoute
import com.memora.app.data.RouteOutcome
import com.memora.app.data.TailscaleAddress
import com.memora.app.data.TailscaleConfig
import com.memora.app.data.Folder
import com.memora.app.data.Health
import com.memora.app.data.MemoraApi
import com.memora.app.data.MemoraException
import com.memora.app.data.PhotoPrep
import com.memora.app.data.SearchHit
import com.memora.app.data.ServerConfig
import com.memora.app.data.ServerInfo
import com.memora.app.data.ServerSettings
import com.memora.app.data.SuggestedFolder
import com.memora.app.local.ModePrefs
import com.memora.app.local.OperatingMode
import com.memora.app.local.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Connection {
    data object NotConfigured : Connection
    data object Checking : Connection
    /** [route] is the path the successful request actually took. */
    data class Connected(val info: ServerInfo, val health: Health?, val route: LaptopRoute = LaptopRoute.LAN) : Connection
    data class Failed(val message: String, val unauthorized: Boolean = false) : Connection
}

/**
 * The connection state before any check has run. "Checking" is only right when a check really starts: in Phone only
 * mode none does, so starting there left the state on "Checking" for good and kept the Settings "Test & save
 * connection" button disabled (it is disabled while a check is running).
 */
internal fun initialConnection(hasLaptopRoute: Boolean, mode: OperatingMode): Connection =
    if (hasLaptopRoute && mode != OperatingMode.PHONE_ONLY) Connection.Checking else Connection.NotConfigured

data class AskState(
    val question: String = "",
    val searching: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val searchError: String? = null,
    val answering: Boolean = false,
    val answer: Answer? = null,
    val answerError: String? = null,
    /** Where the answer was produced, so the user always knows which device handled their question. */
    val where: String? = null,
)

enum class UploadStatus { Waiting, Preparing, Uploading, Indexing, Indexed, AlreadyIndexed, Failed }

data class UploadItem(
    val uri: Uri,
    val name: String,
    val status: UploadStatus = UploadStatus.Waiting,
    val fileId: Long? = null,
    val message: String? = null,
)

data class LibraryState(
    val folders: List<Folder> = emptyList(),
    val suggested: List<SuggestedFolder> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** Result of the Settings "Test connection" button for the Tailscale route (separate from the active connection). */
sealed interface TailscaleTest {
    data object Idle : TailscaleTest
    data object Testing : TailscaleTest
    data class Ok(val serverName: String) : TailscaleTest
    data class Failed(val message: String) : TailscaleTest
}

class MemoraViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = ServerSettings(app)
    private val resolver = app.contentResolver
    private val router = ConnectionRouter { MemoraApi(it, MemoraApi.probeClient).ping() }

    private val _tailscale = MutableStateFlow(settings.loadTailscale())
    val tailscale: StateFlow<TailscaleConfig> = _tailscale

    private val _tailscaleTest = MutableStateFlow<TailscaleTest>(TailscaleTest.Idle)
    val tailscaleTest: StateFlow<TailscaleTest> = _tailscaleTest

    private val _lastSuccess = MutableStateFlow(settings.lastSuccess())
    /** When a request to the laptop last worked, and through which route. History only, not proof of reachability. */
    val lastSuccess: StateFlow<Pair<LaptopRoute, Long>?> = _lastSuccess

    private var activeRoute = LaptopRoute.LAN
    private var api: MemoraApi? = initialApi()

    private fun initialApi(): MemoraApi? {
        settings.load()?.let { return MemoraApi(it) }
        val ts = _tailscale.value
        val code = settings.code()
        if (ts.configured && code.isNotBlank()) {
            activeRoute = LaptopRoute.TAILSCALE
            return MemoraApi(ServerConfig(ts.host, ts.port, code))
        }
        return null
    }

    val savedConfig: ServerConfig? get() = settings.load()
    val savedCode: String get() = settings.code()

    /** True when some route to the laptop (LAN or Tailscale) has been set up. Says nothing about reachability. */
    fun hasLaptopRoute(): Boolean = settings.load() != null || (_tailscale.value.configured && settings.code().isNotBlank())

    private val _connection = MutableStateFlow<Connection>(initialConnection(api != null, ModePrefs(app).mode))
    val connection: StateFlow<Connection> = _connection

    private val _ask = MutableStateFlow(AskState())
    val ask: StateFlow<AskState> = _ask

    private val _uploads = MutableStateFlow<List<UploadItem>>(emptyList())
    val uploads: StateFlow<List<UploadItem>> = _uploads

    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library

    private var askJob: Job? = null
    private var uploadJob: Job? = null
    private val bitmaps = LruCache<String, Bitmap>(64)

    /** The phone's own library, offline AI, downloads and operating mode. */
    val local = LocalController(app, viewModelScope) { api }

    init {
        // Phone-only mode never needs the laptop, so it isn't contacted at all. Otherwise one check at start-up.
        if (hasLaptopRoute() && local.mode.value != OperatingMode.PHONE_ONLY) viewModelScope.launch { checkConnection() }
    }

    fun setMode(mode: OperatingMode) {
        local.setMode(mode)
        // Switching to a mode that can use the laptop: look once, so the status shown is current.
        if (mode != OperatingMode.PHONE_ONLY && hasLaptopRoute() && _connection.value !is Connection.Connected) {
            viewModelScope.launch { checkConnection() }
        }
    }

    val laptopReachable: Boolean get() = _connection.value is Connection.Connected

    fun onLowMemory() = local.releaseModel()

    // Connection

    /** Validates and tests the address typed in Settings; saves it only when the server answers as Memora. */
    fun connect(host: String, port: String, code: String) {
        val problem = ServerConfig.validate(host, port, code)
        if (problem != null) {
            _connection.value = Connection.Failed(problem)
            return
        }
        val config = ServerConfig(host.trim(), port.trim().toInt(), code.trim())
        viewModelScope.launch {
            val previous = _connection.value
            _connection.value = Connection.Checking
            when (val outcome = router.test(LaptopRoute.LAN, config)) {
                is RouteOutcome.Connected -> {
                    settings.save(config)
                    adopt(outcome)
                }
                is RouteOutcome.Failed -> _connection.value = Connection.Failed(outcome.message, outcome.kind == MemoraException.Kind.UNAUTHORIZED)
                else -> _connection.value = previous // another check is already running
            }
        }
    }

    /** Makes a verified route the active one. Only called with a route that just answered a real request. */
    private suspend fun adopt(outcome: RouteOutcome.Connected) {
        val candidate = MemoraApi(outcome.config)
        if (api?.config != outcome.config) bitmaps.evictAll()
        api = candidate
        activeRoute = outcome.route
        val now = System.currentTimeMillis()
        settings.recordSuccess(outcome.route, now)
        _lastSuccess.value = outcome.route to now
        _connection.value = Connection.Connected(outcome.info, runCatching { candidate.health() }.getOrNull(), outcome.route)
    }

    /**
     * Looks for the laptop once: LAN first, then Tailscale if configured. Phone only never gets here.
     * Overlapping calls are ignored, so nothing piles up.
     */
    suspend fun checkConnection() {
        val tailscale = _tailscale.value
        val lan = settings.load()
        val previous = _connection.value
        _connection.value = Connection.Checking
        val code = lan?.code ?: settings.code()
        when (val outcome = router.connect(local.mode.value, lan, tailscale, code)) {
            is RouteOutcome.Connected -> adopt(outcome)
            is RouteOutcome.Failed -> _connection.value = Connection.Failed(outcome.message, outcome.kind == MemoraException.Kind.UNAUTHORIZED)
            is RouteOutcome.NotAttempted -> _connection.value = if (previous is Connection.Checking || !hasLaptopRoute()) Connection.NotConfigured else previous
            RouteOutcome.Busy -> _connection.value = previous // the running check will publish its own result
        }
    }

    /** Refreshes counts and indexing progress without flashing the "checking" state. */
    suspend fun refreshHealth() {
        val current = api ?: return
        val state = _connection.value
        try {
            val health = current.health()
            _connection.value = if (state is Connection.Connected) state.copy(health = health) else Connection.Connected(current.ping(), health, activeRoute)
        } catch (e: Exception) {
            noteFailure(e)
        }
    }

    // Tailscale (the optional remote route; the official Tailscale apps provide the tunnel)

    /** Tests the typed Tailscale address with the pairing code. Saves it only when the laptop answers as Memora. */
    fun testTailscale(host: String, port: String, code: String) {
        val problem = TailscaleAddress.validate(host, port)
            ?: if (code.trim().replace("-", "").length < 6) "Enter the pairing code shown by Memora on the laptop." else null
        if (problem != null) {
            _tailscaleTest.value = TailscaleTest.Failed(problem)
            return
        }
        val config = ServerConfig(host.trim(), port.trim().toInt(), code.trim())
        viewModelScope.launch {
            _tailscaleTest.value = TailscaleTest.Testing
            when (val outcome = router.test(LaptopRoute.TAILSCALE, config)) {
                is RouteOutcome.Connected -> {
                    settings.saveCode(config.code)
                    val saved = TailscaleConfig(true, config.host, config.port)
                    settings.saveTailscale(saved)
                    _tailscale.value = saved
                    _tailscaleTest.value = TailscaleTest.Ok(outcome.info.serverName)
                    // Only adopt it as the active route when the phone isn't already talking to the laptop another way.
                    if (local.mode.value != OperatingMode.PHONE_ONLY && _connection.value !is Connection.Connected) adopt(outcome)
                    else settings.recordSuccess(LaptopRoute.TAILSCALE, System.currentTimeMillis()).also { _lastSuccess.value = settings.lastSuccess() }
                }
                is RouteOutcome.Failed -> _tailscaleTest.value = TailscaleTest.Failed(outcome.message)
                else -> _tailscaleTest.value = TailscaleTest.Failed("Another connection check is still running. Try again in a moment.")
            }
        }
    }

    fun setTailscaleEnabled(enabled: Boolean) {
        val updated = _tailscale.value.copy(enabled = enabled)
        settings.saveTailscale(updated)
        _tailscale.value = updated
        _tailscaleTest.value = TailscaleTest.Idle
        val state = _connection.value
        if (!enabled && state is Connection.Connected && state.route == LaptopRoute.TAILSCALE) {
            _connection.value = Connection.Failed("Tailscale routing is off. Turn it on, or connect over the same Wi-Fi.")
            api = settings.load()?.let { MemoraApi(it) }
            activeRoute = LaptopRoute.LAN
        } else if (enabled && local.mode.value != OperatingMode.PHONE_ONLY && state !is Connection.Connected && hasLaptopRoute()) {
            viewModelScope.launch { checkConnection() }
        }
    }

    // Ask

    fun setQuestion(q: String) = _ask.update { it.copy(question = q) }

    /** Shows matching files right away (fast keyword + vector search), then the AI answer when it's ready. */
    fun submit(question: String = _ask.value.question) {
        val q = question.trim()
        if (q.isEmpty()) return
        val now = _ask.value
        if (now.answering && now.question.trim() == q) return // already being answered; a repeated tap must not start it again
        // The laptop was unreachable last time: look once more before deciding where to answer (bounded, one at a time).
        if (local.mode.value != OperatingMode.PHONE_ONLY && hasLaptopRoute() && !laptopReachable &&
            _connection.value !is Connection.Checking && !recheckingForQuestion
        ) {
            recheckingForQuestion = true
            askJob?.cancel()
            _ask.value = AskState(question = q, searching = true)
            askJob = viewModelScope.launch {
                try { checkConnection() } finally { recheckingForQuestion = false }
                routeAndAsk(q)
            }
            return
        }
        routeAndAsk(q)
    }

    private var recheckingForQuestion = false

    /** Stops the question in progress. Nothing is answered and nothing is shown as if it had been. */
    fun cancelAsk() {
        askJob?.cancel()
        recheckingForQuestion = false
        _ask.update { it.copy(searching = false, answering = false, answer = null, answerError = "Stopped. Nothing was answered.") }
    }

    private fun routeAndAsk(q: String) {
        when (val route = local.route(hasLaptopRoute(), laptopReachable)) {
            is Route.Unavailable -> {
                askJob?.cancel()
                _ask.value = AskState(question = q, answerError = route.reason)
                return
            }
            Route.Phone -> {
                askOnPhone(q)
                return
            }
            Route.Laptop -> Unit
        }
        val current = api ?: return
        askJob?.cancel()
        _ask.value = AskState(question = q, searching = true, answering = true, where = "Answered by the laptop via ${activeRoute.label}")
        askJob = viewModelScope.launch {
            var fallBack = false // Automatic mode: the laptop went away, so the phone answers instead
            val answer = async {
                try {
                    current.ask(q)
                } catch (e: Exception) {
                    noteFailure(e)
                    if (AutoFallback.shouldFallBack(local.mode.value, e)) fallBack = true
                    else _ask.update { it.copy(answerError = message(e)) }
                    null
                } finally {
                    _ask.update { it.copy(answering = false) }
                }
            }
            try {
                val hits = current.search(q).distinctBy { it.fileId }
                _ask.update { it.copy(searching = false, hits = hits) }
            } catch (e: Exception) {
                noteFailure(e)
                if (AutoFallback.shouldFallBack(local.mode.value, e)) fallBack = true
                else _ask.update { it.copy(searching = false, searchError = message(e)) }
            }
            val a = answer.await()
            if (fallBack) {
                askOnPhone(q)
                return@launch
            }
            a?.let { _ask.update { st -> st.copy(answer = it) } }
        }
    }

    /** Offline path: the phone's own files and AI model. Nothing is sent to the laptop or the internet. */
    private fun askOnPhone(q: String) {
        askJob?.cancel()
        _ask.value = AskState(question = q, searching = true, answering = true, where = "Answered on this phone")
        askJob = viewModelScope.launch {
            try {
                val (answer, hits) = local.answer(q)
                _ask.update { it.copy(searching = false, answering = false, hits = hits.distinctBy { h -> h.fileId }, answer = answer) }
                local.recordAnswer(q, answer, "phone")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _ask.update { it.copy(searching = false, answering = false, answerError = "The phone couldn't answer: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    // Phone photos

    fun addPhotos(uris: List<Uri>) {
        val known = _uploads.value.map { it.uri }.toSet()
        val fresh = uris.filter { it !in known }.map { UploadItem(it, PhotoPrep.displayName(resolver, it)) }
        _uploads.update { it + fresh }
    }

    fun removePhoto(item: UploadItem) = _uploads.update { list -> list.filterNot { it.uri == item.uri } }

    fun clearFinished() = _uploads.update { list ->
        list.filterNot { it.status == UploadStatus.Indexed || it.status == UploadStatus.AlreadyIndexed }
    }

    val isUploading: Boolean get() = uploadJob?.isActive == true

    /** Uploads waiting/failed photos one by one, then follows their indexing on the laptop until done. */
    fun uploadAll() {
        val current = api ?: return
        if (isUploading) return
        uploadJob = viewModelScope.launch {
            for (item in _uploads.value.filter { it.status == UploadStatus.Waiting || it.status == UploadStatus.Failed }) {
                setUpload(item.uri) { it.copy(status = UploadStatus.Preparing, message = null) }
                try {
                    val jpeg = withContext(Dispatchers.Default) { PhotoPrep.prepareForUpload(resolver, item.uri) }
                    setUpload(item.uri) { it.copy(status = UploadStatus.Uploading) }
                    val result = current.upload(item.name, jpeg)
                    val status = when {
                        result.duplicate && result.status == "indexed" -> UploadStatus.AlreadyIndexed
                        result.status == "indexed" -> UploadStatus.Indexed
                        result.status == "error" -> UploadStatus.Failed
                        else -> UploadStatus.Indexing
                    }
                    setUpload(item.uri) { it.copy(status = status, fileId = result.id, message = result.error) }
                } catch (e: MemoraException) {
                    setUpload(item.uri) { it.copy(status = UploadStatus.Failed, message = e.message) }
                    noteFailure(e)
                } catch (e: Exception) {
                    setUpload(item.uri) { it.copy(status = UploadStatus.Failed, message = "Couldn't read this photo: ${e.message}") }
                }
            }
            followIndexing(current)
        }
    }

    private suspend fun followIndexing(current: MemoraApi) {
        // Reading a photo with Gemma takes roughly 10-60 s per image on a laptop.
        repeat(400) {
            val pending = _uploads.value.filter { it.status == UploadStatus.Indexing && it.fileId != null }
            if (pending.isEmpty()) return
            delay(3000)
            var finished = false
            for (item in pending) {
                try {
                    val f = current.file(item.fileId!!)
                    when (f.status) {
                        "indexed" -> setUpload(item.uri) { it.copy(status = UploadStatus.Indexed) }
                        "error" -> setUpload(item.uri) { it.copy(status = UploadStatus.Failed, message = f.error ?: "Indexing failed on the laptop.") }
                    }
                    finished = finished || f.status == "indexed" || f.status == "error"
                } catch (e: Exception) {
                    noteFailure(e) // keep polling; the laptop may be briefly unreachable
                }
            }
            refreshHealth()
            // Uploads are indexed outside folder scans, so refresh the folder counts ourselves.
            if (finished) loadLibrary()
        }
    }

    private fun setUpload(uri: Uri, change: (UploadItem) -> UploadItem) =
        _uploads.update { list -> list.map { if (it.uri == uri) change(it) else it } }

    // Laptop folders

    fun loadLibrary() {
        val current = api ?: return
        viewModelScope.launch {
            _library.update { it.copy(loading = true, error = null) }
            try {
                val folders = current.folders()
                val suggested = current.suggestedFolders()
                _library.value = LibraryState(folders, suggested)
            } catch (e: Exception) {
                _library.update { it.copy(loading = false, error = message(e)) }
                noteFailure(e)
            }
        }
    }

    fun addFolder(path: String) = libraryAction { it.addFolder(path) }

    fun rescan(folderId: Long) = libraryAction { it.rescan(folderId) }

    private fun libraryAction(action: suspend (MemoraApi) -> Unit) {
        val current = api ?: return
        viewModelScope.launch {
            try {
                action(current)
                refreshHealth()
                loadLibrary()
            } catch (e: Exception) {
                _library.update { it.copy(error = message(e)) }
            }
        }
    }

    // Files from the laptop

    /** Cached preview of a file on the laptop; null when there is none (e.g. a .docx). */
    suspend fun thumbnail(fileId: Long, size: Int = 320): Bitmap? {
        val current = api ?: return null
        val key = "${current.config.label}/$fileId/$size"
        bitmaps.get(key)?.let { return it }
        val bytes = try {
            current.thumbnail(fileId, size)
        } catch (e: Exception) {
            null
        } ?: return null
        return withContext(Dispatchers.Default) { PhotoPrep.decodeBytes(bytes, size) }?.also { bitmaps.put(key, it) }
    }

    /** The original image at screen size, for the full-screen viewer. */
    suspend fun fullImage(fileId: Long, maxSide: Int): Result<Bitmap?> {
        val current = api ?: return Result.success(null)
        return try {
            val bytes = current.content(fileId)
            Result.success(withContext(Dispatchers.Default) { PhotoPrep.decodeBytes(bytes, maxSide) })
        } catch (e: Exception) {
            Result.failure(Exception(message(e)))
        }
    }

    /** Small preview of a file stored on the phone, decoded off the main thread and cached. Null for non-images. */
    suspend fun storedThumbnail(file: com.memora.app.local.LocalFile, size: Int = 320): Bitmap? {
        if (file.ext !in com.memora.app.local.DefaultExtractor.IMAGE_EXTS && !file.mime.startsWith("image/")) return null
        val key = "local/${file.sha256}/$size"
        bitmaps.get(key)?.let { return it }
        val disk = local.diskFile(file) ?: return null
        return withContext(Dispatchers.IO) { PhotoPrep.decodeFile(disk, size) }?.also { bitmaps.put(key, it) }
    }

    suspend fun localThumbnail(uri: Uri, size: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { PhotoPrep.localThumbnail(resolver, uri, size) }.getOrNull()
    }

    /** Laptop-side details of a file (status and AI description). Null when the laptop can't be asked. */
    suspend fun fileInfo(fileId: Long): com.memora.app.data.FileInfo? = try { api?.file(fileId) } catch (e: Exception) { null }

    suspend fun revealOnLaptop(fileId: Long): String = try {
        api?.revealOnLaptop(fileId)
        "Opened the file's folder on the laptop."
    } catch (e: Exception) {
        message(e)
    }

    // Errors

    private fun noteFailure(e: Exception) {
        if (e is MemoraException && e.kind in setOf(
                MemoraException.Kind.UNREACHABLE, MemoraException.Kind.TIMEOUT, MemoraException.Kind.UNAUTHORIZED,
            )
        ) {
            _connection.value = Connection.Failed(e.message ?: "Connection lost.", e.kind == MemoraException.Kind.UNAUTHORIZED)
        }
    }

    private fun message(e: Exception): String = when (e) {
        is MemoraException -> e.message ?: "Something went wrong."
        is IllegalArgumentException -> "That address isn't valid: ${e.message}"
        else -> "Unexpected error: ${e.message ?: e.javaClass.simpleName}"
    }
}
