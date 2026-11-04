package com.memora.app.local

import java.io.Closeable
import java.io.InputStream

/** One entry of the laptop's sync manifest. The laptop's own paths are never part of it. */
data class RemoteFile(
    val id: Long, val name: String, val ext: String, val kind: String, val mime: String,
    val size: Long, val sha256: String?, val available: Boolean, val status: String,
    /** The laptop's own description of a photo (its vision model), when it has one. Never the user's notes. */
    val photoCaption: String? = null, val photoSummary: String? = null, val capturedDate: String? = null,
)

/** An open download. [startedAt] is the offset the laptop actually honoured (0 when it ignored the Range request). */
class RemoteStream(
    val totalSize: Long, val sha256: String?, val startedAt: Long, val input: InputStream, private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { input.close() }
        onClose()
    }
}

/** What the phone needs from the laptop for syncing; implemented by MemoraApi and faked in tests. */
interface RemoteSource {
    suspend fun manifest(): List<RemoteFile>
    suspend fun open(id: Long, startAt: Long): RemoteStream
}
