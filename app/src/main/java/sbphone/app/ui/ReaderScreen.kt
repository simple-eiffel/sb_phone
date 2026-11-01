package sbphone.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sbphone.app.data.Verse
import sbphone.fold.Ref

/** One English-numbered verse of a chapter, with the rows of each version shown. */
private data class VerseRow(val key: Int, val byVersion: List<Pair<String, List<Verse>>>)

@Composable
fun ReaderScreen(state: AppState) {
    val bible = state.bible
    var pick by remember { mutableStateOf<WordPick?>(null) }
    val pager = rememberPagerState(initialPage = state.page) { bible.chapters.size }

    LaunchedEffect(pager.settledPage) { if (pager.settledPage != state.page) state.turnTo(pager.settledPage) }
    LaunchedEffect(state.page) { if (pager.currentPage != state.page) pager.scrollToPage(state.page) }

    Column(Modifier.fillMaxSize()) {
        ReaderBar(state)
        HorizontalDivider()
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
            val (book, chapter) = bible.chapters[index]
            ChapterPage(state, book, chapter, onVerse = { state.sheetKey = it; state.user.recordVisit(it, state.version) }, onPick = { pick = it })
        }
    }
    pick?.let { WordPickDialog(state, it) { pick = null } }
    // The sheet steps aside while a word page is open over it, and comes back after Back.
    if (state.sheetKey != 0 && state.overlays.isEmpty()) {
        VerseSheet(state, state.sheetKey, onDismiss = { state.sheetKey = 0 })
    }
}

@Composable
private fun ReaderBar(state: AppState) {
    val (book, chapter) = state.chapter
    var versionMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { state.open(Overlay.Picker) }) {
            Text("${state.bible.book(book).name} $chapter  ▾", style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.weight(1f))
        Box {
            TextButton(onClick = { versionMenu = true }) { Text("${state.version} ▾", style = MaterialTheme.typography.titleSmall) }
            DropdownMenu(expanded = versionMenu, onDismissRequest = { versionMenu = false }) {
                for (v in state.bible.versions) {
                    DropdownMenuItem(
                        text = { Column { Text(v.code, fontWeight = FontWeight.SemiBold); Text(v.name + " · " + v.coverage, style = MaterialTheme.typography.bodySmall) } },
                        onClick = { state.chooseVersion(v.code); versionMenu = false },
                    )
                }
            }
        }
        IconButton(onClick = { state.toggleParallel() }) {
            Icon(SbIcons.Columns, contentDescription = if (state.parallelOn) "Parallel on" else "Parallel off",
                tint = if (state.parallelOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChapterPage(state: AppState, book: Int, chapter: Int, onVerse: (Int) -> Unit, onPick: (WordPick) -> Unit) {
    val bible = state.bible
    val versions = remember(state.version, state.parallel, state.parallelOn) {
        listOf(state.version) + if (state.parallelOn) state.parallel.filter { it != state.version } else emptyList()
    }
    val rows = loadOnIo(book, chapter, versions) {
        val perVersion = versions.map { v -> v to bible.versesInChapter(v, book, chapter).groupBy { it.engKey } }
        val keys = perVersion.flatMap { it.second.keys }.toSortedSet()
        keys.map { k -> VerseRow(k, perVersion.map { (v, m) -> v to m[k].orEmpty() }) }
    }
    val marks = loadOnIo(state.userRevision) { Triple(state.user.highlightMap(), state.user.bookmarkedKeys(), state.user.notedKeys()) }
    if (rows == null) { Loading(); return }
    if (rows.isEmpty()) { Empty("This chapter is not in the chosen versions."); return }

    val list = rememberLazyListState()
    var flash by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.focusKey, rows) {
        val k = state.focusKey
        if (k != 0 && k / 1000 == Ref.key(book, chapter, 0) / 1000) {
            val index = rows.indexOfFirst { it.key >= k }.coerceAtLeast(0)
            list.scrollToItem(index + 1)
            flash = k
            state.focusKey = 0
            delay(1600)
            flash = 0
        }
    }
    val dark = isDark(state.theme)
    val base = baseSize(state.textScale)
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Text("${bible.book(book).name} $chapter", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
        }
        items(rows, key = { it.key }) { row ->
            val hl = marks?.first?.get(row.key)
            val target = when {
                flash == row.key -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                hl != null -> highlightColor(hl, dark)
                else -> Color.Transparent
            }
            val bg by animateColorAsState(target, tween(400), label = "verse")
            Column(
                Modifier.fillMaxWidth().background(bg).clickable { onVerse(row.key) }.padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                row.byVersion.forEachIndexed { i, (code, verses) ->
                    if (verses.isEmpty() && i > 0) return@forEachIndexed
                    val v = bible.version(code)
                    val style = readingStyle(v, base)
                    val num = row.key % 1000
                    val text = buildAnnotatedString {
                        if (i == 0) {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontSize = base * 0.65f, baselineShift = BaselineShift.Superscript, fontWeight = FontWeight.Bold)) {
                                append("$num ")
                            }
                        } else {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary, fontSize = base * 0.6f, fontWeight = FontWeight.Bold)) { append("$code  ") }
                        }
                        if (verses.isEmpty()) {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("(not in this version)") }
                        }
                        verses.forEachIndexed { j, vs ->
                            if (j > 0) append(" ")
                            if (vs.differsFromEnglish || verses.size > 1) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = base * 0.6f)) { append("[${vs.ownLabel}] ") }
                            }
                            if (vs.text.isEmpty()) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("(omitted in this edition)") }
                            } else append(vs.text)
                        }
                        if (i == 0) {
                            if (marks?.second?.contains(row.key) == true) withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("  ▮") }
                            if (marks?.third?.contains(row.key) == true) withStyle(SpanStyle(color = MaterialTheme.colorScheme.secondary)) { append("  ✎") }
                        }
                    }
                    // In the KJV, a long press on a word opens the Hebrew or Greek behind it; a tap still opens the verse.
                    val kjvWords = code == "KJV" && verses.size == 1 && verses[0].text.isNotEmpty()
                    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val prefix = if (i == 0) "$num ".length else "$code  ".length
                    Text(text, style = style.copy(color = MaterialTheme.colorScheme.onBackground), onTextLayout = { layout = it },
                        modifier = Modifier.fillMaxWidth().padding(top = if (i > 0) 4.dp else 0.dp).then(
                            if (!kjvWords) Modifier else Modifier.pointerInput(row.key, code) {
                                detectTapGestures(
                                    onTap = { onVerse(row.key) },
                                    onLongPress = { pos ->
                                        val offset = (layout ?: return@detectTapGestures).getOffsetForPosition(pos) - prefix
                                        pickAt(row.key, verses[0].text, offset)?.let(onPick)
                                    },
                                )
                            },
                        ))
                }
            }
        }
        item {
            val credit = bible.source(state.version)
            if (credit != null) {
                Text(credit.credit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp))
            }
        }
    }
}
