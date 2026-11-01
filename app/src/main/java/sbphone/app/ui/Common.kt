package sbphone.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import sbphone.app.data.Version

/** Loads a value off the main thread. Null while loading. */
@Composable
fun <T> loadOnIo(vararg keys: Any?, block: () -> T): T? {
    val value by produceState<T?>(initialValue = null, *keys) {
        value = null
        value = withContext(Dispatchers.IO) { block() }
    }
    return value
}

/** The loading state appears only after 150 ms, so quick loads never flicker. */
@Composable
fun Loading(modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(150); show = true }
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        if (show) CircularProgressIndicator()
    }
}

@Composable
fun Empty(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(SbIcons.Back, contentDescription = "Back") } },
                actions = actions,
            )
        },
        content = content,
    )
}

/** Reading style for a version's text: Hebrew and Aramaic right to left in Ezra SIL, a little larger. */
fun readingStyle(version: Version, base: TextUnit): TextStyle =
    if (version.rtl) TextStyle(fontFamily = HebrewFont, fontSize = base * 1.3f, lineHeight = base * 2.0f, textDirection = TextDirection.Rtl, textAlign = TextAlign.Right)
    else TextStyle(fontFamily = FontFamily.Serif, fontSize = base, lineHeight = base * 1.5f)

fun baseSize(scale: Float): TextUnit = (18f * scale).sp
