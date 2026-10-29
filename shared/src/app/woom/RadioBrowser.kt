package app.woom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A station from the free community directory radio-browser.info. */
@Serializable
data class RadioStation(
    val name: String = "",
    val url_resolved: String = "",
    val url: String = "",
    val favicon: String = "",
    val countrycode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val votes: Int = 0,
) {
    val streamUrl get() = url_resolved.ifBlank { url }

    /** Directory names often carry extras ("Deutschlandfunk | DLF | MP3 128k"); keep the station name. */
    val cleanName get() = name.substringBefore(" | ").trim().ifEmpty { name.trim() }

    /** e.g. "DE · MP3 128 kbit/s" */
    val details get() = listOf(countrycode, listOf(codec, if (bitrate > 0) "$bitrate kbit/s" else "").filter { it.isNotBlank() }.joinToString(" "))
        .filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * Station search and logo lookup via https://www.radio-browser.info (no account or API key).
 * The directory has no single main server, so several mirrors are tried in turn.
 */
object RadioBrowser {
    private val servers = listOf("all.api.radio-browser.info", "de1.api.radio-browser.info", "de2.api.radio-browser.info")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(name: String, limit: Int = 40): List<RadioStation> =
        get("/json/stations/search") {
            addQueryParameter("name", name)
            addQueryParameter("limit", limit.toString())
            addQueryParameter("order", "votes")
            addQueryParameter("reverse", "true")
            addQueryParameter("hidebroken", "true")
        }.filter { it.streamUrl.startsWith("http") }

    /** Logo of the directory entry with exactly this stream URL, or null. */
    suspend fun logoFor(streamUrl: String): String? =
        get("/json/stations/byurl") { addQueryParameter("url", streamUrl) }
            .sortedByDescending { it.votes }
            .firstOrNull { it.favicon.startsWith("http") }?.favicon

    private suspend fun get(path: String, query: HttpUrl.Builder.() -> Unit): List<RadioStation> =
        withContext(Dispatchers.IO) {
            var last: Exception? = null
            for (server in servers) {
                try {
                    val url = "https://$server$path".toHttpUrl().newBuilder().apply(query).build()
                    val req = Request.Builder().url(url).header("User-Agent", "Woom/1.0").build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        return@withContext json.decodeFromString<List<RadioStation>>(resp.body.string())
                    }
                } catch (e: Exception) {
                    last = e
                }
            }
            throw last ?: IOException("radio-browser.info unreachable")
        }
}
