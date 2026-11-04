package com.memora.app.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyncRunnerTest {
    private fun setup(files: Map<Long, ByteArray>): Triple<FakeRemote, LocalLibrary, SyncRunner> {
        val remote = FakeRemote(files)
        val library = newLibrary()
        val runner = SyncRunner(remote, library, FileDownloader(remote, library, File(tempDir(), "part")), maxFiles = 2, maxFileBytes = 100)
        return Triple(remote, library, runner)
    }

    @Test fun downloadsNewFilesOnceAndIsIdempotent() = runBlocking {
        val (remote, library, runner) = setup(mapOf(1L to "alpha".toByteArray(), 2L to "beta".toByteArray()))
        val first = runner.run()
        assertEquals(2, first.downloaded); assertEquals(2, library.files().size)
        val opensAfterFirst = remote.opens
        val second = runner.run()
        assertEquals(0, second.downloaded); assertEquals(opensAfterFirst, remote.opens) // nothing downloaded twice
    }

    @Test fun limitsFilesPerRunAndSkipsOversizedFiles() = runBlocking {
        val (_, library, runner) = setup(mapOf(1L to "a".toByteArray(), 2L to "b".toByteArray(), 3L to "c".toByteArray(), 4L to ByteArray(500)))
        val r = runner.run()
        assertEquals(2, r.downloaded)
        assertEquals(2, r.skipped) // one over the per-run limit, one too large
        assertEquals(2, library.files().size)
    }

    @Test fun unreachableLaptopIsReportedAndRetried() = runBlocking {
        val (remote, library, runner) = setup(mapOf(1L to "x".toByteArray()))
        val dead = object : RemoteSource {
            override suspend fun manifest(): List<RemoteFile> = throw java.io.IOException("no route")
            override suspend fun open(id: Long, startAt: Long) = remote.open(id, startAt)
        }
        val r = SyncRunner(dead, library, FileDownloader(dead, library, File(tempDir(), "p"))).run()
        assertTrue(r.unreachable && r.shouldRetry); assertEquals(0, library.files().size)
    }

    @Test fun interruptedDownloadResumesAndVerifiesOnNextRun() = runBlocking {
        val data = ByteArray(80) { it.toByte() }
        val (remote, library, runner) = setup(mapOf(1L to data))
        remote.failAfterBytes = 30
        val broken = runner.run()
        assertTrue(broken.shouldRetry && library.files().isEmpty()) // a partial file never enters the library
        remote.failAfterBytes = null
        val ok = runner.run()
        assertEquals(1, ok.downloaded); assertEquals(30L, remote.lastStart) // resumed where it stopped
        assertEquals(sha256Of(data), library.files().single().sha256)
    }

    @Test fun phoneKeepsFilesTheLaptopNoLongerLists() = runBlocking {
        val (remote, library, runner) = setup(mapOf(1L to "keep me".toByteArray()))
        runner.run()
        val empty = FakeRemote(emptyMap())
        SyncRunner(empty, library, FileDownloader(empty, library, File(tempDir(), "q"))).run()
        assertEquals(1, library.files().size)
    }
}
