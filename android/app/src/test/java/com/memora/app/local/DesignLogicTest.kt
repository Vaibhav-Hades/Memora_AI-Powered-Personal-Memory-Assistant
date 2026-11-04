package com.memora.app.local

import com.memora.app.data.LaptopRoute
import com.memora.app.data.ServerInfo
import com.memora.app.ui.Connection
import com.memora.app.ui.Connectivity
import com.memora.app.ui.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class DesignLogicTest {
    private fun file(id: Long, name: String, mime: String = "image/jpeg", added: Long = 0, state: IndexState = IndexState.READY) =
        LocalFile(id, name, mime, 10, "sha$id", "s$id", added, "phone-import", state = state)

    // Dates

    @Test fun exifCaptureIsKeptAsWrittenWithoutZoneConversion() {
        val c = ExifDates.parse("2024:03:02 23:50:10", "+05:30")!!
        assertEquals(LocalDate.of(2024, 3, 2), c.date) // would become 3 March if converted to UTC+10
        assertEquals("+05:30", c.offset)
        assertNotNull(c.time)
    }

    @Test fun missingOrBrokenExifDatesAreNotInvented() {
        for (bad in listOf(null, "", "   ", "0000:00:00 00:00:00", "2024:13:40 99:99:99", "garbage", "2024-03-02 10:00:00")) {
            assertNull("'$bad'", ExifDates.parse(bad))
        }
        assertNull(ExifDates.parse("2024:03:02 10:00:00")!!.offset) // no zone recorded -> none shown
        assertNull(ExifDates.parse("2024:03:02 10:00:00", "garbage")!!.offset)
    }

    @Test fun dateFormatIsLocaleAware() {
        val d = LocalDate.of(2024, 3, 2)
        assertTrue(ExifDates.formatDate(d, Locale.US).contains("March"))
        assertTrue(ExifDates.formatDate(d, Locale.GERMANY).contains("März"))
    }

    @Test fun coordinatesAreFormattedAndNullIslandIsRejected() {
        assertEquals("12.9716° N, 77.5946° E", ExifDates.formatCoordinates(12.9716, 77.5946))
        assertEquals("33.8688° S, 151.2093° E", ExifDates.formatCoordinates(-33.8688, 151.2093))
        assertNull(ExifDates.formatCoordinates(0.0, 0.0))
        assertNull(ExifDates.formatCoordinates(91.0, 10.0))
        assertNull(PhotoMetadata().coordinates)
    }

    // Facts read from text

    private val card = "ZENBREW WARRANTY CARD\nProduct: Zenbrew Electric Kettle 1.7L\nSerial number: ZK17-90314\nWarranty period: 2 years from purchase\n" +
        "Purchased on 12 March 2024 for ₹2,499.00 and 03/04/2024 again.\nthis line has no label"

    @Test fun keyValuesAreReadLiterally() {
        val kv = TextFacts.keyValues(card)
        assertEquals(listOf("Product", "Serial number", "Warranty period"), kv.map { it.label })
        assertEquals("2 years from purchase", kv.first { it.label == "Warranty period" }.value)
        assertTrue(TextFacts.keyValues("no structure here at all").isEmpty())
    }

    @Test fun datesAndAmountsAreReturnedAsWritten() {
        assertEquals(listOf("12 March 2024", "03/04/2024"), TextFacts.datesMentioned(card))
        assertEquals(listOf("₹2,499.00"), TextFacts.amountsMentioned(card))
        assertEquals(listOf("2024-01-05"), TextFacts.datesMentioned("Invoice date 2024-01-05"))
        assertTrue(TextFacts.datesMentioned("nothing dated here").isEmpty())
    }

    // Chunk reassembly

    @Test fun joiningChunksDropsTheRepeatedOverlap() {
        val text = (1..400).joinToString(" ") { "word$it" }
        val chunks = Chunker.split(text, size = 300, overlap = 60)
        assertTrue(chunks.size > 2)
        assertEquals(text, Chunker.join(chunks))
        assertEquals("", Chunker.join(emptyList()))
        assertEquals("only", Chunker.join(listOf("only")))
    }

    // Related memories

    @Test fun relatedFilesNeedTwoSharedDistinctiveWords() {
        val db = InMemoryLocalDb()
        val warranty = file(0, "warranty.jpg").let { db.insertFile(it).let { id -> db.file(id)!! } }
        val invoice = db.insertFile(file(0, "invoice.pdf", "application/pdf").copy(sha256 = "i")).let { db.file(it)!! }
        val unrelated = db.insertFile(file(0, "recipe.txt", "text/plain").copy(sha256 = "r")).let { db.file(it)!! }
        val oneWord = db.insertFile(file(0, "note.txt", "text/plain").copy(sha256 = "n")).let { db.file(it)!! }
        db.replaceChunks(warranty.id, listOf("Zenbrew kettle warranty card. Warranty two years. Serial ZK17-90314"))
        db.replaceChunks(invoice.id, listOf("Invoice for Zenbrew kettle purchased from Homeware store, warranty included"))
        db.replaceChunks(unrelated.id, listOf("Chocolate cake recipe with flour and butter"))
        db.replaceChunks(oneWord.id, listOf("Remember the kettle"))
        val related = Related.find(warranty, db.allChunks().first { it.fileId == warranty.id }.text, Bm25Index(db))
        assertEquals(listOf(invoice.id), related.map { it.file.id })
        assertTrue(related[0].sharedTerms.size >= 2)
        assertFalse(related.any { it.file.id == warranty.id }) // never itself
    }

    // Timeline

    @Test fun capturedDateBeatsAddedDateAndIsLabelledAccordingly() {
        val zone = ZoneId.of("UTC")
        val added = 1_709_000_000_000L // 2024-02-27
        val withExif = Timeline.entry(file(1, "a.jpg", added = added), ExifDates.parse("2019:07:04 08:00:00"), zone)
        val without = Timeline.entry(file(2, "b.jpg", added = added), null, zone)
        assertEquals(DateKind.CAPTURED to LocalDate.of(2019, 7, 4), withExif.kind to withExif.date)
        assertEquals(DateKind.ADDED to LocalDate.of(2024, 2, 27), without.kind to without.date)
    }

    @Test fun groupingSortsAndSplitsByGranularity() {
        val e = listOf(
            TimelineEntry(file(1, "a.jpg"), LocalDate.of(2024, 3, 2), DateKind.CAPTURED),
            TimelineEntry(file(2, "b.pdf", "application/pdf"), LocalDate.of(2024, 3, 20), DateKind.ADDED),
            TimelineEntry(file(3, "c.jpg"), LocalDate.of(2023, 12, 25), DateKind.CAPTURED),
        )
        val months = Timeline.group(e, Granularity.MONTH, true, Locale.US)
        assertEquals(listOf("March 2024", "December 2023"), months.map { it.label })
        assertEquals(listOf(2L, 1L), months[0].entries.map { it.file.id })
        assertEquals(listOf("2024", "2023"), Timeline.group(e, Granularity.YEAR, true, Locale.US).map { it.label })
        assertEquals(3, Timeline.group(e, Granularity.DAY, false, Locale.US).size)
        assertEquals("December 2023", Timeline.group(e, Granularity.MONTH, false, Locale.US).first().label)
    }

    @Test fun filtersSeparatePhotosFromDocumentsAndSearchNames() {
        val e = listOf(
            TimelineEntry(file(1, "kettle.jpg"), LocalDate.of(2024, 3, 2), DateKind.CAPTURED),
            TimelineEntry(file(2, "invoice.pdf", "application/pdf"), LocalDate.of(2024, 3, 2), DateKind.ADDED),
        )
        assertEquals(listOf(1L), Timeline.filter(e, KindFilter.PHOTOS, "").map { it.file.id })
        assertEquals(listOf(2L), Timeline.filter(e, KindFilter.DOCUMENTS, "").map { it.file.id })
        assertEquals(listOf(2L), Timeline.filter(e, KindFilter.ALL, "INV").map { it.file.id })
        assertTrue(Timeline.filter(e, KindFilter.ALL, "zzz").isEmpty())
    }

    // Answer layout

    @Test fun answerIsSplitIntoDirectAnswerAndExplanationWithoutChangingWords() {
        val p = AnswerLayout.split("The kettle has a 2 year warranty [1]. It starts at purchase [2]. Keep the invoice.")
        assertEquals("The kettle has a 2 year warranty [1].", p.direct)
        assertEquals("It starts at purchase [2]. Keep the invoice.", p.explanation)
        assertEquals("One sentence only", AnswerLayout.split("One sentence only").direct)
        assertEquals("", AnswerLayout.split("One sentence only").explanation)
        assertEquals("It weighs 1.7 kg [1].", AnswerLayout.split("It weighs 1.7 kg [1].").direct) // decimals don't end a sentence
    }

    // Connectivity labels

    private val info = ServerInfo("laptop", "0.4.0")

    @Test fun routeLabelsReflectTheVerifiedRoute() {
        val lan = Connectivity.describe(OperatingMode.AUTO, Connection.Connected(info, null, LaptopRoute.LAN), true)
        val ts = Connectivity.describe(OperatingMode.LAPTOP, Connection.Connected(info, null, LaptopRoute.TAILSCALE), true)
        assertEquals("Laptop via LAN", lan.label)
        assertEquals("Laptop via Tailscale", ts.label)
        assertEquals(Tone.OK, ts.tone)
    }

    @Test fun neverClaimsTailscaleOrLaptopWithoutAVerifiedConnection() {
        val failed = Connection.Failed("down")
        assertEquals("Laptop unavailable", Connectivity.describe(OperatingMode.LAPTOP, failed, true).label)
        assertEquals(Tone.ERROR, Connectivity.describe(OperatingMode.LAPTOP, failed, true).tone)
        assertEquals(Tone.CAUTION, Connectivity.describe(OperatingMode.AUTO, failed, true).tone)
        assertEquals("Connecting…", Connectivity.describe(OperatingMode.AUTO, Connection.Checking, true).label)
        assertEquals("Pairing code rejected", Connectivity.describe(OperatingMode.AUTO, Connection.Failed("x", unauthorized = true), true).label)
        assertEquals("Laptop not set up", Connectivity.describe(OperatingMode.LAPTOP, Connection.NotConfigured, false).label)
    }

    @Test fun phoneOnlyAlwaysSaysPhoneOnlyWhateverTheConnectionState() {
        for (c in listOf(Connection.NotConfigured, Connection.Checking, Connection.Failed("x"), Connection.Connected(info, null, LaptopRoute.TAILSCALE))) {
            assertEquals("Phone only", Connectivity.describe(OperatingMode.PHONE_ONLY, c, true).label)
        }
    }
}
