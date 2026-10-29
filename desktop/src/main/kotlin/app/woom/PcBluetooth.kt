package app.woom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The computer's own Bluetooth: its address (the WiiM connects itself to it) and, on Linux, pairing via bluetoothctl.
 * On Windows pairing is left to the Windows settings, since there is no command line tool for it.
 */
object PcBluetooth {
    /** Bluetooth address of this computer (upper case, colons), or null without Bluetooth. */
    suspend fun address(): String? = withContext(Dispatchers.IO) {
        if (isWindows) {
            // The "Bluetooth Personal Area Network" adapter carries the address of the Bluetooth radio.
            run(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-NetAdapter -IncludeHidden | Where-Object InterfaceDescription -like '*Bluetooth*' | Select-Object -First 1).MacAddress",
            )?.trim()?.replace('-', ':')?.uppercase()?.takeIf { MAC.matches(it) }
        } else {
            run("bluetoothctl", "show")?.let { Regex("Controller ([0-9A-F:]{17})").find(it)?.groupValues?.get(1) }
        }
    }

    /** Bluetooth name of this computer, as a WiiM reports its connected source. */
    suspend fun name(): String? = withContext(Dispatchers.IO) {
        if (isWindows) System.getenv("COMPUTERNAME")
        else run("bluetoothctl", "show")?.let { Regex("Alias: (.+)").find(it)?.groupValues?.get(1)?.trim() }
    }

    /** Linux only: whether bluez knows [mac] as paired (the WiiM may still remember a computer that forgot it). */
    suspend fun isPaired(mac: String): Boolean = withContext(Dispatchers.IO) {
        run("bluetoothctl", "info", mac)?.contains("Paired: yes") == true
    }

    /** Linux only: finds [mac] (which must be in pairing mode), pairs and trusts it. */
    suspend fun pair(mac: String): Boolean = withContext(Dispatchers.IO) {
        val scan = ProcessBuilder("bluetoothctl", "--timeout", "30", "scan", "on").redirectInput(NO_INPUT)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            var seen = false
            repeat(25) {
                // Known: "Device <mac> (public)"; unknown: "Device <mac> not available".
                if (!seen && run("bluetoothctl", "info", mac)?.contains("Device $mac (") == true) seen = true
                if (!seen) delay(1000)
            }
            if (!seen) return@withContext false
            val paired = run("bluetoothctl", "pair", mac, timeoutSeconds = 25)
                ?.contains("Pairing successful") == true || isPaired(mac)
            if (paired) run("bluetoothctl", "trust", mac)
            paired
        } finally {
            scan.destroy()
        }
    }

    /** Linux only: connects from the computer's side, in case the WiiM can't. */
    suspend fun connect(mac: String) = withContext(Dispatchers.IO) {
        run("bluetoothctl", "connect", mac, timeoutSeconds = 15)
    }

    /** Windows only: opens the Bluetooth page of the settings, where the user adds the WiiM. */
    fun openSettings() {
        runCatching { ProcessBuilder("cmd", "/c", "start", "", "ms-settings:bluetooth").start() }
    }

    /**
     * Runs a command and returns its output, or null if it can't be run.
     * (bluetoothctl's own --timeout always waits the full time, so the limit is enforced here.
     * Without a terminal bluetoothctl reads further commands from its input, so that is left empty.)
     */
    private fun run(vararg cmd: String, timeoutSeconds: Long = 10): String? = runCatching {
        val p = ProcessBuilder(*cmd).redirectInput(NO_INPUT).redirectErrorStream(true).start()
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) p.destroyForcibly()
        p.inputStream.bufferedReader().readText().replace(ANSI, "")
    }.getOrNull()

    private val NO_INPUT = ProcessBuilder.Redirect.from(java.io.File(if (isWindows) "NUL" else "/dev/null"))
    private val MAC = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
}
