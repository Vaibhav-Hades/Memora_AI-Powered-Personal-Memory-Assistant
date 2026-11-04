package com.memora.app.local

/** Where a file is in the phone's own indexing pipeline. Only READY files are searchable. */
enum class IndexState { PENDING, EXTRACTING, INDEXING, READY, FAILED, UNSUPPORTED }

/**
 * A file stored on the phone. Identity on the phone is [id] (local) and [sha256] (content); [remoteId] is only a
 * hint about which laptop file it came from, because laptop database ids are not permanent.
 */
data class LocalFile(
    val id: Long,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val storedName: String,
    val importedAt: Long,
    val origin: String,
    val remoteId: Long? = null,
    val remoteSha256: String? = null,
    val state: IndexState = IndexState.PENDING,
    val error: String? = null,
    val extractor: String? = null,
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

data class LocalChunk(val id: Long, val fileId: Long, val index: Int, val text: String)

data class QaRecord(val id: Long, val question: String, val answer: String, val sufficient: Boolean, val mode: String, val at: Long)

/** How questions are routed. The user's choice is never overridden silently. */
enum class OperatingMode(val label: String, val summary: String) {
    PHONE_ONLY("Phone only", "Uses only files and the AI model on this phone. Nothing leaves the phone."),
    LAPTOP("Laptop", "Asks the laptop's AI about the laptop's files. Needs the laptop on the same Wi-Fi."),
    AUTO("Automatic", "Uses the laptop when it is reachable, otherwise the phone's own files and AI."),
}

sealed interface Route {
    data object Phone : Route
    data object Laptop : Route
    /** The chosen mode cannot be served right now; [reason] tells the user what to do. */
    data class Unavailable(val reason: String) : Route
}

object ModeResolver {
    /**
     * [laptopReachable] must come from the last known connection state; it is never probed here, so choosing a route
     * does not itself send anything to the laptop.
     */
    fun resolve(mode: OperatingMode, laptopConfigured: Boolean, laptopReachable: Boolean): Route = when (mode) {
        OperatingMode.PHONE_ONLY -> Route.Phone
        OperatingMode.LAPTOP -> when {
            !laptopConfigured -> Route.Unavailable("No laptop is set up. Open Settings to connect one, or switch to Phone only.")
            !laptopReachable -> Route.Unavailable("Laptop unavailable. Check it is on and on the same Wi-Fi, or switch to Phone only or Automatic.")
            else -> Route.Laptop
        }
        OperatingMode.AUTO -> if (laptopConfigured && laptopReachable) Route.Laptop else Route.Phone
    }
}
