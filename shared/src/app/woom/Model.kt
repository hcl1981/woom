package app.woom

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A WiiM device the user added. [uuid] is the 24-hex `uuid` from getStatusEx. */
@Serializable
data class Device(
    val uuid: String,
    val name: String,
    val ip: String,
    val abbr: String,
    val project: String = "",
    /** switchmode names of the physical inputs, decoded from plm_support. */
    val inputs: List<String> = emptyList(),
    val hiddenInputs: List<String> = emptyList(),
    val presetCount: Int = 6,
)

@Serializable
data class Station(
    val id: String,
    val name: String,
    val url: String,
    val image: String = "",
    /** uuids of the devices that get a button in this station's bar (max 5). */
    val devices: List<String> = emptyList(),
)

@Serializable
data class AppData(
    val devices: List<Device> = emptyList(),
    val stations: List<Station> = emptyList(),
)

const val MAX_STATION_DEVICES = 5

/** Saves the app data as JSON in [file]; the platform decides where it lives. */
class Store(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): AppData =
        runCatching { json.decodeFromString<AppData>(file.readText()) }.getOrDefault(AppData())

    fun save(data: AppData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(AppData.serializer(), data))
        // Replace the old file atomically. File.renameTo does NOT overwrite an existing file on
        // Windows (it just returns false), which silently dropped every change after the first save —
        // added devices, logos, order. Files.move with REPLACE_EXISTING does overwrite.
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            // ATOMIC_MOVE is not supported on every filesystem; fall back to a plain replace.
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}

/** A physical input: plm_support bit → switchmode name; playModes are getPlayerStatus `mode` values. */
data class InputDef(val bit: Int, val mode: String, val playModes: Set<Int> = emptySet())

val INPUT_DEFS = listOf(
    InputDef(1, "line-in", setOf(40)),
    InputDef(2, "bluetooth", setOf(41)),
    InputDef(3, "udisk", setOf(51)),
    InputDef(4, "optical", setOf(43)),
    InputDef(5, "RCA", setOf(44)),
    InputDef(6, "co-axial", setOf(45)),
    InputDef(7, "FM"),
    InputDef(8, "line-in2", setOf(47)),
    InputDef(9, "XLR"),
    InputDef(10, "HDMI", setOf(49)),
    InputDef(11, "cd"),
    InputDef(13, "TFcard"),
    InputDef(15, "PCUSB"),
    InputDef(16, "phono"),
    InputDef(17, "DAB"),
    InputDef(18, "optical2"),
    InputDef(19, "co-axial2"),
    InputDef(22, "ARC"),
    // Only reported by getAudioInputCapbility, no plm_support bit.
    InputDef(-1, "external_usb"),
    InputDef(-1, "auxIn"),
)

/** plm_support is only meaningful when capability bit 19 is set (see docs/03). */
fun decodeInputs(plmSupport: Long, capability: Long): List<String> {
    if (capability and (1L shl 19) == 0L) return emptyList()
    return INPUT_DEFS.filter { it.bit >= 0 && plmSupport and (1L shl it.bit) != 0L }.map { it.mode }
}

/** "WiiM Mini (Arbeitszimmer)" → "MI"; avoids abbreviations already in use. */
fun defaultAbbr(name: String, taken: Collection<String>): String {
    val base = name.removePrefix("WiiM").trim().ifEmpty { name }
    val letters = base.filter { it.isLetterOrDigit() }.uppercase()
    val candidates = buildList {
        if (letters.length >= 2) add(letters.take(2))
        for (i in 1 until letters.length) add("${letters[0]}${letters[i]}")
        for (c in 'A'..'Z') add("${letters.firstOrNull() ?: 'G'}$c")
    }
    return candidates.firstOrNull { it !in taken } ?: letters.take(2).ifEmpty { "??" }
}
