package com.sbai.service

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.IpPrefix
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.RoutePrefix as LibboxRoutePrefix

/**
 * Android 平台接口实现（供 libbox Go 侧回调）。
 * 实现模式参考 AsteriskBOX（GPL-3.0）engine/vpn/AndroidLibboxPlatformInterface.kt。
 */
class SbPlatformInterface(
    private val service: VpnService,
) : PlatformInterface {

    val selfPackageName: String get() = service.packageName

    private val connectivityManager =
        service.getSystemService(ConnectivityManager::class.java)

    private var tunFileDescriptor: ParcelFileDescriptor? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    fun closeTun() {
        runCatching { tunFileDescriptor?.close() }
        tunFileDescriptor = null
    }

    // ---- Socket 保护 ----

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(service.protect(fd)) { "android: failed to protect socket" }
    }

    // ---- TUN ----

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(service) == null) { "android: missing VPN permission" }

        val inet4 = options.inet4Address.toList()
        val inet6 = options.inet6Address.toList()

        val builder = service.Builder()
            .setSession("sb-AI")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        (inet4 + inet6).forEach { prefix ->
            builder.addAddress(prefix.address(), prefix.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode?.value != Libbox.DNSModeDisabled) {
                options.dnsServerAddress.toList().forEach { builder.addDnsServer(it) }
            }
            applyRoutes(builder, options, inet4.isNotEmpty(), inet6.isNotEmpty())
            options.includePackage.toList().forEachInstalled { builder.addAllowedApplication(it) }
            options.excludePackage.toList().forEachInstalled { builder.addDisallowedApplication(it) }
        }

        closeTun()
        val fd = builder.establish()
            ?: error("android: failed to establish VPN interface")
        tunFileDescriptor = fd
        return fd.fd
    }

    private fun applyRoutes(
        builder: VpnService.Builder,
        options: TunOptions,
        hasIpv4: Boolean,
        hasIpv6: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val inet4Routes = options.inet4RouteAddress.toList()
            val inet6Routes = options.inet6RouteAddress.toList()
            if (inet4Routes.isEmpty() && hasIpv4) {
                builder.addRoute("0.0.0.0", 0)
            } else {
                inet4Routes.forEach {
                    builder.addRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
                }
            }
            if (inet6Routes.isEmpty() && hasIpv6) {
                builder.addRoute("::", 0)
            } else {
                inet6Routes.forEach {
                    builder.addRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
                }
            }
            options.inet4RouteExcludeAddress.toList().forEach {
                builder.excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
            }
            options.inet6RouteExcludeAddress.toList().forEach {
                builder.excludeRoute(IpPrefix(InetAddress.getByName(it.address()), it.prefix()))
            }
        } else {
            options.inet4RouteRange.toList().forEach { builder.addRoute(it.address(), it.prefix()) }
            options.inet6RouteRange.toList().forEach { builder.addRoute(it.address(), it.prefix()) }
        }
    }

    // ---- 连接归属（Android 10+） ----

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("android: connection owner lookup requires Android 10")
        }
        val uid = connectivityManager.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        check(uid != Process.INVALID_UID) { "android: connection owner not found" }
        val packages = service.packageManager.getPackagesForUid(uid).orEmpty().toList()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(packages.toStringIterator())
        }
    }

    // ---- 默认网卡监控 ----

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        closeDefaultInterfaceMonitor(listener)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateDefaultInterface(listener, network)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                updateDefaultInterface(listener, network)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                updateDefaultInterface(listener, network)
            override fun onLost(network: Network) =
                updateDefaultInterface(listener, connectivityManager.activeNetwork)
        }
        defaultNetworkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
        updateDefaultInterface(listener, connectivityManager.activeNetwork)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        defaultNetworkCallback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        defaultNetworkCallback = null
    }

    private fun updateDefaultInterface(listener: InterfaceUpdateListener, network: Network?) {
        val name = network?.let(connectivityManager::getLinkProperties)?.interfaceName.orEmpty()
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        listener.updateDefaultInterface(name, index, false, false)
    }

    // ---- 网络接口枚举 ----

    override fun getInterfaces(): NetworkInterfaceIterator {
        val androidNetworks = connectivityManager.allNetworks.mapNotNull { network ->
            val lp = connectivityManager.getLinkProperties(network) ?: return@mapNotNull null
            val nc = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            lp.interfaceName.orEmpty() to (lp to nc)
        }.toMap()

        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
            val android = androidNetworks[ni.name]
            LibboxNetworkInterface().apply {
                index = ni.index
                name = ni.name
                mtu = runCatching { ni.mtu }.getOrDefault(0)
                addresses = ni.interfaceAddresses.map { it.toPrefix() }.toStringIterator()
                flags = ni.toFlags()
                type = android?.second?.toLibboxType() ?: Libbox.InterfaceTypeOther
                dnsServer = android?.first?.dnsServers.orEmpty()
                    .mapNotNull { it.hostAddress }.toStringIterator()
                // lx 内核 NetworkInterface 无 dnsSearchDomain 字段
                metered = android?.second
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    ?.not() ?: false
            }
        }
        return LibboxNetworkInterfaceIterator(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun localDNSTransport(): LocalDNSTransport =
        AndroidLocalDnsTransport(connectivityManager)

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    // ---- 不支持的平台特性 ----

    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell(): Unit = unsupported("platform shell")
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = unsupported("platform shell")

    override fun readSystemSSHHostKey(): String = unsupported("system SSH host key")
    override fun lookupSFTPServer(): String = unsupported("SFTP server")
    override fun lookupUser(username: String?): PlatformUser = unsupported("platform user")
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = unsupported("platform bridge")
    override fun registerMyInterface(name: String?) = Unit

    @Suppress("DEPRECATION")
    override fun readWIFIState(): WIFIState? {
        val wifiManager = service.applicationContext.getSystemService(WifiManager::class.java)
        val info = wifiManager?.connectionInfo ?: return null
        val ssid = info.ssid.orEmpty().removeSurrounding("\"")
            .takeUnless { it == "<unknown ssid>" }.orEmpty()
        return WIFIState(ssid, info.bssid.orEmpty())
    }

    override fun tailscaleHostname(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun sendNotification(notification: Notification) {
        Log.i(TAG, "sing-box: ${notification.title}: ${notification.body}")
    }

    override fun cancelNotification(identifier: String, typeID: Int) = Unit

    // ---- helpers ----

    private fun List<String>.forEachInstalled(block: (String) -> Unit) {
        filter { it.isNotBlank() }.distinct().forEach { pkg ->
            runCatching { block(pkg) }
        }
    }

    private fun NetworkCapabilities.toLibboxType(): Int = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
        else -> Libbox.InterfaceTypeOther
    }

    private fun NetworkInterface.toFlags(): Int {
        var value = 0
        if (isUp) value = value or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        if (isLoopback) value = value or OsConstants.IFF_LOOPBACK
        if (isPointToPoint) value = value or OsConstants.IFF_POINTOPOINT
        if (supportsMulticast()) value = value or OsConstants.IFF_MULTICAST
        return value
    }

    private fun InterfaceAddress.toPrefix(): String {
        val host = if (address is Inet6Address) {
            Inet6Address.getByAddress(address.address).hostAddress
        } else {
            address.hostAddress
        }
        return "$host/$networkPrefixLength"
    }

    private fun <T> unsupported(feature: String): T =
        throw UnsupportedOperationException("android: $feature is not supported")

    private companion object {
        const val TAG = "SbPlatformInterface"
    }
}

// ---------------------------------------------------------------------------
// libbox iterator helpers
// ---------------------------------------------------------------------------

internal class LibboxStringIterator(
    private val values: Iterator<String>,
    private val size: Int,
) : StringIterator {
    override fun len(): Int = size
    override fun hasNext(): Boolean = values.hasNext()
    override fun next(): String = values.next()
}

internal fun List<String>.toStringIterator(): StringIterator =
    LibboxStringIterator(iterator(), size)

private class LibboxNetworkInterfaceIterator(
    private val values: Iterator<LibboxNetworkInterface>,
) : NetworkInterfaceIterator {
    override fun hasNext(): Boolean = values.hasNext()
    override fun next(): LibboxNetworkInterface = values.next()
}

private fun RoutePrefixIterator.toList(): List<LibboxRoutePrefix> = buildList {
    while (hasNext()) add(next())
}

internal fun StringIterator.toList(): List<String> = buildList {
    while (hasNext()) add(next())
}

// ---------------------------------------------------------------------------
// 系统 DNS 传输（DnsResolver）
// ---------------------------------------------------------------------------

@Suppress("DEPRECATION")
private class AndroidLocalDnsTransport(
    private val connectivityManager: ConnectivityManager,
) : LocalDNSTransport {

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun exchange(context: ExchangeContext, message: ByteArray) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("android: raw DNS exchange requires Android 10")
        }
        exchangeRaw(context, message)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun exchangeRaw(context: ExchangeContext, message: ByteArray) = runBlocking {
        val network = connectivityManager.activeNetwork
            ?: error("android: default network is unavailable")
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            context.onCancel(cancellation::cancel)
            DnsResolver.getInstance().rawQuery(
                network,
                message,
                DnsResolver.FLAG_NO_RETRY,
                Dispatchers.IO.asExecutor(),
                cancellation,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (rcode == 0) context.rawSuccess(answer) else context.errorCode(rcode)
                        continuation.resume(Unit)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        val cause = error.cause
                        if (cause is ErrnoException) {
                            context.errnoCode(cause.errno)
                            continuation.resume(Unit)
                        } else {
                            continuation.resumeWithException(error)
                        }
                    }
                },
            )
        }
    }

    override fun lookup(context: ExchangeContext, network: String, domain: String) = runBlocking {
        val defaultNetwork = connectivityManager.activeNetwork
            ?: error("android: default network is unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lookupWithResolver(context, defaultNetwork, network, domain)
        } else {
            val answer = try {
                defaultNetwork.getAllByName(domain)
            } catch (_: UnknownHostException) {
                context.errorCode(3)
                return@runBlocking
            }
            context.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun lookupWithResolver(
        context: ExchangeContext,
        defaultNetwork: Network,
        network: String,
        domain: String,
    ) {
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            context.onCancel(cancellation::cancel)
            val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                    if (rcode == 0) {
                        context.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                    } else {
                        context.errorCode(rcode)
                    }
                    continuation.resume(Unit)
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) {
                        context.errnoCode(cause.errno)
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(error)
                    }
                }
            }
            val queryType = when {
                network.endsWith("4") -> DnsResolver.TYPE_A
                network.endsWith("6") -> DnsResolver.TYPE_AAAA
                else -> null
            }
            if (queryType == null) {
                DnsResolver.getInstance().query(
                    defaultNetwork, domain, DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(), cancellation, callback,
                )
            } else {
                DnsResolver.getInstance().query(
                    defaultNetwork, domain, queryType, DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(), cancellation, callback,
                )
            }
        }
    }
}
