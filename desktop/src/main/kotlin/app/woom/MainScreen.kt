package app.woom

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: WoomViewModel,
    onSettings: () -> Unit,
    onEditStation: (String) -> Unit,
    onAddStation: () -> Unit,
    computer: ComputerAudio,
    onContentHeight: (Int?) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val text = vm.message?.text().orEmpty()
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(text); vm.message = null }
    }
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    var devicesExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        vm.moveStation(from.key as? String ?: return@rememberReorderableLazyListState, to.key as? String ?: return@rememberReorderableLazyListState)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Woom", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(Res.string.settings)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Reports how tall the window must be to show the whole list (null: it doesn't fit, use the full height).
        val density = LocalDensity.current
        val top = padding.calculateTopPadding()
        LaunchedEffect(listState, top) {
            snapshotFlow {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull()
                val last = info.visibleItemsInfo.lastOrNull()
                val allVisible = first != null && last != null && first.index == 0 && listState.firstVisibleItemScrollOffset == 0 &&
                    last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding
                if (allVisible) last!!.offset + last.size + info.afterContentPadding - info.viewportStartOffset else null
            }.collect { px ->
                // 2 dp spare, so rounding can't cut off the last item and make the window grow again.
                onContentHeight(px?.let { with(density) { (top + it.toDp()).value.toInt() + 2 } })
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.data.devices.isEmpty()) {
                item {
                    Hint(stringResource(Res.string.no_devices_hint))
                }
            }
            // The computer's sound and the radio stations are both sources for the devices.
            val hasComputer = computer.devices().isNotEmpty()
            if (hasComputer || vm.data.stations.isNotEmpty()) item { SectionTitle(stringResource(Res.string.sources)) }
            if (hasComputer) item(key = "computer") { ComputerBar(vm, computer) }
            items(vm.data.stations, key = { it.id }) { station ->
                // Drag a station bar with the mouse to sort the list.
                ReorderableItem(reorderState, key = station.id) { dragging ->
                    val elevation by animateDpAsState(if (dragging) 8.dp else 0.dp)
                    StationBar(
                        vm, station,
                        onEdit = { onEditStation(station.id) },
                        modifier = Modifier.draggableHandle(),
                        elevation = elevation,
                    )
                }
            }
            item {
                OutlinedButton(onClick = onAddStation, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_station))
                }
            }
            if (vm.data.devices.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { devicesExpanded = !devicesExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SectionTitle(stringResource(Res.string.volume_and_inputs))
                        Icon(
                            if (devicesExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            if (devicesExpanded) stringResource(Res.string.collapse) else stringResource(Res.string.expand),
                            Modifier.padding(start = 4.dp, top = 8.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (devicesExpanded) {
                items(vm.data.devices, key = { "dev/" + it.uuid }) { device ->
                    DeviceBar(vm, device, onSettings = { sheetFor = device.uuid })
                }
            }
        }
    }
    sheetFor?.let { uuid -> vm.device(uuid)?.let { DeviceSheet(vm, it, onDismiss = { sheetFor = null }) } }
}

@Composable
private fun StationBar(
    vm: WoomViewModel,
    station: Station,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    elevation: Dp = 0.dp,
) {
    val devices = station.devices.mapNotNull { vm.device(it) }
    val buttons = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            devices.forEach { dev ->
                DeviceButton(
                    abbr = dev.abbr,
                    active = vm.activeStation[dev.uuid] == station.id,
                    online = vm.live[dev.uuid]?.online != false,
                    busy = vm.busy["station/${station.id}/${dev.uuid}"] == true,
                    onClick = { vm.onStationDevice(station, dev.uuid) },
                )
            }
        }
    }
    Card(modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = elevation)) {
        BoxWithConstraints {
            // Buttons sit beside the name when there is room, otherwise in a second line below it.
            val buttonsWidth = (devices.size * 44).dp
            val beside = maxWidth - buttonsWidth - 44.dp - 48.dp - 20.dp >= 110.dp
            Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                StationImage(station.image, 44)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScrollingText(station.name, MaterialTheme.typography.titleMedium)
                    if (!beside) buttons()
                }
                if (beside) buttons()
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Tune, stringResource(Res.string.station_settings)) }
            }
        }
    }
}

@Composable
fun DeviceButton(abbr: String, active: Boolean, online: Boolean, busy: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = online,
        shape = CircleShape,
        color = if (active) colors.primary else colors.surface,
        contentColor = if (active) colors.onPrimary else colors.onSurface,
        border = if (active) null else BorderStroke(1.dp, colors.outline),
        modifier = Modifier.size(40.dp).alpha(if (online) 1f else 0.35f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = if (active) colors.onPrimary else colors.primary)
            } else {
                Text(abbr, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Full-width but flat volume slider (the Material default is very tall). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeSlider(volume: Int, enabled: Boolean = true, onChange: (Int) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // The default inactive track is almost invisible on the card background.
    val colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
    Slider(
        colors = colors,
        value = volume.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = 0f..100f,
        enabled = enabled,
        interactionSource = interaction,
        thumb = {
            SliderDefaults.Thumb(interactionSource = interaction, colors = colors, enabled = enabled, thumbSize = DpSize(3.dp, 16.dp))
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                enabled = enabled,
                modifier = Modifier.height(4.dp),
                drawStopIndicator = null,
                thumbTrackGapSize = 2.dp,
            )
        },
        modifier = Modifier.fillMaxWidth().height(24.dp),
    )
}

/** Single line that scrolls sideways (marquee) when it doesn't fit. */
@Composable
fun ScrollingText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Text(
        text,
        style = style,
        maxLines = 1,
        modifier = modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500, repeatDelayMillis = 2000),
    )
}

/** A device with its volume and the inputs to switch to. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceBar(vm: WoomViewModel, device: Device, onSettings: () -> Unit) {
    val online = vm.live[device.uuid]?.online != false
    val current = vm.currentInput(device.uuid)
    val volume = vm.volumeOf(device.uuid)
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    device.abbr,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(colors.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = colors.onSecondaryContainer,
                )
                ScrollingText(device.name, MaterialTheme.typography.titleSmall, Modifier.padding(start = 8.dp).weight(1f))
                if (!online) Text(stringResource(Res.string.offline), style = MaterialTheme.typography.labelSmall, color = colors.error)
                IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Tune, stringResource(Res.string.device_settings), Modifier.size(20.dp))
                }
            }
            if (online && volume != null) {
                val muted = vm.isMuted(device.uuid)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.toggleMute(device.uuid) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            stringResource(if (muted) Res.string.unmute else Res.string.mute),
                            Modifier.size(20.dp),
                            tint = if (muted) colors.error else colors.onSurfaceVariant,
                        )
                    }
                    Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        VolumeSlider(volume, enabled = !muted) { vm.setVolume(device.uuid, it) }
                    }
                    Text(
                        "$volume",
                        Modifier.width(28.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (muted) colors.onSurfaceVariant else colors.onSurface,
                        textAlign = TextAlign.End,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                device.inputs.filter { it !in device.hiddenInputs }.forEach { mode ->
                    val busy = vm.busy["input/${device.uuid}/$mode"] == true
                    FilterChip(
                        selected = current == mode,
                        enabled = online,
                        onClick = { vm.onInput(device.uuid, mode) },
                        label = { Text(inputLabel(mode)) },
                        leadingIcon = if (busy) {
                            { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
fun StationImage(url: String, sizeDp: Int) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(sizeDp.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (url.isNotBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(8.dp))
}
