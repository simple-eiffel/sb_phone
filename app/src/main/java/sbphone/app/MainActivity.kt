package sbphone.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import sbphone.app.ui.LocalPointing
import sbphone.app.ui.isDark
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sbphone.app.data.AppDb
import sbphone.app.data.UserDb
import sbphone.app.ui.AppState
import sbphone.app.ui.SbApp
import sbphone.app.ui.SbTheme
import sbphone.app.ui.ThemeChoice

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root() }
    }
}

/** Holds the opened databases across configuration changes (rotation). */
private object Holder { var state: AppState? = null }

@Composable
private fun Root() {
    val context = LocalContext.current.applicationContext
    var state by remember { mutableStateOf(Holder.state) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (state == null) {
            try {
                val s = withContext(Dispatchers.IO) {
                    val bible = AppDb.open(context) { p -> progress = p }
                    AppState(bible, UserDb(context))
                }
                Holder.state = s
                state = s
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
    }
    val s = state
    val activity = LocalActivity.current as? ComponentActivity
    val dark = isDark(s?.theme ?: ThemeChoice.Dark)
    DisposableEffect(dark) {
        val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        onDispose { }
    }
    if (s != null) {
        SbTheme(s.theme) { CompositionLocalProvider(LocalPointing provides s.pointing) { SbApp(s) } }
    } else {
        SbTheme(ThemeChoice.Dark) { Preparing(progress, error) }
    }
}

@Composable
private fun Preparing(progress: Float, error: String?) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (error != null) {
                Text("The Bible database could not be prepared.", style = MaterialTheme.typography.titleMedium)
                Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            } else {
                Text("Preparing the Bible library", style = MaterialTheme.typography.titleMedium)
                Text("This happens once after installing or updating.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
