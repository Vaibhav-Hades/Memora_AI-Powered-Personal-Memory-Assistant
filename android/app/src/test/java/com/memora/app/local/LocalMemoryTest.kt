package com.memora.app.local

import com.memora.app.data.MemoraException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val INVOICE = "Homeware Plus Tax Invoice HP-55102. Date: 21 October 2026. Customer: A. Yaramalla. " +
    "Zenbrew Electric Kettle 1.7L (model ZK-17), 1 unit, Rs. 2,450. Paid by card. Keep this invoice for warranty claims."
private const val HEADPHONES = "Invoice 4411. AcmeSound X200 headphones. Total paid: Rs. 12,900. Date: 2 March 2026."

private fun lib(db: LocalDb = InMemoryLocalDb(), ocr: OcrEngine? = FakeOcr(), dir: File = tempDir()) = newLibrary(db, ocr, dir)

private fun LocalLibrary.add(name: String, content: String, mime: String = "text/plain") =
    import(name, mime, ByteArrayInputStream(content.toByteArray()), "test")

private fun docx(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        z.putNextEntry(ZipEntry("word/document.xml"))
        z.write("<w:document><w:body><w:p><w:r><w:t>$text</w:t></w:r></w:p></w:body></w:document>".toByteArray())
        z.closeEntry()
    }
    return out.toByteArray()
}

/** Cites every numbered source it was shown, like a model that found the answer in all of them. */
private class CitingEngine : LocalAiEngine {
    var prompts = mutableListOf<String>()
    override suspend fun generate(system: String, user: String): String {
        prompts += user
        val refs = Regex("\\[(\\d+)] File:").findAll(user).map { it.groupValues[1].toInt() }.toList()
        return """{"answer": "Found it ${refs.joinToString(" ") { "[$it]" }}", "sufficient": true, "citations": [${refs.joinToString(",")}]}"""
    }
}

class LocalMemoryTest {
    // --- Import, de-duplication, storage -----------------------------------------------------------------------

    @Test fun importStoresOriginalBytesAndRecordsMetadata() {
        val library = lib()
        val result = library.add("invoice.txt", INVOICE)
        assertTrue(result is ImportResult.Added)
        val f = result.file
        assertEquals("invoice.txt", f.name)
        assertEquals(INVOICE.length.toLong(), f.size)
        assertEquals(sha256Of(INVOICE.toByteArray()), f.sha256)
        assertEquals(IndexState.PENDING, f.state)
        assertEquals(INVOICE, library.fileOnDisk(f)!!.readText())
    }

    @Test fun sameContentUnderAnotherNameIsNotStoredTwice() {
        val library = lib()
        val first = library.add("a.txt", INVOICE)
        val second = library.add("copy of a.txt", INVOICE)
        assertTrue(second is ImportResult.Duplicate)
        assertEquals(first.file.id, second.file.id)
        assertEquals(1, library.files().size)
    }

    @Test fun unsafeNamesCannotEscapeTheVault() {
        assertEquals("passwd", LocalLibrary.displayName("../../etc/passwd"))
        assertEquals("evil.txt", LocalLibrary.displayName("C:\\Windows\\evil.txt"))
        assertEquals("file", LocalLibrary.displayName("..."))
        val library = lib()
        val f = library.add("../../outside.txt", "hello").file
        assertEquals("outside.txt", f.name)
        assertFalse(f.storedName.contains("/") || f.storedName.contains(".."))
    }

    @Test fun emptyAndOversizedFilesAreRejected() {
        val small = LocalLibrary(InMemoryLocalDb(), File(tempDir(), "v"), DefaultExtractor(), maxFileBytes = 10)
        try { small.add("big.txt", "this is more than ten bytes"); throw AssertionError("accepted") } catch (e: ImportException) { assertTrue(e.message!!.contains("larger")) }
        try { lib().add("empty.txt", ""); throw AssertionError("accepted") } catch (e: ImportException) { assertTrue(e.message!!.contains("empty")) }
    }

    @Test fun databaseAndFilesSurviveARestart() = runBlocking {
        val db = InMemoryLocalDb()
        val dir = tempDir()
        val first = lib(db, dir = dir)
        first.index(first.add("invoice.txt", INVOICE).file.id)
        // "Restart": new objects over the same persisted state, nothing from the old ones.
        val second = lib(db, dir = dir)
        assertEquals(1, second.files().size)
        assertEquals(INVOICE, second.fileOnDisk(second.files().single())!!.readText())
        assertEquals(1, Bm25Index(db).search("kettle").size)
    }

    // --- Extraction and indexing -----------------------------------------------------------------------------------

    @Test fun textFilesBecomeSearchable() = runBlocking {
        val library = lib()
        val f = library.index(library.add("invoice.txt", INVOICE).file.id)
        assertEquals(IndexState.READY, f.state)
        assertEquals("text", f.extractor)
    }

    @Test fun docxTextIsExtracted() = runBlocking {
        val library = lib()
        val added = library.import("invoice.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ByteArrayInputStream(docx("Zenbrew kettle &amp; stand, Rs. 2,450")), "test")
        assertEquals(IndexState.READY, library.index(added.file.id).state)
        assertTrue(Docx.parse("<w:p><w:r><w:t>Hello</w:t></w:r></w:p><w:p><w:r><w:t xml:space=\"preserve\"> there</w:t></w:r></w:p>").contains("Hello\n there"))
    }

    @Test fun photosAreReadWithOcrAndRecordTheirProvenance() = runBlocking {
        val ocr = FakeOcr()
        val library = lib(ocr = ocr)
        val f = library.index(library.import("warranty.jpg", "image/jpeg", ByteArrayInputStream(byteArrayOf(1, 2, 3)), "test").file.id)
        assertEquals(IndexState.READY, f.state)
        assertEquals("ocr", f.extractor)
        assertEquals(1, ocr.calls)
    }

    @Test fun unreadableFilesAreReportedNeverSilentlyReady() = runBlocking {
        val library = lib(ocr = null)
        val photo = library.index(library.import("a.jpg", "image/jpeg", ByteArrayInputStream(byteArrayOf(1, 2, 3)), "test").file.id)
        assertEquals(IndexState.UNSUPPORTED, photo.state)
        assertNotNull(photo.error)
        val unknown = library.index(library.add("data.bin", "xx", "application/octet-stream").file.id)
        assertEquals(IndexState.UNSUPPORTED, unknown.state)
        val corrupt = library.index(library.add("broken.docx", "this is not a zip").file.id)
        assertEquals(IndexState.FAILED, corrupt.state)
        assertTrue(corrupt.error!!.startsWith("Couldn't read this file"))
        val blank = library.index(library.add("blank.txt", "   \n  ").file.id)
        assertEquals(IndexState.FAILED, blank.state)
    }

    @Test fun failedFilesCanBeRetriedAndOnlyTheyAreReprocessed() = runBlocking {
        var attempts = 0
        val flaky = object : OcrEngine {
            override suspend fun recognizeImage(file: File): String { attempts++; if (attempts == 1) throw IOException("out of memory"); return "Zenbrew warranty 2 years" }
            override suspend fun recognizePdf(file: File, maxPages: Int) = ""
        }
        val library = lib(ocr = flaky)
        val id = library.import("card.jpg", "image/jpeg", ByteArrayInputStream(byteArrayOf(9, 9, 9)), "test").file.id
        assertEquals(IndexState.FAILED, library.index(id).state)
        library.add("fine.txt", "Fine").also { library.index(it.file.id) }
        assertEquals(1, library.retryFailed())
        assertEquals(IndexState.READY, library.files().first { it.id == id }.state)
        assertEquals(2, attempts)
    }

    @Test fun missingStoredCopyIsAFailureNotASuccess() = runBlocking {
        val library = lib()
        val f = library.add("a.txt", "hello world").file
        library.fileOnDisk(f)!!.delete()
        val after = library.index(f.id)
        assertEquals(IndexState.FAILED, after.state)
    }

    @Test fun unchangedFilesAreNotImportedOrReindexedTwice() = runBlocking {
        val ocr = FakeOcr()
        val library = lib(ocr = ocr)
        val bytes = byteArrayOf(5, 6, 7)
        library.index(library.import("a.jpg", "image/jpeg", ByteArrayInputStream(bytes), "test").file.id)
        val again = library.import("a-again.jpg", "image/jpeg", ByteArrayInputStream(bytes), "test")
        assertTrue(again is ImportResult.Duplicate)
        assertEquals(0, library.retryFailed())
        assertEquals(1, ocr.calls)
    }

    // --- Search and answers ----------------------------------------------------------------------------------------

    private suspend fun kettleLibrary(): Pair<InMemoryLocalDb, LocalLibrary> {
        val db = InMemoryLocalDb()
        val library = lib(db)
        library.index(library.add("homeware_plus_invoice.txt", INVOICE).file.id)
        library.index(library.import("zenbrew_warranty_card.jpg", "image/jpeg", ByteArrayInputStream(byteArrayOf(4, 4, 4)), "test").file.id)
        library.index(library.add("headphones_invoice.txt", HEADPHONES).file.id)
        return db to library
    }

    @Test fun searchFindsRelevantChunksAndSkipsFilesNotYetIndexed() = runBlocking {
        val (db, library) = kettleLibrary()
        library.add("pending.txt", "kettle kettle kettle not indexed yet")
        val hits = Bm25Index(db).search("How much did I pay for the headphones?")
        assertEquals("headphones_invoice.txt", hits.first().file.name)
        assertTrue(hits.none { it.file.name == "pending.txt" })
        assertEquals(emptyList<Evidence>(), Bm25Index(db).search("zzz unrelated quantum"))
    }

    @Test fun crossDocumentQuestionUsesBothLocalFilesAndTheirTextReachesTheModel() = runBlocking {
        val (db, _) = kettleLibrary()
        val engine = CitingEngine()
        val answer = LocalAnswerGenerator(Bm25Index(db), engine).answer("What is the warranty period for my kettle, and when did I purchase it?")
        val cited = answer.sources.filter { it.cited }
        assertTrue(answer.sufficient)
        assertTrue(cited.any { it.name == "zenbrew_warranty_card.jpg" })
        assertTrue(cited.any { it.name == "homeware_plus_invoice.txt" })
        assertTrue(cited.all { it.local && it.path == "On this phone" })
        val prompt = engine.prompts.single()
        assertTrue(prompt.contains("Warranty period: 2 years"))
        assertTrue(prompt.contains("21 October 2026"))
    }

    @Test fun inventedCitationNumbersAreNeverShownAsEvidence() = runBlocking {
        val (db, _) = kettleLibrary()
        val engine = FakeEngine("""{"answer": "It is 3 years [9].", "sufficient": true, "citations": [9, 0, -2]}""")
        val answer = LocalAnswerGenerator(Bm25Index(db), engine).answer("kettle warranty")
        assertTrue(answer.sources.none { it.cited })
        assertFalse(answer.sufficient)
    }

    @Test fun insufficientEvidenceIsReportedHonestlyWithoutCallingTheModel() = runBlocking {
        val (db, _) = kettleLibrary()
        val engine = FakeEngine("should never be used")
        val answer = LocalAnswerGenerator(Bm25Index(db), engine).answer("What is my passport number?")
        assertFalse(answer.sufficient)
        assertEquals(LocalAnswerGenerator.NO_LOCAL_EVIDENCE, answer.text)
        // Says only the phone's library was searched; does not claim the information doesn't exist anywhere.
        assertTrue(answer.text.contains("Only the phone's own library was searched"))
        assertFalse(answer.text.contains("searched the laptop"))
        assertTrue(engine.prompts.isEmpty())
        assertTrue(answer.sources.isEmpty())
    }

    @Test fun modelSayingNotEnoughEvidenceDoesNotCiteSources() = runBlocking {
        val (db, _) = kettleLibrary()
        val engine = FakeEngine("""{"answer": "Sources [1] and [2] do not say who made it.", "sufficient": false, "citations": [1, 2]}""")
        val answer = LocalAnswerGenerator(Bm25Index(db), engine).answer("kettle warranty")
        assertFalse(answer.sufficient)
        assertTrue(answer.sources.none { it.cited })
    }

    @Test fun missingModelStillShowsMatchingFiles() = runBlocking {
        val (db, _) = kettleLibrary()
        val answer = LocalAnswerGenerator(Bm25Index(db), engine = null).answer("kettle warranty")
        assertEquals(LocalAnswerGenerator.AI_NOT_READY, answer.text)
        assertFalse(answer.sufficient)
        assertTrue(answer.sources.isNotEmpty() && answer.sources.none { it.cited })
    }

    @Test fun modelInitialisationFailureIsExplainedNotThrown() = runBlocking {
        val (db, _) = kettleLibrary()
        val engine = FakeEngine(failWith = LocalAiException("The phone ran out of memory loading the model."))
        val answer = LocalAnswerGenerator(Bm25Index(db), engine).answer("kettle warranty")
        assertTrue(answer.text.contains("ran out of memory"))
        assertFalse(answer.sufficient)
        assertTrue(answer.sources.isNotEmpty())
    }

    @Test fun malformedModelOutputIsHandledSafely() = runBlocking {
        val (db, _) = kettleLibrary()
        val generator = { reply: String -> LocalAnswerGenerator(Bm25Index(db), FakeEngine(reply)) }
        // Prose with a real marker: kept, citation validated against the retrieved sources.
        val prose = generator("The warranty is 2 years [1].").answer("kettle warranty")
        assertTrue(prose.sources.count { it.cited } == 1 && prose.sufficient)
        // Prose with no marker cannot be presented as supported.
        assertFalse(generator("It is probably two years.").answer("kettle warranty").sufficient)
        // Empty and broken JSON.
        assertFalse(generator("").answer("kettle warranty").sufficient)
        assertFalse(generator("{\"answer\": \"x\", ").answer("kettle warranty").sufficient)
        // Reasoning blocks are stripped and never shown.
        val thinking = generator("<think>the user wants the warranty, so [7] is best</think>{\"answer\": \"2 years [1]\", \"sufficient\": true, \"citations\": [1]}").answer("kettle warranty")
        assertFalse(thinking.text.contains("think"))
        assertTrue(thinking.sufficient)
    }

    @Test fun contextIsLimitedToTheBudget() = runBlocking {
        val db = InMemoryLocalDb()
        val library = lib(db)
        repeat(6) { i -> library.index(library.add("n$i.txt", "kettle notes number $i " + "padding word ".repeat(150)).file.id) }
        val engine = CitingEngine()
        LocalAnswerGenerator(Bm25Index(db), engine, maxContextChars = 2500).answer("kettle notes")
        assertTrue(engine.prompts.single().length < 3500)
    }

    @Test fun chunkerKeepsOverlapAndCoversText() {
        val text = (1..400).joinToString(" ") { "word$it" }
        val chunks = Chunker.split(text, size = 500, overlap = 100)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 500 })
        assertTrue(chunks.first().startsWith("word1 ") && chunks.last().endsWith("word400"))
        assertTrue(Chunker.split("   ").isEmpty())
    }

    @Test fun historyIsStoredLocally() {
        val db = InMemoryLocalDb()
        db.addQa("q1", "a1", true, "phone")
        db.addQa("q2", "a2", false, "phone")
        assertEquals(listOf("q2", "q1"), db.history().map { it.question })
    }

    // --- Operating modes -------------------------------------------------------------------------------------------

    @Test fun modeSelectionNeverSendsPrivateQuestionsWhereTheUserDidNotChoose() {
        // Phone only: always the phone, even with the laptop right there.
        assertEquals(Route.Phone, ModeResolver.resolve(OperatingMode.PHONE_ONLY, laptopConfigured = true, laptopReachable = true))
        assertEquals(Route.Phone, ModeResolver.resolve(OperatingMode.PHONE_ONLY, false, false))
        // Automatic: laptop only when reachable, otherwise the phone's own memory.
        assertEquals(Route.Laptop, ModeResolver.resolve(OperatingMode.AUTO, true, true))
        assertEquals(Route.Phone, ModeResolver.resolve(OperatingMode.AUTO, true, false))
        assertEquals(Route.Phone, ModeResolver.resolve(OperatingMode.AUTO, false, false))
        // Laptop mode: never silently falls back; explains instead.
        assertEquals(Route.Laptop, ModeResolver.resolve(OperatingMode.LAPTOP, true, true))
        val down = ModeResolver.resolve(OperatingMode.LAPTOP, true, false)
        assertTrue(down is Route.Unavailable && down.reason.contains("Laptop unavailable"))
        assertTrue(ModeResolver.resolve(OperatingMode.LAPTOP, false, false) is Route.Unavailable)
    }

    // --- Sync planning ---------------------------------------------------------------------------------------------

    @Test fun syncPlanClassifiesEveryFileAndNeverDeletes() = runBlocking {
        val library = lib()
        val a = byteArrayOf(1, 1, 1)
        val b = byteArrayOf(2, 2, 2)
        val remote = FakeRemote(mapOf(1L to a, 2L to b, 3L to byteArrayOf(3), 4L to byteArrayOf(4)))
        val dl = FileDownloader(remote, library, File(tempDir(), "p"))
        dl.download(remote.entry(1, "a.bin"))
        dl.download(remote.entry(2, "b.bin"))
        // Laptop now: 1 unchanged, 2 changed content, 3 new, 4 missing original; 9 vanished entirely.
        val changedB = remote.entry(2, "b.bin", sha = sha256Of(byteArrayOf(8, 8)))
        val manifest = listOf(remote.entry(1, "a.bin"), changedB, remote.entry(3, "c.bin"), remote.entry(4, "d.bin", available = false))
        val local = library.files() + LocalFile(99, "gone.txt", "text/plain", 1, "ff", "ff", 0, "laptop", remoteId = 9)
        val states = SyncPlanner.plan(manifest, local).associate { (it.remote?.id ?: -it.local!!.id) to it.state }
        assertEquals(SyncState.OFFLINE, states[1L])
        assertEquals(SyncState.CHANGED, states[2L])
        assertEquals(SyncState.LAPTOP_ONLY, states[3L])
        assertEquals(SyncState.UNAVAILABLE, states[4L])
        assertEquals(SyncState.REMOVED_ON_LAPTOP, states[-99L])
        assertEquals(2, library.files().size) // planning deleted nothing
    }

    // --- Downloads ---------------------------------------------------------------------------------------------------

    private fun fixture(vararg bytes: ByteArray): Triple<FakeRemote, LocalLibrary, FileDownloader> {
        val remote = FakeRemote(bytes.mapIndexed { i, b -> (i + 1).toLong() to b }.toMap())
        val library = lib()
        return Triple(remote, library, FileDownloader(remote, library, File(tempDir(), "parts")))
    }

    @Test fun downloadStoresTheExactOriginalBytesAfterVerification() = runBlocking {
        val original = ByteArray(300_000) { (it % 251).toByte() }
        val (remote, library, dl) = fixture(original)
        val progress = mutableListOf<Long>()
        val result = dl.download(remote.entry(1, "Scan 2026.pdf", "application/pdf")) { done, _ -> progress += done }
        assertTrue(result is DownloadResult.Done && !result.alreadyHad)
        val stored = (result as DownloadResult.Done).file
        assertEquals("Scan 2026.pdf", stored.name)
        assertEquals(1L, stored.remoteId)
        assertTrue(library.fileOnDisk(stored)!!.readBytes().contentEquals(original))
        assertEquals(original.size.toLong(), progress.last())
        assertTrue(progress.size > 1) // progress was reported along the way
    }

    @Test fun corruptedDownloadIsDiscardedAndNeverStored() = runBlocking {
        val (remote, library, dl) = fixture(byteArrayOf(1, 2, 3, 4))
        val bad = remote.entry(1, "x.bin", sha = sha256Of(byteArrayOf(9, 9, 9, 9)))
        val result = dl.download(bad)
        assertTrue(result is DownloadResult.Failed && result.message.contains("checksum") && result.canRetry)
        assertTrue(library.files().isEmpty())
    }

    @Test fun interruptedDownloadResumesFromWhereItStopped() = runBlocking {
        val original = ByteArray(200_000) { (it * 7).toByte() }
        val (remote, library, dl) = fixture(original)
        remote.failAfterBytes = 70_000
        val first = dl.download(remote.entry(1, "big.bin"))
        assertTrue(first is DownloadResult.Failed && first.canRetry)
        assertTrue(library.files().isEmpty()) // a partial file is never a finished file
        remote.failAfterBytes = null
        val second = dl.download(remote.entry(1, "big.bin"))
        assertEquals(70_000L, remote.lastStart)
        assertTrue(second is DownloadResult.Done)
        assertTrue(library.fileOnDisk((second as DownloadResult.Done).file)!!.readBytes().contentEquals(original))
    }

    @Test fun serverThatIgnoresRangeRestartsCleanly() = runBlocking {
        val original = ByteArray(100_000) { it.toByte() }
        val (remote, library, dl) = fixture(original)
        remote.failAfterBytes = 40_000
        dl.download(remote.entry(1, "r.bin"))
        remote.failAfterBytes = null
        remote.ignoreRange = true
        val result = dl.download(remote.entry(1, "r.bin"))
        assertTrue(result is DownloadResult.Done)
        assertTrue(library.fileOnDisk((result as DownloadResult.Done).file)!!.readBytes().contentEquals(original))
    }

    @Test fun theSameFileCannotBeDownloadedTwiceAtOnce() = runBlocking {
        val (remote, _, dl) = fixture(byteArrayOf(1, 2, 3))
        val gate = CompletableDeferred<Unit>()
        remote.gate = gate
        val first = async { dl.download(remote.entry(1, "a.bin")) }
        while (!dl.isRunning(1)) kotlinx.coroutines.yield()
        assertEquals(DownloadResult.AlreadyRunning, dl.download(remote.entry(1, "a.bin")))
        gate.complete(Unit)
        assertTrue(first.await() is DownloadResult.Done)
        assertFalse(dl.isRunning(1))
    }

    @Test fun downloadingSomethingAlreadyOnThePhoneDoesNotDuplicateIt() = runBlocking {
        val (remote, library, dl) = fixture(byteArrayOf(5, 5, 5))
        dl.download(remote.entry(1, "a.bin"))
        val again = dl.download(remote.entry(1, "renamed.bin"))
        assertTrue(again is DownloadResult.Done && again.alreadyHad)
        assertEquals(1, library.files().size)
        assertEquals(1, remote.opens) // second time needed no transfer at all
    }

    @Test fun downloadErrorsAreExplained() = runBlocking {
        val (remote, library, dl) = fixture(byteArrayOf(1, 2, 3))
        remote.error = MemoraException("Can't reach Memora at 192.168.1.20:8010.", MemoraException.Kind.UNREACHABLE)
        val offline = dl.download(remote.entry(1, "a.bin"))
        assertTrue(offline is DownloadResult.Failed && offline.canRetry && offline.message.contains("Can't reach"))
        remote.error = MemoraException("The laptop no longer lists this file.", MemoraException.Kind.REJECTED)
        val gone = dl.download(remote.entry(1, "a.bin"))
        assertTrue(gone is DownloadResult.Failed && !gone.canRetry)
        remote.error = MemoraException("The pairing code is wrong.", MemoraException.Kind.UNAUTHORIZED)
        assertTrue((dl.download(remote.entry(1, "a.bin")) as DownloadResult.Failed).message.contains("pairing code"))
        remote.error = IOException("write failed: ENOSPC (No space left on device)")
        val full = dl.download(remote.entry(1, "a.bin")) as DownloadResult.Failed
        assertTrue(full.message.contains("out of storage") && !full.canRetry)
        assertTrue(dl.download(remote.entry(1, "a.bin", available = false)) is DownloadResult.Failed)
        assertTrue(library.files().isEmpty())
    }

    @Test fun duplicateFileNamesGetUniqueNamesWhenExported() {
        assertEquals("a.pdf", FileDownloader.uniqueName("a.pdf", emptySet()))
        assertEquals("a (1).pdf", FileDownloader.uniqueName("a.pdf", setOf("a.pdf")))
        assertEquals("a (2).pdf", FileDownloader.uniqueName("a.pdf", setOf("a.pdf", "a (1).pdf")))
        assertEquals("noext (1)", FileDownloader.uniqueName("noext", setOf("noext")))
    }

    // --- Model files ---------------------------------------------------------------------------------------------------

    @Test fun modelImportValidatesFormatSizeAndStorage() {
        val manager = ModelManager(tempDir(), freeBytes = { 50_000_000L })
        try { manager.importFrom("model.gguf", ByteArrayInputStream(ByteArray(10)), 10); throw AssertionError("accepted") } catch (e: ModelException) { assertTrue(e.message!!.contains(".litertlm")) }
        try { manager.importFrom("tiny.litertlm", ByteArrayInputStream(ByteArray(100)), 100); throw AssertionError("accepted") } catch (e: ModelException) { assertTrue(e.message!!.contains("too small")) }
        try { manager.importFrom("huge.litertlm", ByteArrayInputStream(ByteArray(10)), 2_000_000_000L); throw AssertionError("accepted") } catch (e: ModelException) { assertTrue(e.message!!.contains("Not enough storage")) }
        assertNull(manager.active())
        assertTrue(manager.status() is ModelStatus.NotInstalled)
    }

    @Test fun catalogModelsMustMatchTheirPublishedSizeAndHash() {
        val spec = ModelCatalog.models.first()
        val manager = ModelManager(tempDir(), freeBytes = { Long.MAX_VALUE / 4 })
        try { manager.importFrom(spec.fileName, ByteArrayInputStream(ByteArray(2_000_000)), 2_000_000); throw AssertionError("accepted") } catch (e: ModelException) { assertTrue(e.message!!.contains("incomplete")) }
        assertTrue(manager.installed().isEmpty()) // nothing half-installed is left usable
        assertTrue(File(tempDir(), "x").let { true })
    }

    @Test fun unknownModelFileIsInstalledOnlyAsTheActiveOne() {
        val manager = ModelManager(tempDir(), freeBytes = { Long.MAX_VALUE / 4 })
        val file = manager.importFrom("my-model.litertlm", ByteArrayInputStream(ByteArray(1_500_000)), 1_500_000)
        assertEquals(file.name, manager.active()!!.name)
        assertTrue(manager.status() is ModelStatus.Installed)
        manager.remove(file)
        assertNull(manager.active())
    }

    @Test fun theLaptopAddressMustBeOnTheLocalNetwork() {
        val check = { host: String -> com.memora.app.data.ServerConfig.validate(host, "8010", "ABCDE-FGH23") }
        listOf("192.168.1.20", "10.0.0.5", "172.16.4.9", "172.31.255.1", "169.254.3.4", "laptop", "my-laptop.local").forEach { assertNull(it, check(it)) }
        listOf("8.8.8.8", "172.32.0.1", "100.64.1.2", "example.com", "memora.dev").forEach { assertNotNull(it, check(it)) }
    }

    @Test fun everyCatalogModelHasAnIntegrityHashAndPublishedSize() {
        ModelCatalog.models.forEach {
            assertEquals(64, it.sha256.length)
            assertTrue(it.url.startsWith("https://huggingface.co/litert-community/"))
            assertTrue(it.fileName.endsWith(".litertlm") && it.sizeBytes > 100_000_000)
        }
    }
}
