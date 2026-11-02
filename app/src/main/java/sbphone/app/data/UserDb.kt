package sbphone.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class Folder(val id: Long, val name: String, val count: Int)
data class Favorite(val id: Long, val folder: Long, val key: Int, val keyEnd: Int, val wholeChapter: Boolean, val version: String, val created: Long)
data class Highlight(val key: Int, val color: Int, val reason: String, val created: Long)
data class Note(val id: Long, val key: Int, val text: String, val created: Long, val updated: Long)
data class Visit(val key: Int, val version: String, val at: Long)
data class JournalEntry(val id: Long, val title: String, val body: String, val created: Long, val updated: Long)
data class Tag(val id: Long, val name: String, val count: Int)
data class Card(val id: Long, val key: Int, val keyEnd: Int, val due: Long, val intervalDays: Int, val reps: Int, val lapses: Int)
data class Prayer(val id: Long, val title: String, val details: String, val key: Int, val created: Long, val answered: Long, val answer: String)

/**
 * The user's own data, on this phone only: favorites in folders, highlights with reasons,
 * notes (every saved version kept, deletions restorable), a journal, tags, memory cards,
 * a prayer list, reading history and settings. Verse keys use English numbering.
 */
class UserDb(context: Context) : SQLiteOpenHelper(context, "user.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        createV1(db)
        upgradeTo2(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upgradeTo2(db)
    }

    private fun createV1(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmark(key INTEGER PRIMARY KEY, version TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE highlight(key INTEGER PRIMARY KEY, color INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE note(id INTEGER PRIMARY KEY AUTOINCREMENT, key INTEGER NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX note_key ON note(key)")
        db.execSQL("CREATE TABLE note_version(note_id INTEGER NOT NULL, text TEXT NOT NULL, saved INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history(key INTEGER NOT NULL, version TEXT NOT NULL, at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_at ON history(at)")
        db.execSQL("CREATE TABLE setting(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    /** Version 2: folders and favorites (bookmarks move into "Favorites"), highlight reasons, journal, tags, cards, prayers. */
    private fun upgradeTo2(db: SQLiteDatabase) {
        val t = now()
        db.execSQL("CREATE TABLE folder(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ord INTEGER NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("INSERT INTO folder(id, name, ord, created) VALUES (1, 'Favorites', 0, $t)")
        db.execSQL("CREATE TABLE favorite(id INTEGER PRIMARY KEY AUTOINCREMENT, folder INTEGER NOT NULL, key INTEGER NOT NULL, key_end INTEGER NOT NULL DEFAULT 0, whole_chapter INTEGER NOT NULL DEFAULT 0, version TEXT NOT NULL, created INTEGER NOT NULL, UNIQUE(folder, key, key_end, whole_chapter))")
        db.execSQL("CREATE INDEX favorite_key ON favorite(key)")
        db.execSQL("INSERT INTO favorite(folder, key, version, created) SELECT 1, key, version, created FROM bookmark")
        db.execSQL("DROP TABLE bookmark")
        db.execSQL("ALTER TABLE highlight ADD COLUMN reason TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE journal(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE journal_version(journal_id INTEGER NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, saved INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tag(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE verse_tag(key INTEGER NOT NULL, tag INTEGER NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(key, tag))")
        db.execSQL("CREATE TABLE card(id INTEGER PRIMARY KEY AUTOINCREMENT, key INTEGER NOT NULL, key_end INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, due INTEGER NOT NULL, interval_days INTEGER NOT NULL DEFAULT 0, reps INTEGER NOT NULL DEFAULT 0, lapses INTEGER NOT NULL DEFAULT 0, UNIQUE(key, key_end))")
        db.execSQL("CREATE TABLE prayer(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, details TEXT NOT NULL, key INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, answered INTEGER NOT NULL DEFAULT 0, answer TEXT NOT NULL DEFAULT '', deleted INTEGER NOT NULL DEFAULT 0)")
    }

    private fun now() = System.currentTimeMillis()

    private fun <T> list(sql: String, args: Array<String> = emptyArray(), row: (Cursor) -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    // ---- settings ----

    fun setting(name: String): String? = readableDatabase.rawQuery("SELECT value FROM setting WHERE name = ?", arrayOf(name)).use { if (it.moveToNext()) it.getString(0) else null }

    fun setSetting(name: String, value: String) {
        writableDatabase.insertWithOnConflict("setting", null, ContentValues().apply { put("name", name); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- favorites in folders ----

    fun folders(): List<Folder> = list("SELECT f.id, f.name, (SELECT COUNT(*) FROM favorite v WHERE v.folder = f.id) FROM folder f ORDER BY f.ord, f.id") {
        Folder(it.getLong(0), it.getString(1), it.getInt(2))
    }

    fun favorites(folder: Long): List<Favorite> = list("SELECT id, folder, key, key_end, whole_chapter, version, created FROM favorite WHERE folder = ? ORDER BY key", arrayOf(folder.toString())) { fav(it) }

    fun favoritesOf(key: Int): List<Favorite> = list("SELECT id, folder, key, key_end, whole_chapter, version, created FROM favorite WHERE key = ?", arrayOf(key.toString())) { fav(it) }

    private fun fav(c: Cursor) = Favorite(c.getLong(0), c.getLong(1), c.getInt(2), c.getInt(3), c.getInt(4) == 1, c.getString(5), c.getLong(6))

    /** Verse keys with a star: every verse favorite (ranges count for each verse in them). */
    fun starredKeys(): Set<Int> = buildSet {
        for ((k, e) in list("SELECT key, key_end FROM favorite WHERE whole_chapter = 0") { it.getInt(0) to it.getInt(1) }) {
            if (e > k) addAll(k..e) else add(k)
        }
    }

    /** Chapter keys (verse 0) that are favorites as a whole chapter. */
    fun starredChapters(): Set<Int> = list("SELECT key FROM favorite WHERE whole_chapter = 1") { it.getInt(0) }.toSet()

    fun addFavorite(folder: Long, key: Int, version: String, keyEnd: Int = 0, wholeChapter: Boolean = false) {
        writableDatabase.insertWithOnConflict("favorite", null, ContentValues().apply {
            put("folder", folder); put("key", key); put("key_end", keyEnd); put("whole_chapter", if (wholeChapter) 1 else 0); put("version", version); put("created", now())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeFavorite(id: Long) { writableDatabase.delete("favorite", "id = ?", arrayOf(id.toString())) }

    /** The star in the verse sheet: in "Favorites" (folder 1) or out of every folder. Returns true when now starred. */
    fun toggleStar(key: Int, version: String): Boolean {
        val existing = favoritesOf(key).filter { !it.wholeChapter }
        return if (existing.isNotEmpty()) {
            existing.forEach { removeFavorite(it.id) }; false
        } else {
            addFavorite(1, key, version); true
        }
    }

    fun addFolder(name: String): Long {
        val ord = readableDatabase.rawQuery("SELECT COALESCE(MAX(ord), 0) + 1 FROM folder", null).use { it.moveToNext(); it.getInt(0) }
        return writableDatabase.insert("folder", null, ContentValues().apply { put("name", name.trim()); put("ord", ord); put("created", now()) })
    }

    fun renameFolder(id: Long, name: String) {
        writableDatabase.update("folder", ContentValues().apply { put("name", name.trim()) }, "id = ?", arrayOf(id.toString()))
    }

    /** Deletes a folder and its favorites. The first folder ("Favorites") stays. */
    fun deleteFolder(id: Long) {
        if (id == 1L) return
        writableDatabase.delete("favorite", "folder = ?", arrayOf(id.toString()))
        writableDatabase.delete("folder", "id = ?", arrayOf(id.toString()))
    }

    // ---- highlights ----

    fun highlights(): List<Highlight> = list("SELECT key, color, reason, created FROM highlight ORDER BY created DESC") { Highlight(it.getInt(0), it.getInt(1), it.getString(2), it.getLong(3)) }

    fun highlightMap(): Map<Int, Int> = list("SELECT key, color FROM highlight") { it.getInt(0) to it.getInt(1) }.toMap()

    fun highlight(key: Int): Highlight? = list("SELECT key, color, reason, created FROM highlight WHERE key = ?", arrayOf(key.toString())) { Highlight(it.getInt(0), it.getInt(1), it.getString(2), it.getLong(3)) }.firstOrNull()

    /** Sets a highlight colour (0..3), keeping any reason, or clears it when [color] is null. */
    fun setHighlight(key: Int, color: Int?) {
        val db = writableDatabase
        if (color == null) { db.delete("highlight", "key = ?", arrayOf(key.toString())); return }
        val updated = db.update("highlight", ContentValues().apply { put("color", color) }, "key = ?", arrayOf(key.toString()))
        if (updated == 0) db.insert("highlight", null, ContentValues().apply { put("key", key); put("color", color); put("reason", ""); put("created", now()) })
    }

    fun setHighlightReason(key: Int, reason: String) {
        writableDatabase.update("highlight", ContentValues().apply { put("reason", reason.trim()) }, "key = ?", arrayOf(key.toString()))
    }

    // ---- notes ----

    fun notes(): List<Note> = list("SELECT id, key, text, created, updated FROM note WHERE deleted = 0 ORDER BY updated DESC") { Note(it.getLong(0), it.getInt(1), it.getString(2), it.getLong(3), it.getLong(4)) }

    /** Deleted notes, each with the last text it had before deletion. */
    fun deletedNotes(): List<Note> = list(
        """SELECT n.id, n.key, COALESCE((SELECT text FROM note_version v WHERE v.note_id = n.id AND v.text <> '' ORDER BY saved DESC LIMIT 1), n.text), n.created, n.updated
           FROM note n WHERE n.deleted = 1 ORDER BY n.updated DESC""",
    ) { Note(it.getLong(0), it.getInt(1), it.getString(2), it.getLong(3), it.getLong(4)) }

    fun noteFor(key: Int): Note? = list("SELECT id, key, text, created, updated FROM note WHERE key = ? AND deleted = 0 ORDER BY updated DESC LIMIT 1", arrayOf(key.toString())) {
        Note(it.getLong(0), it.getInt(1), it.getString(2), it.getLong(3), it.getLong(4))
    }.firstOrNull()

    fun notedKeys(): Set<Int> = list("SELECT DISTINCT key FROM note WHERE deleted = 0") { it.getInt(0) }.toSet()

    /** Saves a note's text; every saved text is also kept in note_version. An empty text deletes it (restorably). */
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

    /** Brings a deleted note back with its last non-empty text. If the verse has a newer note, the restored text is appended to it. */
    fun restoreNote(id: Long) {
        val note = deletedNotes().firstOrNull { it.id == id } ?: return
        val current = noteFor(note.key)
        if (current != null) {
            saveNote(note.key, current.text + "\n\n" + note.text)
            writableDatabase.delete("note", "id = ?", arrayOf(id.toString()))
        } else {
            val t = now()
            writableDatabase.update("note", ContentValues().apply { put("text", note.text); put("deleted", 0); put("updated", t) }, "id = ?", arrayOf(id.toString()))
            writableDatabase.insert("note_version", null, ContentValues().apply { put("note_id", id); put("text", note.text); put("saved", t) })
        }
    }

    // ---- journal ----

    fun journal(deleted: Boolean = false): List<JournalEntry> = list("SELECT id, title, body, created, updated FROM journal WHERE deleted = ? ORDER BY updated DESC", arrayOf(if (deleted) "1" else "0")) {
        JournalEntry(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4))
    }

    fun journalEntry(id: Long): JournalEntry? = list("SELECT id, title, body, created, updated FROM journal WHERE id = ?", arrayOf(id.toString())) {
        JournalEntry(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getLong(4))
    }.firstOrNull()

    fun newJournalEntry(): Long { val t = now(); return writableDatabase.insert("journal", null, ContentValues().apply { put("title", ""); put("body", ""); put("created", t); put("updated", t) }) }

    /** Saves an entry, keeping every saved version. An entry left completely empty is removed. */
    fun saveJournal(id: Long, title: String, body: String) {
        val old = journalEntry(id) ?: return
        if (title.isBlank() && body.isBlank() && old.title.isBlank() && old.body.isBlank()) { writableDatabase.delete("journal", "id = ?", arrayOf(id.toString())); return }
        if (old.title == title && old.body == body) return
        val t = now()
        writableDatabase.update("journal", ContentValues().apply { put("title", title); put("body", body); put("updated", t) }, "id = ?", arrayOf(id.toString()))
        writableDatabase.insert("journal_version", null, ContentValues().apply { put("journal_id", id); put("title", title); put("body", body); put("saved", t) })
    }

    fun setJournalDeleted(id: Long, deleted: Boolean) {
        writableDatabase.update("journal", ContentValues().apply { put("deleted", if (deleted) 1 else 0); put("updated", now()) }, "id = ?", arrayOf(id.toString()))
    }

    // ---- tags ----

    fun tags(): List<Tag> = list("SELECT t.id, t.name, (SELECT COUNT(*) FROM verse_tag v WHERE v.tag = t.id) FROM tag t ORDER BY t.name COLLATE NOCASE") { Tag(it.getLong(0), it.getString(1), it.getInt(2)) }

    fun tagsOf(key: Int): Set<Long> = list("SELECT tag FROM verse_tag WHERE key = ?", arrayOf(key.toString())) { it.getLong(0) }.toSet()

    fun taggedKeys(tag: Long): List<Int> = list("SELECT key FROM verse_tag WHERE tag = ? ORDER BY key", arrayOf(tag.toString())) { it.getInt(0) }

    /** Finds or creates a tag by name (case does not matter). */
    fun tagId(name: String): Long {
        val n = name.trim()
        list("SELECT id FROM tag WHERE name = ? COLLATE NOCASE", arrayOf(n)) { it.getLong(0) }.firstOrNull()?.let { return it }
        return writableDatabase.insert("tag", null, ContentValues().apply { put("name", n); put("created", now()) })
    }

    fun setTagged(key: Int, tag: Long, on: Boolean) {
        if (on) writableDatabase.insertWithOnConflict("verse_tag", null, ContentValues().apply { put("key", key); put("tag", tag); put("created", now()) }, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.delete("verse_tag", "key = ? AND tag = ?", arrayOf(key.toString(), tag.toString()))
    }

    fun deleteTag(tag: Long) {
        writableDatabase.delete("verse_tag", "tag = ?", arrayOf(tag.toString()))
        writableDatabase.delete("tag", "id = ?", arrayOf(tag.toString()))
    }

    // ---- memory cards ----

    fun cards(): List<Card> = list("SELECT id, key, key_end, due, interval_days, reps, lapses FROM card ORDER BY key") { card(it) }

    fun dueCards(): List<Card> = list("SELECT id, key, key_end, due, interval_days, reps, lapses FROM card WHERE due <= ? ORDER BY due, key", arrayOf(now().toString())) { card(it) }

    private fun card(c: Cursor) = Card(c.getLong(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getInt(4), c.getInt(5), c.getInt(6))

    fun hasCard(key: Int): Boolean = list("SELECT 1 FROM card WHERE key = ?", arrayOf(key.toString())) { 1 }.isNotEmpty()

    fun addCard(key: Int, keyEnd: Int = 0) {
        writableDatabase.insertWithOnConflict("card", null, ContentValues().apply { put("key", key); put("key_end", keyEnd); put("created", now()); put("due", now()) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeCard(id: Long) { writableDatabase.delete("card", "id = ?", arrayOf(id.toString())) }

    /** "Got it": the wait doubles (1, 2, 4, 8 … days, at most 180). */
    fun cardGotIt(card: Card) {
        val days = (if (card.intervalDays == 0) 1 else card.intervalDays * 2).coerceAtMost(180)
        writableDatabase.update("card", ContentValues().apply {
            put("interval_days", days); put("reps", card.reps + 1); put("due", now() + days * 86_400_000L)
        }, "id = ?", arrayOf(card.id.toString()))
    }

    /** "Again": back to the start; the card comes round again in this review. */
    fun cardAgain(card: Card) {
        writableDatabase.update("card", ContentValues().apply { put("interval_days", 0); put("lapses", card.lapses + 1); put("due", now()) }, "id = ?", arrayOf(card.id.toString()))
    }

    fun nextDue(): Long? = readableDatabase.rawQuery("SELECT MIN(due) FROM card", null).use { if (it.moveToNext() && !it.isNull(0)) it.getLong(0) else null }

    // ---- prayer list ----

    fun prayers(answered: Boolean): List<Prayer> = list(
        "SELECT id, title, details, key, created, answered, answer FROM prayer WHERE deleted = 0 AND ${if (answered) "answered > 0" else "answered = 0"} ORDER BY ${if (answered) "answered" else "created"} DESC",
    ) { Prayer(it.getLong(0), it.getString(1), it.getString(2), it.getInt(3), it.getLong(4), it.getLong(5), it.getString(6)) }

    fun savePrayer(id: Long?, title: String, details: String, key: Int) {
        val v = ContentValues().apply { put("title", title.trim()); put("details", details.trim()); put("key", key) }
        if (id == null) writableDatabase.insert("prayer", null, v.apply { put("created", now()) })
        else writableDatabase.update("prayer", v, "id = ?", arrayOf(id.toString()))
    }

    fun answerPrayer(id: Long, answer: String) {
        writableDatabase.update("prayer", ContentValues().apply { put("answered", now()); put("answer", answer.trim()) }, "id = ?", arrayOf(id.toString()))
    }

    fun reopenPrayer(id: Long) {
        writableDatabase.update("prayer", ContentValues().apply { put("answered", 0) }, "id = ?", arrayOf(id.toString()))
    }

    fun deletePrayer(id: Long) { writableDatabase.update("prayer", ContentValues().apply { put("deleted", 1) }, "id = ?", arrayOf(id.toString())) }

    // ---- history ----

    fun recordVisit(key: Int, version: String) {
        val db = writableDatabase
        val last = db.rawQuery("SELECT key FROM history ORDER BY at DESC LIMIT 1", null).use { if (it.moveToNext()) it.getInt(0) else null }
        if (last == key) return
        db.insert("history", null, ContentValues().apply { put("key", key); put("version", version); put("at", now()) })
        db.execSQL("DELETE FROM history WHERE rowid NOT IN (SELECT rowid FROM history ORDER BY at DESC LIMIT 500)")
    }

    fun history(): List<Visit> = list("SELECT key, version, at FROM history ORDER BY at DESC LIMIT 200") { Visit(it.getInt(0), it.getString(1), it.getLong(2)) }

    // ---- export / import ----

    fun exportJson(): String {
        val root = JSONObject()
        root.put("format", "sb_phone user data")
        root.put("formatVersion", 2)
        root.put("exported", now())
        root.put("verseKeys", "book*1000000 + chapter*1000 + verse, English (KJV) numbering")
        root.put("folders", JSONArray().apply {
            for (f in folders()) put(JSONObject().put("name", f.name).put("favorites", JSONArray().apply {
                for (v in favorites(f.id)) put(JSONObject().put("key", v.key).put("keyEnd", v.keyEnd).put("wholeChapter", v.wholeChapter).put("version", v.version).put("created", v.created))
            }))
        })
        root.put("highlights", JSONArray().apply { highlights().forEach { put(JSONObject().put("key", it.key).put("color", it.color).put("reason", it.reason).put("created", it.created)) } })
        val versions = list("SELECT note_id, text, saved FROM note_version ORDER BY saved") { Triple(it.getLong(0), it.getString(1), it.getLong(2)) }.groupBy { it.first }
        root.put("notes", JSONArray().apply {
            val all = notes().map { it to false } + deletedNotes().map { it to true }
            for ((n, deleted) in all) put(JSONObject().put("key", n.key).put("text", n.text).put("created", n.created).put("updated", n.updated).put("deleted", deleted)
                .put("versions", JSONArray().apply { versions[n.id].orEmpty().forEach { put(JSONObject().put("text", it.second).put("saved", it.third)) } }))
        })
        root.put("journal", JSONArray().apply {
            for ((e, deleted) in journal().map { it to false } + journal(deleted = true).map { it to true }) {
                put(JSONObject().put("title", e.title).put("body", e.body).put("created", e.created).put("updated", e.updated).put("deleted", deleted))
            }
        })
        root.put("tags", JSONArray().apply {
            for (t in tags()) put(JSONObject().put("name", t.name).put("keys", JSONArray(taggedKeys(t.id))))
        })
        root.put("cards", JSONArray().apply { cards().forEach { put(JSONObject().put("key", it.key).put("keyEnd", it.keyEnd).put("due", it.due).put("intervalDays", it.intervalDays).put("reps", it.reps).put("lapses", it.lapses)) } })
        root.put("prayers", JSONArray().apply {
            for (p in prayers(false) + prayers(true)) put(JSONObject().put("title", p.title).put("details", p.details).put("key", p.key).put("created", p.created).put("answered", p.answered).put("answer", p.answer))
        })
        root.put("history", JSONArray().apply { history().forEach { put(JSONObject().put("key", it.key).put("version", it.version).put("at", it.at)) } })
        return root.toString(2)
    }

    /** Merges an export (format 1 or 2) into this phone's data. Returns a short summary. */
    fun importJson(text: String): String {
        val root = JSONObject(text)
        require(root.optString("format") == "sb_phone user data") { "This file is not an SB Phone export." }
        val db = writableDatabase
        var favs = 0; var h = 0; var n = 0; var j = 0; var tg = 0; var cd = 0; var pr = 0
        db.beginTransaction()
        try {
            // Format 1 had bookmarks; they go into "Favorites".
            root.optJSONArray("bookmarks")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i); addFavorite(1, o.getInt("key"), o.getString("version")); favs++ } }
            root.optJSONArray("folders")?.let { a ->
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val name = o.getString("name")
                    val id = folders().firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: addFolder(name)
                    o.optJSONArray("favorites")?.let { fs -> for (k in 0 until fs.length()) { val f = fs.getJSONObject(k)
                        addFavorite(id, f.getInt("key"), f.optString("version", "KJV"), f.optInt("keyEnd"), f.optBoolean("wholeChapter")); favs++ } }
                }
            }
            root.optJSONArray("highlights")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    db.insertWithOnConflict("highlight", null, ContentValues().apply { put("key", o.getInt("key")); put("color", o.getInt("color")); put("reason", o.optString("reason")); put("created", o.getLong("created")) }, SQLiteDatabase.CONFLICT_REPLACE); h++ }
            }
            root.optJSONArray("notes")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    val id = db.insert("note", null, ContentValues().apply { put("key", o.getInt("key")); put("text", o.getString("text")); put("created", o.getLong("created")); put("updated", o.getLong("updated")); put("deleted", if (o.optBoolean("deleted")) 1 else 0) })
                    o.optJSONArray("versions")?.let { vs -> for (k in 0 until vs.length()) { val v = vs.getJSONObject(k)
                        db.insert("note_version", null, ContentValues().apply { put("note_id", id); put("text", v.getString("text")); put("saved", v.getLong("saved")) }) } }
                    n++ }
            }
            root.optJSONArray("journal")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    db.insert("journal", null, ContentValues().apply { put("title", o.getString("title")); put("body", o.getString("body")); put("created", o.getLong("created")); put("updated", o.getLong("updated")); put("deleted", if (o.optBoolean("deleted")) 1 else 0) }); j++ }
            }
            root.optJSONArray("tags")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    val id = tagId(o.getString("name")); tg++
                    o.optJSONArray("keys")?.let { ks -> for (k in 0 until ks.length()) setTagged(ks.getInt(k), id, true) } }
            }
            root.optJSONArray("cards")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    db.insertWithOnConflict("card", null, ContentValues().apply { put("key", o.getInt("key")); put("key_end", o.optInt("keyEnd")); put("created", now()); put("due", o.optLong("due", now()))
                        put("interval_days", o.optInt("intervalDays")); put("reps", o.optInt("reps")); put("lapses", o.optInt("lapses")) }, SQLiteDatabase.CONFLICT_IGNORE); cd++ }
            }
            root.optJSONArray("prayers")?.let { a ->
                for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    db.insert("prayer", null, ContentValues().apply { put("title", o.getString("title")); put("details", o.optString("details")); put("key", o.optInt("key")); put("created", o.optLong("created", now()))
                        put("answered", o.optLong("answered")); put("answer", o.optString("answer")) }); pr++ }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return "Imported $favs favorites, $h highlights, $n notes, $j journal entries, $tg tags, $cd memory cards and $pr prayers."
    }
}
