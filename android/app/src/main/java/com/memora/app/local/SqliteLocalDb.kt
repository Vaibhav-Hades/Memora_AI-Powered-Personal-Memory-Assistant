package com.memora.app.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The phone's own SQLite database (separate from the laptop's; never copied from it).
 * Writes go through SQLite transactions, so a crash mid-update leaves the previous state intact.
 * Migrations: bump [VERSION] and add a step in [onUpgrade]; existing rows are kept.
 */
class SqliteLocalDb(context: Context, name: String = "memora-local.db") : SQLiteOpenHelper(context, name, null, VERSION), LocalDb {
    @Volatile override var version: Long = 0
        private set

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE files (
                id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL,
                sha256 TEXT NOT NULL UNIQUE, stored_name TEXT NOT NULL, imported_at INTEGER NOT NULL, origin TEXT NOT NULL,
                remote_id INTEGER, remote_sha256 TEXT, state TEXT NOT NULL, error TEXT, extractor TEXT)""",
        )
        db.execSQL(
            """CREATE TABLE chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT, file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
                idx INTEGER NOT NULL, text TEXT NOT NULL)""",
        )
        db.execSQL("CREATE INDEX chunks_file ON chunks(file_id)")
        db.execSQL(
            """CREATE TABLE qa (id INTEGER PRIMARY KEY AUTOINCREMENT, question TEXT NOT NULL, answer TEXT NOT NULL,
                sufficient INTEGER NOT NULL, mode TEXT NOT NULL, at INTEGER NOT NULL)""",
        )
        createPhotoNotes(db)
    }

    private fun createPhotoNotes(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS photo_notes (
                file_id INTEGER PRIMARY KEY REFERENCES files(id) ON DELETE CASCADE,
                user_caption TEXT NOT NULL DEFAULT '', user_notes TEXT NOT NULL DEFAULT '', user_tags TEXT NOT NULL DEFAULT '',
                event_title TEXT NOT NULL DEFAULT '', event_date TEXT NOT NULL DEFAULT '', location_label TEXT NOT NULL DEFAULT '',
                favorite INTEGER NOT NULL DEFAULT 0, ocr_text TEXT NOT NULL DEFAULT '',
                laptop_caption TEXT NOT NULL DEFAULT '', laptop_summary TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL DEFAULT 0)""",
        )
    }

    // Migrations only add; existing rows (files, chunks, history) are kept as they are. Version 2 adds photo_notes.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createPhotoNotes(db)
    }

    private fun bump() { version++ }

    private fun Cursor.toFile() = LocalFile(
        id = getLong(0), name = getString(1), mime = getString(2), size = getLong(3), sha256 = getString(4),
        storedName = getString(5), importedAt = getLong(6), origin = getString(7),
        remoteId = if (isNull(8)) null else getLong(8), remoteSha256 = getString(9),
        state = IndexState.valueOf(getString(10)), error = getString(11), extractor = getString(12),
    )

    private val cols = "id,name,mime,size,sha256,stored_name,imported_at,origin,remote_id,remote_sha256,state,error,extractor"

    private fun query(where: String?, vararg args: String): List<LocalFile> =
        readableDatabase.rawQuery("SELECT $cols FROM files ${where ?: ""} ORDER BY imported_at DESC", args).use { c ->
            buildList { while (c.moveToNext()) add(c.toFile()) }
        }

    override fun allFiles() = query(null)
    override fun file(id: Long) = query("WHERE id = ?", id.toString()).firstOrNull()
    override fun findBySha(sha256: String) = query("WHERE sha256 = ?", sha256).firstOrNull()
    override fun findByRemote(origin: String, remoteId: Long) =
        query("WHERE origin = ? AND remote_id = ?", origin, remoteId.toString()).firstOrNull()

    override fun insertFile(f: LocalFile): Long {
        val v = ContentValues().apply {
            put("name", f.name); put("mime", f.mime); put("size", f.size); put("sha256", f.sha256)
            put("stored_name", f.storedName); put("imported_at", f.importedAt); put("origin", f.origin)
            put("remote_id", f.remoteId); put("remote_sha256", f.remoteSha256)
            put("state", f.state.name); put("error", f.error); put("extractor", f.extractor)
        }
        return writableDatabase.insertOrThrow("files", null, v).also { bump() }
    }

    override fun updateState(id: Long, state: IndexState, error: String?, extractor: String?) {
        val v = ContentValues().apply { put("state", state.name); put("error", error); put("extractor", extractor) }
        writableDatabase.update("files", v, "id = ?", arrayOf(id.toString()))
        bump()
    }

    override fun updateRemote(id: Long, remoteId: Long, remoteSha256: String) {
        val v = ContentValues().apply { put("remote_id", remoteId); put("remote_sha256", remoteSha256) }
        writableDatabase.update("files", v, "id = ?", arrayOf(id.toString()))
        bump()
    }

    override fun deleteFile(id: Long) {
        writableDatabase.delete("files", "id = ?", arrayOf(id.toString()))
        bump()
    }

    override fun replaceChunks(fileId: Long, texts: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("chunks", "file_id = ?", arrayOf(fileId.toString()))
            texts.forEachIndexed { i, t ->
                db.insertOrThrow("chunks", null, ContentValues().apply { put("file_id", fileId); put("idx", i); put("text", t) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    override fun allChunks(): List<LocalChunk> =
        readableDatabase.rawQuery("SELECT id,file_id,idx,text FROM chunks ORDER BY file_id, idx", null).use { c ->
            buildList { while (c.moveToNext()) add(LocalChunk(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3))) }
        }

    override fun addQa(question: String, answer: String, sufficient: Boolean, mode: String) {
        val v = ContentValues().apply {
            put("question", question); put("answer", answer); put("sufficient", if (sufficient) 1 else 0)
            put("mode", mode); put("at", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("qa", null, v)
    }

    override fun history(limit: Int): List<QaRecord> =
        readableDatabase.rawQuery("SELECT id,question,answer,sufficient,mode,at FROM qa ORDER BY at DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(QaRecord(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4), c.getLong(5))) }
        }

    private val noteCols = "file_id,user_caption,user_notes,user_tags,event_title,event_date,location_label,favorite,ocr_text,laptop_caption,laptop_summary,updated_at"

    private fun Cursor.toNote() = PhotoNote(
        fileId = getLong(0),
        user = PhotoUserFields(
            caption = getString(1), notes = getString(2), tags = getString(3).split('\n').filter { it.isNotEmpty() },
            eventTitle = getString(4), eventDate = getString(5), locationLabel = getString(6), favorite = getInt(7) == 1,
        ),
        derived = PhotoDerived(ocrText = getString(8), laptopCaption = getString(9), laptopSummary = getString(10)),
        updatedAt = getLong(11),
    )

    override fun photoNote(fileId: Long): PhotoNote? =
        readableDatabase.rawQuery("SELECT $noteCols FROM photo_notes WHERE file_id = ?", arrayOf(fileId.toString())).use { c ->
            if (c.moveToFirst()) c.toNote() else null
        }

    override fun allPhotoNotes(): List<PhotoNote> =
        readableDatabase.rawQuery("SELECT $noteCols FROM photo_notes", null).use { c -> buildList { while (c.moveToNext()) add(c.toNote()) } }

    private fun ensureNote(fileId: Long) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO photo_notes(file_id) VALUES (?)", arrayOf(fileId))
    }

    override fun saveUserFields(fileId: Long, user: PhotoUserFields, now: Long) {
        ensureNote(fileId)
        val v = ContentValues().apply {
            put("user_caption", user.caption); put("user_notes", user.notes); put("user_tags", user.tags.joinToString("\n"))
            put("event_title", user.eventTitle); put("event_date", user.eventDate); put("location_label", user.locationLabel)
            put("favorite", if (user.favorite) 1 else 0); put("updated_at", now)
        }
        writableDatabase.update("photo_notes", v, "file_id = ?", arrayOf(fileId.toString()))
        bump()
    }

    override fun saveDerived(fileId: Long, ocrText: String?, laptopCaption: String?, laptopSummary: String?) {
        ensureNote(fileId)
        val v = ContentValues().apply {
            ocrText?.let { put("ocr_text", it) }
            laptopCaption?.let { put("laptop_caption", it) }
            laptopSummary?.let { put("laptop_summary", it) }
        }
        if (v.size() > 0) writableDatabase.update("photo_notes", v, "file_id = ?", arrayOf(fileId.toString()))
        bump()
    }

    companion object { const val VERSION = 2 }
}
