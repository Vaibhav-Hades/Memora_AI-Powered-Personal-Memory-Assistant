package com.memora.app.local

import android.content.Context
import androidx.core.content.edit
import java.io.File

/** Remembers the user's chosen operating mode. Changing it is always an explicit user action. */
class ModePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("memora-mode", Context.MODE_PRIVATE)

    var mode: OperatingMode
        get() = runCatching { OperatingMode.valueOf(prefs.getString("mode", null) ?: "") }.getOrDefault(OperatingMode.AUTO)
        set(value) = prefs.edit { putString("mode", value.name) }
}

/**
 * Everything the phone needs to work without the laptop: its own database, file vault, search index, model files and
 * AI engine. All of it lives in app-private storage.
 */
class LocalMemory(context: Context) {
    private val root = File(context.filesDir, "memora").apply { mkdirs() }
    val db: LocalDb = SqliteLocalDb(context)
    val library = LocalLibrary(db, File(root, "library"), DefaultExtractor(MlKitOcr()))
    val index = Bm25Index(db)
    val models = ModelManager(File(root, "models"))
    val engine = LiteRtEngine(File(context.cacheDir, "litert").apply { mkdirs() }, models)
    val partDir = File(root, "partial")

    /** The model is only offered to the generator when one is installed, so "no model" is reported honestly. */
    fun answerer() = LocalAnswerGenerator(index, if (models.active() != null) engine else null)

    fun downloader(remote: RemoteSource) = FileDownloader(remote, library, partDir)
}
