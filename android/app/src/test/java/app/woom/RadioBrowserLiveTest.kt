package app.woom

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Talks to radio-browser.info; skipped unless WOOM_LIVE_NET=1. */
class RadioBrowserLiveTest {
    @Test
    fun searchAndLogo() = runBlocking {
        assumeTrue(System.getenv("WOOM_LIVE_NET") == "1")
        val hits = RadioBrowser.search("wdr 2")
        hits.take(5).forEach { println("HIT ${it.cleanName} | ${it.details} | ${it.streamUrl} | ${it.favicon}") }
        assertTrue(hits.isNotEmpty())
        val logo = RadioBrowser.logoFor("https://st02.sslstream.dlf.de/dlf/02/128/mp3/stream.mp3?aggregator=web")
        println("LOGO $logo")
        assertTrue(logo!!.startsWith("http"))
        assertEquals("Deutschlandfunk", RadioStation(name = "Deutschlandfunk | DLF | MP3 128k").cleanName)
    }
}
