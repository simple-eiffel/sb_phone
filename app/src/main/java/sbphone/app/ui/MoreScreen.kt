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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row_("Settings", "Text size, theme, parallel versions") { state.open(Overlay.Settings) }
        Row_("Credits and licenses", "Every text and dataset in this app, with its license") { state.open(Overlay.Credits) }
        Row_("About", "What this app does and does not do") { state.open(Overlay.About) }
        Row_("Export my notes and marks", "Save bookmarks, highlights, notes and history to a file") { exporter.launch("sb-phone-notes-${LocalDate.now()}.json") }
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
    OverlayScaffold(title = "Credits and licenses", onBack = { state.back() }) { padding ->
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
    OverlayScaffold(title = "About", onBack = { state.back() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("A free Bible-study app that checks what is said about the Bible against the Bible itself.", style = MaterialTheme.typography.titleMedium)
            val points = listOf(
                "Every fact shows its source." to "Each word tag, dictionary entry and cross-reference names where it came from and its license.",
                "No network." to "This app has no permission to use the internet. It cannot send anything anywhere.",
                "No ads, accounts or tracking." to "Nothing to sign in to, nothing to buy, nothing counted.",
                "No AI." to "Everything shown was built ahead of time from sourced data.",
                "Your notes stay yours." to "They live only on this phone. Export them to a file whenever you like.",
                "Public texts only." to "Only texts whose licenses allow sharing are included, and each is credited.",
            )
            for ((head, body) in points) {
                Text(head, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            Text("Verse numbers follow the English (KJV) numbering. Where the Hebrew numbering differs, the Hebrew number is shown in brackets.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
        }
    }
}
