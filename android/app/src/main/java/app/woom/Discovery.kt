package app.woom

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Browses mDNS `_linkplay._tcp` and reports the IPv4 address of every WiiM device found.
 * Resolves one service at a time, since older Android versions reject parallel resolves.
 */
class Discovery(context: Context) : Scanner {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private var onFound: (ip: String) -> Unit = {}

    override fun start(onFound: (ip: String) -> Unit) {
        if (listener != null) return
        this.onFound = onFound
        lock = wifi.createMulticastLock("woom").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                pending.add(info)
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        nsd.discoverServices("_linkplay._tcp", NsdManager.PROTOCOL_DNS_SD, l)
    }

    override fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
        lock?.release()
        lock = null
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val info = pending.poll() ?: run { resolving.set(false); return }
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val addresses = if (Build.VERSION.SDK_INT >= 34) serviceInfo.hostAddresses else listOfNotNull(serviceInfo.host)
                addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress?.let(onFound)
                done()
            }
            private fun done() {
                resolving.set(false)
                resolveNext()
            }
        })
    }
}
