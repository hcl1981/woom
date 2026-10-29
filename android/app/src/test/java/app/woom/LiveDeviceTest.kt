package app.woom

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Silent checks against a real device. Skipped unless WOOM_LIVE_IP is set, e.g.
 * `WOOM_LIVE_IP=192.168.0.4 WOOM_LIVE_SLOT=3 ./gradlew testDebugUnitTest --tests '*LiveDeviceTest*'`.
 * Writes a test preset into an empty slot and restores the original preset list afterwards.
 */
class LiveDeviceTest {
    private val ip = System.getenv("WOOM_LIVE_IP")
    private val slot = System.getenv("WOOM_LIVE_SLOT")?.toIntOrNull() ?: 3
    private val queue = "urn:schemas-wiimu-com:service:PlayQueue:1"

    /** Audible: only runs with WOOM_LIVE_AUDIBLE=1. Plays quietly, switches input, stops, restores volume. */
    @Test
    fun playAndSwitchInput() = runBlocking {
        assumeTrue(ip != null && System.getenv("WOOM_LIVE_AUDIBLE") == "1")
        val volume = System.getenv("WOOM_LIVE_VOLUME") ?: "10"
        val before = Wiim.playerStatus(ip!!)
        println("before: $before")
        try {
            Wiim.cmd(ip, "setPlayerCmd:vol:$volume")
            println("volume now: ${Wiim.playerStatus(ip).vol}")

            val station = Station(
                "t", "Deutschlandfunk", "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3?aggregator=web",
                "https://appupload-output-eu.wiimhome.com/2025/12/08/16/7883c50e-f168-4693-8450-39d8cd6227c8/19afeb57e7c.jpg",
            )
            val t0 = System.currentTimeMillis()
            Wiim.playStation(ip, station)
            println("playStation returned after ${System.currentTimeMillis() - t0} ms")
            println("playing: ${Wiim.playerStatus(ip)}")
            println("vendor: " + Wiim.cmd(ip, "getPlayerStatus").substringAfter("\"vendor\":\"").substringBefore("\""))
            println("meta: ${Wiim.cmd(ip, "getMetaInfo")}")
            kotlinx.coroutines.delay(4000)

            Wiim.switchInput(ip, "line-in")
            kotlinx.coroutines.delay(2500)
            println("after switchmode line-in: ${Wiim.playerStatus(ip)}")
        } finally {
            runCatching { Wiim.stop(ip) }.onFailure { println("stop failed: $it") }
            Wiim.cmd(ip, "setPlayerCmd:vol:${before.vol}")
            kotlinx.coroutines.delay(1000)
            println("final: ${Wiim.playerStatus(ip)}")
        }
    }

    /**
     * Audible: WOOM_LIVE_AUDIBLE=1 and WOOM_LIVE_IP2 (the follower). Leader plays, follower joins,
     * leaves, joins again, leader ungroups; reports whether the follower keeps playing after each step.
     */
    @Test
    fun playInSync() = runBlocking {
        val ip2 = System.getenv("WOOM_LIVE_IP2")
        assumeTrue(ip != null && ip2 != null && System.getenv("WOOM_LIVE_AUDIBLE") == "1")
        val volume = System.getenv("WOOM_LIVE_VOLUME") ?: "10"
        val leader = Wiim.deviceInfo(ip!!)
        val vol1 = Wiim.playerStatus(ip).vol
        val vol2 = Wiim.playerStatus(ip2!!).vol
        suspend fun report(step: String) {
            kotlinx.coroutines.delay(3000)
            val f = Wiim.deviceInfo(ip2)
            println("$step | leader: ${Wiim.playerStatus(ip)} | follower: ${Wiim.playerStatus(ip2)} group=${f.group} master=${f.masterUuid}")
        }
        try {
            Wiim.cmd(ip, "setPlayerCmd:vol:$volume")
            Wiim.cmd(ip2, "setPlayerCmd:vol:$volume")
            val station = Station("t", "Deutschlandfunk", "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3?aggregator=web")
            Wiim.playStation(ip, station)
            report("1 leader plays")
            println("   trackUri: ${Wiim.trackUri(ip)}")
            assertEquals(station.url, Wiim.trackUri(ip))

            Wiim.joinGroup(ip2, ip, leader.uuid)
            report("2 follower joined")
            println("   slave list: " + Wiim.cmd(ip, "multiroom:getSlaveList"))

            Wiim.leaveGroup(ip2)
            report("3 follower left")

            Wiim.joinGroup(ip2, ip, leader.uuid)
            report("4 follower joined again")

            Wiim.ungroup(ip)
            report("5 leader ungrouped")
        } finally {
            runCatching { Wiim.stop(ip2) }
            runCatching { Wiim.stop(ip) }
            runCatching { Wiim.cmd(ip, "setPlayerCmd:vol:$vol1") }
            runCatching { Wiim.cmd(ip2, "setPlayerCmd:vol:$vol2") }
            report("final")
        }
    }

    @Test
    fun presetWriteRoundTrip() = runBlocking {
        assumeTrue(ip != null)
        val original = Wiim.soap(ip!!, "PlayQueue1", queue, "GetKeyMapping", "")
            .let { Wiim.tag(it, "QueueContext")!! }
        val before = Wiim.parsePresetNames(original)
        assumeTrue("slot $slot is not empty", before[slot] == null)

        val station = Station("t", "Woom Test", "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3?aggregator=web")
        try {
            Wiim.savePreset(ip, slot, station)
            val after = Wiim.presetNames(ip)
            println("after write: $after")
            assertEquals(before + (slot to "Woom Test"), after)
        } finally {
            Wiim.soap(ip, "PlayQueue1", queue, "SetKeyMapping", "<QueueContext>${Wiim.xml(original)}</QueueContext>")
            val restored = Wiim.presetNames(ip)
            println("restored: $restored")
            assertEquals(before, restored)
        }
    }
}
