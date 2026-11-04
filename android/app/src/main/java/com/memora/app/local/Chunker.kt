package com.memora.app.local

object Chunker {
    /** Puts chunks back together, dropping the text each chunk repeats from the end of the one before it. */
    fun join(chunks: List<String>): String {
        val sb = StringBuilder()
        for (c in chunks) {
            if (sb.isEmpty()) { sb.append(c); continue }
            val tail = sb.takeLast(minOf(300, sb.length, c.length)).toString()
            var overlap = 0
            for (n in tail.length downTo 20) if (c.startsWith(tail.takeLast(n))) { overlap = n; break }
            sb.append(if (overlap > 0) c.substring(overlap) else "\n\n$c")
        }
        return sb.toString()
    }

    /** Splits text into overlapping pieces on paragraph/sentence/space boundaries when possible. */
    fun split(text: String, size: Int = 1000, overlap: Int = 150): List<String> {
        require(size > overlap && overlap >= 0)
        val clean = text.replace("\r\n", "\n").replace(Regex("[ \t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= size) return listOf(clean)
        val out = ArrayList<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + size, clean.length)
            if (end < clean.length) {
                val window = clean.substring(start, end)
                val cut = maxOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf('\n'), window.lastIndexOf(' '))
                if (cut > size / 2) end = start + cut + 1
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) out += piece
            if (end >= clean.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return out
    }
}
