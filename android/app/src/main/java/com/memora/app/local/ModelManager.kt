package com.memora.app.local

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A model Memora knows how to run on the phone. Sizes and hashes are those published by the model host. */
data class ModelSpec(
    val id: String,
    val label: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val note: String,
)

object ModelCatalog {
    private const val HF = "https://huggingface.co/litert-community"

    val models = listOf(
        ModelSpec(
            "qwen3-0.6b", "Qwen3 0.6B (smallest, fastest)", "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            "$HF/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm", 343_490_560L,
            "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0",
            "About 0.35 GB. Works on most phones; answers are less reliable.",
        ),
        ModelSpec(
            "qwen3-1.7b", "Qwen3 1.7B (recommended)", "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            "$HF/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", 977_184_032L,
            "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8",
            "About 1 GB. Needs roughly 3 GB of free memory while answering.",
        ),
        ModelSpec(
            "gemma-4-e2b", "Gemma 4 E2B (best quality, largest)", "gemma-4-E2B-it.litertlm",
            "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", 2_588_147_712L,
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            "About 2.6 GB. Needs a phone with plenty of RAM (8 GB or more).",
        ),
    )

    fun byId(id: String) = models.firstOrNull { it.id == id }
}

sealed interface ModelStatus {
    data object NotInstalled : ModelStatus
    data class Downloading(val done: Long, val total: Long) : ModelStatus
    data class Installed(val file: File, val label: String) : ModelStatus
    data class Problem(val message: String) : ModelStatus
}

class ModelException(message: String) : Exception(message)

/**
 * Keeps model files in app-private storage. Nothing is downloaded unless [download] is called (the UI asks the user
 * first), downloads resume, and a file only gets its final name after its size and SHA-256 are verified.
 */
class ModelManager(private val modelsDir: File, private val freeBytes: () -> Long = { modelsDir.usableSpace }) {
    init { modelsDir.mkdirs() }

    private val activeFile = File(modelsDir, "active.txt")

    fun installed(): List<File> = modelsDir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }?.sortedBy { it.name }.orEmpty()

    /** The model to run: the one the user selected if still present, else the only installed one. */
    fun active(): File? {
        val chosen = runCatching { activeFile.readText().trim() }.getOrNull()
        return installed().firstOrNull { it.name == chosen } ?: installed().singleOrNull()
    }

    fun select(file: File) { activeFile.writeText(file.name) }

    fun status(): ModelStatus = active()?.let { ModelStatus.Installed(it, labelFor(it)) } ?: ModelStatus.NotInstalled

    fun labelFor(f: File) = ModelCatalog.models.firstOrNull { it.fileName == f.name }?.label ?: f.name

    /** Checks there is room for [needed] bytes (plus 10% slack) before anything is written. */
    fun checkSpace(needed: Long) {
        val required = needed + needed / 10
        if (freeBytes() < required) {
            throw ModelException("Not enough storage: this model needs about ${required / (1024 * 1024)} MB free, the phone has ${freeBytes() / (1024 * 1024)} MB.")
        }
    }

    /** Downloads a catalogued model (requires internet, once). Resumes a previous partial download. */
    fun download(spec: ModelSpec, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val target = File(modelsDir, spec.fileName)
        if (target.isFile && target.length() == spec.sizeBytes) return target
        val part = File(modelsDir, spec.fileName + ".part")
        if (part.length() > spec.sizeBytes) part.delete()
        checkSpace(spec.sizeBytes - part.length())
        var done = part.length()
        if (done < spec.sizeBytes) {
            val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            try {
                val code = conn.responseCode
                if (code != 200 && code != 206) throw ModelException("The model host answered $code. Check the internet connection and try again.")
                val append = code == 206 && done > 0
                if (!append) done = 0
                java.io.FileOutputStream(part, append).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, spec.sizeBytes)
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                throw ModelException("The download was interrupted (${e.message}). Try again to resume.")
            } finally {
                conn.disconnect()
            }
        }
        return finish(part, target, spec.sizeBytes, spec.sha256)
    }

    /** Imports a model file the user picked (for example copied to the phone from a computer). */
    fun importFrom(name: String, input: InputStream, size: Long = -1): File {
        if (!name.endsWith(".litertlm")) throw ModelException("Choose a .litertlm model file (LiteRT-LM format). .gguf and .task files are not supported.")
        if (size > 0) checkSpace(size)
        val safe = LocalLibrary.displayName(name)
        val part = File(modelsDir, "$safe.part")
        part.outputStream().use { input.copyTo(it) }
        val known = ModelCatalog.models.firstOrNull { it.fileName == safe }
        return if (known != null) finish(part, File(modelsDir, safe), known.sizeBytes, known.sha256)
        else finish(part, File(modelsDir, safe), null, null) // unknown model: only loading it can prove it works
    }

    private fun finish(part: File, target: File, size: Long?, sha: String?): File {
        if (size != null && part.length() != size) {
            throw ModelException("The model file is incomplete (${part.length()} of $size bytes). Try again to resume.")
        }
        if (part.length() < 1_000_000) { part.delete(); throw ModelException("That file is too small to be a model.") }
        if (sha != null && FileDownloader.sha256(part) != sha) {
            part.delete()
            throw ModelException("The model file failed its integrity check and was discarded. Download it again.")
        }
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
        select(target)
        return target
    }

    fun remove(file: File) { file.delete(); if (activeFile.exists() && runCatching { activeFile.readText().trim() }.getOrNull() == file.name) activeFile.delete() }
}
