package sbphone.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import sbphone.app.data.Behind
import sbphone.app.data.EnglishSense
import sbphone.app.data.Lemma
import sbphone.fold.Fold
import sbphone.fold.Ref

private val thenSources = listOf("BLOUNT1661", "WRIGHT1884-QUOTING-BACON1604", "WEBSTER1828", "WRIGHT1884")

/** An English (KJV) word: the Hebrew and Greek behind it, what it meant then, and what it means today. */
@Composable
fun EnglishScreen(state: AppState, word: String) {
    val fold = Fold.fold(word)
    val entry = loadOnIo(fold) { state.bible.englishEntry(fold) }
    OverlayScaffold(title = entry?.lemma ?: word, onBack = { state.back() }, actions = {
        if (entry != null) SpeakButton(state, "english:$fold", entry.lemma) { englishSpeech(state, entry) }
    }) { padding ->
        if (entry == null) { Loading(Modifier.padding(padding)); return@OverlayScaffold }
        val behind = entry.behind
        val shifts = entry.shifts
        val then = entry.senses.filter { it.source in thenSources }
        val today = entry.senses.filter { it.source == "OEWN2025" }
        val head = entry.lemma
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(head, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
                Text(if (entry.forms.size > 1) "In the KJV as: " + entry.forms.joinToString(", ") else "An English word in the King James Version",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
            item { Heading("The Hebrew and Greek behind it") }
            if (behind.isEmpty()) item { Note("The KJV word links have no Hebrew or Greek word for \"$head\".") }
            for ((label, group) in listOf("Old Testament" to behind.filter { it.lemma.isHebrew }, "New Testament" to behind.filter { !it.lemma.isHebrew })) {
                if (group.isEmpty()) continue
                item { Text("$label (${group.sumOf { it.n }} places)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 20.dp, top = 8.dp)) }
                items(group) { b -> BehindRow(b) { state.open(Overlay.Word(b.lemma.id)) } }
            }
            if (behind.isNotEmpty()) item { state.bible.source("KJV-links")?.let { SourceLine(it.credit) } }

            item { Heading("What it meant then") }
            if (shifts.isNotEmpty()) item {
                Note("Wright's Bible Word-Book (1884) lists \"$head\" among the words whose meaning has changed since the King James Version. The places he cites:")
                Column(Modifier.padding(horizontal = 20.dp)) {
                    for (k in shifts) Text(Ref.fromKey(k).label() + "  →", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { state.goTo(Ref.fromKey(k)) }.padding(vertical = 6.dp))
                }
            }
            if (then.isEmpty() && shifts.isEmpty()) item { Note("None of the period dictionaries in this app has an entry for \"$head\" yet.") }
            items(then.groupBy { it.source }.toList()) { (source, list) -> SenseGroup(state, source, list) }

            if (today.isNotEmpty()) {
                item { Heading("What it means today") }
                items(today.groupBy { it.pos }.toList()) { (pos, list) ->
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(partOfSpeech(pos), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        list.forEachIndexed { i, s -> Text("${i + 1}. ${s.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
                    }
                }
                item { state.bible.source("OEWN2025")?.let { SourceLine(it.credit) } }
            }
        }
    }
}

@Composable
private fun SenseGroup(state: AppState, source: String, list: List<EnglishSense>) {
    val info = state.bible.source(source)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(info?.name ?: source, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
        for (s in list) {
            Text((if (s.senseNo.isNotBlank()) "${s.senseNo}. " else "") + s.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        list.firstOrNull()?.cite?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
    HorizontalDivider()
}

@Composable
private fun BehindRow(b: Behind, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LemmaHead(b.lemma)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(b.lemma.translit, fontStyle = FontStyle.Italic)
            if (b.lemma.hint.isNotBlank()) Text(b.lemma.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text("${b.lemma.number}  ·  ${b.n}×", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LemmaHead(l: Lemma) {
    Text(pointed(l.headword), style = if (l.isHebrew) MaterialTheme.typography.titleLarge.copy(fontFamily = HebrewFont) else MaterialTheme.typography.titleLarge)
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}

private fun partOfSpeech(code: String) = when (code) {
    "n" -> "noun"; "v" -> "verb"; "a", "s" -> "adjective"; "r" -> "adverb"; else -> code
}

/** A KJV word the reader picked: its verse, its text, and which occurrence of that word in the verse it is. */
data class WordPick(val engKey: Int, val word: String, val fold: String, val nth: Int)

/** Finds the word at [offset] in [text] (offset counted within the verse text) and which occurrence of it this is. */
fun pickAt(engKey: Int, text: String, offset: Int): WordPick? {
    val words = Regex("""[\p{L}\p{M}'’]+""").findAll(text).toList()
    val hit = words.firstOrNull { offset in it.range.first..it.range.last + 1 } ?: return null
    val fold = Fold.fold(hit.value).split(' ').firstOrNull { it.isNotEmpty() } ?: return null
    val nth = words.takeWhile { it !== hit }.flatMap { Fold.words(it.value) }.count { it == fold } + 1
    return WordPick(engKey, hit.value.trim('\'', '’'), fold, nth)
}

/** Asks what to open for a picked KJV word: the Hebrew/Greek behind it, or its English meaning then and now. */
@Composable
fun WordPickDialog(state: AppState, pick: WordPick, onDismiss: () -> Unit) {
    val lemmas = loadOnIo(pick) { state.bible.kjvLemmas(pick.engKey, pick.fold, pick.nth) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("“${pick.word}”  ·  ${Ref.fromKey(pick.engKey).shortLabel()}") },
        text = {
            Column {
                when {
                    lemmas == null -> Text("…")
                    lemmas.isEmpty() -> Text("No Hebrew or Greek word is linked to this English word here (often a word the translators supplied).", style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text("Translates", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        for (l in lemmas) {
                            Row(Modifier.fillMaxWidth().clickable { onDismiss(); state.open(Overlay.Word(l.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                LemmaHead(l)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(l.translit, fontStyle = FontStyle.Italic)
                                    Text(l.number + if (l.hint.isNotBlank()) "  ·  ${l.hint}" else "", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Text("What “${pick.word}” meant then and means now  →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable { onDismiss(); state.open(Overlay.English(pick.word.lowercase())) }.padding(top = 16.dp, bottom = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * KJV text with every word tappable. Words linked to Hebrew or Greek are underlined; words
 * Wright (1884) lists as changed in meaning are marked with a dagger.
 */
fun kjvTappable(engKey: Int, text: String, linked: Set<Pair<String, Int>>, shifted: Set<String>, linkColor: Color, shiftColor: Color, onPick: (WordPick) -> Unit): AnnotatedString =
    buildAnnotatedString {
        val words = Regex("""[\p{L}\p{M}'’]+""").findAll(text).toList()
        val seen = HashMap<String, Int>()
        var last = 0
        for (m in words) {
            append(text.substring(last, m.range.first))
            val fold = Fold.fold(m.value).split(' ').firstOrNull { it.isNotEmpty() }
            if (fold == null) { append(m.value); last = m.range.last + 1; continue }
            val nth = seen.merge(fold, 1, Int::plus)!!
            val isLinked = (fold to nth) in linked
            val isShifted = fold in shifted
            val style = SpanStyle(
                color = if (isShifted) shiftColor else Color.Unspecified,
                textDecoration = if (isLinked) TextDecoration.Underline else null,
            )
            val pick = WordPick(engKey, m.value.trim('\'', '’'), fold, nth)
            withLink(LinkAnnotation.Clickable("w$nth$fold", TextLinkStyles(style = style)) { onPick(pick) }) { append(m.value) }
            if (isShifted) withStyle(SpanStyle(color = shiftColor)) { append("†") }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }

/** The English word page read aloud: the words behind it, what it meant then, what it means today. */
private fun englishSpeech(state: AppState, e: sbphone.app.data.EnglishEntry): List<sbphone.app.speech.Speech> = buildList {
    val S = { text: String, label: String -> sbphone.app.speech.Speech(text, label = label) }
    add(S(e.lemma + if (e.forms.size > 1) ". In the King James Version as: " + e.forms.joinToString(", ") + "." else ".", e.lemma))
    if (e.behind.isNotEmpty()) {
        add(S("The Hebrew and Greek behind it.", "Behind it"))
        for (b in e.behind) add(S("${b.lemma.headword} . ${spokenTranslit(b.lemma.translit)}${if (b.lemma.hint.isNotBlank()) ", " + b.lemma.hint else ""}. ${b.n} places.", b.lemma.translit))
    }
    val then = e.senses.filter { it.source in thenSources }
    if (then.isNotEmpty() || e.shifts.isNotEmpty()) add(S("What it meant then.", "What it meant then"))
    if (e.shifts.isNotEmpty()) add(S("Wright's Bible Word-Book, 1884, lists ${e.lemma} among the words whose meaning has changed since the King James Version, at " +
        e.shifts.joinToString("; ") { spokenRef(it) } + ".", "Wright 1884"))
    for ((source, list) in then.groupBy { it.source }) {
        val name = state.bible.source(source)?.name ?: source
        add(S("$name.", name))
        for (s in list) add(S((if (s.senseNo.isNotBlank()) "${s.senseNo}. " else "") + s.text, name))
    }
    val today = e.senses.filter { it.source == "OEWN2025" }
    if (today.isNotEmpty()) {
        add(S("What it means today.", "What it means today"))
        for ((pos, list) in today.groupBy { it.pos }) {
            add(S(partOfSpeech(pos) + ".", partOfSpeech(pos)))
            list.forEachIndexed { i, s -> add(S("${i + 1}. ${s.text}", partOfSpeech(pos))) }
        }
    }
}
