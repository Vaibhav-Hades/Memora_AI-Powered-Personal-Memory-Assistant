package com.memora.app.local

import kotlin.math.ln

/** A retrieved piece of evidence from the phone's own files. */
data class Evidence(val chunk: LocalChunk, val file: LocalFile, val score: Double)

/**
 * Keyword (BM25) search over the chunks stored on the phone. Rebuilt in memory from the database when its
 * [LocalDb.version] changes, which is cheap at personal-library scale (thousands of chunks).
 */
class Bm25Index(private val db: LocalDb) {
    private var builtFor = -1L
    private var chunks: List<LocalChunk> = emptyList()
    private var tf: List<Map<String, Int>> = emptyList()
    private var df: Map<String, Int> = emptyMap()
    private var avgLen = 1.0

    private fun ensureBuilt() {
        if (builtFor == db.version) return
        val ready = db.allFiles().filter { it.state == IndexState.READY }.map { it.id }.toSet()
        chunks = db.allChunks().filter { it.fileId in ready }
        tf = chunks.map { c -> tokenize(c.text).groupingBy { it }.eachCount() }
        val d = HashMap<String, Int>()
        tf.forEach { m -> m.keys.forEach { d[it] = (d[it] ?: 0) + 1 } }
        df = d
        avgLen = (tf.sumOf { it.values.sum() }.toDouble() / maxOf(tf.size, 1)).coerceAtLeast(1.0)
        builtFor = db.version
    }

    fun search(query: String, limit: Int = 8, maxPerFile: Int = 3): List<Evidence> {
        ensureBuilt()
        val terms = queryTerms(query)
        if (terms.isEmpty() || chunks.isEmpty()) return emptyList()
        val n = chunks.size.toDouble()
        val scored = ArrayList<Pair<Int, Double>>()
        for (i in chunks.indices) {
            val len = tf[i].values.sum().toDouble()
            var s = 0.0
            for (t in terms) {
                val f = (tf[i][t] ?: 0).toDouble()
                if (f == 0.0) continue
                val dft = (df[t] ?: 1).toDouble()
                val idf = ln(1 + (n - dft + 0.5) / (dft + 0.5))
                s += idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * len / avgLen))
            }
            if (s > 0) scored += i to s
        }
        val files = db.allFiles().associateBy { it.id }
        val perFile = HashMap<Long, Int>()
        val out = ArrayList<Evidence>()
        for ((i, s) in scored.sortedByDescending { it.second }) {
            val c = chunks[i]
            val file = files[c.fileId] ?: continue
            if ((perFile[c.fileId] ?: 0) >= maxPerFile) continue
            perFile[c.fileId] = (perFile[c.fileId] ?: 0) + 1
            out += Evidence(c, file, s)
            if (out.size >= limit) break
        }
        return out
    }

    companion object {
        private val WORD = Regex("""[\p{L}\p{N}]+""")
        val STOPWORDS = setOf(
            "the", "a", "an", "of", "my", "me", "i", "is", "are", "was", "were", "do", "does", "did", "to", "and", "or",
            "in", "on", "for", "what", "which", "who", "when", "where", "how", "much", "many", "it", "its", "this", "that",
            "with", "at", "by", "from", "have", "has", "had", "pay", "paid", "can", "you",
        )

        /** Lowercased words; a light plural/suffix stripper so "headphones" matches "headphone" and "years" matches "year". */
        fun tokenize(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value }.map(::stem).toList()

        /** Words worth searching for: stopwords removed first (on the raw word), then stemmed. */
        fun queryTerms(query: String): List<String> =
            WORD.findAll(query.lowercase()).map { it.value }.filter { it !in STOPWORDS }.map(::stem).distinct().toList()

        fun stem(w: String): String = when {
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }
    }
}
