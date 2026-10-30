package app.woom

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: WoomViewModel by viewModels {
        viewModelFactory { initializer { WoomViewModel(Store(File(filesDir, "woom.json")), Discovery(application), AndroidBluetooth(application)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WoomTheme {
                // null = main screen, "settings" = device settings, otherwise the id of the station being edited.
                var screen by rememberSaveable { mutableStateOf<String?>(null) }
                var draft by rememberSaveable { mutableStateOf<String?>(null) }
                BackHandler(enabled = screen != null) { screen = null }
                when (val s = screen) {
                    null -> MainScreen(
                        vm,
                        onSettings = { screen = SETTINGS },
                        onEditStation = { screen = it },
                        onAddStation = { val st = vm.newStation(); draft = st.id; screen = st.id },
                    )
                    SETTINGS -> SettingsScreen(vm, onBack = { screen = null })
                    else -> key(s) {
                        val station = vm.data.stations.firstOrNull { it.id == s }
                            ?: remember { vm.newStation().copy(id = draft ?: s) }
                        StationScreen(vm, station, isNew = vm.data.stations.none { it.id == s }, onBack = { screen = null })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        vm.onBackground()
    }

    private companion object {
        const val SETTINGS = "settings"
    }
}

@Composable
fun WoomTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFFFB86B), secondary = Color(0xFFF2E8DC))
        else -> lightColorScheme(primary = Color(0xFF8A4B00), secondary = Color(0xFF1E2A44))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
