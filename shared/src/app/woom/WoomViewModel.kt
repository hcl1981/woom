package app.woom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Last polled state of a device. */
data class Live(
    val online: Boolean,
    val status: String = "",
    val mode: Int = 0,
    val title: String = "",
    val group: Int = 0,
    val masterUuid: String = "",
    val vol: Int = 0,
    val mute: Boolean = false,
) {
    val isSlave get() = online && group > 0
    val isPlaying get() = online && (status == "play" || status == "load")
}

/** Finds WiiM devices on the local network (mDNS on Android, an address scan on the PC). */
interface Scanner {
    fun start(onFound: (ip: String) -> Unit)
    fun stop()
}

/** Feedback for the user; each platform turns it into localized text. */
sealed interface Msg {
    data class NoDeviceAt(val ip: String) : Msg
    data class AlreadyAdded(val device: String) : Msg
    data class Added(val device: String) : Msg
    data class Unreachable(val device: String) : Msg
    data class Error(val device: String, val reason: String) : Msg
    data class VolumeFailed(val device: String) : Msg
    data class PresetSaved(val device: String, val slot: Int) : Msg
    data class PresetFailed(val device: String, val reason: String) : Msg
    data object NoBluetooth : Msg
    data class PairInSettings(val device: String) : Msg
    data class BluetoothFailed(val device: String) : Msg
}

/**
 * The device to join in order to play along with [playing]. Those can also be followers of a group,
 * so a follower stands for its leader. [self] never leads; a device that can't be asked is skipped.
 */
suspend fun groupLeader(playing: List<Device>, all: List<Device>, self: String, info: suspend (Device) -> DeviceInfo?): Device? {
    for (device in playing) {
        val state = info(device) ?: continue
        val leader = if (state.group == 0) device else all.firstOrNull { it.uuid.equals(state.masterUuid, ignoreCase = true) }
        if (leader != null && leader.uuid != self) return leader
    }
    return null
}

class WoomViewModel(private val store: Store, private val scanner: Scanner, bluetooth: LocalBluetooth) : ViewModel() {
    var data by mutableStateOf(store.load())
        private set

    val live = mutableStateMapOf<String, Live>()

    /** device uuid → id of the station it is playing (set locally on play, inferred while polling). */
    val activeStation = mutableStateMapOf<String, String>()

    /** device uuid → input switched to by this app, used when the play mode code is unknown. */
    private val lastInput = mutableMapOf<String, String>()

    /** Keys of buttons with a running action ("station/<id>/<uuid>", "input/<uuid>/<mode>"). */
    val busy = mutableStateMapOf<String, Boolean>()

    /** Devices found on the network that are not added yet. */
    val found = mutableStateListOf<Device>()

    var message by mutableStateOf<Msg?>(null)

    private var pollJob: Job? = null
    private var logosChecked = false
    private val lastAction = mutableMapOf<String, Long>()

    /** Stations being started right now (station id → finished). */
    private val startingStation = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** "<device>: not reachable" for network failures, otherwise "<device>: <technical reason>". */
    private fun failure(device: String, e: Throwable): Msg = when (e) {
        is java.net.SocketException, is java.net.SocketTimeoutException, is java.net.UnknownHostException ->
            Msg.Unreachable(device)
        else -> e.message?.let { Msg.Error(device, it) } ?: Msg.Unreachable(device)
    }

    // --- lifecycle -----------------------------------------------------------------------------

    private fun startScanner() = scanner.start { ip -> viewModelScope.launch { onDiscovered(ip) } }

    fun onForeground() {
        startScanner()
        if (!logosChecked) {
            logosChecked = true
            data.stations.filter { it.image.isBlank() }.forEach { findLogo(it.id) }
        }
        if (pollJob?.isActive != true) {
            pollJob = viewModelScope.launch {
                while (isActive) {
                    pollAll()
                    delay(3000)
                }
            }
        }
    }

    fun onBackground() {
        scanner.stop()
        pollJob?.cancel()
    }

    fun rescan() {
        found.clear()
        scanner.stop()
        startScanner()
    }

    // --- persistence ---------------------------------------------------------------------------

    private fun update(transform: (AppData) -> AppData) {
        data = transform(data)
        store.save(data)
    }

    fun device(uuid: String) = data.devices.firstOrNull { it.uuid == uuid }

    fun setDeviceAudio(enabled: Boolean) = update { it.copy(deviceAudio = enabled) }

    // --- devices -------------------------------------------------------------------------------

    private suspend fun onDiscovered(ip: String) {
        val basic = runCatching { Wiim.deviceInfo(ip) }.getOrNull() ?: return
        if (basic.uuid.isEmpty()) return
        val info = basic.copy(inputs = Wiim.audioInputs(ip, basic.inputs))
        val known = device(info.uuid)
        if (known != null) {
            if (known.ip != ip || known.inputs != info.inputs || known.presetCount != info.presetCount) {
                update { d ->
                    d.copy(devices = d.devices.map {
                        if (it.uuid == info.uuid) it.copy(ip = ip, inputs = info.inputs, presetCount = info.presetCount) else it
                    })
                }
            }
        } else if (found.none { it.uuid == info.uuid }) {
            found.add(info.toDevice(ip))
        }
    }

    private fun DeviceInfo.toDevice(ip: String) = Device(
        uuid = uuid,
        name = name,
        ip = ip,
        abbr = defaultAbbr(name, data.devices.map { it.abbr } + found.map { it.abbr }),
        project = project,
        inputs = inputs,
        presetCount = presetCount,
    )

    fun addDevice(device: Device) {
        found.removeAll { it.uuid == device.uuid }
        if (device(device.uuid) != null) return
        val abbr = defaultAbbr(device.name, data.devices.map { it.abbr })
        update { d ->
            d.copy(
                devices = d.devices + device.copy(abbr = abbr),
                // New devices get a button in every station bar that still has room.
                stations = d.stations.map {
                    if (it.devices.size < MAX_STATION_DEVICES) it.copy(devices = it.devices + device.uuid) else it
                },
            )
        }
        viewModelScope.launch { pollAll() }
    }

    fun addDeviceByIp(ip: String) = viewModelScope.launch {
        val info = runCatching { Wiim.deviceInfo(ip.trim()) }.getOrElse {
            message = Msg.NoDeviceAt(ip)
            return@launch
        }.let { it.copy(inputs = Wiim.audioInputs(ip.trim(), it.inputs)) }
        if (device(info.uuid) != null) {
            message = Msg.AlreadyAdded(info.name)
            return@launch
        }
        addDevice(info.toDevice(ip.trim()))
        message = Msg.Added(info.name)
    }

    fun removeDevice(uuid: String) {
        val ip = device(uuid)?.ip
        update { d ->
            d.copy(
                devices = d.devices.filter { it.uuid != uuid },
                stations = d.stations.map { s -> s.copy(devices = s.devices - uuid) },
            )
        }
        live.remove(uuid)
        activeStation.remove(uuid)
        // Offer it again under "Gefunden" so it can be re-added.
        if (ip != null) viewModelScope.launch { onDiscovered(ip) }
    }

    fun updateDevice(device: Device) = update { d ->
        d.copy(devices = d.devices.map { if (it.uuid == device.uuid) device else it })
    }

    fun moveDevice(uuid: String, delta: Int) = update { d ->
        val list = d.devices.toMutableList()
        val i = list.indexOfFirst { it.uuid == uuid }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@update d
        list.add(j, list.removeAt(i))
        d.copy(devices = list)
    }

    // --- stations ------------------------------------------------------------------------------

    fun newStation() = Station(
        id = UUID.randomUUID().toString(),
        name = "",
        url = "",
        devices = data.devices.take(MAX_STATION_DEVICES).map { it.uuid },
    )

    fun saveStation(station: Station) {
        update { d ->
            val exists = d.stations.any { it.id == station.id }
            d.copy(stations = if (exists) d.stations.map { if (it.id == station.id) station else it } else d.stations + station)
        }
        if (station.image.isBlank()) findLogo(station.id)
    }

    /** Looks up a logo for a station without image in radio-browser.info (by stream URL); never replaces an image. */
    private fun findLogo(stationId: String) = viewModelScope.launch {
        val url = data.stations.firstOrNull { it.id == stationId }?.url ?: return@launch
        val logo = runCatching { RadioBrowser.logoFor(url) }.getOrNull() ?: return@launch
        update { d ->
            d.copy(stations = d.stations.map { if (it.id == stationId && it.image.isBlank()) it.copy(image = logo) else it })
        }
    }

    fun deleteStation(id: String) {
        update { d -> d.copy(stations = d.stations.filter { it.id != id }) }
        activeStation.entries.removeAll { it.value == id }
    }

    /** Drag & drop: puts station [fromId] at the position of station [toId]. Ignores keys that aren't stations. */
    fun moveStation(fromId: String, toId: String) = update { d ->
        val list = d.stations.toMutableList()
        val i = list.indexOfFirst { it.id == fromId }
        val j = list.indexOfFirst { it.id == toId }
        if (i < 0 || j < 0) return@update d
        list.add(j, list.removeAt(i))
        d.copy(stations = list)
    }

    fun moveStation(id: String, delta: Int) = update { d ->
        val list = d.stations.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return@update d
        list.add(j, list.removeAt(i))
        d.copy(stations = list)
    }

    /**
     * Station bar button:
     * - device already plays this station → it stops (a group follower leaves the group,
     *   a group leader stops and dissolves the group);
     * - another device already plays this station → this device joins it, so both play in sync;
     * - otherwise → this device starts the station.
     */
    fun onStationDevice(station: Station, uuid: String) {
        val dev = device(uuid) ?: return
        val key = "station/${station.id}/$uuid"
        if (busy[key] == true) return
        busy[key] = true
        lastAction[uuid] = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                if (activeStation[uuid] == station.id) {
                    // Ask the device itself: the polled state can be seconds old right after (un)grouping.
                    if (Wiim.deviceInfo(dev.ip).group > 0) {
                        Wiim.leaveGroup(dev.ip)
                    } else {
                        handOverAndStop(dev, station)
                    }
                    activeStation.remove(uuid)
                } else {
                    startInSync(dev, station)
                }
            } catch (e: Exception) {
                message = failure(dev.name, e)
            } finally {
                busy.remove(key)
                lastAction[uuid] = System.currentTimeMillis()
                pollAll()
            }
        }
    }

    /**
     * Joins the device that already plays [station], or starts it. One device at a time per station:
     * a station only counts as playing once it has started, so buttons tapped in quick succession
     * would otherwise each start their own, unsynchronized stream.
     */
    private suspend fun startInSync(dev: Device, station: Station) {
        while (true) (startingStation[station.id] ?: break).await()
        val done = CompletableDeferred<Unit>()
        startingStation[station.id] = done
        try {
            // Right after opening nothing is known yet about what the devices are playing.
            if (data.devices.none { activeStation[it.uuid] == station.id }) pollAll()
            val playing = data.devices.filter {
                it.uuid != dev.uuid && activeStation[it.uuid] == station.id && live[it.uuid]?.online == true
            }
            val leader = groupLeader(playing, data.devices, dev.uuid) { runCatching { Wiim.deviceInfo(it.ip) }.getOrNull() }
            if (Wiim.deviceInfo(dev.ip).group > 0) {
                Wiim.leaveGroup(dev.ip)
                delay(1000)
            }
            if (leader != null) {
                Wiim.joinGroup(dev.ip, leader.ip, leader.uuid)
            } else {
                Wiim.playStation(dev.ip, station)
                followersOf(dev.uuid).forEach { activeStation[it] = station.id }
            }
            activeStation[dev.uuid] = station.id
        } finally {
            startingStation.remove(station.id)
            done.complete(Unit)
        }
    }

    /**
     * Stops a group leader without silencing its followers: the group is dissolved, the first follower
     * starts the station itself and the remaining followers join it.
     */
    private suspend fun handOverAndStop(leader: Device, station: Station) {
        val followers = Wiim.slaveUuids(leader.ip).mapNotNull { device(it) }
        if (followers.isEmpty()) {
            Wiim.stop(leader.ip)
            return
        }
        Wiim.ungroup(leader.ip)
        Wiim.stop(leader.ip)
        val newLeader = followers.first()
        Wiim.playStation(newLeader.ip, station)
        activeStation[newLeader.uuid] = station.id
        lastAction[newLeader.uuid] = System.currentTimeMillis()
        followers.drop(1).forEach { f ->
            runCatching { Wiim.joinGroup(f.ip, newLeader.ip, newLeader.uuid) }
                .onFailure { message = failure(f.name, it) }
            activeStation[f.uuid] = station.id
            lastAction[f.uuid] = System.currentTimeMillis()
        }
    }

    private fun followersOf(uuid: String) =
        data.devices.filter { live[it.uuid]?.isSlave == true && live[it.uuid]?.masterUuid == uuid }.map { it.uuid }

    // --- inputs --------------------------------------------------------------------------------

    fun currentInput(uuid: String): String? {
        val state = live[uuid] ?: return null
        if (!state.online || state.isSlave) return null
        INPUT_DEFS.firstOrNull { state.mode in it.playModes }?.let { return it.mode }
        // Unknown mode codes: trust the input this app switched to, unless the device streams again.
        return lastInput[uuid]?.takeIf { state.mode !in 1..39 && state.mode != 0 }
    }

    fun onInput(uuid: String, mode: String) {
        val dev = device(uuid) ?: return
        val key = "input/$uuid/$mode"
        if (busy[key] == true) return
        busy[key] = true
        lastAction[uuid] = System.currentTimeMillis()
        viewModelScope.launch {
            try {
                if (live[uuid]?.isSlave == true) {
                    Wiim.leaveGroup(dev.ip)
                    delay(1000)
                }
                Wiim.switchInput(dev.ip, mode)
                lastInput[uuid] = mode
                activeStation.remove(uuid)
            } catch (e: Exception) {
                message = failure(dev.name, e)
            } finally {
                busy.remove(key)
                lastAction[uuid] = System.currentTimeMillis()
                pollAll()
            }
        }
    }

    // --- volume -------------------------------------------------------------------------------

    /** Volume the user just set but the device has not confirmed yet (uuid → volume). */
    val pendingVolume = mutableStateMapOf<String, Int>()

    private val volumeJobs = mutableMapOf<String, Job>()

    /** Mute state the user just set but the device has not confirmed yet. */
    val pendingMute = mutableStateMapOf<String, Boolean>()

    fun isMuted(uuid: String): Boolean = pendingMute[uuid] ?: (live[uuid]?.mute == true)

    fun toggleMute(uuid: String) {
        val dev = device(uuid) ?: return
        val mute = !isMuted(uuid)
        pendingMute[uuid] = mute
        lastAction[uuid] = System.currentTimeMillis()
        viewModelScope.launch {
            runCatching { Wiim.setMute(dev.ip, mute) }.onFailure { message = failure(dev.name, it) }
            // Keep showing the new state until a poll has picked it up.
            delay(3500)
            if (pendingMute[uuid] == mute) pendingMute.remove(uuid)
        }
    }

    fun volumeOf(uuid: String): Int? = pendingVolume[uuid] ?: live[uuid]?.takeIf { it.online }?.vol

    /** Sets the volume right away in the UI and sends only the latest value to the device. */
    fun setVolume(uuid: String, value: Int) {
        val dev = device(uuid) ?: return
        val v = value.coerceIn(0, 100)
        pendingVolume[uuid] = v
        lastAction[uuid] = System.currentTimeMillis()
        volumeJobs[uuid]?.cancel()
        volumeJobs[uuid] = viewModelScope.launch {
            delay(150)
            runCatching { Wiim.cmd(dev.ip, "setPlayerCmd:vol:$v") }
                .onFailure { message = Msg.VolumeFailed(dev.name) }
            // Keep showing the new value until a poll has picked it up.
            delay(3500)
            if (pendingVolume[uuid] == v) pendingVolume.remove(uuid)
        }
    }

    // --- presets -------------------------------------------------------------------------------

    suspend fun presetNames(uuid: String): Map<Int, String> =
        device(uuid)?.let { runCatching { Wiim.presetNames(it.ip) }.getOrNull() } ?: emptyMap()

    /** Saves [station] as preset [slot] on each device; returns a result line per device. */
    suspend fun savePreset(station: Station, uuids: List<String>, slot: Int): List<Msg> =
        uuids.mapNotNull { device(it) }.map { dev ->
            runCatching { Wiim.savePreset(dev.ip, slot, station) }
                .fold({ Msg.PresetSaved(dev.name, slot) }, { Msg.PresetFailed(dev.name, it.message.orEmpty()) })
        }

    // --- polling -------------------------------------------------------------------------------

    private suspend fun pollAll() {
        val devices = data.devices
        val results = devices.map { dev ->
            viewModelScope.async {
                val info = runCatching { Wiim.deviceInfo(dev.ip) }.getOrNull()
                val player = if (info != null) runCatching { Wiim.playerStatus(dev.ip) }.getOrNull() else null
                dev to if (info == null || player == null) {
                    Live(online = false)
                } else {
                    Live(true, player.status, player.mode, player.title, info.group, info.masterUuid, player.vol, player.mute)
                }
            }
        }.awaitAll()
        val now = System.currentTimeMillis()
        results.forEach { (dev, state) -> live[dev.uuid] = state }
        for ((dev, state) in results) {
            // Don't second-guess the device right after the user pressed something.
            if (now - (lastAction[dev.uuid] ?: 0) < 8000) continue
            when {
                !state.online -> activeStation.remove(dev.uuid)
                state.isSlave -> {
                    val leaderStation = activeStation[state.masterUuid]
                    if (leaderStation != null) activeStation[dev.uuid] = leaderStation else activeStation.remove(dev.uuid)
                }
                state.isPlaying && state.mode in 1..39 -> {
                    // Match by stream URL: radio streams replace the title with their programme info.
                    val playing = runCatching { Wiim.nowPlaying(dev.ip) }.getOrNull()
                    val match = data.stations.firstOrNull { it.url == playing?.uri }
                        // Other apps may play the same station from another stream, e.g. in a different quality.
                        ?: data.stations.firstOrNull { it.name.equals(playing?.stationName, ignoreCase = true) }
                        ?: data.stations.firstOrNull { it.name == state.title || it.url == state.title }
                    if (match != null) activeStation[dev.uuid] = match.id
                    else if (playing != null) activeStation.remove(dev.uuid)
                }
                else -> activeStation.remove(dev.uuid)
            }
            if (state.online && state.mode in 1..39) lastInput.remove(dev.uuid)
        }
    }

    val deviceAudio = DeviceAudio(this, bluetooth)

    override fun onCleared() {
        scanner.stop()
    }
}
