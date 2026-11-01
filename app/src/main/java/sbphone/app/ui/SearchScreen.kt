package sbphone.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import sbphone.app.data.Lemma
import sbphone.app.data.SearchResult
import sbphone.fold.Fold
import sbphone.fold.Match
import sbphone.fold.QueryParse
import sbphone.fold.QueryParser
import sbphone.fold.Ref
import sbphone.fold.RefParse
import sbphone.fold.RefParser

private sealed interface Outcome {
    data class Found(val result: SearchResult, val refs: List<Ref>, val lemmas: List<Lemma>) : Outcome
    data class Problem(val message: String) : Outcome
}

@Composable
fun SearchScreen(state: AppState) {
    var submitted by remember { mutableStateOf(state.searchText) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchText, onValueChange = { state.searchText = it },
            label = { Text("Search") }, placeholder = { Text("love \"one another\"  ·  μιμ*  ·  H7225") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = state.searchText; keyboard?.hide(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !state.searchAllVersions, onClick = { state.searchAllVersions = false; submitted = state.searchText }, label = { Text(state.version) })
            Text("  ")
            FilterChip(selected = state.searchAllVersions, onClick = { state.searchAllVersions = true; submitted = state.searchText }, label = { Text("All versions") })
        }
        if (submitted.isBlank()) { Help(); return@Column }
        val outcome = loadOnIo(submitted, state.searchAllVersions, state.version) { run(state, submitted) }
        when (outcome) {
            null -> Loading()
            is Outcome.Problem -> Empty(outcome.message)
            is Outcome.Found -> Results(state, outcome)
        }
    }
}

private fun run(state: AppState, text: String): Outcome {
    val bible = state.bible
    val refs = when (val r = RefParser.parse(text)) {
        is RefParse.One -> listOf(r.ref)
        is RefParse.Choice -> r.refs
        RefParse.NotARef -> emptyList()
    }
    return when (val q = QueryParser.parse(text)) {
        is QueryParse.Strongs -> {
            val lemmas = bible.lemmasByStrongs(q.number)
            if (lemmas.isEmpty()) Outcome.Problem("No word carries Strong's ${q.number}.") else Outcome.Found(SearchResult(0, emptyList(), emptyList()), emptyList(), lemmas)
        }
        is QueryParse.Error -> if (refs.isNotEmpty()) Outcome.Found(SearchResult(0, emptyList(), emptyList()), refs, emptyList()) else Outcome.Problem(q.message)
        is QueryParse.Text -> {
            val result = bible.search(q.query, if (state.searchAllVersions) null else state.version)
            val single = q.query.required.singleOrNull()?.singleOrNull()?.takeIf { it.words.size == 1 && q.query.excluded.isEmpty() }
            val lemmas = single?.let { bible.lemmasMatching(it.words[0], it.words[0].lowercase()) }.orEmpty()
            Outcome.Found(result, refs, lemmas)
        }
    }
}

@Composable
private fun Results(state: AppState, found: Outcome.Found) {
    val base = baseSize(state.textScale)
    LazyColumn(Modifier.fillMaxSize()) {
        if (found.refs.isNotEmpty()) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                for (r in found.refs) Text("Go to ${r.label()}  →", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { state.goTo(r) }.padding(vertical = 6.dp))
            }
            HorizontalDivider()
        }
        if (found.lemmas.isNotEmpty()) {
            item { Label("Original words") }
            items(found.lemmas) { l ->
                Row(Modifier.fillMaxWidth().clickable { state.open(Overlay.Word(l.id)) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.headword, style = if (l.isHebrew) MaterialTheme.typography.titleLarge.copy(fontFamily = HebrewFont) else MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(l.translit, fontStyle = FontStyle.Italic)
                        if (l.hint.isNotBlank()) Text(l.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text("${l.number}  ·  ${l.count}×", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { HorizontalDivider() }
        }
        if (found.result.matches.isNotEmpty()) {
            item {
                val shown = found.result.verses.size
                Label(if (found.result.total == 0) "No verses found" else if (shown < found.result.total) "${found.result.total} verses (first $shown shown)" else "${found.result.total} verses")
            }
            items(found.result.verses, key = { it.id }) { v ->
                val ref = Ref(v.book, v.chapter, v.verse)
                val eng = Ref.fromKey(v.engKey)
                val version = state.bible.version(v.version)
                Column(Modifier.fillMaxWidth().clickable { state.goTo(eng) }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(eng.shortLabel() + (if (v.differsFromEnglish) "  [${ref.chapter}:${ref.verse}]" else "") + "  ·  ${v.version}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(marked(v.text, found.result.matches), style = readingStyle(version, base * 0.9f).copy(color = MaterialTheme.colorScheme.onBackground))
                }
                HorizontalDivider()
            }
        }
    }
}

/** Bolds the words of [text] that a match found, comparing folded forms. */
@Composable
private fun marked(text: String, matches: List<Match>): AnnotatedString {
    val exact = matches.flatMap { m -> if (m.prefix) m.words.dropLast(1) else m.words }.toSet()
    val prefixes = matches.filter { it.prefix }.map { it.words.last() }
    val color = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        val word = Regex("""[\p{L}\p{M}\p{N}'’]+""")
        var last = 0
        for (m in word.findAll(text)) {
            append(text.substring(last, m.range.first))
            val f = Fold.fold(m.value)
            if (f in exact || prefixes.any { f.startsWith(it) }) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(m.value) }
            else append(m.value)
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Help() {
    Column(Modifier.padding(20.dp)) {
        Text("How to search", style = MaterialTheme.typography.titleMedium)
        val lines = listOf(
            "grace peace" to "verses with both words",
            "\"living water\"" to "an exact phrase",
            "believ*" to "words that begin with it",
            "faith OR hope" to "either word",
            "love -hate" to "leave out verses with a word",
            "ברא  ·  λογος" to "Hebrew and Greek, with or without points and accents",
            "bara  ·  logos" to "an original word by its sound",
            "H1254  ·  G3056" to "a Strong's number",
            "jn 3 16" to "go to a reference",
        )
        for ((example, meaning) in lines) {
            Row(Modifier.padding(top = 8.dp)) {
                Text(example, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.45f))
                Text(meaning, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
            }
        }
    }
}
