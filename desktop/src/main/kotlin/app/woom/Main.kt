package app.woom

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.application
import dev.nucleusframework.composenativetray.tray.api.Tray
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val WINDOW_WIDTH = 400

/** Height of the settings and station editor; the main list sizes the window to its content. */
private const val FORM_HEIGHT = 640
private const val MIN_HEIGHT = 200

/** Port on localhost that a second start uses to bring up the window of the running instance. */
private const val INSTANCE_PORT = 47853

/** Visibility of the popup window; changed by the tray icon and a second start. */
private object Popup {
    var visible by mutableStateOf(false)
    var hiddenAt = 0L

    /** Height (dp) the main list needs, or null if it doesn't fit and the window should be as tall as possible. */
    var contentHeight by mutableStateOf<Int?>(null)

    fun show() {
        visible = true
    }

    fun hide() {
        if (visible) hiddenAt = System.currentTimeMillis()
        visible = false
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    Log.log("start: args=[${args.joinToString(" ")}] os=${System.getProperty("os.name")} java=${System.getProperty("java.version")}")
    if (!acquireSingleInstance()) {
        Log.log("start: another instance already running — bringing it to the front and exiting")
        exitProcess(0)
    }
    val hidden = Autostart.HIDDEN in args
    // Immediate feedback: the first cold start on Windows can take a while (virus scan of the unsigned
    // program, one-time unpacking of the graphics library). The splash appears at once, long before
    // Compose is ready, and is closed as soon as the real window shows (see below).
    if (!hidden) Splash.show()
    val vm = WoomViewModel(Store(File(configDir(), "woom.json")), NetworkScanner())
    val computer = ComputerAudio(vm)
    if (!hidden) Popup.show()
    // Keeps the start-at-login entry pointing at this program, e.g. after an update renamed or moved it.
    if (Autostart.available && Autostart.isEnabled()) Autostart.set(true)
    val icon = loadIcon().toComposeImageBitmap()

    application {
        // Click on the tray icon opens or closes the window; its menu offers "Open" and "Quit".
        val openText = stringResource(Res.string.tray_open)
        val quitText = stringResource(Res.string.tray_quit)
        var trayReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            awaitTrayService()
            trayReady = true
        }
        if (trayReady) {
            Tray(
                icon = BitmapPainter(icon),
                tooltip = "Woom",
                primaryAction = {
                    // The click on the icon first deactivates (and so hides) an open window; don't reopen it then.
                    if (Popup.visible || System.currentTimeMillis() - Popup.hiddenAt < 400) Popup.hide() else Popup.show()
                },
            ) {
                Item(label = openText) { Popup.show() }
                Divider()
                Item(label = quitText) { exitApplication() }
            }
        }
        LaunchedEffect(Popup.visible) {
            if (Popup.visible) {
                vm.onForeground()
            } else {
                vm.onBackground()
            }
        }
        var screen by remember { mutableStateOf<String?>(null) }
        DialogWindow(
            visible = Popup.visible,
            onPreviewKeyEvent = { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                    if (screen != null) screen = null else Popup.hide()
                    true
                } else false
            },
            create = {
                // A dialog of an owner that is never shown gets no taskbar entry; it only belongs to the tray icon.
                ComposeDialog(JFrame("Woom").apply { iconImage = loadIcon() }).apply {
                    type = java.awt.Window.Type.UTILITY
                    title = "Woom"
                    setIconImage(loadIcon())
                    isUndecorated = true
                    isResizable = false
                    isAlwaysOnTop = true
                    defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                    isModal = false
                    bounds = popupBounds(FORM_HEIGHT)
                }
            },
            dispose = { it.dispose(); it.owner.dispose() },
        ) {
            // The real window is up now — close the start-up splash.
            LaunchedEffect(Unit) {
                Log.log("start: main window shown")
                Splash.hide()
            }
            // Behaves like a tray flyout: clicking anywhere else closes it.
            DisposableEffect(window) {
                val listener = object : WindowAdapter() {
                    override fun windowDeactivated(e: WindowEvent) = Popup.hide()
                }
                window.addWindowListener(listener)
                onDispose { window.removeWindowListener(listener) }
            }
            val height = if (screen == null) Popup.contentHeight ?: Int.MAX_VALUE else FORM_HEIGHT
            LaunchedEffect(height) { if (Popup.visible) window.bounds = popupBounds(height) }
            LaunchedEffect(Popup.visible) {
                if (Popup.visible) {
                    // Set after showing: the window manager may place a newly mapped window on its own.
                    window.bounds = popupBounds(height)
                    window.toFront()
                    skipTaskbar(window)
                    // Again once the window manager has surely mapped the window; before that the request is lost.
                    delay(300)
                    skipTaskbar(window)
                }
            }
            WoomTheme {
                Surface(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    App(vm, computer, screen, onScreen = { screen = it })
                }
            }
        }
    }
}

@Composable
private fun App(vm: WoomViewModel, computer: ComputerAudio, screen: String?, onScreen: (String?) -> Unit) {
    // null = main screen, SETTINGS = device settings, otherwise the id of the station being edited.
    var draft by remember { mutableStateOf<String?>(null) }
    when (screen) {
        null -> MainScreen(
            vm,
            onSettings = { onScreen(SETTINGS) },
            onEditStation = { onScreen(it) },
            onAddStation = { val st = vm.newStation(); draft = st.id; onScreen(st.id) },
            onContentHeight = { Popup.contentHeight = it },
            computer = computer,
        )
        SETTINGS -> SettingsScreen(vm, onBack = { onScreen(null) })
        else -> key(screen) {
            val station = vm.data.stations.firstOrNull { it.id == screen }
                ?: remember { vm.newStation().copy(id = draft ?: screen) }
            StationScreen(vm, station, isNew = vm.data.stations.none { it.id == screen }, onBack = { onScreen(null) })
        }
    }
}

private const val SETTINGS = "settings"

@Composable
fun WoomTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFFFB86B), secondary = Color(0xFFF2E8DC))
    } else {
        lightColorScheme(primary = Color(0xFF8A4B00), secondary = Color(0xFF1E2A44))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun loadIcon(): BufferedImage = ImageIO.read(Popup::class.java.getResource("/woom.png"))

/**
 * Top or bottom right corner of the main screen's usable area, depending on where the panel is:
 * that is where the tray usually sits (the tray icon doesn't report its own position).
 * The window stays attached to the panel when its [height] changes, and never gets taller than the screen.
 */
private fun popupBounds(height: Int): Rectangle {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val b = config.bounds
    val area = Rectangle(b.x + insets.left, b.y + insets.top, b.width - insets.left - insets.right, b.height - insets.top - insets.bottom)
    val margin = 8
    val h = height.coerceIn(MIN_HEIGHT, area.height - 2 * margin)
    val x = area.x + area.width - WINDOW_WIDTH - margin
    val y = if (insets.top > insets.bottom) area.y + margin else area.y + area.height - h - margin
    return Rectangle(x, y, WINDOW_WIDTH, h)
}

/** Allows only one running Woom; a second start opens the window of the first one and quits. */
private fun acquireSingleInstance(): Boolean {
    val loopback = InetAddress.getLoopbackAddress()
    return try {
        val server = ServerSocket(INSTANCE_PORT, 5, loopback)
        thread(isDaemon = true, name = "woom-instance") {
            while (true) {
                runCatching { server.accept().use { it.getInputStream().read() } }
                SwingUtilities.invokeLater { Popup.show() }
            }
        }
        true
    } catch (_: BindException) {
        runCatching { Socket(loopback, INSTANCE_PORT).use { it.getOutputStream().write(1) } }
        false
    }
}
