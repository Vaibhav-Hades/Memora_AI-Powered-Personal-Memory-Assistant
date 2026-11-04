package com.memora.app.local

import com.memora.app.data.Answer
import com.memora.app.data.Source
import org.json.JSONObject

/** Anything that can turn a prompt into text on the phone. The real one wraps LiteRT-LM; tests use a fake. */
interface LocalAiEngine {
    /** Runs one prompt to completion. Throws [LocalAiException] if the model isn't installed or can't run. */
    suspend fun generate(system: String, user: String): String
}

/** Why the on-device AI could not answer, so the UI can say something specific and offer the right next step. */
enum class AiFailure { NOT_INSTALLED, UNSUPPORTED_FORMAT, OUT_OF_MEMORY, INIT_FAILED, INFERENCE_FAILED }

class LocalAiException(message: String, cause: Throwable? = null, val kind: AiFailure = AiFailure.INFERENCE_FAILED) : Exception(message, cause)

/**
 * Answers a question from the phone's own library: BM25 retrieval, then the on-device model, then validation.
 * Never calls the laptop or the internet. Citation checks prove a source exists and was retrieved, not that the
 * sentence is true; the evidence excerpts are shown so the user can check.
 */
class LocalAnswerGenerator(
    private val index: Bm25Index,
    private val engine: LocalAiEngine?,
    private val maxContextChars: Int = 6000,
    private val topK: Int = 6,
) {
    suspend fun answer(question: String): Answer {
        val evidence = index.search(question, topK)
        if (evidence.isEmpty()) return Answer(NO_LOCAL_EVIDENCE, sufficient = false, sources = emptyList())

        val used = fitToBudget(evidence)
        val sources = used.mapIndexed { i, e -> source(i + 1, e, cited = false) }
        val eng = engine ?: return Answer(AI_NOT_READY, false, sources)

        val raw = try {
            eng.generate(SYSTEM_PROMPT, buildUserPrompt(question, used))
        } catch (e: LocalAiException) {
            return Answer("${e.message} The matching files on this phone are listed below.", false, sources)
        }
        return validate(parse(raw), used)
    }

    private fun fitToBudget(evidence: List<Evidence>): List<Evidence> {
        var budget = maxContextChars
        val out = ArrayList<Evidence>()
        for (e in evidence) {
            val cost = e.chunk.text.length + e.file.name.length + 40
            if (cost > budget && out.isNotEmpty()) break
            out += e
            budget -= cost
        }
        return out
    }

    fun buildUserPrompt(question: String, used: List<Evidence>): String {
        val sources = used.mapIndexed { i, e -> "[${i + 1}] File: ${e.file.name}\n${e.chunk.text}" }.joinToString("\n\n---\n\n")
        return "Sources:\n\n$sources\n\n===\n\nQuestion: $question"
    }

    /** What the model produced, before citation checks. */
    data class Parsed(val answer: String, val sufficient: Boolean, val citations: Set<Int>, val structured: Boolean)

    fun parse(raw: String): Parsed {
        val text = stripThinking(raw).trim()
        val json = extractJson(text)
        if (json != null) {
            val cites = HashSet<Int>()
            json.optJSONArray("citations")?.let { arr -> for (i in 0 until arr.length()) arr.optInt(i, -1).takeIf { it > 0 }?.let(cites::add) }
            return Parsed(json.optString("answer").trim(), json.optBoolean("sufficient", false), cites, structured = true)
        }
        // The model ignored the format. Keep its words, but only [n] markers in them can count as citations.
        val marks = Regex("\\[(\\d+)]").findAll(text).map { it.groupValues[1].toInt() }.toSet()
        return Parsed(text, sufficient = marks.isNotEmpty(), citations = marks, structured = false)
    }

    fun validate(p: Parsed, used: List<Evidence>): Answer {
        val answer = p.answer.ifBlank { return Answer(NO_USABLE_ANSWER, false, used.mapIndexed { i, e -> source(i + 1, e, false) }) }
        val valid = p.citations.filter { it in 1..used.size }.toMutableSet()
        if (p.sufficient) valid += Regex("\\[(\\d+)]").findAll(answer).map { it.groupValues[1].toInt() }.filter { it in 1..used.size }
        // A "not enough evidence" reply often names sources only to say they don't help: those aren't evidence.
        val cited = if (p.sufficient) valid else emptySet()
        val sources = used.mapIndexed { i, e -> source(i + 1, e, (i + 1) in cited) }.sortedWith(compareBy({ !it.cited }, { it.ref }))
        val supported = p.sufficient && cited.isNotEmpty()
        val text = if (supported) answer else if (p.sufficient) "$answer\n\n(The model did not cite a valid source, so this is not shown as supported.)" else answer
        return Answer(text, supported, sources)
    }

    private fun source(ref: Int, e: Evidence, cited: Boolean) = Source(
        ref = ref, cited = cited, fileId = e.file.id, name = e.file.name, path = "On this phone",
        kind = if (e.file.ext in DefaultExtractor.IMAGE_EXTS) "image" else "document", ext = e.file.ext,
        snippet = e.chunk.text.take(500), local = true,
    )

    companion object {
        const val NO_LOCAL_EVIDENCE =
            "The files stored on this phone don't contain enough information to answer this. Only the phone's own " +
                "library was searched. Import files or download them from the laptop to search them here."
        const val AI_NOT_READY =
            "I found matching files on this phone, but the on-device AI isn't ready, so I can't write an answer. " +
                "The matching files are listed below. Install a model in Settings to get answers offline."
        const val NO_USABLE_ANSWER = "The on-device AI didn't produce a usable answer. The matching files are listed below."

        val SYSTEM_PROMPT = """You are Memora, a private assistant answering from the user's own files.
Use ONLY the numbered sources below. Never use outside knowledge and never guess.
Cite every fact with its source number in square brackets, like [1].
If the sources do not contain the answer, say what is missing and set "sufficient" to false.
Reply with ONLY one JSON object: {"answer": "<short answer with [n] citations>", "sufficient": true or false, "citations": [<source numbers you used>]}.
Do not add any text outside the JSON."""

        private val THINK = Regex("(?s)<think>.*?</think>")

        /** Removes reasoning blocks some models emit; only the final answer is kept and shown. */
        fun stripThinking(s: String): String = s.replace(THINK, "").replace(Regex("(?s)^.*?</think>"), "")

        fun extractJson(s: String): JSONObject? {
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try { JSONObject(s.substring(start, end + 1)) } catch (_: Exception) { null }
        }
    }
}
