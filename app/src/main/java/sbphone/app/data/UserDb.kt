package sbphone.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val key: Int, val version: String, val created: Long)
data class Highlight(val key: Int, val color: Int, val created: Long)
data class Note(val id: Long, val key: Int, val text: String, val created: Long, val updated: Long)
data class Visit(val key: Int, val version: String, val at: Long)

/**
 * The user's own data, on this phone only: bookmarks, highlights, notes (every saved
 * version kept), reading history and settings. Verse keys use English numbering.
 */
class UserDb(context: Context) : SQLiteOpenHelper(context, "user.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmark(key INTEGER PRIMARY KEY, version TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE highlight(key INTEGER PRIMARY KEY, color INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE note(id INTEGER PRIMARY KEY AUTOINCREMENT, key INTEGER NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX note_key ON note(key)")
        db.execSQL("CREATE TABLE note_version(note_id INTEGER NOT NULL, text TEXT NOT NULL, saved INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history(key INTEGER NOT NULL, version TEXT NOT NULL, at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_at ON history(at)")
        db.execSQL("CREATE TABLE setting(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun now() = System.currentTimeMillis()

    // ---- settings ----

    fun setting(name: String): String? = readableDatabase.rawQuery("SELECT value FROM setting WHERE name = ?", arrayOf(name)).use { if (it.moveToNext()) it.getString(0) else null }

    fun setSetting(name: String, value: String) {
        writableDatabase.insertWithOnConflict("setting", null, ContentValues().apply { put("name", name); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- bookmarks ----

    fun bookmarks(): List<Bookmark> = readableDatabase.rawQuery("SELECT key, version, created FROM bookmark ORDER BY created DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Bookmark(c.getInt(0), c.getString(1), c.getLong(2))) }
    }

    fun bookmarkedKeys(): Set<Int> = readableDatabase.rawQuery("SELECT key FROM bookmark", null).use { c -> buildSet { while (c.moveToNext()) add(c.getInt(0)) } }

    fun toggleBookmark(key: Int, version: String): Boolean {
        val db = writableDatabase
        val removed = db.delete("bookmark", "key = ?", arrayOf(key.toString()))
        if (removed > 0) return false
        db.insert("bookmark", null, ContentValues().apply { put("key", key); put("version", version); put("created", now()) })
        return true
    }

    // ---- highlights ----

    fun highlights(): List<Highlight> = readableDatabase.rawQuery("SELECT key, color, created FROM highlight ORDER BY created DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Highlight(c.getInt(0), c.getInt(1), c.getLong(2))) }
    }

    fun highlightMap(): Map<Int, Int> = readableDatabase.rawQuery("SELECT key, color FROM highlight", null).use { c -> buildMap { while (c.moveToNext()) put(c.getInt(0), c.getInt(1)) } }

    /** Sets a highlight colour (0..3), or clears it when [color] is null. */
    fun setHighlight(key: Int, color: Int?) {
        val db = writableDatabase
        db.delete("highlight", "key = ?", arrayOf(key.toString()))
        if (color != null) db.insert("highlight", null, ContentValues().apply { put("key", key); put("color", color); put("created", now()) })
    }

    // ---- notes ----

    fun notes(): List<Note> = readableDatabase.rawQuery("SELECT id, key, text, created, updated FROM note WHERE deleted = 0 ORDER BY updated DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(Note(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3), c.getLong(4))) }
    }

    fun noteFor(key: Int): Note? = readableDatabase.rawQuery("SELECT id, key, text, created, updated FROM note WHERE key = ? AND deleted = 0 ORDER BY updated DESC LIMIT 1", arrayOf(key.toString())).use { c ->
        if (c.moveToNext()) Note(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3), c.getLong(4)) else null
    }

    fun notedKeys(): Set<Int> = readableDatabase.rawQuery("SELECT DISTINCT key FROM note WHERE deleted = 0", null).use { c -> buildSet { while (c.moveToNext()) add(c.getInt(0)) } }

    /** Saves a note's text; every saved text is also kept in note_version. An empty text deletes it (softly). */
    fun saveNote(key: Int, text: String) {
        val db = writableDatabase
        val existing = noteFor(key)
        val t = now()
        db.beginTransaction()
        try {
            val id = if (existing == null) {
                if (text.isBlank()) return
                db.insert("note", null, ContentValues().apply { put("key", key); put("text", text); put("created", t); put("updated", t) })
            } else {
                if (existing.text == text) return
                db.update("note", ContentValues().apply { put("text", text); put("updated", t); put("deleted", if (text.isBlank()) 1 else 0) }, "id = ?", arrayOf(existing.id.toString()))
                existing.id
            }
            db.insert("note_version", null, ContentValues().apply { put("note_id", id); put("text", text); put("saved", t) })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- history ----

    fun recordVisit(key: Int, version: String) {
        val db = writableDatabase
        val last = db.rawQuery("SELECT key FROM history ORDER BY at DESC LIMIT 1", null).use { if (it.moveToNext()) it.getInt(0) else null }
        if (last == key) return
        db.insert("history", null, ContentValues().apply { put("key", key); put("version", version); put("at", now()) })
        db.execSQL("DELETE FROM history WHERE rowid NOT IN (SELECT rowid FROM history ORDER BY at DESC LIMIT 500)")
    }

    fun history(): List<Visit> = readableDatabase.rawQuery("SELECT key, version, at FROM history ORDER BY at DESC LIMIT 200", null).use { c ->
        buildList { while (c.moveToNext()) add(Visit(c.getInt(0), c.getString(1), c.getLong(2))) }
    }

    // ---- export / import ----

    fun exportJson(): String {
        val root = JSONObject()
        root.put("format", "sb_phone user data")
        root.put("formatVersion", 1)
        root.put("exported", now())
        root.put("verseKeys", "book*1000000 + chapter*1000 + verse, English (KJV) numbering")
        root.put("bookmarks", JSONArray().apply { bookmarks().forEach { put(JSONObject().put("key", it.key).put("version", it.version).put("created", it.created)) } })
        root.put("highlights", JSONArray().apply { highlights().forEach { put(JSONObject().put("key", it.key).put("color", it.color).put("created", it.created)) } })
        val versions = readableDatabase.rawQuery("SELECT note_id, text, saved FROM note_version ORDER BY saved", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getLong(2))) }
        }.groupBy { it.first }
        root.put("notes", JSONArray().apply {
            notes().forEach { n ->
                put(JSONObject().put("key", n.key).put("text", n.text).put("created", n.created).put("updated", n.updated)
                    .put("versions", JSONArray().apply { versions[n.id].orEmpty().forEach { put(JSONObject().put("text", it.second).put("saved", it.third)) } }))
            }
        })
        root.put("history", JSONArray().apply { history().forEach { put(JSONObject().put("key", it.key).put("version", it.version).put("at", it.at)) } })
        return root.toString(2)
    }

    /** Merges an export into this phone's data. Returns a short summary. Nothing is overwritten except identical-verse highlights. */
    fun importJson(text: String): String {
        val root = JSONObject(text)
        require(root.optString("format") == "sb_phone user data") { "This file is not an SB Phone export." }
        val db = writableDatabase
        var b = 0; var h = 0; var n = 0
        db.beginTransaction()
        try {
            root.optJSONArray("bookmarks")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    if (db.insertWithOnConflict("bookmark", null, ContentValues().apply { put("key", o.getInt("key")); put("version", o.getString("version")); put("created", o.getLong("created")) }, SQLiteDatabase.CONFLICT_IGNORE) != -1L) b++ }
            }
            root.optJSONArray("highlights")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    db.insertWithOnConflict("highlight", null, ContentValues().apply { put("key", o.getInt("key")); put("color", o.getInt("color")); put("created", o.getLong("created")) }, SQLiteDatabase.CONFLICT_REPLACE); h++ }
            }
            root.optJSONArray("notes")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    val id = db.insert("note", null, ContentValues().apply { put("key", o.getInt("key")); put("text", o.getString("text")); put("created", o.getLong("created")); put("updated", o.getLong("updated")) })
                    o.optJSONArray("versions")?.let { vs -> for (j in 0 until vs.length()) { val v = vs.getJSONObject(j)
                        db.insert("note_version", null, ContentValues().apply { put("note_id", id); put("text", v.getString("text")); put("saved", v.getLong("saved")) }) } }
                    n++ }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return "Imported $b bookmarks, $h highlights and $n notes."
    }
}
