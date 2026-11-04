package com.memora.app.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.File

/**
 * Runs the installed .litertlm model inside the app process with Google's LiteRT-LM runtime (CPU backend: it works on
 * every phone; no server, no network). One model is loaded at a time, requests are serialised, and any failure
 * (missing model, too little memory, bad file) becomes a [LocalAiException] instead of a crash.
 */
class LiteRtEngine(private val cacheDir: File, private val models: ModelManager) : LocalAiEngine {
    private companion object { const val TAG = "MemoraLiteRt" }

    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null

    /** Last initialisation problem, shown in Settings. */
    @Volatile var lastError: String? = null
        private set

    override suspend fun generate(system: String, user: String): String = lock.withLock {
        withContext(Dispatchers.Default) {
            val model = models.active() ?: throw LocalAiException("No on-device AI model is installed yet.", kind = AiFailure.NOT_INSTALLED)
            if (!model.name.endsWith(".litertlm", ignoreCase = true) || model.length() == 0L) {
                val reason = "${model.name} is not a .litertlm model file, which is the only format this app can run. Install a different model in Settings."
                lastError = reason
                throw LocalAiException(reason, kind = AiFailure.UNSUPPORTED_FORMAT)
            }
            val eng = try {
                load(model)
            } catch (e: Throwable) { // native loading can throw Errors (OOM, missing libs) as well as exceptions
                releaseLocked()
                val oom = e is OutOfMemoryError || e.message?.contains("memory", ignoreCase = true) == true
                val reason = if (oom) "The phone ran out of memory loading ${model.name}. Close other apps or choose a smaller model."
                else "The on-device model couldn't start (${e.message ?: e.javaClass.simpleName}). The file may be incompatible or damaged."
                lastError = reason
                // Class name and message only: never the question or the user's files.
                Log.w(TAG, "model load failed: ${e.javaClass.name}: ${e.message}")
                throw LocalAiException(reason, e, if (oom) AiFailure.OUT_OF_MEMORY else AiFailure.INIT_FAILED)
            }
            try {
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2, seed = 0),
                )
                eng.createConversation(config).use { conversation ->
                    conversation.sendMessage(Contents.of(user)).toString()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A failed generation can leave the native engine in an unknown state; reload it on the next question.
                releaseLocked()
                Log.w(TAG, "generation failed: ${e.javaClass.name}: ${e.message}")
                val oom = e is OutOfMemoryError
                throw LocalAiException(
                    if (oom) "The phone ran out of memory while answering. Close other apps and try again."
                    else "The on-device AI failed while answering (${e.message ?: e.javaClass.simpleName}). Try again.",
                    e, if (oom) AiFailure.OUT_OF_MEMORY else AiFailure.INFERENCE_FAILED,
                )
            }
        }
    }

    private fun load(model: File): Engine {
        engine?.let { if (loadedPath == model.path) return it }
        releaseLocked() // never keep two large models in memory
        val e = Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU(), maxNumTokens = 4096, cacheDir = cacheDir.path))
        e.initialize()
        engine = e
        loadedPath = model.path
        lastError = null
        return e
    }

    /**
     * Frees the model's native memory (memory pressure, or a new model being installed). If a question is being
     * answered right now the model is left alone: closing it mid-generation would crash the native runtime.
     * It is simply released after the next failure, or reloaded lazily when needed.
     */
    fun release() {
        if (!lock.tryLock()) return
        try { releaseLocked() } finally { lock.unlock() }
    }

    private fun releaseLocked() {
        try { engine?.close() } catch (e: Exception) { Log.w(TAG, "closing the model failed: ${e.javaClass.name}") }
        engine = null
        loadedPath = null
    }
}
