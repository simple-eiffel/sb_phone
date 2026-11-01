package sbphone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import sbphone.fold.Ref
import sbphone.fold.RefParse
import sbphone.fold.RefParser

/** Book, then chapter; or type a reference ("jn 3 16"). An ambiguous book gives a choice, never a guess. */
@Composable
fun PickerScreen(state: AppState) {
    var book by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<Ref>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    fun go() {
        when (val p = RefParser.parse(typed)) {
            is RefParse.One -> state.goTo(p.ref)
            is RefParse.Choice -> { choices = p.refs; message = null }
            RefParse.NotARef -> { message = "That doesn't read as a reference. Try \"John 3:16\" or \"ps 23\"."; choices = emptyList() }
        }
    }
    OverlayScaffold(title = if (book == 0) "Go to" else state.bible.book(book).name, onBack = { if (book != 0) book = 0 else state.back() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = typed, onValueChange = { typed = it; message = null; choices = emptyList() },
                label = { Text("Reference") }, placeholder = { Text("jn 3 16") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
            if (choices.isNotEmpty()) {
                Text("Which did you mean?", modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.padding(horizontal = 12.dp)) {
                    for (r in choices) TextButton(onClick = { state.goTo(r) }) { Text(r.label()) }
                }
            }
            if (book == 0) BookGrid(state) { book = it } else ChapterGrid(state, book) { state.goTo(Ref(book, it)) }
        }
    }
}

@Composable
private fun BookGrid(state: AppState, onBook: (Int) -> Unit) {
    val books = state.bible.books
    LazyVerticalGrid(columns = GridCells.Adaptive(76.dp), modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Header("Old Testament") }
        items(books.filter { it.id <= 39 }) { b -> Cell(b.short, b.id == state.chapter.first) { onBook(b.id) } }
        item(span = { GridItemSpan(maxLineSpan) }) { Header("New Testament") }
        items(books.filter { it.id > 39 }) { b -> Cell(b.short, b.id == state.chapter.first) { onBook(b.id) } }
    }
}

@Composable
private fun ChapterGrid(state: AppState, book: Int, onChapter: (Int) -> Unit) {
    val n = state.bible.book(book).chapters
    LazyVerticalGrid(columns = GridCells.Adaptive(56.dp), modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Header("Chapter") }
        items((1..n).toList()) { c -> Cell(c.toString(), state.chapter == book to c) { onChapter(c) } }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp))
}

@Composable
private fun Cell(text: String, current: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(44.dp).background(if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}
