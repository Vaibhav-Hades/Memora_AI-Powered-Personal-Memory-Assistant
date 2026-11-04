package com.memora.app.data

import com.memora.app.local.RemoteFile
import com.memora.app.local.RemoteSource
import com.memora.app.local.RemoteStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where the Memora server runs: the laptop's address on the Wi-Fi, its port, and the pairing code. */
data class ServerConfig(val host: String, val port: Int, val code: String) {
    val label: String get() = "$host:$port"

    companion object {
        /** 10/8, 172.16/12, 192.168/16 and link-local 169.254/16: the ranges used by home and hotspot networks. */
        fun isPrivateIpv4(ip: String): Boolean {
            val p = ip.split(".").mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return false
            return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
        }

        /**
         * Checks what the user typed before any network call. Returns an error message, or null when it looks usable.
         * "localhost" is the most common mistake: on the phone it means the phone itself, not the laptop.
         */
        fun validate(host: String, port: String, code: String): String? {
            val h = host.trim()
            if (h.isEmpty()) return "Enter the laptop's Wi-Fi address, for example 192.168.1.20."
            if (h.contains("://") || h.contains("/")) return "Enter only the address, without http:// or slashes."
            val lower = h.lowercase()
            if (lower == "localhost" || lower.startsWith("127.") || lower == "::1" || lower == "0.0.0.0") {
                return "“$h” means this phone itself. Enter the laptop's Wi-Fi address shown by Memora on the laptop."
            }
            if (!Regex("^[A-Za-z0-9.\\-]+$").matches(h)) return "“$h” is not a valid address."
            val looksLikeIp = Regex("^[0-9.]+$").matches(h)
            if (looksLikeIp) {
                val parts = h.split(".")
                if (parts.size != 4 || parts.any { it.isEmpty() || it.toInt() > 255 }) {
                    return "“$h” is not a valid IP address. It should look like 192.168.1.20."
                }
            }
            // Plain HTTP is allowed by the network security config (LAN addresses can't be listed there), so the app
            // itself refuses anything that isn't a local-network address: never a public IP or internet host name.
            if (looksLikeIp && !isPrivateIpv4(h)) {
                return "“$h” is not a local-network address. Memora only talks to a laptop on your own Wi-Fi (for example 192.168.x.x or 10.x.x.x)."
            }
            if (!looksLikeIp && !(lower.endsWith(".local") || lower.endsWith(".lan") || !lower.contains('.'))) {
                return "“$h” looks like an internet address. Use the laptop's Wi-Fi IP address shown by Memora."
            }
            val p = port.trim().toIntOrNull()
            if (p == null || p !in 1..65535) return "The port must be a number between 1 and 65535 (usually 8000)."
            if (code.trim().replace("-", "").length < 6) return "Enter the pairing code shown by Memora on the laptop."
            return null
        }
    }
}

/** A failure explained in words the user can act on. */
class MemoraException(message: String, val kind: Kind, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { UNREACHABLE, TIMEOUT, UNAUTHORIZED, NOT_MEMORA, AI_UNAVAILABLE, REJECTED, SERVER }
}

data class ServerInfo(val serverName: String, val version: String)

data class Health(
    val modelOk: Boolean,
    val modelError: String?,
    val filesIndexed: Int,
    val indexingRunning: Boolean,
    val indexingDone: Int,
    val indexingTotal: Int,
    val indexingCurrent: String?,
)

data class Source(
    val ref: Int,
    val cited: Boolean,
    val fileId: Long,
    val name: String,
    val path: String,
    val kind: String,
    val ext: String,
    val snippet: String,
    /** True for evidence from the phone's own library: [fileId] is then a local id and [path] is not a laptop path. */
    val local: Boolean = false,
)

data class Answer(val text: String, val sufficient: Boolean, val sources: List<Source>)

data class SearchHit(
    val fileId: Long, val name: String, val path: String, val kind: String, val ext: String, val text: String,
    /** True when [fileId] is an id in the phone's own library. */
    val local: Boolean = false,
)

data class FileInfo(
    val id: Long,
    val name: String,
    val path: String,
    val kind: String,
    val status: String,
    val error: String?,
    val summary: String?,
    val duplicate: Boolean = false,
)

data class Folder(
    val id: Long,
    val path: String,
    val fileCount: Int,
    val indexedCount: Int,
    val errorCount: Int,
    val isUploads: Boolean,
)

data class SuggestedFolder(val name: String, val path: String, val approved: Boolean)

/** HTTP client for the Memora server on the laptop. All calls are main-safe suspend functions. */
class MemoraApi(val config: ServerConfig, client: OkHttpClient = sharedClient) : RemoteSource {

    private val http = client
    // Answers can take a while: the first question after a restart loads the model on the laptop.
    private val slowHttp = client.newBuilder().readTimeout(4, TimeUnit.MINUTES).build()
    private val uploadHttp = client.newBuilder().writeTimeout(2, TimeUnit.MINUTES).readTimeout(1, TimeUnit.MINUTES).build()

    private val viaTailscale = TailscaleAddress.isTailscaleIpv4(config.host.trim()) || config.host.trim().lowercase().endsWith(".ts.net")

    private val base: HttpUrl =HttpUrl.Builder().scheme("http").host(config.host.trim()).port(config.port).build()

    private fun url(path: String, vararg query: Pair<String, String>): HttpUrl =
        base.newBuilder().addPathSegments(path.trimStart('/')).apply { query.forEach { addQueryParameter(it.first, it.second) } }.build()

    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url).header("X-Memora-Code", config.code.trim())

    // Connection

    suspend fun ping(): ServerInfo {
        val json = getJson(url("api/ping"))
        if (json.optString("app") != "memora") {
            throw MemoraException("Something answered at ${config.label}, but it isn't Memora.", MemoraException.Kind.NOT_MEMORA)
        }
        return ServerInfo(json.optString("server_name", "laptop"), json.optString("version"))
    }

    suspend fun health(): Health {
        val json = getJson(url("api/health"))
        val model = json.getJSONObject("model")
        val stats = json.getJSONObject("stats")
        val indexing = json.getJSONObject("indexing")
        return Health(
            modelOk = model.optBoolean("ok"),
            modelError = model.optStringOrNull("error")
                ?: model.optJSONArray("missing_models")?.takeIf { it.length() > 0 }
                    ?.let { "Missing AI model on the laptop: ${it.join(", ").replace("\"", "")}" },
            filesIndexed = stats.optInt("indexed"),
            indexingRunning = indexing.optBoolean("running"),
            indexingDone = indexing.optInt("done"),
            indexingTotal = indexing.optInt("total"),
            indexingCurrent = indexing.optStringOrNull("current"),
        )
    }

    // Search and answers

    suspend fun search(query: String, limit: Int = 12): List<SearchHit> {
        val arr = getJsonArray(url("api/search", "q" to query, "limit" to limit.toString()))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SearchHit(o.getLong("file_id"), o.getString("name"), o.getString("path"), o.getString("kind"), o.optString("ext"), o.optString("text"))
        }
    }

    suspend fun ask(question: String): Answer {
        val body = JSONObject().put("question", question).toString().toRequestBody(JSON)
        val json = JSONObject(String(execute(slowHttp, request(url("api/ask")).post(body).build())))
        val sources = json.getJSONArray("sources")
        return Answer(
            text = json.getString("answer"),
            sufficient = json.optBoolean("sufficient"),
            sources = (0 until sources.length()).map { i ->
                val o = sources.getJSONObject(i)
                Source(
                    ref = o.getInt("ref"),
                    cited = o.optBoolean("cited"),
                    fileId = o.getLong("file_id"),
                    name = o.getString("name"),
                    path = o.getString("path"),
                    kind = o.optString("kind"),
                    ext = o.optString("ext"),
                    snippet = o.optString("snippet"),
                )
            },
        )
    }

    // Files

    suspend fun file(id: Long): FileInfo = parseFile(getJson(url("api/files/$id")))

    /** JPEG preview bytes, or null when the server has no preview for this file type. */
    suspend fun thumbnail(id: Long, size: Int): ByteArray? = try {
        execute(http, request(url("api/files/$id/thumbnail", "size" to size.toString())).build())
    } catch (e: MemoraException) {
        if (e.kind == MemoraException.Kind.REJECTED) null else throw e
    }

    /** What the laptop offers for download, with sizes and SHA-256 hashes. */
    override suspend fun manifest(): List<RemoteFile> {
        val arr = getJson(url("api/sync/manifest")).getJSONArray("files")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val photo = o.optJSONObject("photo")
            RemoteFile(
                id = o.getLong("id"), name = o.getString("name"), ext = o.optString("ext"), kind = o.optString("kind"),
                mime = o.optString("mime", "application/octet-stream"), size = o.getLong("size"),
                sha256 = o.optStringOrNull("sha256"), available = o.optBoolean("available"), status = o.optString("status"),
                photoCaption = photo?.optStringOrNull("caption"), photoSummary = photo?.optStringOrNull("summary"),
                capturedDate = photo?.optStringOrNull("captured_date"),
            )
        }
    }

    /** Streams the original bytes (never a thumbnail or text), resuming at [startAt] when the laptop allows it. */
    override suspend fun open(id: Long, startAt: Long): RemoteStream = withContext(Dispatchers.IO) {
        val req = request(url("api/files/$id/download")).apply { if (startAt > 0) header("Range", "bytes=$startAt-") }.build()
        val response = try {
            slowHttp.newCall(req).execute()
        } catch (e: IOException) {
            throw networkError(e)
        }
        if (response.code !in 200..299) {
            val detail = runCatching { JSONObject(response.use { it.body.string() }).opt("detail") }.getOrNull()
                ?.let { if (it is JSONArray) "The server rejected the request." else it.toString() }
            throw when (response.code) {
                404 -> MemoraException("The laptop no longer lists this file.", MemoraException.Kind.REJECTED)
                410 -> MemoraException("The original file has been moved or deleted on the laptop.", MemoraException.Kind.REJECTED)
                else -> httpError(response.code, detail)
            }
        }
        val resumed = response.code == 206
        val length = response.body.contentLength()
        MemoraStreamFactory.create(response, if (resumed) startAt else 0L, length)
    }

    suspend fun content(id: Long): ByteArray = execute(slowHttp, request(url("api/files/$id/content")).build())

    suspend fun revealOnLaptop(id: Long) {
        execute(http, request(url("api/files/$id/reveal")).post(ByteArray(0).toRequestBody(null)).build())
    }

    /** Sends one prepared JPEG; the laptop stores it in its phone-uploads folder and indexes it in the background. */
    suspend fun upload(fileName: String, jpeg: ByteArray): FileInfo {
        val req = request(url("api/uploads", "filename" to fileName)).post(jpeg.toRequestBody("image/jpeg".toMediaType())).build()
        return parseFile(JSONObject(String(execute(uploadHttp, req))))
    }

    // Folders on the laptop

    suspend fun folders(): List<Folder> {
        val arr = getJsonArray(url("api/folders"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Folder(o.getLong("id"), o.getString("path"), o.optInt("file_count"), o.optInt("indexed_count"), o.optInt("error_count"), o.optBoolean("is_uploads"))
        }
    }

    suspend fun suggestedFolders(): List<SuggestedFolder> {
        val arr = getJsonArray(url("api/folders/suggested"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SuggestedFolder(o.getString("name"), o.getString("path"), o.optBoolean("approved"))
        }
    }

    suspend fun addFolder(path: String) {
        val body = JSONObject().put("path", path).toString().toRequestBody(JSON)
        execute(http, request(url("api/folders")).post(body).build())
    }

    suspend fun rescan(folderId: Long) {
        execute(http, request(url("api/folders/$folderId/reindex")).post(ByteArray(0).toRequestBody(null)).build())
    }

    // Plumbing

    private suspend fun getJson(url: HttpUrl): JSONObject = parse { JSONObject(String(execute(http, request(url).build()))) }

    private suspend fun getJsonArray(url: HttpUrl): JSONArray = parse { JSONArray(String(execute(http, request(url).build()))) }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw MemoraException("Something answered at ${config.label}, but it isn't Memora.", MemoraException.Kind.NOT_MEMORA, e)
    }

    private fun parseFile(o: JSONObject) = FileInfo(
        id = o.getLong("id"),
        name = o.getString("name"),
        path = o.getString("path"),
        kind = o.optString("kind"),
        status = o.optString("status"),
        error = o.optStringOrNull("error"),
        summary = o.optStringOrNull("summary"),
        duplicate = o.optBoolean("duplicate"),
    )

    /**
     * Runs the call (cancelled if the coroutine is) and returns the response body.
     * Every failure becomes a [MemoraException].
     */
    private suspend fun execute(client: OkHttpClient, req: Request): ByteArray {
        val reply = try {
            client.newCall(req).await()
        } catch (e: IOException) {
            throw networkError(e)
        }
        if (reply.code in 200..299) return reply.body
        val detail = runCatching { JSONObject(String(reply.body)).opt("detail") }.getOrNull()
            ?.let { if (it is JSONArray) "The server rejected the request." else it.toString() }
        throw httpError(reply.code, detail)
    }

    private fun networkError(e: IOException): MemoraException = when (e) {
        is UnknownHostException -> MemoraException(
            "Can't find “${config.host}” on this network. Check the address in Settings.", MemoraException.Kind.UNREACHABLE, e,
        )
        is SocketTimeoutException -> MemoraException(
            "The laptop at ${config.label} didn't answer in time. It may be busy or asleep, or the AI is still loading. Try again.",
            MemoraException.Kind.TIMEOUT, e,
        )
        is ConnectException, is NoRouteToHostException -> MemoraException(
            if (viaTailscale) {
                "Can't reach Memora at ${config.label} over Tailscale. Check that Tailscale is connected on the phone and the " +
                    "laptop, that the laptop is on, and that Memora was started with --lan --tailscale."
            } else {
                "Can't reach Memora at ${config.label}. Check that the phone and laptop are on the same Wi-Fi, " +
                    "that the server was started with --lan, and that Windows Firewall allows port ${config.port}."
            },
            MemoraException.Kind.UNREACHABLE, e,
        )
        else -> MemoraException(
            "Lost the connection to the laptop (${e.message ?: e.javaClass.simpleName}). Try again.", MemoraException.Kind.UNREACHABLE, e,
        )
    }

    private fun httpError(code: Int, detail: String?): MemoraException = when (code) {
        401 -> MemoraException("The pairing code is wrong. Copy it again from Memora on the laptop.", MemoraException.Kind.UNAUTHORIZED)
        503 -> MemoraException("The AI on the laptop isn't available: ${detail ?: "is Ollama running?"}", MemoraException.Kind.AI_UNAVAILABLE)
        in 400..499 -> MemoraException(detail ?: "The server rejected the request ($code).", MemoraException.Kind.REJECTED)
        else -> MemoraException(detail ?: "The server had a problem ($code).", MemoraException.Kind.SERVER)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        /** For connection checks: the whole request, DNS included, gives up after 8 seconds. */
        val probeClient: OkHttpClient = sharedClient.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    }
}

/** Builds a [RemoteStream] from a streaming OkHttp response; closing the stream closes the response. */
private object MemoraStreamFactory {
    fun create(response: Response, startedAt: Long, remaining: Long): RemoteStream {
        val total = if (remaining >= 0) startedAt + remaining else -1L
        return RemoteStream(total, response.header("X-Memora-Sha256"), startedAt, response.body.byteStream()) { response.close() }
    }
}

/** Status and fully-read body. */
private class Reply(val code: Int, val body: ByteArray)

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

/**
 * Enqueues the call and reads the whole body on OkHttp's background thread, so callers on the main thread
 * never touch the network (Android throws NetworkOnMainThreadException otherwise).
 */
private suspend fun Call.await(): Reply = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val reply = try {
                response.use { Reply(it.code, it.body.bytes()) }
            } catch (e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
                return
            }
            cont.resume(reply)
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
