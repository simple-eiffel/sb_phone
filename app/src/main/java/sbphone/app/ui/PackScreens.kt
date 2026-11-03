package sbphone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import sbphone.app.data.PackDb
import sbphone.app.data.PackDoc
import sbphone.app.data.PackHit
import sbphone.app.speech.Speech

/** Names for document kinds, in the order shown. */
private val kindNames = linkedMapOf(
    "commentary" to "Commentary", "framework" to "Frameworks", "book" to "Books", "essay" to "Essays",
    "series" to "Series", "analysis" to "Analyses", "research" to "Research", "note" to "Notes",
)

fun kindName(kind: String) = kindNames[kind] ?: kind.replaceFirstChar { it.uppercase() }

/** Status shown on every document, so a withdrawn or draft text is never mistaken for a settled one. */
@Composable
fun StatusChip(status: String) {
    if (status.isBlank() || status == "unmarked") return
    val warn = status == "withdrawn" || status == "ungated"
    Text(
        status,
        style = MaterialTheme.typography.labelSmall,
        color = if (warn) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.background(if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun PackHomeScreen(state: AppState, file: String) {
    val pack = state.packs.firstOrNull { it.file.name == file } ?: run { state.back(); return }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<String?>(null) }
    OverlayScaffold(title = pack.title, onBack = { if (kind != null) kind = null else state.back() }, actions = {
        SpeakButton(state, "pack:$file:$submitted:$kind", pack.title) {
            when {
                submitted.isNotBlank() -> pack.search(submitted).map { h -> Speech(h.doc.title + ". " + plain(h.excerpt), label = h.doc.title) }
                kind == null -> pack.kinds().map { (k, n) -> Speech("${kindName(k)}, $n.", label = kindName(k)) }
                else -> pack.docs(kind!!).map { d -> Speech(d.title + if (d.status.isNotBlank() && d.status != "unmarked") ", ${d.status}." else ".", label = d.title) }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search ${pack.title}") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitted = query }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                submitted.isNotBlank() -> {
                    val hits = loadOnIo(submitted) { pack.search(submitted) }
                    when {
                        hits == null -> Loading()
                        hits.isEmpty() -> Empty("Nothing found for “$submitted”.")
                        else -> HitList(state, pack, hits)
                    }
                }
                kind == null -> {
                    val kinds = loadOnIo(pack.file.name) { pack.kinds() }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(kinds.orEmpty().sortedBy { kindNames.keys.indexOf(it.first).let { i -> if (i < 0) 99 else i } }) { (k, n) ->
                            Row(Modifier.fillMaxWidth().clickable { kind = k }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                                Text(kindName(k), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                        item {
                            Text(listOfNotNull(pack.meta["built_on"]?.let { "Built $it" }, pack.meta["note"]).joinToString(". "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
                        }
                    }
                }
                else -> {
                    val docs = loadOnIo(pack.file.name, kind) { pack.docs(kind!!) }
                    if (docs == null) Loading() else LazyColumn(Modifier.fillMaxSize()) {
                        var lastCollection: String? = null
                        for (d in docs) {
                            if (d.collection != lastCollection && d.collection.isNotBlank()) {
                                val c = d.collection
                                item(key = "c$c${d.id}") { Text(c, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)) }
                            }
                            lastCollection = d.collection
                            item(key = d.id) { DocRow(state, pack, d, null) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocRow(state: AppState, pack: PackDb, d: PackDoc, excerpt: String?) {
    Column(Modifier.fillMaxWidth().clickable { state.open(Overlay.PackDocument(pack.file.name, d.id)) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp))
            StatusChip(d.status)
        }
        if (excerpt != null) Text(plain(excerpt).trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
    }
    HorizontalDivider()
}

@Composable
fun HitList(state: AppState, pack: PackDb, hits: List<PackHit>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(hits, key = { it.doc.id }) { h ->
            Text(kindName(h.doc.kind) + if (h.doc.collection.isNotBlank()) " · " + h.doc.collection else "", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
            DocRow(state, pack, h.doc, h.excerpt)
        }
    }
}

/** A document from a pack: its status first, then the text with references made into links. */
@Composable
fun PackDocumentScreen(state: AppState, file: String, id: Long) {
    val pack = state.packs.firstOrNull { it.file.name == file } ?: run { state.back(); return }
    val data = loadOnIo(file, id) { pack.doc(id) to pack.body(id) }
    OverlayScaffold(title = data?.first?.title ?: "", onBack = { state.back() }, actions = {
        val d = data?.first
        if (d != null) SpeakButton(state, "doc:$file:$id", d.title) {
            listOf(Speech(d.title + ". " + kindName(d.kind) + if (d.status.isNotBlank() && d.status != "unmarked") ", ${d.status}." else ".", label = d.title)) +
                markdownBlocks(data?.second.orEmpty()).mapIndexed { i, b -> Speech(b.text, tag = DocTag(file, id, i), label = if (b.kind == 'h') b.text else d.title) }
        }
    }) { padding ->
        val (doc, body) = data ?: run { Loading(Modifier.padding(padding)); return@OverlayScaffold }
        if (doc == null) { Empty("This document is not in the pack.", Modifier.padding(padding)); return@OverlayScaffold }
        val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
        val blocks = remember(id) { markdownBlocks(body) }
        val list = rememberLazyListState()
        val reading = (state.speaker.nowTag as? DocTag)?.takeIf { it.file == file && it.id == id }?.index ?: -1
        LaunchedEffect(reading) {
            if (reading < 0) return@LaunchedEffect
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == reading + 1 }
            val fits = item != null && item.offset >= list.layoutInfo.viewportStartOffset && item.offset + item.size <= list.layoutInfo.viewportEndOffset
            if (!fits) list.animateScrollToItem(reading + 1)
        }
        val readBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), state = list) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(kindName(doc.kind) + if (doc.collection.isNotBlank()) " · ${doc.collection}" else "", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 8.dp))
                    StatusChip(doc.status)
                }
                if (doc.status == "withdrawn") Text("Withdrawn: kept for the record, not to be taken as current.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
            }
            itemsIndexed(blocks) { i, b ->
                Box(Modifier.fillMaxWidth().background(if (i == reading) readBg else androidx.compose.ui.graphics.Color.Transparent)) {
                when (b.kind) {
                    'h' -> Text(b.text, style = if (b.level <= 1) MaterialTheme.typography.headlineSmall else if (b.level == 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    'q' -> Text(linkRefs(b.text, linkStyle) { state.goTo(it) }, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp))
                    'l' -> Text(linkRefs("•  " + b.text, linkStyle) { state.goTo(it) }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                    else -> Text(linkRefs(b.text, linkStyle) { state.goTo(it) }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
                }
                }
            }
            item { Text(doc.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
        }
    }
}

/** The verse sheet's "Mine" tab: commentary sections covering the verse, then writings that cite it. */
@Composable
fun MineTab(state: AppState, key: Int) {
    val hits = loadOnIo(key, state.packs.size) { state.packs.flatMap { p -> p.forVerse(key).map { p to it } } }
    when {
        hits == null -> Loading()
        hits.isEmpty() -> Empty("Nothing in your materials covers or cites this verse.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${hits.size} in your materials", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f).padding(start = 20.dp))
                    SpeakButton(state, "mine:$key", "Mine · " + sbphone.fold.Ref.fromKey(key).label()) { hits.map { (_, h) -> Speech(h.doc.title + ". " + plain(h.excerpt), label = h.doc.title) } }
                }
            }
            items(hits, key = { (p, h) -> p.file.name + h.doc.id }) { (p, h) ->
                Text(kindName(h.doc.kind) + if (h.doc.collection.isNotBlank()) " · " + h.doc.collection else "", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                DocRow(state, p, h.doc, h.excerpt)
            }
        }
    }
}

// ---- a small Markdown reader: headings, quotes, list items, paragraphs; emphasis marks removed ----

private data class Block(val kind: Char, val text: String, val level: Int = 0)

private fun markdownBlocks(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block('p', plain(para.toString().trim())); para.clear() }
    var inFrontMatter = false
    md.lines().forEachIndexed { i, raw ->
        val line = raw.trimEnd()
        if (i == 0 && line == "---") { inFrontMatter = true; return@forEachIndexed }
        if (inFrontMatter) { if (line == "---") inFrontMatter = false; return@forEachIndexed }
        when {
            line.isBlank() -> flush()
            line.startsWith("#") -> { flush(); val level = line.takeWhile { it == '#' }.length; out += Block('h', plain(line.drop(level).trim()), level) }
            line.startsWith(">") -> { flush(); out += Block('q', plain(line.trimStart('>', ' '))) }
            Regex("""^\s*([-*+]|\d+[.)])\s+""").containsMatchIn(line) -> { flush(); out += Block('l', plain(line.replaceFirst(Regex("""^\s*([-*+]|\d+[.)])\s+"""), ""))) }
            line.startsWith("|") -> { flush(); out += Block('p', plain(line.trim('|').split('|').joinToString("  ·  ") { it.trim() })) }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(line.trim()) }
        }
    }
    flush()
    return out.filterNot { it.kind == 'p' && Regex("""^[-:| ]+$""").matches(it.text) }
}

/** Removes Markdown emphasis and wiki-link brackets, keeping the words. */
fun plain(s: String): String = s
    .replace(Regex("""\[\[([^\]|]+)\|([^\]]+)]]"""), "$2")
    .replace(Regex("""\[\[([^\]]+)]]"""), "$1")
    .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
    .replace("**", "").replace("__", "").replace("`", "")
    .replace(Regex("""(?<![\w*])\*(?!\s)([^*]+)(?<!\s)\*(?![\w*])"""), "$1")

/** Marks the paragraph of a pack document being read aloud. */
data class DocTag(val file: String, val id: Long, val index: Int)
