package com.memora.app.local

/** Persistence for the phone's own library. [version] changes on every write so caches know to rebuild. */
interface LocalDb {
    val version: Long
    fun allFiles(): List<LocalFile>
    fun file(id: Long): LocalFile?
    fun findBySha(sha256: String): LocalFile?
    fun findByRemote(origin: String, remoteId: Long): LocalFile?
    fun insertFile(f: LocalFile): Long
    fun updateState(id: Long, state: IndexState, error: String?, extractor: String?)
    fun updateRemote(id: Long, remoteId: Long, remoteSha256: String)
    fun deleteFile(id: Long)
    fun replaceChunks(fileId: Long, texts: List<String>)
    fun allChunks(): List<LocalChunk>
    fun addQa(question: String, answer: String, sufficient: Boolean, mode: String)
    fun history(limit: Int = 50): List<QaRecord>

    fun photoNote(fileId: Long): PhotoNote?
    fun allPhotoNotes(): List<PhotoNote>
    /** Writes only the user's own fields; derived text is untouched. */
    fun saveUserFields(fileId: Long, user: PhotoUserFields, now: Long)
    /** Writes only derived text; the user's fields are untouched. Null arguments keep the stored value. */
    fun saveDerived(fileId: Long, ocrText: String?, laptopCaption: String?, laptopSummary: String?)
}
