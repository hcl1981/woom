package app.woom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Bluetooth of the phone or computer Woom runs on. */
interface LocalBluetooth {
    /** Whether this system pairs with a WiiM by itself; otherwise the user does it in the system settings. */
    val canPairItself: Boolean get() = false

    /** Bluetooth is present, switched on and may be used. */
    suspend fun ready(): Boolean

    /** Name a WiiM shows for this system as its connected source. */
    suspend fun name(): String?

    /** Address a WiiM can connect to, given the sources it remembers; null if this system isn't one of them. */
    suspend fun sourceAddress(history: List<BtSource>): String?

    /** Whether this system and the WiiM with [wiimMac] are paired. */
    suspend fun isPaired(wiimMac: String, history: List<BtSource>): Boolean

    suspend fun pair(wiimMac: String): Boolean = false

    /** Connects from this side; false if that isn't possible here, the WiiM then connects itself. */
    suspend fun connect(wiimMac: String): Boolean = false

    /** Opens the system's Bluetooth settings, where the user adds the WiiM. */
    fun openSettings() {}

    fun log(text: String) {}
}

/**
 * Plays the sound of the phone or computer on one WiiM over Bluetooth, for the lowest delay (films).
 * The WiiM switches to its Bluetooth input and connects to this system; the operating system then
 * sends its sound there and accounts for the delay, so picture and sound stay in sync.
 */
class DeviceAudio(private val vm: WoomViewModel, private val bluetooth: LocalBluetooth) {
    /** uuid of the WiiM this system is connected to. */
    var connected by mutableStateOf<String?>(null)
        private set

    private var localName: String? = null

    init {
        // Runs also while the UI is closed: the WiiM may be switched elsewhere (station, remote, WiiM app).
        vm.viewModelScope.launch {
            while (true) {
                delay(3000)
                releaseIfSwitchedAway()
            }
        }
    }

    /** WiiMs that can receive Bluetooth audio; empty while the feature is switched off in the settings. */
    fun devices() = if (vm.data.deviceAudio) vm.data.devices.filter { "bluetooth" in it.inputs }.take(MAX_STATION_DEVICES) else emptyList()

    fun onDevice(uuid: String) {
        val dev = vm.device(uuid) ?: return
        val key = "audio/$uuid"
        if (vm.busy[key] == true) return
        vm.busy[key] = true
        vm.viewModelScope.launch {
            try {
                bluetooth.log("DeviceAudio: tap device=${dev.name} ip=${dev.ip} connected=$connected")
                if (connected == uuid) {
                    Wiim.btDisconnectAll(dev.ip)
                    connected = null
                    bluetooth.log("DeviceAudio: disconnected ${dev.name}")
                    return@launch
                }
                if (!bluetooth.ready()) {
                    bluetooth.log("DeviceAudio: Bluetooth not available")
                    vm.message = Msg.NoBluetooth
                    return@launch
                }
                // One device at a time: release the previous one first.
                connected?.let { prev -> vm.device(prev)?.let { runCatching { Wiim.btDisconnectAll(it.ip) } } }
                connected = null
                val info = Wiim.deviceInfo(dev.ip)
                if (info.group > 0) {
                    Wiim.leaveGroup(dev.ip)
                    delay(1000)
                }
                Wiim.switchInput(dev.ip, "bluetooth")
                // The station bar would only notice at its next poll.
                vm.activeStation.remove(uuid)
                if (!bluetooth.isPaired(info.btMac, history(dev.ip))) {
                    bluetooth.log("DeviceAudio: first-time pairing ${dev.name}")
                    Wiim.btEnterPairing(dev.ip)
                    if (bluetooth.canPairItself) {
                        if (!bluetooth.pair(info.btMac)) {
                            bluetooth.log("DeviceAudio: pairing failed ${dev.name}")
                            vm.message = Msg.BluetoothFailed(dev.name)
                            return@launch
                        }
                    } else {
                        // The user adds the WiiM in the system settings; it stays discoverable meanwhile.
                        bluetooth.openSettings()
                        vm.message = Msg.PairInSettings(dev.name)
                        if (!awaitPaired(dev.ip, info.btMac, seconds = 180)) {
                            bluetooth.log("DeviceAudio: pairing not completed in time for ${dev.name}")
                            vm.message = Msg.BluetoothFailed(dev.name)
                            return@launch
                        }
                        // The system usually connects right after pairing.
                        if (awaitConnected(dev.ip, 12)) {
                            connected = uuid
                            bluetooth.log("DeviceAudio: connected ${dev.name} after pairing")
                            return@launch
                        }
                    }
                }
                var ok = bluetooth.connect(info.btMac) && awaitConnected(dev.ip, 5)
                if (!ok) {
                    bluetooth.sourceAddress(history(dev.ip))?.let { Wiim.btConnectSource(dev.ip, it) }
                    ok = awaitConnected(dev.ip, 15)
                }
                if (ok) {
                    connected = uuid
                    bluetooth.log("DeviceAudio: connected ${dev.name}")
                } else {
                    bluetooth.log("DeviceAudio: connection failed ${dev.name}")
                    vm.message = Msg.BluetoothFailed(dev.name)
                }
            } catch (e: Exception) {
                bluetooth.log("DeviceAudio: error on ${dev.name}: $e")
                vm.message = Msg.Error(dev.name, e.message ?: e.toString())
            } finally {
                vm.busy.remove(key)
            }
        }
    }

    /**
     * A WiiM that switches to another source keeps the Bluetooth link, so this system would keep sending
     * its sound there unheard. Disconnecting makes it fall back to its own speakers.
     * The same applies when the feature is switched off in the settings.
     */
    private suspend fun releaseIfSwitchedAway() {
        val dev = connected?.let { vm.device(it) } ?: return
        if (busy()) return
        val mode = runCatching { Wiim.playerStatus(dev.ip).mode }.getOrNull() ?: return
        if (mode != BLUETOOTH_MODE || !vm.data.deviceAudio) {
            runCatching { Wiim.btDisconnectAll(dev.ip) }
            connected = null
        }
    }

    /** Checks which WiiM is connected to this system; releases links of WiiMs that play something else. */
    suspend fun refresh() {
        if (busy() || !bluetooth.ready()) return
        if (localName == null) localName = bluetooth.name()
        var found: String? = null
        for (dev in devices()) {
            if (vm.live[dev.uuid]?.online == false) continue
            val bt = runCatching { Wiim.btStatus(dev.ip) }.getOrNull() ?: continue
            if (!bt.connected || localName != null && !bt.source.equals(localName, ignoreCase = true)) continue
            // Ask the device itself: the polled state can be seconds old right after switching.
            val mode = runCatching { Wiim.playerStatus(dev.ip).mode }.getOrNull() ?: continue
            if (mode == BLUETOOTH_MODE) found = dev.uuid else runCatching { Wiim.btDisconnectAll(dev.ip) }
        }
        connected = found
    }

    private fun busy() = vm.busy.keys.any { it.startsWith("audio/") }

    private suspend fun history(ip: String) = runCatching { Wiim.btHistory(ip) }.getOrDefault(emptyList())

    private suspend fun awaitConnected(ip: String, seconds: Int): Boolean {
        repeat(seconds * 2) {
            if (runCatching { Wiim.btStatus(ip).connected }.getOrDefault(false)) return true
            delay(500)
        }
        return false
    }

    /**
     * Waits until the system has been paired with the WiiM: it reports a connected source, or both sides
     * know each other. Pairing mode is renewed every 20 s, since the WiiM leaves it on its own after a while.
     */
    private suspend fun awaitPaired(ip: String, wiimMac: String, seconds: Int): Boolean {
        var elapsed = 0
        while (elapsed < seconds) {
            if (runCatching { Wiim.btStatus(ip).connected }.getOrDefault(false)) return true
            if (bluetooth.isPaired(wiimMac, history(ip))) return true
            if (elapsed > 0 && elapsed % 20 == 0) runCatching { Wiim.btEnterPairing(ip) }
            delay(2000)
            elapsed += 2
        }
        return false
    }

    private companion object {
        /** getPlayerStatus mode of the Bluetooth input. */
        const val BLUETOOTH_MODE = 41
    }
}
