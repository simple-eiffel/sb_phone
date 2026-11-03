package sbphone.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.Normalizer
import sbphone.app.data.Version
import sbphone.app.speech.Batch
import sbphone.app.speech.Lang
import sbphone.app.speech.Speech
import sbphone.fold.Books
import sbphone.fold.Ref

/** Marks a verse being read, so the reader can follow along. */
data class VerseTag(val book: Int, val chapter: Int, val key: Int)

/**
 * The read-aloud button every screen carries. It reads what that screen shows; tapped again
 * while that screen is being read, it stops. [build] runs off the main thread.
 */
@Composable
fun SpeakButton(state: AppState, source: String, title: String, build: () -> List<Speech>) {
    val sp = state.speaker
    val mine = sp.active && sp.source == source
    IconButton(onClick = { if (mine) sp.stop() else sp.read(source) { Batch(title, build()) } }) {
        Icon(if (mine) SbIcons.Stop else SbIcons.Speaker, contentDescription = if (mine) "Stop reading aloud" else "Read aloud",
            tint = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The bar at the bottom while something is being read: what it is, and back, pause, next, stop. */
@Composable
fun PlayerBar(state: AppState, modifier: Modifier = Modifier) {
    val sp = state.speaker
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sp.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    val line = sp.notice ?: sp.nowLabel
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        color = if (sp.notice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { sp.skipPrevious() }) { Icon(SbIcons.SkipPrevious, contentDescription = "Back one") }
                IconButton(onClick = { if (sp.playing) sp.pause() else sp.resume() }) {
                    Icon(if (sp.playing) SbIcons.Pause else SbIcons.Play, contentDescription = if (sp.playing) "Pause" else "Go on reading", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { sp.skipNext() }) { Icon(SbIcons.SkipNext, contentDescription = "Next") }
                IconButton(onClick = { sp.stop() }) { Icon(SbIcons.Close, contentDescription = "Stop reading aloud") }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- spoken forms

/** "1 Corinthians" as it is said: "First Corinthians". */
fun spokenBook(id: Int): String = Books.byId(id).name
    .replaceFirst(Regex("^1 "), "First ").replaceFirst(Regex("^2 "), "Second ").replaceFirst(Regex("^3 "), "Third ")

/** A reference as it is said: "John 3, verse 16"; "Psalms, chapter 23". */
fun Ref.spoken(): String {
    val b = spokenBook(book)
    return when {
        verse == 0 -> "$b, chapter $chapter"
        verseEnd > verse -> "$b $chapter, verses $verse to $verseEnd"
        else -> "$b $chapter, verse $verse"
    }
}

fun spokenRef(key: Int): String = Ref.fromKey(key).spoken()

/** Transliterations without their marks, which voices stumble over: "bərēʾšîṯ" → "bereshit"-like "beresit". */
fun spokenTranslit(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    .replace(Regex("[ʾʿʼʽ'’]"), "").replace("ə", "e")

/** Latin letters in this version are read as English, except the Vulgate (Latin). Hebrew and Greek script choose their own voice. */
fun langOf(v: Version): Lang = if (v.language == "la") Lang.La else Lang.En

/** A verse's text as one thing to read, in its version's voice. */
fun verseSpeech(state: AppState, key: Int, version: String, lead: String = spokenRef(key) + ". "): Speech {
    val v = state.bible.version(version)
    val text = state.bible.versesAt(key, version).joinToString(" ") { it.text }
    return Speech(lead + text.ifBlank { "Not in the ${v.name}." }, langOf(v), label = Ref.fromKey(key).label())
}

/** A list of verses, each with its reference, in the reading version. */
fun versesSpeech(state: AppState, keys: List<Int>): List<Speech> = keys.map { verseSpeech(state, it, state.version) }

// ---------------------------------------------------------------------------------- the reader

/** Reads the chapter on [page] from verse [fromKey] (0 = the start), then on into the next chapters if that setting is on. */
fun readChapter(state: AppState, page: Int, fromKey: Int = 0) {
    state.speaker.read("reader") { chapterBatch(state, page, fromKey) }
}

private fun chapterBatch(state: AppState, page: Int, fromKey: Int): Batch {
    val bible = state.bible
    val (book, chapter) = bible.chapters[page]
    val versions = listOf(state.version) + if (state.parallelOn) state.parallel.filter { it != state.version } else emptyList()
    val perVersion = versions.map { code -> bible.version(code) to bible.versesInChapter(code, book, chapter).groupBy { it.engKey } }
    val keys = perVersion.flatMap { it.second.keys }.toSortedSet().filter { it >= fromKey }
    val name = "${bible.book(book).name} $chapter"
    val items = mutableListOf<Speech>()
    if (fromKey == 0 || keys.firstOrNull()?.rem(1000) == 1) items += Speech("${spokenBook(book)}, chapter $chapter.", tag = VerseTag(book, chapter, 0), label = name)
    if (keys.isEmpty()) {
        val v = bible.version(state.version)
        items += Speech("$name is not in the ${v.name}.", label = name)
        return Batch("$name · ${state.version}", items)
    }
    for (k in keys) {
        perVersion.forEachIndexed { i, (v, byKey) ->
            val verses = byKey[k].orEmpty().filter { it.text.isNotBlank() }
            if (verses.isEmpty()) return@forEachIndexed
            val lead = when {
                i > 0 -> "${v.code}. "
                state.sayVerseNumbers -> "${k % 1000}. "
                else -> ""
            }
            items += Speech(lead + verses.joinToString(" ") { it.text }, langOf(v), VerseTag(book, chapter, k), "$name:${k % 1000}" + if (i > 0) " · ${v.code}" else "")
        }
    }
    val next = if (state.readOn && page < bible.chapters.lastIndex) ({ chapterBatch(state, page + 1, 0) }) else null
    return Batch("$name · ${state.version}", items, next)
}
