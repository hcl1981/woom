package app.woom

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

private val INPUT_LABELS = mapOf(
    "line-in" to R.string.input_line_in,
    "bluetooth" to R.string.input_bluetooth,
    "udisk" to R.string.input_usb,
    "optical" to R.string.input_optical,
    "RCA" to R.string.input_rca,
    "co-axial" to R.string.input_coax,
    "FM" to R.string.input_fm,
    "line-in2" to R.string.input_line_in2,
    "XLR" to R.string.input_xlr,
    "HDMI" to R.string.input_hdmi,
    "cd" to R.string.input_cd,
    "TFcard" to R.string.input_sd,
    "PCUSB" to R.string.input_usb_audio,
    "phono" to R.string.input_phono,
    "DAB" to R.string.input_dab,
    "optical2" to R.string.input_optical2,
    "co-axial2" to R.string.input_coax2,
    "ARC" to R.string.input_arc,
    "external_usb" to R.string.input_usb,
    "auxIn" to R.string.input_aux,
)

/** Localized input name; unknown switchmode names are shown as they are. */
@Composable
fun inputLabel(mode: String): String = INPUT_LABELS[mode]?.let { stringResource(it) } ?: mode

/** Localized text of a view model message. */
@Composable
fun Msg.text(): String = when (this) {
    is Msg.NoDeviceAt -> stringResource(R.string.msg_no_device_at, ip)
    is Msg.AlreadyAdded -> stringResource(R.string.msg_already_added, device)
    is Msg.Added -> stringResource(R.string.msg_added, device)
    is Msg.Unreachable -> stringResource(R.string.msg_unreachable, device)
    is Msg.Error -> stringResource(R.string.msg_error, device, reason)
    is Msg.VolumeFailed -> stringResource(R.string.msg_volume_failed, device)
    is Msg.PresetSaved -> stringResource(R.string.msg_preset_saved, device, slot)
    is Msg.PresetFailed -> stringResource(R.string.msg_preset_failed, device, reason)
    Msg.NoBluetooth -> stringResource(R.string.msg_no_bluetooth)
    is Msg.PairInSettings -> stringResource(R.string.msg_pair_in_settings, device)
    is Msg.BluetoothFailed -> stringResource(R.string.msg_bluetooth_failed, device)
}
