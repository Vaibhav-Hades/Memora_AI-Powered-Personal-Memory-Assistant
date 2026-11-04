package com.memora.app.local

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** How one laptop file relates to the phone's library. */
enum class SyncState {
    /** Only on the laptop. */
    LAPTOP_ONLY,

    /** On the phone and identical to the laptop's current version. */
    OFFLINE,

    /** The laptop's version differs from the copy the phone downloaded. */
    CHANGED,

    /** The laptop no longer offers it (moved or deleted); the phone's copy is kept. */
    REMOVED_ON_LAPTOP,

    /** Listed by the laptop but its original is missing there, so it can't be downloaded now. */
    UNAVAILABLE,
}

data class SyncEntry(val remote: RemoteFile?, val local: LocalFile?, val state: SyncState) {
    val name: String get() = remote?.name ?: local?.name ?: ""
}

object SyncPlanner {
    /** Matches by content hash first (stable), then by the laptop's id as a hint. Never deletes anything. */
    fun plan(manifest: List<RemoteFile>, local: List<LocalFile>): List<SyncEntry> {
        val bySha = local.associateBy { it.sha256 }
        val byRemote = local.filter { it.remoteId != null }.associateBy { it.remoteId }
        val matched = HashSet<Long>()
        val entries = manifest.map { r ->
            val sameContent = r.sha256?.let { bySha[it] }
            val l = sameContent ?: byRemote[r.id]
            if (l != null) matched += l.id
            val state = when {
                sameContent != null -> SyncState.OFFLINE
                l != null -> if (r.available) SyncState.CHANGED else SyncState.UNAVAILABLE
                !r.available -> SyncState.UNAVAILABLE
                else -> SyncState.LAPTOP_ONLY
            }
            SyncEntry(r, l, state)
        }
        val orphans = local.filter { it.remoteId != null && it.id !in matched }.map { SyncEntry(null, it, SyncState.REMOVED_ON_LAPTOP) }
        return entries + orphans
    }
}

sealed interface DownloadResult {
    data class Done(val file: LocalFile, val alreadyHad: Boolean) : DownloadResult
    data class Failed(val message: String, val canRetry: Boolean) : DownloadResult
    data object AlreadyRunning : DownloadResult
}

class DownloadException(message: String, val canRetry: Boolean = true) : Exception(message)

/**
 * Downloads the laptop's ORIGINAL file bytes into a partial file, resuming where a previous attempt stopped, then
 * verifies size and SHA-256 before the file enters the library. A partial file is never treated as complete.
 */
class FileDownloader(private val remote: RemoteSource, private val library: LocalLibrary, private val partDir: File, private val origin: String = "laptop") {
    private val running = ConcurrentHashMap.newKeySet<Long>()

    init { partDir.mkdirs() }

    fun isRunning(remoteId: Long) = remoteId in running

    suspend fun download(file: RemoteFile, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): DownloadResult {
        if (!running.add(file.id)) return DownloadResult.AlreadyRunning
        try {
            if (!file.available) return DownloadResult.Failed("The original is no longer on the laptop.", canRetry = false)
            val expected = file.sha256 ?: return DownloadResult.Failed("The laptop did not provide a checksum, so the copy can't be verified.", canRetry = false)
            library.files().firstOrNull { it.sha256 == expected }?.let { return DownloadResult.Done(it, alreadyHad = true) }

            val part = File(partDir, "${expected}.part")
            if (part.exists() && part.length() > file.size) part.delete() // stale or corrupt leftovers
            val resumeAt = if (part.exists()) part.length() else 0L

            if (resumeAt < file.size) remote.open(file.id, resumeAt).use { stream ->
                val append = stream.startedAt > 0 && stream.startedAt == resumeAt
                if (!append && part.exists()) part.delete() // the laptop restarted from 0, so must we
                var done = if (append) resumeAt else 0L
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, file.size)
                    }
                }
            }
            // Verify before the file can become part of the library.
            if (part.length() != file.size) {
                return DownloadResult.Failed("The download was interrupted (${part.length()} of ${file.size} bytes). Try again to resume.", canRetry = true)
            }
            val actual = sha256(part)
            if (actual != expected) {
                part.delete()
                return DownloadResult.Failed("The downloaded file did not match the laptop's checksum, so it was discarded. Try again.", canRetry = true)
            }
            val result = library.adopt(part, file.size, actual, file.name, file.mime, origin, file.id, expected)
            runCatching { library.recordLaptopDescription(result.file.id, file.photoCaption, file.photoSummary) }
            return DownloadResult.Done(result.file, alreadyHad = result is ImportResult.Duplicate)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // the partial file is kept so a retry resumes
        } catch (e: com.memora.app.data.MemoraException) {
            return DownloadResult.Failed(e.message ?: "Download failed.", canRetry = e.kind != com.memora.app.data.MemoraException.Kind.REJECTED)
        } catch (e: java.io.IOException) {
            val full = e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true
            return DownloadResult.Failed(
                if (full) "The phone is out of storage space." else "The connection dropped (${e.message ?: "network error"}). Try again to resume.",
                canRetry = !full,
            )
        } finally {
            running.remove(file.id)
        }
    }

    companion object {
        fun sha256(f: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
            }
            return LocalLibrary.hex(d.digest())
        }

        /** A name that doesn't overwrite anything in [existing]: "a.pdf" -> "a (1).pdf". */
        fun uniqueName(name: String, existing: Set<String>): String {
            if (name !in existing) return name
            val dot = name.lastIndexOf('.')
            val stem = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var i = 1
            while ("$stem ($i)$ext" in existing) i++
            return "$stem ($i)$ext"
        }
    }
}
