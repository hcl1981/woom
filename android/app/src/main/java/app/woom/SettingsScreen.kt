package app.woom

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: WoomViewModel, onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val text = vm.message?.text().orEmpty()
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(text); vm.message = null }
    }
    var manualIp by rememberSaveable { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<Device?>(null) }
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle(stringResource(R.string.my_devices)) }
            if (vm.data.devices.isEmpty()) item { Hint(stringResource(R.string.no_devices_added)) }
            items(vm.data.devices, key = { it.uuid }) { dev ->
                DeviceCard(vm, dev, onOpen = { sheetFor = dev.uuid }, onRemove = { confirmRemove = dev })
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(stringResource(R.string.found_in_wifi))
                    IconButton(onClick = vm::rescan) { Icon(Icons.Filled.Refresh, stringResource(R.string.search_again)) }
                }
            }
            if (vm.found.isEmpty()) item { Hint(stringResource(R.string.searching_hint)) }
            items(vm.found.toList(), key = { "found/" + it.uuid }) { dev ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dev.name, style = MaterialTheme.typography.titleMedium)
                            Text(dev.ip, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { vm.addDevice(dev) }) {
                            Icon(Icons.Filled.Add, null)
                            Text(stringResource(R.string.add))
                        }
                    }
                }
            }

            item { SectionTitle(stringResource(R.string.add_by_ip)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text(stringResource(R.string.ip_example)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.addDeviceByIp(manualIp); manualIp = "" }, enabled = manualIp.isNotBlank()) {
                        Text(stringResource(R.string.add))
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.device_audio), Modifier.weight(1f))
                    Switch(checked = vm.data.deviceAudio, onCheckedChange = vm::setDeviceAudio)
                }
            }
        }
    }

    sheetFor?.let { uuid -> vm.device(uuid)?.let { DeviceSheet(vm, it, onDismiss = { sheetFor = null }) } }

    confirmRemove?.let { dev ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.remove_device_question, dev.name)) },
            text = { Text(stringResource(R.string.remove_device_text)) },
            confirmButton = { TextButton(onClick = { vm.removeDevice(dev.uuid); confirmRemove = null }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Device in the list; tap opens [DeviceSheet] for name, abbreviation and shown inputs. */
@Composable
private fun DeviceCard(vm: WoomViewModel, dev: Device, onOpen: () -> Unit, onRemove: () -> Unit) {
    val online = vm.live[dev.uuid]?.online
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                dev.abbr,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(dev.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    dev.ip + when (online) { true -> " · " + stringResource(R.string.online); false -> " · " + stringResource(R.string.offline); null -> "" },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (online == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.moveDevice(dev.uuid, -1) }) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up)) }
            IconButton(onClick = { vm.moveDevice(dev.uuid, 1) }) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down)) }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, stringResource(R.string.remove)) }
        }
    }
}
