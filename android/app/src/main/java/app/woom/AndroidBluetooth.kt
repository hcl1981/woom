package app.woom

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Android hides the phone's own Bluetooth address from apps and doesn't let them pair or connect audio.
 * So the phone is found by name in the WiiM's list of known sources, the user pairs in the system
 * settings, and the WiiM connects itself. All calls need BLUETOOTH_CONNECT, which the UI asks for.
 */
@SuppressLint("MissingPermission")
class AndroidBluetooth(context: Context) : LocalBluetooth {
    private val app = context.applicationContext
    private val adapter get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    private fun permitted() = Build.VERSION.SDK_INT < 31 ||
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override suspend fun ready() = permitted() && adapter?.isEnabled == true

    override suspend fun name(): String? = if (permitted()) adapter?.name else null

    override suspend fun sourceAddress(history: List<BtSource>): String? {
        val own = name() ?: return null
        return history.firstOrNull { it.name.equals(own, ignoreCase = true) }?.address
    }

    override suspend fun isPaired(wiimMac: String, history: List<BtSource>): Boolean =
        permitted() && adapter?.bondedDevices?.any { it.address.equals(wiimMac, ignoreCase = true) } == true &&
            sourceAddress(history) != null

    override fun openSettings() {
        app.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
