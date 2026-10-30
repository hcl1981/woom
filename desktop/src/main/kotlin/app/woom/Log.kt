package app.woom

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Small append-only log in the config directory (%APPDATA%\Woom\woom.log on Windows).
 * Helps diagnose start-up and Bluetooth problems on machines we cannot watch directly.
 * Kept tiny: the file is trimmed when it grows past [MAX_BYTES].
 */
object Log {
    private val file = File(configDir(), "woom.log")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private const val MAX_BYTES = 256 * 1024

    @Synchronized
    fun log(message: String) {
        runCatching {
            configDir().mkdirs()
            if (file.length() > MAX_BYTES) file.writeText(file.readText().takeLast(MAX_BYTES / 2))
            file.appendText("${LocalDateTime.now().format(stamp)}  $message\n")
        }
    }

    fun error(message: String, e: Throwable) = log("$message: ${e.javaClass.simpleName}: ${e.message}")
}
