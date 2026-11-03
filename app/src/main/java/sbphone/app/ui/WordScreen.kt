package sbphone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sbphone.app.data.Lemma
import sbphone.app.data.Sense
import sbphone.fold.Ref

private val hebrewOrder = listOf("BDB1906", "SDBH", "TBESH")
private val greekOrder = listOf("AS1922", "SDGNT", "ML1889", "LSJ")

/** A word's page: heading, how often, how the KJV renders it, every lexicon in its own words, every place. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordScreen(state: AppState, lemmaId: Int) {
    val bible = state.bible
    val data = loadOnIo(lemmaId) {
        val lemma = bible.lemma(lemmaId)
        if (lemma == null) null else WordData(lemma, bible.renderings(lemmaId), bible.senses(lemmaId), bible.occurrences(lemmaId))
    }
    OverlayScaffold(title = data?.lemma?.headword ?: "Word", onBack = { state.back() }, actions = {
        if (data != null) SpeakButton(state, "word:$lemmaId", data.lemma.headword) { wordSpeech(state, data) }
    }) { padding ->
        if (data == null) { Loading(Modifier.padding(padding)); return@OverlayScaffold }
        val lemma = data.lemma
        var shown by remember(lemmaId) { mutableIntStateOf(50) }
        val open = remember(lemmaId) { mutableStateMapOf<String, Boolean>() }
        val order = if (lemma.isHebrew) hebrewOrder else greekOrder
        val bySource = data.senses.groupBy { it.source }.toList().sortedBy { order.indexOf(it.first).let { i -> if (i < 0) 99 else i } }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Heading(lemma) }
            if (data.renderings.isNotEmpty()) item {
                Section("How the KJV renders it")
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (r in data.renderings.take(24)) {
                        Text("${r.english} ${r.n}", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                                .clickable { state.open(Overlay.English(r.english)) }.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
                Text("Tap an English word for every Hebrew or Greek word behind it, and what it meant then and now.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
                state.bible.source("KJV-links")?.let { SourceLine(it.credit) }
            }
            if (bySource.isNotEmpty()) item {
                Section("The dictionaries, each in its own words")
                Text("Shown side by side as candidates, not ranked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            }
            for ((source, senses) in bySource) {
                val info = bible.source(source)
                val expanded = open[source] ?: (source == bySource.first().first)
                item(key = "src-$source") {
                    Column(Modifier.fillMaxWidth().clickable { open[source] = !expanded }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        Row {
                            Text(info?.name ?: source, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
                            Text(if (expanded) "▴" else "▾  ${senses.size}", style = MaterialTheme.typography.titleSmall)
                        }
                        if (!expanded) Text(senses.firstOrNull { it.gloss.isNotBlank() }?.gloss.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
                if (expanded) {
                    items(senses) { s -> SenseRow(s) }
                    item { info?.let { SourceLine("${it.credit} (${it.license})") } }
                }
                item { HorizontalDivider() }
            }
            item { Section("Every place (${data.places.size})") }
            items(data.places.take(shown)) { (key, gloss) ->
                val ref = Ref.fromKey(key)
                val preview = loadOnIo(key, state.version) { bible.previewText(ref, state.version) }
                Column(Modifier.fillMaxWidth().clickable { state.goTo(ref) }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row {
                        Text(ref.shortLabel(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Text(gloss, style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic)
                    }
                    Text(preview.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
            }
            if (data.places.size > shown) item {
                TextButton(onClick = { shown += 100 }, modifier = Modifier.padding(16.dp)) { Text("Show more (${data.places.size - shown} left)") }
            }
            item { state.bible.source(if (lemma.isHebrew) "macula_hebrew" else "macula_greek")?.let { SourceLine(it.credit) } }
        }
    }
}

private data class WordData(val lemma: Lemma, val renderings: List<sbphone.app.data.Rendering>, val senses: List<Sense>, val places: List<Pair<Int, String>>)

@Composable
private fun Heading(lemma: Lemma) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(pointed(lemma.headword), style = if (lemma.isHebrew) MaterialTheme.typography.displaySmall.copy(fontFamily = HebrewFont) else MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Start)
        Text(listOf(lemma.translit, lemma.pron.takeIf { it.isNotBlank() }?.let { "($it)" }).filterNotNull().joinToString("  "),
            style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(listOf(lemma.languageName, lemma.pos, lemma.number.takeIf { it.isNotBlank() }?.let { "Strong's $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text("Occurs ${lemma.count} times in the ${if (lemma.isHebrew) "Hebrew Bible (WLC)" else "Greek New Testament"}", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun SenseRow(s: Sense) {
    Column(Modifier.fillMaxWidth().padding(start = (20 + 14 * s.depth.coerceIn(0, 4)).dp, end = 20.dp, top = 4.dp, bottom = 4.dp)) {
        val head = listOf(s.label, s.gloss).filter { it.isNotBlank() }.joinToString("  ")
        if (head.isNotBlank()) Text(head, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        // Skip the full text when it only repeats the gloss (some lexicons give both, differing by a final period).
        val repeats = s.text.trim().trimEnd('.', ';', ',') == s.gloss.trim().trimEnd('.', ';', ',') || s.text.trim().trimEnd('.') == head.trim().trimEnd('.')
        if (s.text.isNotBlank() && !repeats) Text(s.text, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp))
        if (s.domain.isNotBlank()) Text(s.domain, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        if (s.note.isNotBlank()) Text(s.note.replace("|", "\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The word page read aloud: heading, the KJV's renderings, each dictionary in turn, then the places. */
private fun wordSpeech(state: AppState, data: WordData): List<sbphone.app.speech.Speech> = buildList {
    val bible = state.bible
    val l = data.lemma
    val order = if (l.isHebrew) hebrewOrder else greekOrder
    add(sbphone.app.speech.Speech("${l.headword} . ${spokenTranslit(l.translit)}. ${l.languageName}${if (l.pos.isNotBlank()) ", " + l.pos else ""}. " +
        "Occurs ${l.count} times.", label = l.headword))
    if (data.renderings.isNotEmpty()) add(sbphone.app.speech.Speech("The King James Version renders it: " +
        data.renderings.take(24).joinToString(", ") { "${it.english}, ${it.n}" } + ".", label = "How the KJV renders it"))
    for ((source, senses) in data.senses.groupBy { it.source }.toList().sortedBy { order.indexOf(it.first).let { i -> if (i < 0) 99 else i } }) {
        val name = bible.source(source)?.name ?: source
        add(sbphone.app.speech.Speech("$name.", label = name))
        for (s in senses) {
            val head = listOf(s.label, s.gloss).filter { it.isNotBlank() }.joinToString(" ")
            val repeats = s.text.trim().trimEnd('.', ';', ',') == s.gloss.trim().trimEnd('.', ';', ',') || s.text.trim().trimEnd('.') == head.trim().trimEnd('.')
            val text = listOf(head, if (repeats) "" else s.text).filter { it.isNotBlank() }.joinToString(". ")
            if (text.isNotBlank()) add(sbphone.app.speech.Speech(text, label = name))
        }
    }
    add(sbphone.app.speech.Speech("Every place: ${data.places.size}.", label = "Every place"))
    for ((key, gloss) in data.places.take(50)) {
        val r = Ref.fromKey(key)
        add(sbphone.app.speech.Speech("${r.spoken()}, $gloss. " + bible.previewText(r, state.version), langOf(bible.version(state.version)), label = r.label()))
    }
}
