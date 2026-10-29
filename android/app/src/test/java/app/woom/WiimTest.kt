package app.woom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses real getStatusEx responses (captured 2026-10-20, identifiers replaced by made-up values). */
class WiimTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test
    fun parsesUltraStatus() {
        val info = Wiim.parseDeviceInfo(resource("status_ultra.json"))
        assertEquals("FF0A1B2C3D4E5F6A7B8C9D0E", info.uuid)
        assertEquals("WiiM Ultra", info.name)
        assertEquals("WiiM_Ultra", info.project)
        assertEquals(12, info.presetCount)
        assertEquals(0, info.group)
        assertEquals(listOf("line-in", "bluetooth", "optical", "HDMI", "phono"), info.inputs)
    }

    @Test
    fun parsesMiniStatusWithParenthesesInName() {
        val info = Wiim.parseDeviceInfo(resource("status_mini.json"))
        assertEquals("WiiM Mini (Arbeitszimmer)", info.name)
        assertEquals(listOf("line-in", "bluetooth"), info.inputs)
    }

    @Test
    fun parsesNameWithUnescapedQuotes() {
        val raw = """{ "uuid": "AB", "DeviceName": "Bob's "Kitchen"", "GroupName": "x"y", "project": "P", "group": "1", "master_uuid": "CD" }"""
        val info = Wiim.parseDeviceInfo(raw)
        assertEquals("Bob's \"Kitchen\"", info.name)
        assertEquals(1, info.group)
        assertEquals("CD", info.masterUuid)
    }

    @Test
    fun mdnsUuidMatchesStatusUuid() {
        assertEquals("FF0A1B2C3D4E5F6A7B8C9D0E", Wiim.normalizeUuid("uuid:FF0A1B2C-3D4E-5F6A-7B8C-9D0EFF0A1B2C"))
    }

    @Test
    fun decodesHexTitle() {
        // From getPlayerStatus on the Ultra.
        assertEquals("Hörertelefon 00800 44644464", Wiim.hexToText("48C3B672657274656C65666F6E203030383030203434363434343634"))
        assertEquals("", Wiim.hexToText("556E6B6E6F776E")) // "Unknown"
    }

    private val keyList = """<?xml version="1.0"?>
<KeyList>
<ListName>KeyMappingQueue</ListName>
<MaxNumber>33</MaxNumber>
<Key0></Key0>
<Key1>
<Name>Deutschlandfunk</Name>
<Url>https://st01.sslstream.dlf.de/dlf/01/high/aac/stream.aac?aggregator=web</Url>
<Metadata>&lt;DIDL-Lite&gt;x&lt;/DIDL-Lite&gt;</Metadata>
<PicUrl>https://example.org/dlf.png</PicUrl>
<Source>CustomRadio</Source>
</Key1>
<Key2><RoutineId>Empty</RoutineId></Key2>
</KeyList>"""

    private val station = Station("id", "Rock & Pop \$1", "https://radio.example/stream.mp3?a=1&b=2", "https://radio.example/logo.png")

    @Test
    fun readsPresetNames() {
        assertEquals(mapOf(1 to "Deutschlandfunk"), Wiim.parsePresetNames(keyList))
    }

    @Test
    fun replacesExistingPresetAndKeepsOthers() {
        val out = Wiim.withPresetKey(keyList, 2, station)
        assertEquals(mapOf(1 to "Deutschlandfunk", 2 to "Rock & Pop \$1"), Wiim.parsePresetNames(out))
        assertTrue(out.contains("<Url>https://radio.example/stream.mp3?a=1&amp;b=2</Url>"))
        assertEquals(1, Regex("<Key2>").findAll(out).count())
    }

    @Test
    fun overwritesOccupiedPreset() {
        val out = Wiim.withPresetKey(keyList, 1, station)
        assertEquals(mapOf(1 to "Rock & Pop \$1"), Wiim.parsePresetNames(out))
        assertTrue(!out.contains("Deutschlandfunk"))
    }

    @Test
    fun appendsMissingPresetSlot() {
        val out = Wiim.withPresetKey(keyList, 7, station)
        assertTrue(out.trimEnd().endsWith("</Key7></KeyList>"))
        assertEquals(setOf(1, 7), Wiim.parsePresetNames(out).keys)
    }

    @Test
    fun parsesSlaveList() {
        // Captured from the Ultra while the Sound followed it.
        val raw = """{ "slaves": 1, "wmrm_version": "4.3", "surround": 0, "group_type": -1, "slave_list": [ { "name": "WiiM Sound", "uuid": "FF1B2C3D4E5F6A7B8C9D0E1F", "ip": "192.168.0.5", "version": "4.3", "type": "WiiMu-AmlogicA1", "channel": 0, "volume": 10, "mute": 0, "battery_percent": 0, "battery_charging": 0 } ] }"""
        assertEquals(listOf("FF1B2C3D4E5F6A7B8C9D0E1F"), Wiim.parseSlaveUuids(raw))
        assertEquals(emptyList<String>(), Wiim.parseSlaveUuids("""{ "slaves": 0, "wmrm_version": "4.3" }"""))
    }

    @Test
    fun abbreviations() {
        assertEquals("UL", defaultAbbr("WiiM Ultra", emptyList()))
        assertEquals("MI", defaultAbbr("WiiM Mini (Arbeitszimmer)", emptyList()))
        assertEquals("SO", defaultAbbr("WiiM Sound", emptyList()))
        assertEquals("UT", defaultAbbr("WiiM Ultra", listOf("UL")))
    }

    @Test
    fun readsBluetoothAddressUnderBothFieldNames() {
        assertEquals("02:00:00:00:01:53", Wiim.parseDeviceInfo(resource("status_ultra.json")).btMac)
        assertEquals("02:00:00:00:03:C9", Wiim.parseDeviceInfo(resource("status_mini.json")).btMac)
    }

    @Test
    fun parsesBluetoothStatus() {
        // Captured from the Ultra on 2026-10-29.
        val on = Wiim.parseBtStatus("""{ "status": 6, "server": 68, "a2dp_sink": { "pairing": 0, "link_state": "connected", "name": "ThinkPad-T14-Gen-4" } }""")
        assertEquals(BtStatus(true, "ThinkPad-T14-Gen-4"), on)
        val off = Wiim.parseBtStatus("""{ "status": 6, "server": 68, "a2dp_sink": { "pairing": 1, "link_state": "disconnected", "name": "" } }""")
        assertEquals(BtStatus(false, ""), off)
    }
}
