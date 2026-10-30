package app.woom

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

private val INPUT_LABELS: Map<String, StringResource> = mapOf(
    "line-in" to Res.string.input_line_in,
    "bluetooth" to Res.string.input_bluetooth,
    "udisk" to Res.string.input_usb,
    "optical" to Res.string.input_optical,
    "RCA" to Res.string.input_rca,
    "co-axial" to Res.string.input_coax,
    "FM" to Res.string.input_fm,
    "line-in2" to Res.string.input_line_in2,
    "XLR" to Res.string.input_xlr,
    "HDMI" to Res.string.input_hdmi,
    "cd" to Res.string.input_cd,
    "TFcard" to Res.string.input_sd,
    "PCUSB" to Res.string.input_usb_audio,
    "phono" to Res.string.input_phono,
    "DAB" to Res.string.input_dab,
    "optical2" to Res.string.input_optical2,
    "co-axial2" to Res.string.input_coax2,
    "ARC" to Res.string.input_arc,
    "external_usb" to Res.string.input_usb,
    "auxIn" to Res.string.input_aux,
)

/** Localized input name; unknown switchmode names are shown as they are. */
@Composable
fun inputLabel(mode: String): String = INPUT_LABELS[mode]?.let { stringResource(it) } ?: mode

/** Localized text of a view model message. */
@Composable
fun Msg.text(): String = when (this) {
    is Msg.NoDeviceAt -> stringResource(Res.string.msg_no_device_at, ip)
    is Msg.AlreadyAdded -> stringResource(Res.string.msg_already_added, device)
    is Msg.Added -> stringResource(Res.string.msg_added, device)
    is Msg.Unreachable -> stringResource(Res.string.msg_unreachable, device)
    is Msg.Error -> stringResource(Res.string.msg_error, device, reason)
    is Msg.VolumeFailed -> stringResource(Res.string.msg_volume_failed, device)
    is Msg.PresetSaved -> stringResource(Res.string.msg_preset_saved, device, slot)
    is Msg.PresetFailed -> stringResource(Res.string.msg_preset_failed, device, reason)
    Msg.NoBluetooth -> stringResource(Res.string.msg_no_bluetooth)
    is Msg.PairInSettings -> stringResource(Res.string.msg_pair_in_settings, device)
    is Msg.BluetoothFailed -> stringResource(Res.string.msg_bluetooth_failed, device)
}

val isWindows = System.getProperty("os.name").startsWith("Windows")

/**
 * Linux: waits for the desktop's tray service. At login it can start after Woom, and the tray library
 * registers only once, so Woom would run without an icon. Gives up after a minute (e.g. GNOME has no such service).
 */
suspend fun awaitTrayService() {
    if (isWindows) return
    withContext(Dispatchers.IO) {
        repeat(60) {
            val output = runCatching {
                val p = ProcessBuilder(
                    "gdbus", "call", "--session", "--dest", "org.freedesktop.DBus", "--object-path", "/org/freedesktop/DBus",
                    "--method", "org.freedesktop.DBus.NameHasOwner", "org.kde.StatusNotifierWatcher",
                ).redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readText().also { p.waitFor() }
            }.getOrNull() ?: return@withContext // Without gdbus there is nothing to wait for.
            if ("true" in output) return@withContext
            delay(1000)
        }
    }
}

/** %APPDATA%\Woom on Windows, $XDG_CONFIG_HOME/woom (usually ~/.config/woom) elsewhere. */
fun configDir(): File =
    if (isWindows) File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Woom")
    else File(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"), "woom")

/**
 * Start at login: a Run entry in the Windows registry, an XDG autostart file on Linux.
 * Only available in the installed app, where the path of the launcher is known.
 */
object Autostart {
    /** Set by jpackage (Linux package) or by Woom.exe (Windows). */
    private val launcher: String? = System.getProperty("jpackage.app-path") ?: System.getProperty("woom.launcher")
    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private val desktopFile = File(
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.config"),
        "autostart/woom.desktop",
    )

    val available get() = launcher != null

    fun isEnabled(): Boolean =
        if (isWindows) runCatching { run("reg", "query", RUN_KEY, "/v", "Woom") == 0 }.getOrDefault(false)
        else desktopFile.exists()

    fun set(enabled: Boolean) {
        val exe = launcher ?: return
        if (isWindows) {
            // reg.exe needs the quotes around the path escaped, or it drops them (paths with spaces would break).
            if (enabled) run("reg", "add", RUN_KEY, "/v", "Woom", "/t", "REG_SZ", "/d", "\\\"$exe\\\" $HIDDEN", "/f")
            else run("reg", "delete", RUN_KEY, "/v", "Woom", "/f")
        } else if (enabled) {
            desktopFile.parentFile.mkdirs()
            desktopFile.writeText(
                """
                [Desktop Entry]
                Type=Application
                Name=Woom
                Exec="$exe" $HIDDEN
                Icon=${File(exe).parentFile.parentFile}/lib/Woom.png
                Terminal=false
                X-GNOME-Autostart-enabled=true
                """.trimIndent() + "\n",
            )
        } else {
            desktopFile.delete()
        }
    }

    private fun run(vararg cmd: String): Int =
        ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor()

    /** Command line flag: start in the tray without opening the window. */
    const val HIDDEN = "--hidden"
}
