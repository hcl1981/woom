package app.woom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Per-device settings: name and abbreviation as shown in Woom, and which inputs get a button. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeviceSheet(vm: WoomViewModel, device: Device, onDismiss: () -> Unit) {
    var name by rememberSaveable(device.uuid) { mutableStateOf(device.name) }
    var abbr by rememberSaveable(device.uuid) { mutableStateOf(device.abbr) }
    var hidden by rememberSaveable(device.uuid) { mutableStateOf(device.hiddenInputs) }

    fun save() {
        vm.updateDevice(
            device.copy(
                name = name.trim().ifEmpty { device.name },
                abbr = abbr.trim().ifEmpty { device.abbr },
                hiddenInputs = hidden,
            ),
        )
    }

    ModalBottomSheet(
        onDismissRequest = { save(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(device.ip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = abbr,
                    onValueChange = { v -> abbr = v.filter { !it.isWhitespace() }.take(2).uppercase() },
                    label = { Text(stringResource(Res.string.abbreviation)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.width(84.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.name)) },
                    singleLine = true,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
            }
            if (device.inputs.isNotEmpty()) {
                Text(stringResource(Res.string.shown_inputs), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    device.inputs.forEach { mode ->
                        val shown = mode !in hidden
                        FilterChip(
                            selected = shown,
                            onClick = { hidden = if (shown) hidden + mode else hidden - mode },
                            label = { Text(inputLabel(mode)) },
                        )
                    }
                }
            }
            Button(onClick = { save(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.done)) }
        }
    }
}
