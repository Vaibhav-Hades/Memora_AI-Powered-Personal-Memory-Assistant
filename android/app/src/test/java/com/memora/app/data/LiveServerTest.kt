package com.memora.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Runs the app's real networking code against a real Memora server, the way the phone does.
 * Skipped unless MEMORA_IT_HOST and MEMORA_IT_CODE are set, e.g.:
 *   MEMORA_IT_HOST=192.168.1.20 MEMORA_IT_CODE=ABCDE-FGH23 gradle :app:testDebugUnitTest
 * Uses the laptop's Wi-Fi address (not localhost), so the server applies its phone rules: pairing code, API only.
 *
 * Cross-device scenario from the project brief: the warranty card is a photo on the phone, the invoice is a
 * document on the laptop (samples/homeware_plus_invoice.docx). One question must use both.
 */
class LiveServerTest {
    private val host = System.getenv("MEMORA_IT_HOST")
    private val code = System.getenv("MEMORA_IT_CODE")
    private val port = System.getenv("MEMORA_IT_PORT")?.toInt() ?: 8000

    private fun api(pairing: String = code!!) = MemoraApi(ServerConfig(host!!, port, pairing))

    @Test
    fun phoneWarrantyPhotoPlusLaptopInvoice() = runBlocking {
        assumeTrue("set MEMORA_IT_HOST and MEMORA_IT_CODE to run", host != null && code != null)

        // Wrong code is refused with a clear message.
        try {
            api("WRONG-CODE0").ping()
            throw AssertionError("wrong code was accepted")
        } catch (e: MemoraException) {
            assertEquals(MemoraException.Kind.UNAUTHORIZED, e.kind)
        }

        val api = api()
        val info = api.ping()
        println("Connected to ${info.serverName} (Memora ${info.version})")
        val health = api.health()
        assertTrue("AI not ready on the laptop: ${health.modelError}", health.modelOk)

        // The laptop already has the invoice indexed.
        val invoice = api.search("Homeware Plus invoice Zenbrew kettle").firstOrNull { it.name == "homeware_plus_invoice.docx" }
        assertTrue("laptop invoice not indexed; add the samples folder first", invoice != null)

        // The phone sends a photo of the warranty card (as after picking it in the Photo Picker).
        val uploaded = api.upload("kettle_warranty_card.jpg", warrantyCardJpeg())
        println("Uploaded as ${uploaded.name} (status ${uploaded.status}, duplicate=${uploaded.duplicate})")
        var file = uploaded
        repeat(120) {
            if (file.status == "indexed" || file.status == "error") return@repeat
            delay(2000)
            file = api.file(uploaded.id)
        }
        assertEquals("indexing ended with: ${file.error}", "indexed", file.status)
        println("Laptop AI read the photo as: ${file.summary?.take(200)}")
        assertTrue(api.thumbnail(uploaded.id, 160)!!.isNotEmpty())

        val answer = api.ask("What is the warranty period of my Zenbrew kettle, and where is its invoice?")
        println("Answer: ${answer.text}")
        answer.sources.filter { it.cited }.forEach { println("  cited [${it.ref}] ${it.name}  (${it.path})") }
        assertTrue(answer.sufficient)
        val cited = answer.sources.filter { it.cited }.map { it.fileId }.toSet()
        assertTrue("phone photo not cited", uploaded.id in cited)
        assertTrue("laptop invoice not cited", invoice!!.fileId in cited)
        assertTrue(answer.text.contains("2") && answer.text.contains("homeware_plus_invoice"))
    }

    private fun warrantyCardJpeg(): ByteArray {
        val img = BufferedImage(1000, 640, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(0xF4F1E8)
        g.fillRect(0, 0, 1000, 640)
        g.color = Color.BLACK
        g.drawRect(20, 20, 960, 600)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 44)
        g.drawString("ZENBREW WARRANTY CARD", 70, 110)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 34)
        listOf(
            "Product: Zenbrew Electric Kettle 1.7L",
            "Model: ZK-17   Serial: ZK17-90314",
            "Warranty period: 2 years from purchase",
            "Purchased at: Homeware Plus",
            "Keep your invoice as proof of purchase.",
        ).forEachIndexed { i, line -> g.drawString(line, 70, 200 + i * 80) }
        g.dispose()
        return ByteArrayOutputStream().use { ImageIO.write(img, "jpg", it); it.toByteArray() }
    }
}
