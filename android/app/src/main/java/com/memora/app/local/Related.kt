package com.memora.app.local

/** Another stored file that shares distinctive words with this one. The link is inferred from text, never asserted. */
data class RelatedMemory(val file: LocalFile, val sharedTerms: List<String>, val excerpt: String)

object Related {
    /**
     * Finds files whose text shares the most distinctive words with [text]. "Distinctive" means longer words that are not
     * stopwords, ranked by how often they appear in this file; the index's own BM25 scoring then decides the order.
     */
    fun find(self: LocalFile, text: String, index: Bm25Index, limit: Int = 4): List<RelatedMemory> {
        val terms = Bm25Index.tokenize(text).filter { it.length >= 4 && it !in Bm25Index.STOPWORDS && !it.all(Char::isDigit) }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).map { it.key }
        if (terms.isEmpty()) return emptyList()
        return index.search(terms.joinToString(" "), limit = 20, maxPerFile = 1)
            .filter { it.file.id != self.id }
            .mapNotNull { e ->
                val words = Bm25Index.tokenize(e.chunk.text).toSet()
                val shared = terms.filter { it in words }
                // One shared common word is not a relationship worth showing.
                if (shared.size < 2) null else RelatedMemory(e.file, shared.take(3), e.chunk.text.replace('\n', ' ').take(140))
            }
            .take(limit)
    }
}
