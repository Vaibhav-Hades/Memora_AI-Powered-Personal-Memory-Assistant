package com.memora.app.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

private const val RECEIPT_OCR = "TECHMART RECEIPT Order 9931 Noise-cancelling headphones Total paid: 129.00"

private fun photoLib(db: LocalDb = InMemoryLocalDb(), ocr: OcrEngine? = FakeOcr(RECEIPT_OCR), dir: File = tempDir()) = newLibrary(db, ocr, dir)

private fun LocalLibrary.addPhoto(name: String = "receipt.jpg", bytes: ByteArray = byteArrayOf(1, 2, 3, 4)) =
    import(name, "image/jpeg", ByteArrayInputStream(bytes), "phone-import")

private fun Bm25Index.files(q: String) = search(q).map { it.file.id }.toSet()

class PhotoMemoryTest {
    @Test fun ocrTextIsStoredSeparatelyAndSearchable() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val f = library.index(library.addPhoto().file.id)
        assertEquals(IndexState.READY, f.state)
        assertEquals(RECEIPT_OCR, db.photoNote(f.id)!!.derived.ocrText)
        assertTrue(Bm25Index(db).files("headphones").contains(f.id))
    }

    @Test fun userWordsMakeAPhotoSearchableAndAreLabelledAsTheirs() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val id = library.index(library.addPhoto().file.id).id
        library.saveUserFields(id, PhotoUserFields(caption = "Grandma's birthday cake", notes = "Lemon sponge", tags = listOf("Family", "#Cake"), locationLabel = "Hyderabad"))
        val index = Bm25Index(db)
        assertTrue(index.files("birthday").contains(id))
        assertTrue(index.files("lemon").contains(id))
        assertTrue(index.files("cake").contains(id))
        assertTrue(index.files("hyderabad").contains(id))
        assertEquals(listOf("family", "cake"), db.photoNote(id)!!.user.tags)
        val text = db.allChunks().filter { it.fileId == id }.joinToString("\n") { it.text }
        assertTrue(text.contains("written by you"))
        assertTrue(text.contains("headphones")) // OCR text is still there
    }

    @Test fun userFieldsSurviveReindexing() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val id = library.index(library.addPhoto().file.id).id
        library.saveUserFields(id, PhotoUserFields(caption = "Keep me", favorite = true))
        library.index(id)
        library.index(id)
        val note = db.photoNote(id)!!
        assertEquals("Keep me", note.user.caption)
        assertTrue(note.user.favorite)
        assertTrue(Bm25Index(db).files("keep").contains(id))
    }

    @Test fun clearingNotesRemovesThemFromTheIndex() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val id = library.index(library.addPhoto().file.id).id
        library.saveUserFields(id, PhotoUserFields(notes = "zebra crossing"))
        assertTrue(Bm25Index(db).files("zebra").contains(id))
        library.saveUserFields(id, PhotoUserFields())
        assertFalse(Bm25Index(db).files("zebra").contains(id))
        assertTrue(Bm25Index(db).files("headphones").contains(id))
    }

    @Test fun favouriteToggleDoesNotChangeTheIndex() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val id = library.index(library.addPhoto().file.id).id
        val before = db.allChunks().map { it.id }
        library.saveUserFields(id, PhotoUserFields(favorite = true))
        assertEquals(before, db.allChunks().map { it.id })
        assertTrue(db.photoNote(id)!!.user.favorite)
    }

    @Test fun badEventDateIsRejectedAndNothingIsSaved() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val id = library.index(library.addPhoto().file.id).id
        try { library.saveUserFields(id, PhotoUserFields(eventDate = "31/02/2025", caption = "x")); throw AssertionError("accepted") }
        catch (e: ImportException) { assertTrue(e.message!!.contains("date")) }
        assertNull(db.photoNote(id)?.user?.caption?.takeIf { it.isNotEmpty() })
        library.saveUserFields(id, PhotoUserFields(eventDate = "2025-10-14"))
        assertEquals("2025-10-14", db.photoNote(id)!!.user.eventDate)
    }

    @Test fun documentsCannotHavePhotoNotes() = runBlocking {
        val library = photoLib()
        val id = library.import("a.txt", "text/plain", ByteArrayInputStream("hello world".toByteArray()), "test").file.id
        try { library.saveUserFields(id, PhotoUserFields(caption = "x")); throw AssertionError("accepted") } catch (e: ImportException) { assertNotNull(e.message) }
    }

    @Test fun aPhotoWithNoReadableTextBecomesSearchableOnceDescribed() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db, ocr = FakeOcr(""))
        val f = library.index(library.addPhoto("sunset.jpg").file.id)
        assertEquals(IndexState.FAILED, f.state)
        library.saveUserFields(f.id, PhotoUserFields(caption = "Sunset over the lake"))
        assertEquals(IndexState.READY, db.file(f.id)!!.state)
        assertTrue(Bm25Index(db).files("sunset").contains(f.id))
    }

    @Test fun laptopDescriptionIsKeptApartFromUserWords() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val id = library.index(library.addPhoto().file.id).id
        library.saveUserFields(id, PhotoUserFields(caption = "My caption"))
        library.recordLaptopDescription(id, "A paper receipt on a table", "A receipt for headphones.")
        val note = db.photoNote(id)!!
        assertEquals("My caption", note.user.caption)
        assertEquals("A paper receipt on a table", note.derived.laptopCaption)
        assertTrue(Bm25Index(db).files("table").contains(id))
        val text = db.allChunks().filter { it.fileId == id }.joinToString("\n") { it.text }
        assertTrue(text.contains("from the laptop's AI"))
    }

    @Test fun downloadedPhotoKeepsLaptopCaptionAndNeverTouchesNotes() = runBlocking {
        val bytes = ByteArray(2048) { (it * 7).toByte() }
        val remote = FakeRemote(mapOf(1L to bytes))
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val downloader = FileDownloader(remote, library, File(tempDir(), "part"))
        val entry = remote.entry(1, "beach.jpg", "image/jpeg", photoCaption = "A red boat on a beach", photoSummary = "A beach scene.")
        val done = downloader.download(entry) { _, _ -> } as DownloadResult.Done
        assertEquals(sha256Of(bytes), done.file.sha256)
        assertEquals("A red boat on a beach", db.photoNote(done.file.id)!!.derived.laptopCaption)
        library.saveUserFields(done.file.id, PhotoUserFields(notes = "holiday"))
        downloader.download(entry) { _, _ -> } // already stored: nothing is overwritten
        assertEquals("holiday", db.photoNote(done.file.id)!!.user.notes)
        assertEquals(1, library.files().size)
    }

    @Test fun photoIndexedBeforeNotesExistedKeepsItsOcrTextWhenEdited() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val id = library.addPhoto().file.id
        // Simulate the old version: chunks exist, state READY, no photo_notes row.
        db.replaceChunks(id, listOf("ZENBREW WARRANTY CARD two years"))
        db.updateState(id, IndexState.READY, null, "ocr")
        assertNull(db.photoNote(id))
        assertEquals("ZENBREW WARRANTY CARD two years", library.photoNote(id)!!.derived.ocrText)
        library.saveUserFields(id, PhotoUserFields(caption = "Kettle card"))
        assertTrue(Bm25Index(db).files("zenbrew").contains(id))
        assertTrue(Bm25Index(db).files("kettle").contains(id))
    }

    @Test fun removingAPhotoRemovesItsNotes() = runBlocking {
        val db = InMemoryLocalDb()
        val library = photoLib(db)
        val id = library.index(library.addPhoto().file.id).id
        library.saveUserFields(id, PhotoUserFields(caption = "gone soon"))
        library.remove(id)
        assertNull(db.photoNote(id))
        assertTrue(Bm25Index(db).files("gone").isEmpty())
    }

    @Test fun duplicatePhotoIsReportedNotStoredTwice() {
        val library = photoLib()
        val first = library.addPhoto("a.jpg")
        val second = library.addPhoto("b.jpg")
        assertTrue(second is ImportResult.Duplicate)
        assertEquals(first.file.id, second.file.id)
    }

    @Test fun photoTextHelpers() {
        assertEquals(listOf("a", "b"), PhotoText.parseTags(" #A, b ;; a\n"))
        assertTrue(PhotoText.validDate(""))
        assertTrue(PhotoText.validDate("2025-02-28"))
        assertFalse(PhotoText.validDate("2025-02-30"))
        assertFalse(PhotoText.validDate("yesterday"))
        assertEquals("", PhotoText.compose(null, null))
        assertEquals(20, PhotoText.parseTags((1..40).joinToString(",") { "t$it" }).size)
    }
}
