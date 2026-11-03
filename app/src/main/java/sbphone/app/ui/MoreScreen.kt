package sbphone.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import sbphone.app.data.PackDb
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import sbphone.fold.PointingLevel
import sbphone.app.speech.Speech

@Composable
fun MoreScreen(state: AppState) {
    val context = LocalContext.current
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(state.user.exportJson().toByteArray()) }
        }.onSuccess { Toast.makeText(context, "Your notes and marks were saved to the file.", Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            state.user.importJson(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() })
        }.onSuccess { state.touched(); Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    var loadingPack by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<PackDb?>(null) }
    val packLoader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loadingPack = true
        Thread {
            // Copy and check off the main thread; change the app's list of packs on it.
            val result = runCatching { PackDb.load(context, uri) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                loadingPack = false
                result.onSuccess { pack ->
                    // A newer copy of the same pack replaces the old one.
                    state.packs.filter { it.file.name == pack.file.name && it !== pack }.forEach { old -> runCatching { old.close() }; state.packs.remove(old) }
                    state.packs += pack
                }
                Toast.makeText(context, result.fold({ "Loaded: ${it.title}" }, { it.message ?: "That file could not be loaded." }), Toast.LENGTH_LONG).show()
            }
        }.start()
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove “${p.title}” from this phone?") },
            text = { Text("Only the pack file on this phone is removed. You can load it again from the file any time.") },
            confirmButton = { TextButton(onClick = { state.packs.remove(p); PackDb.remove(p); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
    val due = loadOnIo(state.userRevision) { state.user.dueCards().size to state.user.prayers(answered = false).size }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        for (p in state.packs) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Row_(p.title, "Your materials · tap to browse and search") { state.open(Overlay.PackHome(p.file.name)) } }
                TextButton(onClick = { removing = p }, modifier = Modifier.padding(end = 8.dp)) { Text("Remove") }
            }
        }
        Row_(if (loadingPack) "Loading pack…" else "Load pack…", "Open a pack file you downloaded (your own materials). It stays on this phone only.") {
            if (!loadingPack) packLoader.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3", "*/*"))
        }
        Row_("Timeline", "The Bible's world from prehistory to the apostles (a draft)") { state.open(Overlay.Timeline) }
        Row_("Empires atlas", "Who ruled where, year by year, 3400 BC – AD 400") { state.open(Overlay.Atlas(-1000, null)) }
        Row_("Memory cards",due?.first?.let { if (it > 0) "$it due for review" else "Nothing due right now" } ?: "") { state.open(Overlay.Memory) }
        Row_("Prayer list", due?.second?.let { if (it > 0) "$it on the list" else "Add what you are praying for" } ?: "") { state.open(Overlay.Prayers) }
        Row_("Settings", "Text size, theme, Hebrew pointing, parallel versions") { state.open(Overlay.Settings) }
        Row_("Credits and licenses", "Every text and dataset in this app, with its license") { state.open(Overlay.Credits) }
        Row_("About", "What this app does and does not do") { state.open(Overlay.About) }
        Row_("Export my notes and marks", "Save favorites, highlights, notes, journal, tags, cards, prayers and history to a file") { exporter.launch("sb-phone-notes-${LocalDate.now()}.json") }
        Row_("Import notes and marks", "Merge a file saved from this app") { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
    }
}

@Composable
private fun Row_(title: String, detail: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
fun SettingsScreen(state: AppState) {
    OverlayScaffold(title = "Settings", onBack = { state.back() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("Text size", style = MaterialTheme.typography.titleMedium)
            Slider(value = state.textScale, onValueChange = { state.chooseTextScale(it) }, valueRange = 0.8f..2.0f, steps = 11)
            Text("In the beginning God created the heaven and the earth.", style = readingStyle(state.bible.version("KJV"), baseSize(state.textScale)))
            Text("בְּרֵאשִׁ֖ית בָּרָ֣א אֱלֹהִ֑ים", style = readingStyle(state.bible.version("WLC"), baseSize(state.textScale)), modifier = Modifier.fillMaxWidth())

            Text("Theme", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (t in ThemeChoice.entries) {
                    FilterChip(selected = state.theme == t, onClick = { state.chooseTheme(t) }, label = { Text(t.name) }, modifier = Modifier.padding(end = 8.dp))
                }
            }

            Text("Hebrew pointing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for ((level, label) in listOf(PointingLevel.Full to "Full", PointingLevel.Vowels to "Vowels", PointingLevel.Consonants to "Consonants")) {
                    FilterChip(selected = state.pointing == level, onClick = { state.choosePointing(level) }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                }
            }
            Text(state.hebrew("בְּרֵאשִׁ֖ית בָּרָ֣א אֱלֹהִ֑ים"), style = readingStyle(state.bible.version("WLC"), baseSize(state.textScale)), modifier = Modifier.fillMaxWidth())
            Text("Full shows the accents (cantillation) too; Vowels drops them; Consonants shows the letters alone.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            ReadAloudSettings(state)

            Text("Parallel versions (up to three)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            Text("Shown under the main version when the columns button in the reader is on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (v in state.bible.versions) {
                val on = v.code in state.parallel
                Row(Modifier.fillMaxWidth().clickable {
                    state.chooseParallel(if (on) state.parallel - v.code else (state.parallel + v.code).takeLast(3))
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) "☑" else "☐", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(v.code, style = MaterialTheme.typography.titleSmall)
                        Text(v.name + " · " + v.coverage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun CreditsScreen(state: AppState) {
    val context = LocalContext.current
    val fontLicense = loadOnIo(Unit) { context.assets.open("licenses/EzraSIL-license.txt").use { it.readBytes().decodeToString() } }
    OverlayScaffold(title = "Credits and licenses", onBack = { state.back() }, actions = {
        SpeakButton(state, "credits", "Credits and licenses") {
            state.bible.sources.map { s -> Speech("${s.name}. ${s.license}. ${s.credit}", label = s.name) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text("Every text and dataset in this app is listed here with its license. Texts under share-alike licenses may be shared on the same terms.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
            }
            items(state.bible.sources) { s ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall)
                    Text("${s.kind}  ·  ${s.license}${if (s.shareAlike) "  ·  share-alike" else ""}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(s.credit, style = MaterialTheme.typography.bodySmall)
                    if (s.url.isNotBlank()) Text(s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            item {
                Column(Modifier.padding(20.dp)) {
                    Text("Hebrew font: Ezra SIL", style = MaterialTheme.typography.titleSmall)
                    Text(fontLicense.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    Text("Icons: Material Design icon shapes, Apache License 2.0.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    Text("Data built ${state.bible.meta("built_on").orEmpty()}. ${state.bible.meta("vmap_basis").orEmpty()}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
fun AboutScreen(state: AppState) {
    val points = aboutPoints
    OverlayScaffold(title = "About", onBack = { state.back() }, actions = {
        SpeakButton(state, "about", "About") { listOf(Speech(aboutLead, label = "About")) + points.map { (h, b) -> Speech("$h $b", label = h) } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(aboutLead, style = MaterialTheme.typography.titleMedium)
            for ((head, body) in points) {
                Text(head, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            Text("Verse numbers follow the English (KJV) numbering. Where the Hebrew numbering differs, the Hebrew number is shown in brackets.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        }
    }
}

private const val aboutLead = "A free Bible-study app that checks what is said about the Bible against the Bible itself."

private val aboutPoints = listOf(
                "Every fact shows its source." to "Each word tag, dictionary entry and cross-reference names where it came from and its license.",
                "No network." to "This app has no permission to use the internet. It cannot send anything anywhere.",
                "No ads, accounts or tracking." to "Nothing to sign in to, nothing to buy, nothing counted.",
                "No AI." to "Everything shown was built ahead of time from sourced data.",
                "Your notes stay yours." to "They live only on this phone. Export them to a file whenever you like.",
                "Public texts only." to "Only texts whose licenses allow sharing are included, and each is credited.",
                "Read aloud." to "Uses the phone's own text-to-speech voices, and only voices installed on the phone. The words go to the phone's speech engine and nowhere else.",
            )

@Composable
private fun ReadAloudSettings(state: AppState) {
    val context = LocalContext.current
    val sp = state.speaker
    androidx.compose.runtime.LaunchedEffect(Unit) { sp.checkVoices() }
    Text("Read aloud", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
    Text("Every screen has a speaker button that reads that screen. In the reader it reads the chapter and goes on into the next.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Speed: " + "%.2f".format(state.speechRate).trimEnd('0').trimEnd('.') + "×", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
    Slider(value = state.speechRate, onValueChange = { state.chooseSpeechRate((it * 20).toInt() / 20f) }, valueRange = 0.5f..2.0f, steps = 29)
    Row(Modifier.fillMaxWidth().clickable { state.chooseSayVerseNumbers(!state.sayVerseNumbers) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Switch(checked = state.sayVerseNumbers, onCheckedChange = { state.chooseSayVerseNumbers(it) })
        Text("Say verse numbers", modifier = Modifier.padding(start = 12.dp))
    }
    Row(Modifier.fillMaxWidth().clickable { state.chooseReadOn(!state.readOn) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Switch(checked = state.readOn, onCheckedChange = { state.chooseReadOn(it) })
        Text("Go on into the next chapter", modifier = Modifier.padding(start = 12.dp))
    }
    Text("Voices on this phone", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(sp.voiceReport ?: "Checking…", style = MaterialTheme.typography.bodySmall)
    Text("Hebrew and Greek are read with modern Israeli and Greek voices: a help for hearing the words, not a reconstruction of how they were said. " +
        "To add a voice, open the phone's speech settings, choose an engine (Google's has Hebrew and Greek), then its settings → Install voice data.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    Row {
        TextButton(onClick = {
            sp.read("test") {
                sbphone.app.speech.Batch("Testing the voices", listOf(
                    Speech("In the beginning God created the heaven and the earth.", label = "English"),
                    Speech("בְּרֵאשִׁית בָּרָא אֱלֹהִים אֵת הַשָּׁמַיִם וְאֵת הָאָרֶץ", label = "Hebrew"),
                    Speech("Ἐν ἀρχῇ ἦν ὁ λόγος", label = "Greek"),
                    Speech("In principio erat Verbum", sbphone.app.speech.Lang.La, label = "Latin"),
                ))
            }
        }) { Text("Test the voices") }
        TextButton(onClick = {
            runCatching { context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(context, "Open the phone's Settings and search for “text-to-speech”.", Toast.LENGTH_LONG).show() }
        }) { Text("Phone's speech settings") }
    }
}
