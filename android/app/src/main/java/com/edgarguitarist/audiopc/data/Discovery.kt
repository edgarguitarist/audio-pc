package com.edgarguitarist.audiopc.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class DiscoveredServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val version: String? = null,
)

/**
 * Finds Audio PC servers on the LAN (API.md section 1): mDNS and UDP broadcast in parallel, then
 * a /24 sweep if neither answered quickly. Results are deduped by server id.
 */
class Discovery(context: Context, client: OkHttpClient) {
    private val appContext = context.applicationContext
    private val probeClient = client.newBuilder()
        .connectTimeout(600, TimeUnit.MILLISECONDS)
        .readTimeout(600, TimeUnit.MILLISECONDS)
        .callTimeout(900, TimeUnit.MILLISECONDS)
        .build()
    private val patientClient = client

    /** Emits servers as they appear and completes after [windowMs]. */
    fun discover(windowMs: Long = 6000): Flow<DiscoveredServer> = channelFlow {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val found: (DiscoveredServer) -> Unit = { if (seen.add(it.id)) trySend(it) }
        // Without the lock many phones drop incoming multicast/broadcast to save battery.
        val lock = runCatching {
            appContext.getSystemService(WifiManager::class.java)
                .createMulticastLock("audiopc-discovery").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        try {
            withTimeoutOrNull(windowMs) {
                coroutineScope {
                    launch { runCatching { nsd(found) } }
                    launch { runCatching { udp(found) } }
                    launch {
                        delay(2000)
                        if (seen.isEmpty()) runCatching { sweep(found) }
                    }
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }.flowOn(Dispatchers.IO)

    /** Waits for one specific server (used to re-resolve a saved server whose host changed). */
    suspend fun findById(id: String, timeoutMs: Long = 7000): DiscoveredServer? =
        withTimeoutOrNull(timeoutMs) { discover(timeoutMs).first { it.id == id } }

    /** GET /api/info; null when the host is not an Audio PC server. */
    suspend fun probe(host: String, port: Int, fast: Boolean = true): DiscoveredServer? {
        val c = if (fast) probeClient else patientClient
        val raw = c.exchange("http://${hostForUrl(host)}:$port", null, "GET", "/api/info", null) as? Raw.Ok ?: return null
        val info = runCatching { Json.decodeFromString<ServerInfo>(raw.body) }.getOrNull() ?: return null
        if (info.service != "audio-pc") return null
        return DiscoveredServer(info.id, info.name, host, port, info.version)
    }

    private fun hostForUrl(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    // --- mDNS -------------------------------------------------------------------------------

    private suspend fun nsd(found: (DiscoveredServer) -> Unit) {
        val manager = appContext.getSystemService(NsdManager::class.java)
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { queue.trySend(info) }
            override fun onStartDiscoveryFailed(type: String?, code: Int) { queue.close() }
            override fun onStopDiscoveryFailed(type: String?, code: Int) {}
            override fun onDiscoveryStarted(type: String?) {}
            override fun onDiscoveryStopped(type: String?) {}
            override fun onServiceLost(info: NsdServiceInfo?) {}
        }
        manager.discoverServices("_audiopc._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            // Resolve strictly one at a time: concurrent resolveService calls fail with
            // FAILURE_ALREADY_ACTIVE on older Android versions.
            for (info in queue) {
                val resolved = withTimeoutOrNull(3000) { resolve(manager, info) } ?: continue
                toServer(resolved)?.let(found)
            }
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= 34) {
                val cb = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(code: Int) { done(cont, null) }
                    override fun onServiceUpdated(si: NsdServiceInfo) { done(cont, si) }
                    override fun onServiceLost() {}
                    override fun onServiceInfoCallbackUnregistered() {}
                }
                manager.registerServiceInfoCallback(info, Runnable::run, cb)
                cont.invokeOnCancellation { runCatching { manager.unregisterServiceInfoCallback(cb) } }
            } else {
                @Suppress("DEPRECATION")
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo?, code: Int) { done(cont, null) }
                    override fun onServiceResolved(si: NsdServiceInfo) { done(cont, si) }
                })
            }
        }

    private fun done(cont: CancellableContinuation<NsdServiceInfo?>, value: NsdServiceInfo?) {
        if (cont.isActive) cont.resume(value)
    }

    private suspend fun toServer(si: NsdServiceInfo): DiscoveredServer? {
        @Suppress("DEPRECATION")
        val addresses = if (Build.VERSION.SDK_INT >= 34) si.hostAddresses else listOfNotNull(si.host)
        val addr = addresses.filterIsInstance<Inet4Address>().firstOrNull() ?: return null
        val host = addr.hostAddress ?: return null
        val txt = si.attributes
        val id = txt["id"]?.decodeToString()
        if (id.isNullOrBlank()) return probe(host, si.port) // no TXT id: ask the server itself
        return DiscoveredServer(id, txt["name"]?.decodeToString() ?: si.serviceName, host, si.port, txt["ver"]?.decodeToString())
    }

    // --- UDP broadcast ----------------------------------------------------------------------

    private suspend fun udp(found: (DiscoveredServer) -> Unit) = withContext(Dispatchers.IO) {
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 400
            val payload = "AUDIOPC_DISCOVER 1".toByteArray()
            val targets = buildList {
                add(InetAddress.getByName("255.255.255.255"))
                localIpv4()?.let { subnetBroadcast(it.first, it.second) }?.let { add(it) }
            }
            // UDP is lossy: repeat the probe a few times while listening.
            val sender = launch {
                repeat(3) {
                    for (t in targets) runCatching { sock.send(DatagramPacket(payload, payload.size, t, DISCOVERY_PORT)) }
                    delay(700)
                }
            }
            val buf = ByteArray(2048)
            try {
                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    try { sock.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val info = runCatching {
                        Json.decodeFromString<ServerInfo>(String(packet.data, 0, packet.length))
                    }.getOrNull() ?: continue
                    if (info.service != "audio-pc") continue
                    val host = packet.address.hostAddress ?: continue
                    found(DiscoveredServer(info.id, info.name, host, info.port ?: DEFAULT_PORT, info.version))
                }
            } finally {
                sender.cancel()
            }
        }
    }

    // --- /24 sweep --------------------------------------------------------------------------

    private suspend fun sweep(found: (DiscoveredServer) -> Unit) {
        val (ip, _) = localIpv4() ?: return
        val bytes = ip.address
        val prefix = "${bytes[0].toInt() and 255}.${bytes[1].toInt() and 255}.${bytes[2].toInt() and 255}"
        val own = bytes[3].toInt() and 255
        val gate = Semaphore(64)
        coroutineScope {
            for (i in 1..254) {
                if (i == own) continue
                launch { gate.withPermit { probe("$prefix.$i", DEFAULT_PORT)?.let(found) } }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 7799
        const val DISCOVERY_PORT = 7798

        /** The device's LAN IPv4 address and prefix length (Wi-Fi preferred). */
        fun localIpv4(): Pair<Inet4Address, Int>? {
            val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrNull().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni ->
                    ni.interfaceAddresses.mapNotNull { ia ->
                        (ia.address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }
                            ?.let { Triple(ni.name, it, ia.networkPrefixLength.toInt()) }
                    }
                }
            val best = candidates.firstOrNull { it.first.startsWith("wlan") } ?: candidates.firstOrNull() ?: return null
            return best.second to best.third
        }

        fun subnetBroadcast(ip: Inet4Address, prefix: Int): InetAddress? {
            if (prefix !in 1..30) return null
            val mask = -1 shl (32 - prefix)
            val v = ip.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 255) }
            val bc = v or mask.inv()
            return InetAddress.getByAddress(ByteArray(4) { ((bc ushr (24 - 8 * it)) and 255).toByte() })
        }
    }
}
