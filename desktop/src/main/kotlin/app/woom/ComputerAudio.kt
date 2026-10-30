package app.woom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Messages of the computer audio feature (PC version only). */
data object NoBluetooth : Msg
data class PairInSettings(val device: String) : Msg
data class BluetoothFailed(val device: String) : Msg

/**
 * Plays the computer's sound on one WiiM over Bluetooth, for the lowest delay (films).
 * The WiiM switches to its Bluetooth input and connects itself to the computer; the operating
 * system then sends its sound there and accounts for the delay, so picture and sound stay in sync.
 */
class ComputerAudio(private val vm: WoomViewModel) {
    /** uuid of the device the computer is connected to. */
    var connected by mutableStateOf<String?>(null)
        private set

    private var pcName: String? = null

    init {
        // Runs also while the window is closed: the device may be switched elsewhere (station, remote, WiiM app).
        vm.viewModelScope.launch {
            while (true) {
                delay(3000)
                releaseIfSwitchedAway()
            }
        }
    }

    /** Devices that can receive Bluetooth audio. */
    fun devices() = vm.data.devices.filter { "bluetooth" in it.inputs }.take(MAX_STATION_DEVICES)

    fun onDevice(uuid: String) {
        val dev = vm.device(uuid) ?: return
        val key = "pc/$uuid"
        if (vm.busy[key] == true) return
        vm.busy[key] = true
        vm.viewModelScope.launch {
            try {
                Log.log("ComputerAudio: tap device=${dev.name} ip=${dev.ip} connected=$connected")
                if (connected == uuid) {
                    Wiim.btDisconnectAll(dev.ip)
                    connected = null
                    Log.log("ComputerAudio: disconnected ${dev.name}")
                    return@launch
                }
                val pc = PcBluetooth.address() ?: run {
                    Log.log("ComputerAudio: no Bluetooth adapter found on this PC")
                    vm.message = NoBluetooth
                    return@launch
                }
                Log.log("ComputerAudio: PC Bluetooth address=$pc")
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
                if (isWindows) {
                    val history = runCatching { Wiim.btHistory(dev.ip) }.getOrDefault(emptyList())
                    if (pc !in history) {
                        // First time: the WiiM becomes discoverable, the user adds it in the Windows
                        // Bluetooth settings, Windows pairs and usually connects by itself. Keep the device
                        // discoverable, wait for the pairing, then make sure the link is up.
                        Log.log("ComputerAudio: first-time pairing ${dev.name} (pc not in history=$history)")
                        Wiim.btEnterPairing(dev.ip)
                        PcBluetooth.openSettings()
                        vm.message = PairInSettings(dev.name)
                        if (!awaitPaired(dev.ip, pc, seconds = 180)) {
                            Log.log("ComputerAudio: pairing not completed in time for ${dev.name}")
                            vm.message = BluetoothFailed(dev.name)
                            return@launch
                        }
                        Log.log("ComputerAudio: paired ${dev.name}, ensuring connection")
                        var connectedOk = awaitConnected(dev.ip, 12)
                        if (!connectedOk) {
                            runCatching { Wiim.btConnectSource(dev.ip, pc) }
                            connectedOk = awaitConnected(dev.ip, 20)
                        }
                        if (connectedOk) {
                            connected = uuid
                            Log.log("ComputerAudio: connected ${dev.name} after pairing")
                        } else {
                            Log.log("ComputerAudio: connect after pairing failed ${dev.name}")
                            vm.message = BluetoothFailed(dev.name)
                        }
                        return@launch
                    }
                    Log.log("ComputerAudio: ${dev.name} already in pairing history, connecting")
                } else if (!PcBluetooth.isPaired(info.btMac)) {
                    Wiim.btEnterPairing(dev.ip)
                    if (!PcBluetooth.pair(info.btMac)) {
                        Log.log("ComputerAudio: Linux pairing failed ${dev.name}")
                        vm.message = BluetoothFailed(dev.name)
                        return@launch
                    }
                }
                // Linux connects from the computer (about 2 s); otherwise the WiiM connects itself (about 4 s).
                var ok = !isWindows && PcBluetooth.connect(info.btMac) != null && awaitConnected(dev.ip, 5)
                if (!ok) {
                    Wiim.btConnectSource(dev.ip, pc)
                    ok = awaitConnected(dev.ip, 15)
                }
                if (ok) {
                    connected = uuid
                    Log.log("ComputerAudio: connected ${dev.name}")
                } else {
                    Log.log("ComputerAudio: connection failed ${dev.name}")
                    vm.message = BluetoothFailed(dev.name)
                }
            } catch (e: Exception) {
                Log.error("ComputerAudio: error on ${dev.name}", e)
                vm.message = Msg.Error(dev.name, e.message ?: e.toString())
            } finally {
                vm.busy.remove(key)
            }
        }
    }

    /**
     * A WiiM that switches to another source keeps the Bluetooth link, so the computer would keep sending
     * its sound there unheard. Disconnecting makes the computer fall back to its own speakers.
     */
    private suspend fun releaseIfSwitchedAway() {
        val dev = connected?.let { vm.device(it) } ?: return
        if (vm.busy.keys.any { it.startsWith("pc/") }) return
        val mode = runCatching { Wiim.playerStatus(dev.ip).mode }.getOrNull() ?: return
        if (mode != BLUETOOTH_MODE) {
            runCatching { Wiim.btDisconnectAll(dev.ip) }
            connected = null
        }
    }

    /** Checks which device is connected to this computer; releases links of devices that play something else. */
    suspend fun refresh() {
        if (vm.busy.keys.any { it.startsWith("pc/") }) return
        if (pcName == null) pcName = PcBluetooth.name()
        var found: String? = null
        for (dev in devices()) {
            if (vm.live[dev.uuid]?.online == false) continue
            val bt = runCatching { Wiim.btStatus(dev.ip) }.getOrNull() ?: continue
            if (!bt.connected || pcName != null && !bt.source.equals(pcName, ignoreCase = true)) continue
            // Ask the device itself: the polled state can be seconds old right after switching.
            val mode = runCatching { Wiim.playerStatus(dev.ip).mode }.getOrNull() ?: continue
            if (mode == BLUETOOTH_MODE) found = dev.uuid else runCatching { Wiim.btDisconnectAll(dev.ip) }
        }
        connected = found
    }

    private suspend fun awaitConnected(ip: String, seconds: Int): Boolean {
        repeat(seconds * 2) {
            if (runCatching { Wiim.btStatus(ip).connected }.getOrDefault(false)) return true
            delay(500)
        }
        return false
    }

    /**
     * Windows only: waits until the PC has been paired with the device — either the device already
     * reports a connected Bluetooth source, or the PC's address appears in the device's pairing history.
     * The device is re-armed for pairing every 20 s, since it leaves pairing mode on its own after a while.
     */
    private suspend fun awaitPaired(ip: String, pc: String, seconds: Int): Boolean {
        var elapsed = 0
        while (elapsed < seconds) {
            if (runCatching { Wiim.btStatus(ip).connected }.getOrDefault(false)) return true
            if (pc in runCatching { Wiim.btHistory(ip) }.getOrDefault(emptyList())) return true
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

/** First bar of the list: the computer's sound, with a button per device that can receive it. */
@Composable
fun ComputerBar(vm: WoomViewModel, audio: ComputerAudio) {
    val devices = audio.devices()
    LaunchedEffect(Unit) {
        while (true) {
            audio.refresh()
            delay(3000)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Computer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScrollingText(
                stringResource(Res.string.computer_audio),
                MaterialTheme.typography.titleMedium,
                Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                devices.forEach { dev ->
                    DeviceButton(
                        abbr = dev.abbr,
                        // A station started on the device takes over at once, before Bluetooth is released.
                        active = audio.connected == dev.uuid && vm.activeStation[dev.uuid] == null,
                        online = vm.live[dev.uuid]?.online != false,
                        busy = vm.busy["pc/${dev.uuid}"] == true,
                        onClick = { audio.onDevice(dev.uuid) },
                    )
                }
            }
            // Lines the buttons up with the station bars, which have a settings button here.
            Spacer(Modifier.width(38.dp))
        }
    }
}
