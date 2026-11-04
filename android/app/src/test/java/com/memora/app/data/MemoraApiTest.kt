package com.memora.app.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Exercises the real OkHttp client against a tiny in-process HTTP server (no Android device needed). */
class MemoraApiTest {
    private lateinit var server: HttpServer
    private val routes = mutableMapOf<String, (HttpExchange) -> Unit>()
    private val seen = mutableListOf<Triple<String, String?, ByteArray>>() // path+query, code header, body

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen += Triple(ex.requestURI.toString(), ex.requestHeaders.getFirst("X-Memora-Code"), ex.requestBody.readBytes())
            val handler = routes[ex.requestURI.path]
            if (handler == null) reply(ex, 404, """{"detail":"Not Found"}""") else handler(ex)
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun reply(ex: HttpExchange, status: Int, body: String, type: String = "application/json") {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun api(code: String = "ABCDE-FGH23", client: OkHttpClient = MemoraApi.sharedClient) =
        MemoraApi(ServerConfig("127.0.0.1", server.address.port, code), client)

    private inline fun expectError(kind: MemoraException.Kind, block: () -> Unit): MemoraException {
        try {
            block()
        } catch (e: MemoraException) {
            assertEquals(e.message, kind, e.kind)
            return e
        }
        fail("expected $kind")
        throw AssertionError()
    }

    @Test
    fun validateCatchesCommonMistakes() {
        assertNull(ServerConfig.validate("192.168.1.20", "8000", "ABCDE-FGH23"))
        assertNull(ServerConfig.validate("my-laptop.local", "8000", "ABCDEFGH23"))
        assertTrue(ServerConfig.validate("localhost", "8000", "ABCDE-FGH23")!!.contains("phone itself"))
        assertTrue(ServerConfig.validate("127.0.0.1", "8000", "ABCDE-FGH23")!!.contains("phone itself"))
        assertNotNull(ServerConfig.validate("http://192.168.1.20", "8000", "ABCDE-FGH23"))
        assertNotNull(ServerConfig.validate("192.168.1.300", "8000", "ABCDE-FGH23"))
        assertNotNull(ServerConfig.validate("192.168.1", "8000", "ABCDE-FGH23"))
        assertNotNull(ServerConfig.validate("192.168.1.20", "99999", "ABCDE-FGH23"))
        assertNotNull(ServerConfig.validate("", "8000", "ABCDE-FGH23"))
        assertNotNull(ServerConfig.validate("192.168.1.20", "8000", ""))
    }

    @Test
    fun pingSendsPairingCodeAndReadsServerName() = runBlocking {
        routes["/api/ping"] = { reply(it, 200, """{"app":"memora","version":"0.3.0","server_name":"LAPTOP","lan":true}""") }
        val info = api().ping()
        assertEquals("LAPTOP", info.serverName)
        assertEquals("ABCDE-FGH23", seen.single().second)
    }

    @Test
    fun wrongPairingCodeIsExplained() = runBlocking {
        routes["/api/ping"] = { reply(it, 401, """{"detail":"Missing or wrong pairing code."}""") }
        val e = expectError(MemoraException.Kind.UNAUTHORIZED) { api().ping() }
        assertTrue(e.message!!.contains("pairing code"))
    }

    @Test
    fun otherServersAreNotMistakenForMemora() = runBlocking {
        routes["/api/ping"] = { reply(it, 200, """{"status":"ok"}""") }
        expectError(MemoraException.Kind.NOT_MEMORA) { api().ping() }
        routes["/api/ping"] = { reply(it, 200, "<html>router login</html>", "text/html") }
        expectError(MemoraException.Kind.NOT_MEMORA) { api().ping() }
        Unit
    }

    @Test
    fun nothingListeningMeansUnreachable() = runBlocking {
        val freePort = ServerSocket(0).use { it.localPort }
        val e = expectError(MemoraException.Kind.UNREACHABLE) {
            MemoraApi(ServerConfig("127.0.0.1", freePort, "ABCDE-FGH23")).ping()
        }
        assertTrue(e.message!!.contains("same Wi-Fi"))
    }

    @Test
    fun slowServerTimesOut() = runBlocking {
        routes["/api/ping"] = { Thread.sleep(1500); reply(it, 200, """{"app":"memora"}""") }
        val impatient = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        expectError(MemoraException.Kind.TIMEOUT) { api(client = impatient).ping() }
        Unit
    }

    @Test
    fun askParsesAnswerAndCitedSources() = runBlocking {
        routes["/api/ask"] = {
            reply(
                it, 200,
                """{"answer":"Two years [1].","sufficient":true,"sources":[
                   {"ref":1,"cited":true,"file_id":7,"name":"card.jpg","path":"C:\\x\\card.jpg","kind":"image","ext":".jpg","snippet":"Warranty: 2 years","score":0.03},
                   {"ref":2,"cited":false,"file_id":9,"name":"notes.txt","path":"C:\\x\\notes.txt","kind":"document","ext":".txt","snippet":"eggs","score":0.01}]}""",
            )
        }
        val answer = api().ask("warranty?")
        assertTrue(answer.sufficient)
        assertEquals(listOf(7L), answer.sources.filter { it.cited }.map { it.fileId })
        assertEquals("""{"question":"warranty?"}""", String(seen.single().third))
    }

    @Test
    fun aiOutageIsReportedWithServerDetail() = runBlocking {
        routes["/api/ask"] = { reply(it, 503, """{"detail":"Cannot reach Ollama"}""") }
        val e = expectError(MemoraException.Kind.AI_UNAVAILABLE) { api().ask("hi") }
        assertTrue(e.message!!.contains("Cannot reach Ollama"))
    }

    @Test
    fun uploadSendsRawJpegWithFileName() = runBlocking {
        routes["/api/uploads"] = {
            reply(it, 201, """{"id":12,"name":"IMG 1-abc.jpg","path":"D:\\up\\IMG 1-abc.jpg","kind":"image","status":"pending","error":null,"summary":null,"duplicate":false}""")
        }
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val result = api().upload("IMG 1.heic", jpeg)
        assertEquals(12L, result.id)
        assertEquals("pending", result.status)
        assertFalse(result.duplicate)
        assertNull(result.error)
        val (path, _, body) = seen.single()
        assertEquals("/api/uploads?filename=IMG%201.heic", path)
        assertTrue(body.contentEquals(jpeg))
    }

    @Test
    fun rejectedUploadShowsServerReason() = runBlocking {
        routes["/api/uploads"] = { reply(it, 413, """{"detail":"Photos must be under 20 MB."}""") }
        val e = expectError(MemoraException.Kind.REJECTED) { api().upload("big.jpg", ByteArray(10)) }
        assertEquals("Photos must be under 20 MB.", e.message)
    }

    @Test
    fun missingThumbnailIsNullNotAnError() = runBlocking {
        routes["/api/files/3/thumbnail"] = { reply(it, 404, """{"detail":"No preview for this file type"}""") }
        assertNull(api().thumbnail(3, 320))
    }
}
