package sbphone.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun SbApp(state: AppState) {
    BackHandler(enabled = state.overlays.isNotEmpty() || state.tab != Tab.Read || state.focus) {
        when {
            state.back() -> Unit
            state.focus -> state.focus = false
            else -> state.tab = Tab.Read
        }
    }
    val focusing = state.focus && state.tab == Tab.Read && state.overlays.isEmpty()
    // Focus reading also hides the phone's own status and navigation bars (a swipe from the edge shows them briefly).
    val view = LocalView.current
    val activity = LocalActivity.current
    LaunchedEffect(focusing) {
        val window = activity?.window ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(window, view)
        if (focusing) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val overlay = state.overlays.lastOrNull()
    // While reading aloud the screen stays on (the phone's power button still turns it off; the reading goes on).
    val speaking = state.speaker.active
    DisposableEffect(speaking) {
        view.keepScreenOn = speaking
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxWidth().then(if (speaking) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
        Scaffold(
            bottomBar = {
                if (!focusing) NavigationBar {
                    tabItem(state, Tab.Read, "Read", SbIcons.Book)
                    tabItem(state, Tab.Search, "Search", SbIcons.Search)
                    tabItem(state, Tab.Library, "Library", SbIcons.Bookmark)
                    tabItem(state, Tab.More, "More", SbIcons.More)
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (state.tab) {
                    Tab.Read -> ReaderScreen(state)
                    Tab.Search -> SearchScreen(state)
                    Tab.Library -> LibraryScreen(state)
                    Tab.More -> MoreScreen(state)
                }
            }
        }
        if (overlay != null) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (overlay) {
                    Overlay.Picker -> PickerScreen(state)
                    is Overlay.Word -> WordScreen(state, overlay.lemma)
                    is Overlay.English -> EnglishScreen(state, overlay.word)
                    is Overlay.NoteEdit -> NoteEditScreen(state, overlay.key)
                    is Overlay.Journal -> JournalScreen(state, overlay.id)
                    is Overlay.TagVerses -> TagVersesScreen(state, overlay.tag, overlay.name)
                    Overlay.Memory -> MemoryScreen(state)
                    is Overlay.PackHome -> PackHomeScreen(state, overlay.file)
                    Overlay.Timeline -> TimelineScreen(state)
                    is Overlay.TimelineEvent -> TimelineEventScreen(state, overlay.id)
                    is Overlay.Atlas -> AtlasScreen(state, overlay.year, overlay.place)
                    is Overlay.PackDocument -> PackDocumentScreen(state, overlay.file, overlay.id)
                    Overlay.Prayers -> PrayerScreen(state)
                    Overlay.Credits -> CreditsScreen(state)
                    Overlay.Settings -> SettingsScreen(state)
                    Overlay.About -> AboutScreen(state)
                }
            }
        }
    }
        if (speaking) PlayerBar(state, Modifier.navigationBarsPadding())
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.tabItem(state: AppState, tab: Tab, label: String, icon: ImageVector) {
    NavigationBarItem(
        selected = state.tab == tab && state.overlays.isEmpty(),
        onClick = { state.overlays.clear(); state.tab = tab },
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
    )
}
