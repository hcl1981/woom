package app.woom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Finds WiiM devices by trying the HTTPS port of every address in the local /24 networks.
 * Unlike mDNS or SSDP this needs no incoming packets, so a desktop firewall doesn't get in the way.
 * Every open port is reported; the view model then asks the address whether it is a WiiM device.
 */
class NetworkScanner : Scanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun start(onFound: (ip: String) -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            val limit = Semaphore(64)
            for (ip in candidates()) launch { limit.withPermit { if (httpsOpen(ip)) onFound(ip) } }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private fun httpsOpen(ip: String): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(ip, 443), 400) } }.isSuccess

    /** All other hosts of the /24 network of each active LAN interface (virtual and VPN adapters skipped). */
    private fun candidates(): List<String> {
        val own = mutableSetOf<String>()
        val prefixes = mutableSetOf<String>()
        for (nif in NetworkInterface.getNetworkInterfaces().asSequence()) {
            if (!nif.isUp || nif.isLoopback || nif.isVirtual || nif.isPointToPoint) continue
            if (SKIPPED.any { nif.name.startsWith(it) }) continue
            for (addr in nif.interfaceAddresses) {
                val a = addr.address as? Inet4Address ?: continue
                if (!a.isSiteLocalAddress || addr.networkPrefixLength !in 16..30) continue
                own += a.hostAddress
                prefixes += a.hostAddress.substringBeforeLast('.')
            }
        }
        return prefixes.flatMap { p -> (1..254).map { "$p.$it" } }.filter { it !in own }
    }

    private companion object {
        val SKIPPED = listOf("docker", "br-", "veth", "virbr", "vmnet", "vboxnet", "tun", "tap", "wg", "zt")
    }
}
