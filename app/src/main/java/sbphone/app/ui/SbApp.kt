package sbphone.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
    BackHandler(enabled = state.overlays.isNotEmpty() || state.tab != Tab.Read) {
        if (!state.back()) state.tab = Tab.Read
    }
    val overlay = state.overlays.lastOrNull()
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
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
                    Overlay.Credits -> CreditsScreen(state)
                    Overlay.Settings -> SettingsScreen(state)
                    Overlay.About -> AboutScreen(state)
                }
            }
        }
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
