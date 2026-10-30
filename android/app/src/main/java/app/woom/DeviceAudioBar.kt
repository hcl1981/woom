package app.woom

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** First bar of the list: the sound of the phone, with a button per WiiM that can receive it. */
@Composable
fun DeviceAudioBar(vm: WoomViewModel) {
    val audio = vm.deviceAudio
    val devices = audio.devices()
    LaunchedEffect(Unit) {
        while (true) {
            audio.refresh()
            delay(3000)
        }
    }
    // Android 12+ asks once for permission to use Bluetooth; the tap that triggered it then continues.
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.let { if (granted) audio.onDevice(it) else vm.message = Msg.NoBluetooth }
        pending = null
    }
    fun permitted() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Smartphone, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ScrollingText(
                stringResource(R.string.device_audio),
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
                        busy = vm.busy["audio/${dev.uuid}"] == true,
                        onClick = { if (permitted()) audio.onDevice(dev.uuid) else { pending = dev.uuid; permission.launch(Manifest.permission.BLUETOOTH_CONNECT) } },
                    )
                }
            }
            // Lines the buttons up with the station bars, which have a settings button here.
            Spacer(Modifier.width(38.dp))
        }
    }
}
