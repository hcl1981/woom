package app.woom

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StationScreen(vm: WoomViewModel, station: Station, isNew: Boolean, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(station.name) }
    var url by rememberSaveable { mutableStateOf(station.url) }
    var image by rememberSaveable { mutableStateOf(station.image) }
    var buttons by rememberSaveable { mutableStateOf(station.devices) }
    var confirmDelete by remember { mutableStateOf(false) }

    val valid = name.isNotBlank() && url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    fun current() = station.copy(
        name = name.trim(),
        url = url.trim(),
        image = image.trim(),
        // Keep the button order identical to the device order in the settings.
        devices = vm.data.devices.map { it.uuid }.filter { it in buttons },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) stringResource(Res.string.new_station) else stringResource(Res.string.edit_station)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back)) } },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = { vm.moveStation(station.id, -1) }) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(Res.string.move_up)) }
                        IconButton(onClick = { vm.moveStation(station.id, 1) }) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(Res.string.move_down)) }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(Res.string.delete)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StationSearch(onPick = { s ->
                name = s.cleanName
                url = s.streamUrl
                image = s.favicon.takeIf { it.startsWith("http") }.orEmpty()
            })
            SectionTitle(stringResource(Res.string.or_enter_manually))
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                url, { url = it }, label = { Text(stringResource(Res.string.stream_url)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                isError = url.isNotBlank() && !valid && name.isNotBlank(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StationImage(image, 56)
                OutlinedTextField(
                    image, { image = it }, label = { Text(stringResource(Res.string.image_url)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }

            HorizontalDivider()
            SectionTitle(stringResource(Res.string.station_buttons, MAX_STATION_DEVICES))
            if (vm.data.devices.isEmpty()) Hint(stringResource(Res.string.add_devices_first))
            vm.data.devices.forEach { dev ->
                val checked = dev.uuid in buttons
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked,
                        enabled = checked || buttons.size < MAX_STATION_DEVICES,
                        onCheckedChange = { buttons = if (it) buttons + dev.uuid else buttons - dev.uuid },
                    )
                    Text("${dev.abbr} – ${dev.name}")
                }
            }

            Button(
                onClick = { vm.saveStation(current()); onBack() },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.save)) }

            if (vm.data.devices.isNotEmpty()) {
                HorizontalDivider()
                FavoriteSection(vm, valid, ::current)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.delete_station_question, station.name)) },
            confirmButton = { TextButton(onClick = { vm.deleteStation(station.id); onBack() }) { Text(stringResource(Res.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

/** Search field for radio-browser.info; searches while typing and hands the picked station to [onPick]. */
@Composable
private fun StationSearch(onPick: (RadioStation) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<RadioStation>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList(); searched = false; failed = false
            return@LaunchedEffect
        }
        delay(450) // wait until the user stops typing; a new keystroke restarts this effect
        searching = true
        val found = runCatching { RadioBrowser.search(q) }
        searching = false
        failed = found.isFailure
        results = found.getOrDefault(emptyList())
        searched = true
    }

    SectionTitle(stringResource(Res.string.search_station))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(stringResource(Res.string.search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            when {
                searching -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                query.isNotEmpty() -> IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, stringResource(Res.string.cancel)) }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth(),
    )
    when {
        failed -> Hint(stringResource(Res.string.search_failed))
        searched && results.isEmpty() -> Hint(stringResource(Res.string.no_results))
    }
    if (results.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                results.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onPick(s); query = "" }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StationImage(s.favicon.takeIf { it.startsWith("http") }.orEmpty(), 40)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(s.cleanName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Text(
            stringResource(Res.string.search_source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Stores the station as a preset (the device's own favourite buttons / remote keys). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteSection(vm: WoomViewModel, valid: Boolean, current: () -> Station) {
    val scope = rememberCoroutineScope()
    var targets by rememberSaveable { mutableStateOf(listOf<String>()) }
    var slot by rememberSaveable { mutableStateOf(1) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<List<Msg>>(emptyList()) }
    // uuid → (slot → preset name), loaded lazily per selected device
    val presets = remember { mutableStateMapOf<String, Map<Int, String>>() }

    LaunchedEffect(targets) {
        targets.filter { it !in presets }.forEach { presets[it] = vm.presetNames(it) }
    }
    val maxSlot = targets.mapNotNull { vm.device(it)?.presetCount }.minOrNull() ?: 6

    SectionTitle(stringResource(Res.string.favorite_title))
    vm.data.devices.forEach { dev ->
        val checked = dev.uuid in targets
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { targets = if (it) targets + dev.uuid else targets - dev.uuid })
            Text("${dev.abbr} – ${dev.name}")
        }
    }
    Text(stringResource(Res.string.preset_slot), style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..maxSlot).forEach { n ->
            FilterChip(selected = slot == n, onClick = { slot = n }, label = { Text("$n") })
        }
    }
    targets.forEach { uuid ->
        val dev = vm.device(uuid) ?: return@forEach
        val names = presets[uuid]
        val text = when {
            names == null -> stringResource(Res.string.loading)
            names[slot] != null -> stringResource(Res.string.slot_taken, slot, names[slot]!!)
            else -> stringResource(Res.string.slot_free, slot)
        }
        Text("${dev.abbr}: $text", style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(
        onClick = {
            running = true
            scope.launch {
                result = vm.savePreset(current(), targets, slot)
                targets.forEach { presets.remove(it) }
                targets.forEach { presets[it] = vm.presetNames(it) }
                running = false
            }
        },
        enabled = valid && targets.isNotEmpty() && !running && slot <= maxSlot,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (running) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
        Text(stringResource(Res.string.transfer))
    }
    result.forEach { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
}
