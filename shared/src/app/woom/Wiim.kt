package app.woom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Snapshot of getStatusEx, reduced to what the app needs. */
data class DeviceInfo(
    val uuid: String,
    val name: String,
    val project: String,
    val inputs: List<String>,
    val presetCount: Int,
    val group: Int,
    val masterUuid: String,
    /** Bluetooth address, upper case; empty if the device has no Bluetooth. */
    val btMac: String = "",
)

/** Bluetooth input of a device (getbtstatus): whether a source is connected and its Bluetooth name. */
data class BtStatus(val connected: Boolean, val source: String)

/** A Bluetooth source a device remembers from pairing; [address] is upper case. */
data class BtSource(val name: String, val address: String)

/** What AVTransport reports: the stream, and the station's name if it is a radio station. */
data class NowPlaying(val uri: String, val stationName: String)

/** Snapshot of getPlayerStatus. */
data class PlayerStatus(val status: String, val mode: Int, val title: String, val vol: Int, val mute: Boolean = false)

/**
 * Talks to WiiM/LinkPlay devices. See docs/01-httpapi.md and docs/02-discovery-upnp.md.
 * httpapi runs over HTTPS with a self-signed device certificate, UPnP over plain HTTP on :59152.
 */
object Wiim {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory,
            trustAll,
        )
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val UPNP_PORT = 59152
    private const val PLAY_QUEUE = "urn:schemas-wiimu-com:service:PlayQueue:1"

    // --- httpapi -------------------------------------------------------------------------------

    /** Sends `httpapi.asp?command=<command>`. Parameters inside [command] must already be encoded. */
    suspend fun cmd(ip: String, command: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://$ip/httpapi.asp?command=$command").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body.string().trim()
            if (body.equals("unknown command", true) || body.equals("Failed", true)) {
                throw IOException("device replied: $body")
            }
            body
        }
    }

    suspend fun deviceInfo(ip: String): DeviceInfo = parseDeviceInfo(cmd(ip, "getStatusEx"))

    fun parseDeviceInfo(raw: String): DeviceInfo {
        // Firmware does not escape quotes in names, so cut them out before parsing (as the official app does).
        val name = Regex("\"DeviceName\"\\s*:\\s*\"(.*?)\",\\s*\"").find(raw)?.groupValues?.get(1).orEmpty()
        val cleaned = raw.replace(Regex("\"(DeviceName|GroupName)\"\\s*:\\s*\".*?\",(?=\\s*\")"), "")
        val o = json.parseToJsonElement(cleaned).jsonObject
        return DeviceInfo(
            uuid = o.str("uuid"),
            name = name.ifEmpty { o.str("ssid") },
            project = o.str("project"),
            inputs = decodeInputs(o.hex("plm_support"), o.hex("capability")),
            presetCount = o.str("preset_key").toIntOrNull()?.takeIf { it > 0 } ?: 6,
            group = o.str("group").toIntOrNull() ?: 0,
            masterUuid = o.str("master_uuid"),
            // Field name differs between models (Mini: BT_MAC, Ultra/Sound: BTMAC).
            btMac = o.str("BT_MAC").ifEmpty { o.str("BTMAC") }.uppercase(),
        )
    }

    /**
     * Physical inputs as switchmode names. Newer firmware lists them via getAudioInputCapbility
     * (`{"audioInput":[{"index":0,"mode":"line-in"},…]}`); inputs disabled in the WiiM app
     * (getAudioInputEnable, `"enable":0`) are left out. Returns [fallback] if the device can't tell.
     */
    suspend fun audioInputs(ip: String, fallback: List<String>): List<String> {
        val modes = runCatching {
            json.parseToJsonElement(cmd(ip, "getAudioInputCapbility")).jsonObject["audioInput"]!!.jsonArray
                .map { it.jsonObject.str("mode") }
                .filter { it.isNotEmpty() && it != "wifi" }
        }.getOrNull()
        if (modes.isNullOrEmpty()) return fallback
        val disabled = runCatching {
            json.parseToJsonElement(cmd(ip, "getAudioInputEnable")).jsonObject["audioInput"]!!.jsonArray
                .map { it.jsonObject }
                .filter { it.str("enable") == "0" }
                .map { it.str("mode") }
        }.getOrDefault(emptyList())
        return modes.filter { it !in disabled }.distinct()
    }

    suspend fun playerStatus(ip: String): PlayerStatus {
        val o = json.parseToJsonElement(cmd(ip, "getPlayerStatus")).jsonObject
        return PlayerStatus(
            status = o.str("status"),
            mode = o.str("mode").toIntOrNull() ?: 0,
            title = hexToText(o.str("Title")),
            vol = o.str("vol").toIntOrNull() ?: 0,
            mute = o.str("mute") == "1",
        )
    }

    /**
     * URL the device is playing (AVTransport GetInfoEx TrackURI). Unlike the title, this does not
     * change when a radio stream sends its own programme info.
     */
    suspend fun trackUri(ip: String): String = nowPlaying(ip).uri

    suspend fun nowPlaying(ip: String): NowPlaying =
        parseNowPlaying(soap(ip, "rendertransport1", "urn:schemas-upnp-org:service:AVTransport:1", "GetInfoEx",
            "<InstanceID>0</InstanceID>"))

    /** The station name sits in the track metadata, which is XML escaped twice: `<item type="station" name="…">`. */
    fun parseNowPlaying(xmlText: String): NowPlaying {
        val metadata = unxml(tag(xmlText, "TrackMetaData").orEmpty())
        val name = Regex("<item\\b[^>]*\\btype=\"station\"[^>]*\\bname=\"([^\"]*)\"").find(metadata)?.groupValues?.get(1)
        return NowPlaying(tag(xmlText, "TrackURI").orEmpty(), name?.let(::unxml).orEmpty())
    }

    suspend fun stop(ip: String) = cmd(ip, "setPlayerCmd:stop")

    suspend fun setMute(ip: String, mute: Boolean) = cmd(ip, "setPlayerCmd:mute:${if (mute) 1 else 0}")

    suspend fun switchInput(ip: String, mode: String) = cmd(ip, "setPlayerCmd:switchmode:$mode")

    /** Sent to the device that should follow [masterIp]. */
    suspend fun joinGroup(slaveIp: String, masterIp: String, masterUuid: String) =
        cmd(slaveIp, "multiroom:JoinGroup:IP=$masterIp:uuid=$masterUuid")

    suspend fun leaveGroup(slaveIp: String) = cmd(slaveIp, "multiroom:LeaveGroup")

    suspend fun ungroup(masterIp: String) = cmd(masterIp, "multiroom:Ungroup")

    // --- Bluetooth input (the device as receiver) -----------------------------------------------

    suspend fun btStatus(ip: String): BtStatus = parseBtStatus(cmd(ip, "getbtstatus"))

    /** `{"status":6,"a2dp_sink":{"pairing":0,"link_state":"connected","name":"ThinkPad"}}` */
    fun parseBtStatus(raw: String): BtStatus {
        val sink = json.parseToJsonElement(raw).jsonObject["a2dp_sink"] as? JsonObject ?: return BtStatus(false, "")
        return BtStatus(sink.str("link_state") == "connected", sink.str("name"))
    }

    /** The sources the device has been paired with. */
    suspend fun btHistory(ip: String): List<BtSource> = parseBtHistory(cmd(ip, "getbthistory"))

    /** `{"num":1,"list":[{"name":"iPad","ad":"14:88:e6:5d:71:f0","role":"Audio Source"}]}` */
    fun parseBtHistory(raw: String): List<BtSource> =
        json.parseToJsonElement(raw).jsonObject["list"]?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.let { o -> BtSource(o.str("name"), o.str("ad").uppercase()) } }.orEmpty()

    /** Makes the device visible for pairing. */
    suspend fun btEnterPairing(ip: String) = cmd(ip, "btavkenterpair")

    /** The device connects itself to a source it was paired with before, e.g. a computer. */
    suspend fun btConnectSource(ip: String, sourceMac: String) = cmd(ip, "connectbta2dpsource:${sourceMac.lowercase()}")

    suspend fun btDisconnectAll(ip: String) = cmd(ip, "btdisconnectall")

    /** uuids of the devices following [masterIp], straight from the leader. */
    suspend fun slaveUuids(masterIp: String): List<String> = parseSlaveUuids(cmd(masterIp, "multiroom:getSlaveList"))

    fun parseSlaveUuids(raw: String): List<String> =
        json.parseToJsonElement(raw).jsonObject["slave_list"]?.jsonArray
            ?.map { it.jsonObject.str("uuid") }?.filter { it.isNotEmpty() }
            .orEmpty()

    // --- playback ------------------------------------------------------------------------------

    /**
     * Plays a radio URL the way the official app does (PlayQueue CreateQueue with DIDL metadata, so the
     * device shows the station name and logo). Falls back to `setPlayerCmd:play:<url>` if the device
     * does not start playing.
     */
    suspend fun playStation(ip: String, station: Station) {
        val queued = runCatching {
            soap(ip, "PlayQueue1", PLAY_QUEUE, "CreateQueue", "<QueueContext>${xml(playListXml(station))}</QueueContext>")
        }.isSuccess
        if (queued) {
            if (isPlaying(ip)) return
            runCatching {
                soap(ip, "PlayQueue1", PLAY_QUEUE, "PlayQueueWithIndex",
                    "<QueueName>${xml(station.name)}</QueueName><Index>1</Index>")
            }
            if (isPlaying(ip)) return
        }
        cmd(ip, "setPlayerCmd:play:" + URLEncoder.encode(station.url, "UTF-8"))
    }

    private suspend fun isPlaying(ip: String): Boolean {
        repeat(5) {
            delay(600)
            val s = runCatching { playerStatus(ip) }.getOrNull()
            if (s != null && (s.status == "play" || s.status == "load") && s.mode in 10..39 &&
                s.title.isNotEmpty()) return true
        }
        return false
    }

    private fun didl(station: Station): String =
        "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:song=\"www.linkplay.com/song/\" xmlns:custom=\"www.linkplay.com/custom/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"><upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<item id=\"${xml(station.url)}\"><song:id>${xml(station.url)}</song:id><song:singerid></song:singerid>" +
            "<song:albumid></song:albumid><res protocolInfo=\"http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;\" " +
            "duration=\"0\">${xml(station.url)}</res><dc:title>${xml(station.name)}</dc:title>" +
            "<upnp:artist></upnp:artist><upnp:album></upnp:album>" +
            "<upnp:albumArtURI>${xml(station.image)}</upnp:albumArtURI></item></DIDL-Lite>"

    private fun playListXml(station: Station): String =
        "<?xml version=\"1.0\" ?><PlayList><ListName>${xml(station.name)}</ListName><ListInfo>" +
            "<SourceName>CustomRadio</SourceName><SearchUrl></SearchUrl><TrackNumber>1</TrackNumber>" +
            "<PicUrl>${xml(station.image)}</PicUrl><StreamType>station</StreamType>" +
            "<StreamId>${xml(station.url)}</StreamId><ShowName>${xml(station.name)}</ShowName>" +
            "<ContentType>radio</ContentType><Radio>1</Radio><Quality>0</Quality>" +
            "<StartPlayIndex>1</StartPlayIndex></ListInfo><Tracks><Track1><Source>CustomRadio</Source>" +
            "<Id>${xml(station.url)}</Id><URL>${xml(station.url)}</URL>" +
            "<Metadata>${xml(didl(station))}</Metadata></Track1></Tracks></PlayList>"

    // --- presets -------------------------------------------------------------------------------

    /** Preset slot (1-based) → name, from PlayQueue GetKeyMapping. */
    suspend fun presetNames(ip: String): Map<Int, String> = parsePresetNames(getKeyList(ip))

    /** Stores [station] as preset [slot] (1-based), keeping all other presets (read-modify-write). */
    suspend fun savePreset(ip: String, slot: Int, station: Station) {
        val updated = withPresetKey(getKeyList(ip), slot, station)
        soap(ip, "PlayQueue1", PLAY_QUEUE, "SetKeyMapping", "<QueueContext>${xml(updated)}</QueueContext>")
    }

    /** Returns [keyList] with `<Key[slot]>` replaced (or added) by [station]; all other keys stay untouched. */
    fun withPresetKey(keyList: String, slot: Int, station: Station): String {
        require(keyList.contains("<KeyList>") && keyList.contains("</KeyList>")) { "unexpected preset list from device" }
        val key = "<Key$slot><Name>${xml(station.name)}</Name><PicUrl>${xml(station.image)}</PicUrl>" +
            "<Source>CustomRadio</Source><ContentType>radio</ContentType><RoutineId>Empty</RoutineId>" +
            "<Shuffle>0</Shuffle><Loop>0</Loop><Url>${xml(station.url)}</Url>" +
            "<Metadata>${xml(didl(station))}</Metadata></Key$slot>"
        val existing = Regex("<Key$slot>.*?</Key$slot>|<Key$slot\\s*/>", RegexOption.DOT_MATCHES_ALL)
        return if (existing.containsMatchIn(keyList)) {
            existing.replaceFirst(keyList, Regex.escapeReplacement(key))
        } else {
            keyList.replace("</KeyList>", "$key</KeyList>")
        }
    }

    fun parsePresetNames(keyList: String): Map<Int, String> =
        Regex("<Key(\\d+)>(.*?)</Key\\1>", RegexOption.DOT_MATCHES_ALL).findAll(keyList)
            .mapNotNull { m ->
                val name = tag(m.groupValues[2], "Name") ?: return@mapNotNull null
                m.groupValues[1].toInt() to name
            }.toMap()

    private suspend fun getKeyList(ip: String): String =
        soap(ip, "PlayQueue1", PLAY_QUEUE, "GetKeyMapping", "")
            .let { tag(it, "QueueContext") ?: throw IOException("no preset list received") }

    // --- UPnP SOAP -----------------------------------------------------------------------------

    /** Returns the raw SOAP response; throws on HTTP errors (SOAP faults come back as HTTP 500). */
    suspend fun soap(ip: String, control: String, service: String, action: String, args: String): String =
        withContext(Dispatchers.IO) {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" " +
                "xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                "<u:$action xmlns:u=\"$service\">$args</u:$action></s:Body></s:Envelope>"
            val req = Request.Builder()
                .url("http://$ip:$UPNP_PORT/upnp/control/$control")
                .header("SOAPACTION", "\"$service#$action\"")
                .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("$action: HTTP ${resp.code}")
                resp.body.string()
            }
        }

    /** Content of the first <name>…</name>, XML-unescaped once. */
    fun tag(xmlText: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xmlText)?.groupValues?.get(1)?.let(::unxml)

    // --- helpers -------------------------------------------------------------------------------

    fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun unxml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    fun hexToText(hex: String): String {
        if (hex.isEmpty() || hex.length % 2 != 0 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return hex
        val text = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)
        return if (text == "Unknown") "" else text
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.hex(key: String): Long =
        str(key).removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0L

    /** mDNS/UPnP uuid "uuid:FF0A1B2C-3D4E-5F6A-7B8C-9D0EFF0A1B2C" → getStatusEx form "FF0A1B2C3D4E5F6A7B8C9D0E". */
    fun normalizeUuid(raw: String) = raw.removePrefix("uuid:").replace("-", "").take(24).uppercase()
}
