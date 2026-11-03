package sbphone.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import sbphone.app.data.Card
import sbphone.app.data.Prayer
import sbphone.app.speech.Batch
import sbphone.app.speech.Speech
import sbphone.fold.Ref
import sbphone.fold.RefParse
import sbphone.fold.RefParser

private val dateFormat: DateFormat get() = DateFormat.getDateInstance(DateFormat.MEDIUM)

// ---------------------------------------------------------------------------------- folders

/** Puts a verse (or its whole chapter) into any folders, or makes a new folder for it. */
@Composable
fun FolderDialog(state: AppState, key: Int, onDismiss: () -> Unit) {
    val ref = Ref.fromKey(key)
    var wholeChapter by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val data = loadOnIo(state.userRevision, wholeChapter) {
        val inFolders = state.user.favoritesOf(if (wholeChapter) Ref.key(ref.book, ref.chapter, 0) else key).filter { it.wholeChapter == wholeChapter }.map { it.folder }.toSet()
        state.user.folders() to inFolders
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (wholeChapter) "${state.bible.book(ref.book).name} ${ref.chapter}" else ref.label()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { wholeChapter = !wholeChapter }) {
                    Checkbox(checked = wholeChapter, onCheckedChange = { wholeChapter = it })
                    Text("The whole chapter")
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                data?.let { (folders, inFolders) ->
                    for (f in folders) {
                        val on = f.id in inFolders
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                            val k = if (wholeChapter) Ref.key(ref.book, ref.chapter, 0) else key
                            if (on) state.user.favoritesOf(k).filter { it.folder == f.id && it.wholeChapter == wholeChapter }.forEach { state.user.removeFavorite(it.id) }
                            else state.user.addFavorite(f.id, k, state.version, wholeChapter = wholeChapter)
                            state.touched()
                        }) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Text("${f.name}  (${f.count})")
                        }
                    }
                }
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("New folder") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) {
                            val id = state.user.addFolder(newName)
                            state.user.addFavorite(id, if (wholeChapter) Ref.key(ref.book, ref.chapter, 0) else key, state.version, wholeChapter = wholeChapter)
                            newName = ""; state.touched()
                        }
                    }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

// ---------------------------------------------------------------------------------- tags

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagDialog(state: AppState, key: Int, onDismiss: () -> Unit) {
    var newTag by remember { mutableStateOf("") }
    val data = loadOnIo(state.userRevision, key) { state.user.tags() to state.user.tagsOf(key) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags · ${Ref.fromKey(key).shortLabel()}") },
        text = {
            Column {
                data?.let { (all, mine) ->
                    if (all.isEmpty()) Text("No tags yet. Type one below.", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in all) FilterChip(selected = t.id in mine, onClick = { state.user.setTagged(key, t.id, t.id !in mine); state.touched() }, label = { Text(t.name) })
                    }
                }
                OutlinedTextField(value = newTag, onValueChange = { newTag = it }, singleLine = true, label = { Text("New tag") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newTag.isNotBlank()) { state.user.setTagged(key, state.user.tagId(newTag), true); newTag = ""; state.touched() }
                    }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun TagVersesScreen(state: AppState, tag: Long, name: String) {
    var confirmDelete by remember { mutableStateOf(false) }
    val items = loadOnIo(state.userRevision, tag) { state.user.taggedKeys(tag).map { it to state.bible.previewText(Ref.fromKey(it), state.version) } }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete the tag “$name”?") },
        text = { Text("The verses stay; only the tag is removed from them.") },
        confirmButton = { TextButton(onClick = { state.user.deleteTag(tag); state.touched(); confirmDelete = false; state.back() }) { Text("Delete tag") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
    OverlayScaffold(title = "Tag · $name", onBack = { state.back() }, actions = {
        SpeakButton(state, "tag:$tag", "Tag · $name") { versesSpeech(state, state.user.taggedKeys(tag)) }
        TextButton(onClick = { confirmDelete = true }) { Text("Delete tag") }
    }) { padding ->
        when {
            items == null -> Loading(Modifier.padding(padding))
            items.isEmpty() -> Empty("No verses carry this tag.", Modifier.padding(padding))
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(items) { (k, preview) ->
                    Row(Modifier.fillMaxWidth().clickable { state.goTo(Ref.fromKey(k)) }.padding(start = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Ref.fromKey(k).label(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                        }
                        IconButton(onClick = { state.user.setTagged(k, tag, false); state.touched() }) { Icon(SbIcons.Close, contentDescription = "Remove tag from this verse") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- journal

/** Writing with references in it: each recognised reference ("John 3:16", "Rom 8:28-30") opens its verse. */
fun linkRefs(text: String, linkStyle: SpanStyle, onRef: (Ref) -> Unit): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("""((?:[1-3]\s?)?[A-Za-z]{2,}\.?(?:\s(?:of\s)?Songs?)?)\s(\d{1,3}):(\d{1,3})(?:\s?[-–]\s?(\d{1,3}))?""")
    var last = 0
    for (m in pattern.findAll(text)) {
        val parsed = RefParser.parse(m.value)
        if (parsed !is RefParse.One) continue
        append(text.substring(last, m.range.first))
        withLink(LinkAnnotation.Clickable(m.value, TextLinkStyles(style = linkStyle)) { onRef(parsed.ref) }) { append(m.value) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun JournalScreen(state: AppState, id: Long) {
    val entry = remember(id) { state.user.journalEntry(id) }
    var title by remember(id) { mutableStateOf(entry?.title.orEmpty()) }
    var body by remember(id) { mutableStateOf(entry?.body.orEmpty()) }
    var editing by remember(id) { mutableStateOf(entry?.body.isNullOrBlank()) }
    val currentTitle by rememberUpdatedState(title)
    val currentBody by rememberUpdatedState(body)
    var deleted by remember { mutableStateOf(false) }
    DisposableEffect(id) { onDispose { if (!deleted) { state.user.saveJournal(id, currentTitle, currentBody); state.touched() } } }
    OverlayScaffold(
        title = title.ifBlank { "Journal entry" },
        onBack = { state.back() },
        actions = {
            SpeakButton(state, "journal:$id", title.ifBlank { "Journal entry" }) { listOf(Speech(currentTitle.ifBlank { "Journal entry" } + ".", label = "Title")) + paragraphs(currentBody) }
            TextButton(onClick = { editing = !editing; if (!editing) { state.user.saveJournal(id, title, body); state.touched() } }) { Text(if (editing) "Done" else "Edit") }
            TextButton(onClick = { state.user.saveJournal(id, title, body); state.user.setJournalDeleted(id, true); deleted = true; state.touched(); state.back() }) { Text("Delete") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (editing) {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Entry") }, modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp))
                Text("Write references like John 3:16 or Rom 8:28-30; in reading view they open the verse. Every saved version is kept.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
                    entry?.let { Text("Started ${dateFormat.format(Date(it.created))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(
                        linkRefs(body, SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)) { state.goTo(it) },
                        style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- memory cards

@Composable
fun MemoryScreen(state: AppState) {
    var tab by remember { mutableIntStateOf(0) }
    OverlayScaffold(title = "Memory cards", onBack = { state.back() }, actions = {
        if (tab == 1) SpeakButton(state, "cards", "Memory cards") { versesSpeech(state, state.user.cards().map { it.key }) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Review") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("All cards") })
            }
            if (tab == 0) Review(state) else AllCards(state)
        }
    }
}

@Composable
private fun Review(state: AppState) {
    // The cards due when the review opens; "Again" puts a card back at the end.
    val queue = remember { mutableStateListOf<Card>().apply { addAll(state.user.dueCards()) } }
    var shown by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    val card = queue.firstOrNull()
    if (card == null) {
        val next = remember(done) { state.user.nextDue() }
        Empty(
            (if (done > 0) "Done: $done reviewed.\n\n" else "") + when {
                next == null -> "No memory cards yet. Open a verse, tap ⋮, then “Add to memory cards”."
                else -> "Nothing is due. The next card is due ${dateFormat.format(Date(next))}."
            },
        )
        return
    }
    val ref = Ref.fromKey(card.key).let { if (card.keyEnd > card.key) it.copy(verseEnd = card.keyEnd % 1000) else it }
    val text = loadOnIo(card.id, state.version) { state.bible.previewText(ref, state.version) }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${queue.size} due", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ref.label(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 24.dp))
        if (!shown) {
            Text("Say the verse from memory, then check yourself.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Button(onClick = { shown = true }, modifier = Modifier.padding(top = 24.dp)) { Text("Show the verse") }
        } else {
            Text(text.orEmpty(), style = readingStyle(state.bible.version(state.version), baseSize(state.textScale)), textAlign = TextAlign.Center)
            Text(state.version, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = {
                state.speaker.read("card:${card.id}") { Batch(ref.label(), listOf(Speech(ref.spoken() + ". " + state.bible.previewText(ref, state.version), langOf(state.bible.version(state.version)), label = ref.label()))) }
            }) { Text("Hear it") }
            Row(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = {
                    state.user.cardAgain(card); queue.removeAt(0); queue.add(card.copy(intervalDays = 0)); shown = false
                }) { Text("Again") }
                Button(onClick = {
                    state.user.cardGotIt(card); queue.removeAt(0); done++; shown = false
                }) { Text("Got it") }
            }
            Text("Got it waits 1, 2, 4, 8… days before showing it again. Again keeps it in this review.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun AllCards(state: AppState) {
    val cards = loadOnIo(state.userRevision) { state.user.cards().map { it to state.bible.previewText(Ref.fromKey(it.key), state.version) } }
    when {
        cards == null -> Loading()
        cards.isEmpty() -> Empty("No memory cards yet. Open a verse, tap ⋮, then “Add to memory cards”.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(cards) { (c, preview) ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { state.goTo(Ref.fromKey(c.key)) }) {
                        Text(Ref.fromKey(c.key).label(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text("Next: ${dateFormat.format(Date(c.due))}  ·  reviewed ${c.reps}×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { state.user.removeCard(c.id); state.touched() }) { Icon(SbIcons.Close, contentDescription = "Remove card") }
                }
                HorizontalDivider()
            }
        }
    }
}

// ---------------------------------------------------------------------------------- prayer list

@Composable
fun PrayerScreen(state: AppState) {
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Prayer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var answering by remember { mutableStateOf<Prayer?>(null) }
    if (adding || editing != null) PrayerDialog(state, editing) { adding = false; editing = null }
    answering?.let { p -> AnswerDialog(state, p) { answering = null } }
    OverlayScaffold(title = "Prayer list", onBack = { state.back() }, actions = {
        SpeakButton(state, "prayers:$tab", if (tab == 0) "Prayer list" else "Answered prayers") {
            state.user.prayers(answered = tab == 1).map { p ->
                Speech(listOf(p.title, p.details, if (p.key != 0) Ref.fromKey(p.key).spoken() + ". " + state.bible.previewText(Ref.fromKey(p.key), state.version) else "",
                    if (p.answer.isNotBlank()) "Answered: " + p.answer else "").filter { it.isNotBlank() }.joinToString(". "), label = p.title)
            }
        }
        IconButton(onClick = { adding = true }) { Icon(SbIcons.Add, contentDescription = "Add a prayer") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Praying") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Answered") })
            }
            val list = loadOnIo(state.userRevision, tab) { state.user.prayers(answered = tab == 1) }
            when {
                list == null -> Loading()
                list.isEmpty() -> Empty(if (tab == 0) "Nothing on the list. Tap + to add a prayer." else "Answered prayers appear here.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list) { p ->
                        Column(Modifier.fillMaxWidth().clickable { editing = p }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleMedium)
                            if (p.details.isNotBlank()) Text(p.details, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                            if (p.key != 0) Text(Ref.fromKey(p.key).label() + "  →", color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { state.goTo(Ref.fromKey(p.key)) }.padding(vertical = 4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (p.answered > 0) "Answered ${dateFormat.format(Date(p.answered))}" else "Since ${dateFormat.format(Date(p.created))}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                                )
                                if (p.answered == 0L) TextButton(onClick = { answering = p }) { Text("Answered…") }
                                else TextButton(onClick = { state.user.reopenPrayer(p.id); state.touched() }) { Text("Pray again") }
                            }
                            if (p.answer.isNotBlank()) Text(p.answer, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PrayerDialog(state: AppState, prayer: Prayer?, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(prayer?.title.orEmpty()) }
    var details by remember { mutableStateOf(prayer?.details.orEmpty()) }
    var refText by remember { mutableStateOf(prayer?.key?.takeIf { it != 0 }?.let { Ref.fromKey(it).shortLabel() }.orEmpty()) }
    val parsed = RefParser.parse(refText)
    val refOk = refText.isBlank() || parsed is RefParse.One
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (prayer == null) "New prayer" else "Prayer") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("For") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = details, onValueChange = { details = it }, label = { Text("Details (optional)") }, modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp))
                OutlinedTextField(value = refText, onValueChange = { refText = it }, singleLine = true, label = { Text("A verse to pray (optional)") }, isError = !refOk,
                    placeholder = { Text("phil 4 6") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (!refOk) Text("That doesn't read as one verse.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && refOk, onClick = {
                val key = (parsed as? RefParse.One)?.ref?.let { Ref.key(it.book, it.chapter, it.verse.coerceAtLeast(1)) } ?: 0
                state.user.savePrayer(prayer?.id, title, details, key); state.touched(); onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (prayer != null) TextButton(onClick = { state.user.deletePrayer(prayer.id); state.touched(); onDismiss() }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun AnswerDialog(state: AppState, prayer: Prayer, onDismiss: () -> Unit) {
    var answer by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Answered: ${prayer.title}") },
        text = {
            OutlinedTextField(value = answer, onValueChange = { answer = it }, label = { Text("How it was answered (optional)") }, modifier = Modifier.fillMaxWidth().height(140.dp))
        },
        confirmButton = { TextButton(onClick = { state.user.answerPrayer(prayer.id, answer); state.touched(); onDismiss() }) { Text("Mark answered") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Writing split into paragraphs, so Next and Back move a paragraph at a time. */
fun paragraphs(text: String): List<Speech> =
    text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, p -> Speech(p, label = "Paragraph ${i + 1}") }
