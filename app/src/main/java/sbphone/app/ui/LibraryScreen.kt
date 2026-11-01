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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import sbphone.fold.Ref

private data class Item(val key: Int, val detail: String, val color: Int? = null)

@Composable
fun LibraryScreen(state: AppState) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val labels = listOf("Bookmarks", "Highlights", "Notes", "History")
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            labels.forEachIndexed { i, l -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l, maxLines = 1) }) }
        }
        val items = loadOnIo(tab, state.userRevision) {
            val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
            when (tab) {
                0 -> state.user.bookmarks().map { Item(it.key, fmt.format(Date(it.created))) }
                1 -> state.user.highlights().map { Item(it.key, highlightNames.getOrElse(it.color) { "" }, it.color) }
                2 -> state.user.notes().map { Item(it.key, it.text) }
                else -> state.user.history().map { Item(it.key, fmt.format(Date(it.at)) + "  ·  " + it.version) }
            }.map { it to state.bible.previewText(Ref.fromKey(it.key), state.version) }
        }
        when {
            items == null -> Loading()
            items.isEmpty() -> Empty(when (tab) {
                0 -> "No bookmarks yet. Tap a verse, then the bookmark."
                1 -> "No highlights yet. Tap a verse, then a colour."
                2 -> "No notes yet. Tap a verse, then the pencil."
                else -> "Verses you open appear here."
            })
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(items) { (item, preview) ->
                    Column(Modifier.fillMaxWidth().clickable { if (tab == 2) state.open(Overlay.NoteEdit(item.key)) else state.goTo(Ref.fromKey(item.key)) }
                        .padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            item.color?.let { Box(Modifier.size(12.dp).background(highlightColor(it, isDark(state.theme)).copy(alpha = 1f), CircleShape)); Text("  ") }
                            Text(Ref.fromKey(item.key).label(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            if (tab != 2) Text(item.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (tab == 2) Text(item.detail, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                        Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Edits the note on one verse. It saves when you leave; every saved text is kept. */
@Composable
fun NoteEditScreen(state: AppState, key: Int) {
    val initial = remember(key) { state.user.noteFor(key)?.text.orEmpty() }
    var text by remember(key) { mutableStateOf(initial) }
    val current by rememberUpdatedState(text)
    DisposableEffect(key) {
        onDispose { state.user.saveNote(key, current); state.touched() }
    }
    val ref = Ref.fromKey(key)
    OverlayScaffold(title = "Note · ${ref.shortLabel()}", onBack = { state.back() }, actions = {
        TextButton(onClick = { state.goTo(ref) }) { Text("Open verse") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            val preview = loadOnIo(key, state.version) { state.bible.previewText(ref, state.version) }
            Text(preview.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Your note") }, modifier = Modifier.fillMaxWidth().weight(1f))
            Text("Saved when you leave this page. Earlier versions are kept. Clear the text to delete the note.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
