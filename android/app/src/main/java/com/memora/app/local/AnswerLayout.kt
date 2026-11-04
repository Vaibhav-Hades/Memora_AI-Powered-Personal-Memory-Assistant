package com.memora.app.local

/** An answer split for display: the sentence that answers, then whatever explains it. Wording is never changed. */
data class AnswerParts(val direct: String, val explanation: String)

object AnswerLayout {
    // A sentence ends at . ! or ? followed by whitespace (so "1.7 L" and "[2]." handling stay intact).
    private val END = Regex("""(?<=[.!?])(?:\[\d+])*\s+""")

    fun split(text: String): AnswerParts {
        val t = text.trim()
        val m = END.find(t) ?: return AnswerParts(t, "")
        val direct = t.substring(0, m.range.first).trim()
        val rest = t.substring(m.range.last + 1).trim()
        return AnswerParts(direct.ifEmpty { t }, rest)
    }
}
