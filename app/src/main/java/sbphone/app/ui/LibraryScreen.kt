package sbphone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import sbphone.app.data.Folder
import sbphone.fold.Ref

private val tabs = listOf("Favorites", "Highlights", "Notes", "Journal", "Tags", "History")

@Composable
fun LibraryScreen(state: AppState) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, modifier = Modifier.weight(1f)) {
                tabs.forEachIndexed { i, l -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l, maxLines = 1) }) }
            }
            SpeakButton(state, "library:$tab", "Library · ${tabs[tab]}") { librarySpeech(state, tab) }
        }
        when (tab) {
            0 -> FavoritesTab(state)
            1 -> HighlightsTab(state)
            2 -> NotesTab(state)
            3 -> JournalTab(state)
            4 -> TagsTab(state)
            else -> HistoryTab(state)
        }
    }
}

private val fmt: DateFormat get() = DateFormat.getDateInstance(DateFormat.MEDIUM)

@Composable
private fun VerseRow(state: AppState, key: Int, title: String = Ref.fromKey(key).label(), detail: String = "", preview: String, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
            }
            Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        trailing()
    }
    HorizontalDivider()
}

// ---- Favorites: folders, each opening to its verses and chapters ----

@Composable
private fun FavoritesTab(state: AppState) {
    val open = remember { mutableStateMapOf<Long, Boolean>() }
    var renaming by remember { mutableStateOf<Folder?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    val data = loadOnIo(state.userRevision) {
        state.user.folders().map { f ->
            f to state.user.favorites(f.id).map { fav ->
                val ref = Ref.fromKey(fav.key)
                val shown = if (fav.wholeChapter) Ref(ref.book, ref.chapter) else if (fav.keyEnd > fav.key) ref.copy(verseEnd = fav.keyEnd % 1000) else ref
                Triple(fav, shown, if (fav.wholeChapter) "" else state.bible.previewText(shown, state.version))
            }
        }
    }
    renaming?.let { f -> FolderNameDialog(f.name, onDelete = if (f.id != 1L) ({ state.user.deleteFolder(f.id); state.touched(); renaming = null }) else null,
        onSave = { state.user.renameFolder(f.id, it); state.touched(); renaming = null }, onDismiss = { renaming = null }) }
    if (newFolder) FolderNameDialog("", onDelete = null, onSave = { state.user.addFolder(it); state.touched(); newFolder = false }, onDismiss = { newFolder = false })
    if (data == null) { Loading(); return }
    LazyColumn(Modifier.fillMaxSize()) {
        for ((folder, items) in data) {
            val expanded = open[folder.id] ?: (folder.id == 1L)
            item(key = "f${folder.id}") {
                Row(Modifier.fillMaxWidth().clickable { open[folder.id] = !expanded }.padding(start = 20.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (expanded) "▾  " else "▸  ") + folder.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${folder.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { renaming = folder }) { Icon(SbIcons.MoreVert, contentDescription = "Rename or delete folder") }
                }
            }
            if (expanded) {
                if (items.isEmpty()) item(key = "e${folder.id}") {
                    Text(if (folder.id == 1L) "Tap ☆ on a verse to add it here." else "Empty. Add verses with ⋮ → Add to a folder.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp, bottom = 8.dp))
                }
                items(items, key = { "v${it.first.id}" }) { (fav, ref, preview) ->
                    VerseRow(state, fav.key, title = "★  " + ref.label(), preview = if (fav.wholeChapter) "Whole chapter" else preview, onClick = { state.goTo(ref) }) {
                        IconButton(onClick = { state.user.removeFavorite(fav.id); state.touched() }) { Icon(SbIcons.Close, contentDescription = "Remove from folder") }
                    }
                }
            }
        }
        item { TextButton(onClick = { newFolder = true }, modifier = Modifier.padding(12.dp)) { Text("+ New folder") } }
    }
}

@Composable
private fun FolderNameDialog(initial: String, onDelete: (() -> Unit)?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirm) "Delete this folder and its favorites?" else if (initial.isEmpty()) "New folder" else "Folder") },
        text = { if (!confirm) OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) else Text("The verses themselves are not touched.") },
        confirmButton = {
            if (confirm) TextButton(onClick = { onDelete?.invoke() }) { Text("Delete") }
            else TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null && !confirm) TextButton(onClick = { confirm = true }) { Text("Delete…") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ---- Highlights, with their reasons; filter by colour ----

@Composable
private fun HighlightsTab(state: AppState) {
    var color by remember { mutableStateOf<Int?>(null) }
    val items = loadOnIo(state.userRevision) { state.user.highlights().map { it to state.bible.previewText(Ref.fromKey(it.key), state.version) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp)) {
            FilterChip(selected = color == null, onClick = { color = null }, label = { Text("All") }, modifier = Modifier.padding(end = 6.dp))
            highlightNames.forEachIndexed { i, n -> FilterChip(selected = color == i, onClick = { color = i }, label = { Text(n) }, modifier = Modifier.padding(end = 6.dp)) }
        }
        val shown = items?.filter { color == null || it.first.color == color }
        when {
            shown == null -> Loading()
            shown.isEmpty() -> Empty("No highlights${color?.let { " in " + highlightNames[it].lowercase() } ?: ""} yet. Tap a verse, then a colour.")
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { (h, preview) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(start = 12.dp).size(12.dp).background(highlightColor(h.color, isDark(state.theme)).copy(alpha = 1f), CircleShape))
                        Box(Modifier.weight(1f)) {
                            VerseRow(state, h.key, detail = h.reason.ifBlank { highlightNames[h.color] }, preview = preview, onClick = { state.goTo(Ref.fromKey(h.key)) })
                        }
                    }
                }
            }
        }
    }
}

// ---- Notes, with deleted notes restorable ----

@Composable
private fun NotesTab(state: AppState) {
    var showDeleted by remember { mutableStateOf(false) }
    val data = loadOnIo(state.userRevision, showDeleted) {
        val list = if (showDeleted) state.user.deletedNotes() else state.user.notes()
        list.map { it to state.bible.previewText(Ref.fromKey(it.key), state.version) } to state.user.deletedNotes().size
    }
    if (data == null) { Loading(); return }
    val (items, deletedCount) = data
    LazyColumn(Modifier.fillMaxSize()) {
        if (deletedCount > 0 || showDeleted) item {
            TextButton(onClick = { showDeleted = !showDeleted }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if (showDeleted) "← Back to notes" else "Deleted notes ($deletedCount)")
            }
        }
        if (items.isEmpty()) item { Empty(if (showDeleted) "No deleted notes." else "No notes yet. Tap a verse, then the pencil.") }
        items(items) { (note, preview) ->
            Column(Modifier.fillMaxWidth().clickable { if (!showDeleted) state.open(Overlay.NoteEdit(note.key)) }.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Ref.fromKey(note.key).label(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    if (showDeleted) TextButton(onClick = { state.user.restoreNote(note.id); state.touched() }) { Text("Restore") }
                    else Text(fmt.format(Date(note.updated)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(note.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            HorizontalDivider()
        }
    }
}

// ---- Journal: notes on a subject ----

@Composable
private fun JournalTab(state: AppState) {
    var showDeleted by remember { mutableStateOf(false) }
    val data = loadOnIo(state.userRevision, showDeleted) { state.user.journal(showDeleted) to state.user.journal(deleted = true).size }
    if (data == null) { Loading(); return }
    val (entries, deletedCount) = data
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(horizontal = 8.dp)) {
                if (!showDeleted) TextButton(onClick = { state.open(Overlay.Journal(state.user.newJournalEntry())) }) { Text("+ New entry") }
                if (deletedCount > 0 || showDeleted) TextButton(onClick = { showDeleted = !showDeleted }) { Text(if (showDeleted) "← Back to journal" else "Deleted ($deletedCount)") }
            }
        }
        if (entries.isEmpty()) item { Empty(if (showDeleted) "Nothing deleted." else "A journal is for notes on a subject rather than one verse. References you write in it become links.") }
        items(entries) { e ->
            Column(Modifier.fillMaxWidth().clickable { if (!showDeleted) state.open(Overlay.Journal(e.id)) }.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (showDeleted) TextButton(onClick = { state.user.setJournalDeleted(e.id, false); state.touched() }) { Text("Restore") }
                    else Text(fmt.format(Date(e.updated)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(e.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            HorizontalDivider()
        }
    }
}

// ---- Tags ----

@Composable
private fun TagsTab(state: AppState) {
    val tags = loadOnIo(state.userRevision) { state.user.tags() }
    when {
        tags == null -> Loading()
        tags.isEmpty() -> Empty("No tags yet. Tap a verse, then ⋮ → Tag.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(tags) { t ->
                Row(Modifier.fillMaxWidth().clickable { state.open(Overlay.TagVerses(t.id, t.name)) }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text("# " + t.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${t.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

// ---- History ----

@Composable
private fun HistoryTab(state: AppState) {
    val items = loadOnIo(state.userRevision) { state.user.history().map { it to state.bible.previewText(Ref.fromKey(it.key), state.version) } }
    when {
        items == null -> Loading()
        items.isEmpty() -> Empty("Verses you open appear here.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(items) { (v, preview) -> VerseRow(state, v.key, detail = fmt.format(Date(v.at)) + "  ·  " + v.version, preview = preview, onClick = { state.goTo(Ref.fromKey(v.key)) }) }
        }
    }
}

/** Edits the note on one verse. It saves when you leave; every saved text is kept, and a deleted note can be restored. */
@Composable
fun NoteEditScreen(state: AppState, key: Int) {
    val initial = remember(key) { state.user.noteFor(key)?.text.orEmpty() }
    var text by remember(key) { mutableStateOf(initial) }
    val current by rememberUpdatedState(text)
    var deleted by remember(key) { mutableStateOf(false) }
    DisposableEffect(key) {
        onDispose { if (!deleted) state.user.saveNote(key, current); state.touched() }
    }
    val ref = Ref.fromKey(key)
    OverlayScaffold(title = "Note · ${ref.shortLabel()}", onBack = { state.back() }, actions = {
        SpeakButton(state, "note:$key", "Note · ${ref.shortLabel()}") {
            listOf(verseSpeech(state, key, state.version), sbphone.app.speech.Speech("Your note. $current", label = "Your note"))
        }
        TextButton(onClick = { state.goTo(ref) }) { Text("Open verse") }
        if (initial.isNotEmpty()) TextButton(onClick = { state.user.saveNote(key, ""); deleted = true; state.back() }) { Text("Delete") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            val preview = loadOnIo(key, state.version) { state.bible.previewText(ref, state.version) }
            Text(preview.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Your note") }, modifier = Modifier.fillMaxWidth().weight(1f))
            Text("Saved when you leave this page. Earlier versions are kept. A deleted note can be restored from Library → Notes.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** A Library tab read aloud, in the order it is listed. */
private fun librarySpeech(state: AppState, tab: Int): List<sbphone.app.speech.Speech> {
    val u = state.user
    val S = { text: String, label: String -> sbphone.app.speech.Speech(text, label = label) }
    return when (tab) {
        0 -> u.folders().flatMap { f ->
            listOf(S("Folder: ${f.name}, ${f.count}.", f.name)) + u.favorites(f.id).map { fav ->
                val r = Ref.fromKey(fav.key)
                if (fav.wholeChapter) S(Ref(r.book, r.chapter).spoken() + ", the whole chapter.", Ref(r.book, r.chapter).label())
                else verseSpeech(state, fav.key, state.version)
            }
        }
        1 -> u.highlights().map { h ->
            verseSpeech(state, h.key, state.version, spokenRef(h.key) + ", " + h.reason.ifBlank { highlightNames[h.color] } + ". ")
        }
        2 -> u.notes().map { n -> S(spokenRef(n.key) + ". " + n.text, Ref.fromKey(n.key).label()) }
        3 -> u.journal().map { e -> S(e.title.ifBlank { "Untitled" } + ". " + e.body, e.title.ifBlank { "(untitled)" }) }
        4 -> u.tags().map { t -> S("${t.name}, ${t.count}.", t.name) }
        else -> u.history().map { v -> verseSpeech(state, v.key, v.version) }
    }
}
