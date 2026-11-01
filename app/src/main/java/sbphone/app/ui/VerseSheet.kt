package sbphone.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import sbphone.app.data.Word
import sbphone.fold.Ref

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerseSheet(state: AppState, key: Int, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val ref = Ref.fromKey(key)
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).navigationBarsPadding()) {
            Text(ref.label(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            VerseActions(state, key, onNote = { onDismiss(); state.open(Overlay.NoteEdit(key)) })
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Original") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Cross-refs") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Compare") })
            }
            when (tab) {
                0 -> OriginalWords(state, key)
                1 -> CrossRefs(state, key, onGo = { onDismiss(); state.goTo(it) })
                else -> Compare(state, key)
            }
        }
    }
}

@Composable
private fun VerseActions(state: AppState, key: Int, onNote: () -> Unit) {
    val context = LocalContext.current
    val marks = loadOnIo(state.userRevision, key) { state.user.highlightMap()[key] to state.user.bookmarkedKeys().contains(key) }
    val dark = isDark(state.theme)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in highlightNames.indices) {
            val chosen = marks?.first == i
            Box(
                Modifier.padding(4.dp).size(28.dp).background(highlightColor(i, dark).copy(alpha = 0.9f), CircleShape)
                    .border(if (chosen) 2.dp else 0.dp, if (chosen) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                    .clickable { state.user.setHighlight(key, if (chosen) null else i); state.touched() },
            )
        }
        Box(Modifier.weight(1f))
        IconButton(onClick = {
            val added = state.user.toggleBookmark(key, state.version); state.touched()
            Toast.makeText(context, if (added) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
        }) {
            Icon(if (marks?.second == true) SbIcons.Bookmark else SbIcons.BookmarkOutline, contentDescription = "Bookmark")
        }
        IconButton(onClick = onNote) { Icon(SbIcons.Note, contentDescription = "Note") }
        IconButton(onClick = { copyVerse(context, state, key, share = false) }) { Icon(SbIcons.Copy, contentDescription = "Copy") }
        IconButton(onClick = { copyVerse(context, state, key, share = true) }) { Icon(SbIcons.Share, contentDescription = "Share") }
    }
}

/** Copies or shares a verse with its reference, version and the version's credit line. */
private fun copyVerse(context: Context, state: AppState, key: Int, share: Boolean) {
    val verses = state.bible.versesAt(key, state.version)
    val text = verses.joinToString(" ") { it.text }
    val credit = state.bible.source(state.version)?.credit.orEmpty()
    val body = "“$text” — ${Ref.fromKey(key).label()} (${state.version})\n$credit"
    if (share) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "Share verse"))
    } else {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(Ref.fromKey(key).label(), body))
        Toast.makeText(context, "Copied with its credit", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OriginalWords(state: AppState, key: Int) {
    val data = loadOnIo(key) {
        val kjv = state.bible.versesAt(key, "KJV").joinToString(" ") { it.text }
        OriginalData(state.bible.words(key), kjv, state.bible.kjvLinked(key), state.bible.shiftedIn(key))
    }
    if (data == null) { Loading(); return }
    val words = data.words
    var pick by remember { mutableStateOf<WordPick?>(null) }
    pick?.let { WordPickDialog(state, it) { pick = null } }
    val hebrew = Ref.fromKey(key).book <= 39
    val credit = state.bible.source(if (hebrew) "macula_hebrew" else "macula_greek")
    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (data.kjv.isNotBlank()) {
            Text("KJV  ·  tap a word", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 8.dp))
            Text(
                kjvTappable(key, data.kjv, data.linked, data.shifted, MaterialTheme.colorScheme.onBackground, MaterialTheme.colorScheme.primary) { pick = it },
                style = readingStyle(state.bible.version("KJV"), baseSize(state.textScale)).copy(color = MaterialTheme.colorScheme.onBackground),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
            )
            if (data.shifted.isNotEmpty()) Text("† Wright (1884) lists this word as changed in meaning since the KJV.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        if (words.isEmpty()) { Text("No Hebrew or Greek word data for this verse.", modifier = Modifier.padding(8.dp)); return@Column }
        val own = words.map { "${it.chapter}:${it.verse}" }.distinct()
        val ownDiffers = own.size > 1 || own.first() != "${Ref.fromKey(key).chapter}:${Ref.fromKey(key).verse}"
        if (ownDiffers) {
            Text("${if (hebrew) "Hebrew" else "Greek"} numbering: ${own.joinToString(", ")}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
        }
        CompositionLocalProvider(LocalLayoutDirection provides if (hebrew) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (w in words) WordChip(w, hebrew) { state.open(Overlay.Word(w.lemma)) }
            }
        }
        Text("Tap a word for its dictionary entries and every place it occurs.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, start = 8.dp))
        if (credit != null) SourceLine(credit.credit)
    }
}

private data class OriginalData(val words: List<Word>, val kjv: String, val linked: Set<Pair<String, Int>>, val shifted: Set<String>)

@Composable
private fun WordChip(w: Word, hebrew: Boolean, onClick: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(w.surface, style = if (hebrew) MaterialTheme.typography.headlineSmall.copy(fontFamily = HebrewFont) else MaterialTheme.typography.titleMedium)
            Text(w.translit, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(w.gloss, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(w.morph, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CrossRefs(state: AppState, key: Int, onGo: (Ref) -> Unit) {
    val refs = loadOnIo(key, state.version) {
        state.bible.xrefs(key).map { x -> Triple(x, x.ref(), state.bible.previewText(x.ref(), state.version)) }
    }
    if (refs == null) { Loading(); return }
    if (refs.isEmpty()) { Empty("No cross-references for this verse."); return }
    LazyColumn {
        items(refs) { (x, ref, preview) ->
            Column(Modifier.fillMaxWidth().clickable { onGo(ref) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Row {
                    Text(ref.label(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Text("${x.votes} votes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            }
            HorizontalDivider()
        }
        item { state.bible.source("cross_references")?.let { SourceLine(it.credit) } }
    }
}

@Composable
private fun Compare(state: AppState, key: Int) {
    val verses = loadOnIo(key) { state.bible.versesAt(key) }
    if (verses == null) { Loading(); return }
    val base = baseSize(state.textScale)
    LazyColumn {
        items(verses.groupBy { it.version }.toList()) { (code, vs) ->
            val v = state.bible.version(code)
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(code + "  ·  " + v.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                val own = if (vs.any { it.differsFromEnglish }) "[" + vs.joinToString(", ") { it.ownLabel } + "] " else ""
                Text(own + vs.joinToString(" ") { it.text.ifEmpty { "(omitted in this edition)" } }, style = readingStyle(v, base * 0.9f).copy(color = MaterialTheme.colorScheme.onBackground))
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun SourceLine(text: String) {
    Text("Source: $text", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}
