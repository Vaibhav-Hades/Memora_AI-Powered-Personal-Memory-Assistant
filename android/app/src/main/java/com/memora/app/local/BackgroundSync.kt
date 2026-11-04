package com.memora.app.local

/** What one background sync run did. Counts only; no file names or paths are kept for logs. */
data class SyncReport(
    val downloaded: Int = 0,
    val alreadyHad: Int = 0,
    val failed: Int = 0,
    /** Failures worth retrying later (dropped connection, laptop restarting). */
    val retryable: Int = 0,
    /** Left alone on purpose: larger than the per-file limit, or beyond this run's file limit. */
    val skipped: Int = 0,
    /** The laptop could not be reached at all, so nothing was attempted. */
    val unreachable: Boolean = false,
    val message: String = "",
) {
    val shouldRetry: Boolean get() = unreachable || retryable > 0
}

/**
 * Brings laptop files that the phone does not have yet onto the phone, within limits. Idempotent: a file that is
 * already here (same SHA-256) is never downloaded twice, an interrupted download resumes from its partial file, and
 * nothing on the phone is ever deleted, even if the laptop no longer lists it.
 */
class SyncRunner(
    private val remote: RemoteSource,
    private val library: LocalLibrary,
    private val downloader: FileDownloader,
    private val maxFiles: Int = 20,
    private val maxFileBytes: Long = 25L * 1024 * 1024,
) {
    suspend fun run(): SyncReport {
        val manifest = try {
            remote.manifest()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncReport(unreachable = true, message = "The laptop could not be reached.")
        }
        val wanted = SyncPlanner.plan(manifest, library.files())
            .filter { (it.state == SyncState.LAPTOP_ONLY || it.state == SyncState.CHANGED) && it.remote?.available == true }
            .mapNotNull { it.remote }
        val eligible = wanted.filter { it.size <= maxFileBytes }
        val batch = eligible.take(maxFiles)
        var downloaded = 0; var had = 0; var failed = 0; var retryable = 0
        for (file in batch) {
            when (val r = downloader.download(file)) {
                is DownloadResult.Done -> if (r.alreadyHad) had++ else downloaded++
                is DownloadResult.Failed -> { failed++; if (r.canRetry) retryable++ }
                DownloadResult.AlreadyRunning -> Unit
            }
        }
        val skipped = (wanted.size - eligible.size) + (eligible.size - batch.size)
        return SyncReport(downloaded, had, failed, retryable, skipped, message = "Synced: $downloaded new, $failed failed, $skipped left for later.")
    }
}
