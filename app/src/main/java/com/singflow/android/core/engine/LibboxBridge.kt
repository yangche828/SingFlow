package com.singflow.android.core.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.system.OsConstants
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * libbox（sing-box 1.14+）双模引擎桥接。
 *
 * sing-box 1.14 的 Android 绑定不再提供 `Libbox.newService`，而是：
 * 1. `Libbox.setup(SetupOptions)` 初始化路径与日志；
 * 2. `Libbox.newCommandServer(CommandServerHandler, PlatformInterface)` 创建内核；
 * 3. `CommandServer.startOrReloadService(configJson, OverrideOptions)` 启动配置；
 * 4. 内核通过 `PlatformInterface.openTun(TunOptions)` 回调索取 TUN fd。
 *
 * 全部通过反射调用，因此 `libbox.aar` 缺失时项目依然可以编译运行（诊断模式）。
 *
 * @param context 用于 ConnectityManager / PackageManager
 * @param logSink 内核日志回调
 */
class LibboxBridge(
    private val context: Context,
    private val logSink: (String) -> Unit = {}
) {

    /** 是否检测到原生 libbox。 */
    val isNativeAvailable: Boolean by lazy {
        runCatching { Class.forName(LIBBOX) }.isSuccess
    }

    private var commandServer: Any? = null
    private var started: Boolean = false
    private var setupDone: Boolean = false

    private var tunOpener: ((Any) -> Int)? = null
    private var fdProtector: ((Int) -> Boolean)? = null

    private val monitorThread: HandlerThread by lazy {
        HandlerThread("singflow-netmon").apply { start() }
    }
    private var monitorListener: Any? = null
    private var monitorRegistered = false
    private var connectivityManager: ConnectivityManager? = null

    /**
     * 启动内核。
     *
     * 注意：本方法会阻塞到内核完成初始化，调用方需自行放到 IO 线程。
     *
     * @param configJson 完整 sing-box 配置
     * @param baseDir 数据根目录
     * @param workingDir 工作目录
     * @param tempDir 临时目录
     * @param openTun 收到 TunOptions 后建立 TUN，返回 fd
     * @param protectFd 保护 socket 免于回环
     * @return 是否成功启动
     */
    fun start(
        configJson: String,
        baseDir: String,
        workingDir: String,
        tempDir: String,
        openTun: (Any) -> Int,
        protectFd: (Int) -> Boolean
    ): Boolean {
        if (!isNativeAvailable) {
            logSink("未检测到 libbox.aar，跳过原生内核启动")
            return false
        }
        tunOpener = openTun
        fdProtector = protectFd
        return runCatching {
            val libbox = Class.forName(LIBBOX)
            setupIfNeeded(libbox, baseDir, workingDir, tempDir)

            val handlerInterface = Class.forName(COMMAND_SERVER_HANDLER)
            val platformInterface = Class.forName(PLATFORM_INTERFACE)

            val handlerProxy = Proxy.newProxyInstance(
                handlerInterface.classLoader,
                arrayOf(handlerInterface),
                CommandHandler()
            )
            val platformProxy = Proxy.newProxyInstance(
                platformInterface.classLoader,
                arrayOf(platformInterface),
                PlatformHandler()
            )

            val serverClass = Class.forName(COMMAND_SERVER)
            val constructor = serverClass.getConstructor(handlerInterface, platformInterface)
            val server = constructor.newInstance(handlerProxy, platformProxy)
                ?: error("CommandServer 构造失败")

            runCatching { serverClass.getMethod("start").invoke(server) }
                .onFailure { Log.w(TAG, "commandServer.start failed", it) }

            val overrideClass = Class.forName(OVERRIDE_OPTIONS)
            val override = overrideClass.getDeclaredConstructor().newInstance()
            serverClass
                .getMethod("startOrReloadService", String::class.java, overrideClass)
                .invoke(server, configJson, override)

            commandServer = server
            started = true
            logSink("sing-box 内核已启动: ${version()}")
            true
        }.getOrElse { throwable ->
            val cause = unwrap(throwable)
            logSink("libbox 启动失败: ${cause.javaClass.simpleName}: ${cause.message}")
            Log.w(TAG, "libbox start failed", cause)
            stop()
            false
        }
    }

    /** 停止内核。 */
    fun stop() {
        val server = commandServer
        commandServer = null
        started = false
        if (server != null) {
            runCatching { server.javaClass.getMethod("closeService").invoke(server) }
                .onFailure { Log.w(TAG, "closeService failed", unwrap(it)) }
            runCatching { server.javaClass.getMethod("close").invoke(server) }
                .onFailure { Log.w(TAG, "close failed", unwrap(it)) }
        }
        stopInterfaceMonitor()
    }

    /** 原生内核是否正在运行。 */
    fun isRunning(): Boolean = started

    /** sing-box 内核版本。 */
    fun version(): String {
        return runCatching {
            Class.forName(LIBBOX).getMethod("version").invoke(null)?.toString().orEmpty()
        }.getOrDefault("")
    }

    // ---------------------------------------------------------------------
    // 初始化
    // ---------------------------------------------------------------------

    private fun setupIfNeeded(libbox: Class<*>, baseDir: String, workingDir: String, tempDir: String) {
        if (setupDone) return
        val optionsClass = Class.forName(SETUP_OPTIONS)
        val options = optionsClass.getDeclaredConstructor().newInstance()
        optionsClass.getMethod("setBasePath", String::class.java).invoke(options, baseDir)
        optionsClass.getMethod("setWorkingPath", String::class.java).invoke(options, workingDir)
        optionsClass.getMethod("setTempPath", String::class.java).invoke(options, tempDir)
        optionsClass.getMethod("setLogMaxLines", Long::class.javaPrimitiveType).invoke(options, 3000L)
        optionsClass.getMethod("setDebug", Boolean::class.javaPrimitiveType).invoke(options, false)
        runCatching {
            libbox.getMethod("setLocale", String::class.java)
                .invoke(null, java.util.Locale.getDefault().toLanguageTag())
        }
        libbox.getMethod("setup", optionsClass).invoke(null, options)
        setupDone = true
    }

    // ---------------------------------------------------------------------
    // PlatformInterface 动态代理
    // ---------------------------------------------------------------------

    /**
     * 把内核需要的平台能力桥接回 Android。
     */
    private inner class PlatformHandler : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val safeArgs = args ?: emptyArray()
            return when (method.name) {
                "openTun" -> {
                    val options = safeArgs.getOrNull(0) ?: return -1
                    runCatching { tunOpener?.invoke(options) ?: -1 }
                        .onFailure { Log.e(TAG, "openTun failed", unwrap(it)) }
                        .getOrDefault(-1)
                }
                "autoDetectInterfaceControl" -> {
                    val fd = (safeArgs.getOrNull(0) as? Int) ?: return null
                    val protected = runCatching { fdProtector?.invoke(fd) ?: false }
                        .onFailure { Log.w(TAG, "protect($fd) failed", unwrap(it)) }
                        .getOrDefault(false)
                    Log.d(TAG, "protect fd=$fd -> $protected")
                    null
                }
                "usePlatformAutoDetectInterfaceControl" -> true
                "useProcFS" -> false
                "findConnectionOwner" -> findConnectionOwner(safeArgs)
                "startDefaultInterfaceMonitor" -> {
                    safeArgs.getOrNull(0)?.let { startInterfaceMonitor(it) }
                    null
                }
                "closeDefaultInterfaceMonitor" -> {
                    stopInterfaceMonitor()
                    null
                }
                "getInterfaces" -> buildNetworkInterfaceIterator()
                "readWIFIState" -> null
                "localDNSTransport" -> null
                "clearDNSCache" -> null
                "underNetworkExtension" -> false
                "includeAllNetworks" -> false
                "usePlatformShell" -> false
                "usePlatformBridge" -> false
                "sendNotification", "cancelNotification", "registerMyInterface",
                "checkPlatformShell", "lookupUser", "lookupSFTPServer",
                "readSystemSSHHostKey", "openShellSession" -> null
                else -> defaultValue(method.returnType)
            }
        }
    }

    /**
     * 通过 ConnectivityManager 查询 socket 归属 UID。
     *
     * 注意：libbox 的 Go 侧会直接解引用本方法的返回值（`experimental/libbox/service.go` 中的
     * `FindConnectionOwner`），因此**必须返回非空对象**；查不到归属时返回 `userId = -1`
     * 的空对象，否则原生层会因空指针直接 SIGSEGV。
     */
    private fun findConnectionOwner(args: Array<out Any?>): Any {
        val owner = newConnectionOwner(-1, emptyList())
        runCatching {
            val protocol = (args.getOrNull(0) as? Int) ?: return owner
            val source = args.getOrNull(1)?.toString().orEmpty()
            val sourcePort = (args.getOrNull(2) as? Int) ?: 0
            val destination = args.getOrNull(3)?.toString().orEmpty()
            val destinationPort = (args.getOrNull(4) as? Int) ?: 0
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return owner
            val uid = manager.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(source, sourcePort),
                InetSocketAddress(destination, destinationPort)
            )
            if (uid < 0) return owner
            return newConnectionOwner(uid, context.packageManager.getPackagesForUid(uid)?.toList().orEmpty())
        }.onFailure { Log.w(TAG, "findConnectionOwner failed", unwrap(it)) }
        return owner
    }

    /**
     * 构造 libbox `ConnectionOwner`。
     *
     * @param uid 归属 UID，`-1` 表示未知
     * @param packages 该 UID 对应的包名列表
     */
    private fun newConnectionOwner(uid: Int, packages: List<String>): Any {
        val ownerClass = Class.forName(CONNECTION_OWNER)
        val owner = ownerClass.getDeclaredConstructor().newInstance()
        ownerClass.getMethod("setUserId", Int::class.javaPrimitiveType).invoke(owner, uid)
        ownerClass.getMethod("setUserName", String::class.java)
            .invoke(owner, packages.firstOrNull().orEmpty())
        ownerClass.getMethod("setAndroidPackageNames", Class.forName(STRING_ITERATOR))
            .invoke(owner, stringIterator(packages))
        return owner
    }

    // ---------------------------------------------------------------------
    // 默认网络监听
    // ---------------------------------------------------------------------

    private fun startInterfaceMonitor(listener: Any) {
        monitorListener = listener
        if (monitorRegistered) {
            pushDefaultInterface(null)
            return
        }
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = manager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        runCatching {
            manager.registerBestMatchingNetworkCallback(
                request,
                networkCallback,
                Handler(monitorThread.looper)
            )
            monitorRegistered = true
        }.onFailure {
            Log.w(TAG, "registerBestMatchingNetworkCallback failed", unwrap(it))
        }
        pushDefaultInterface(null)
    }

    private fun stopInterfaceMonitor() {
        if (monitorRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
                .onFailure { Log.w(TAG, "unregisterNetworkCallback failed", unwrap(it)) }
        }
        monitorRegistered = false
        monitorListener = null
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            pushDefaultInterface(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            pushDefaultInterface(network)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            pushDefaultInterface(network)
        }

        override fun onLost(network: Network) {
            // 物理网络断开时重新解析，仍保留可用的非 VPN 网卡。
            pushDefaultInterface(null)
        }
    }

    /**
     * 计算默认网络对应的物理网卡名与索引并推送给内核。
     *
     * 注意：VPN 建立后系统默认网络会变成 tun0，但内核必须绑定到 **物理网卡**，
     * 否则出站流量会重新进入 TUN 形成回环。因此这里始终解析非 tun 网卡。
     */
    private fun pushDefaultInterface(network: Network?) {
        val resolved = resolvePhysicalInterface(network)
        if (resolved == null) {
            updateDefaultInterface("", -1)
            return
        }
        updateDefaultInterface(resolved.first, resolved.second)
    }

    private fun resolvePhysicalInterface(preferred: Network?): Pair<String, Int>? {
        val manager = connectivityManager ?: return null
        val candidates = mutableListOf<Network?>()
        if (preferred != null) candidates += preferred
        candidates += runCatching { manager.activeNetwork }.getOrNull()
        candidates += runCatching { manager.allNetworks.toList() }.getOrDefault(emptyList())

        for (network in candidates) {
            if (network == null) continue
            val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val name = runCatching { manager.getLinkProperties(network) }.getOrNull()?.interfaceName
                ?: continue
            if (name.startsWith("tun")) continue
            val index = runCatching { JavaNetworkInterface.getByName(name).index }.getOrDefault(-1)
            if (index <= 0) continue
            return name to index
        }
        return null
    }

    private fun updateDefaultInterface(name: String, index: Int) {
        val listener = monitorListener ?: return
        Log.d(TAG, "default interface -> ${name.ifBlank { "none" }} ($index)")
        runCatching {
            listener.javaClass
                .getMethod(
                    "updateDefaultInterface",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                .invoke(listener, name, index, false, false)
        }.onFailure { Log.w(TAG, "updateDefaultInterface failed", unwrap(it)) }
    }

    // ---------------------------------------------------------------------
    // 网卡枚举（sing-tun 的 InterfaceFinder 依赖该列表解析默认网卡）
    // ---------------------------------------------------------------------

    /**
     * 把 Android 的物理网卡列表转换为 libbox 的 `NetworkInterfaceIterator`。
     *
     * sing-tun 在收到默认网卡更新后会调用 `NetworkInterfaces()` 并按 index 查找，
     * 因此这里必须返回真实数据，否则会出现 “no available network interface”。
     */
    private fun buildNetworkInterfaceIterator(): Any {
        val interfaces = runCatching { buildNetworkInterfaces() }
            .onFailure { Log.w(TAG, "buildNetworkInterfaces failed", unwrap(it)) }
            .getOrDefault(emptyList())
        return iterator(NETWORK_INTERFACE_ITERATOR, interfaces)
    }

    private fun buildNetworkInterfaces(): List<Any> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val javaInterfaces = runCatching {
            JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val result = mutableListOf<Any>()
        for (network in manager.allNetworks) {
            val linkProperties = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: continue
            val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull() ?: continue
            val name = linkProperties.interfaceName ?: continue
            // tun 由内核自己管理，不参与默认网卡选择。
            if (name.startsWith("tun")) continue
            val javaInterface = javaInterfaces.firstOrNull { it.name == name } ?: continue

            val type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    libboxConstant("InterfaceTypeWIFI", 1)
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    libboxConstant("InterfaceTypeCellular", 2)
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    libboxConstant("InterfaceTypeEthernet", 3)
                else -> libboxConstant("InterfaceTypeOther", 0)
            }

            var flags = 0
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (javaInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
            if (javaInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
            if (javaInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST

            val addresses = javaInterface.interfaceAddresses.mapNotNull { item ->
                val host = item.address?.hostAddress ?: return@mapNotNull null
                "${host.substringBefore('%')}/${item.networkPrefixLength}"
            }
            val dnsServers = linkProperties.dnsServers.mapNotNull { it.hostAddress }
            val gateways = linkProperties.routes
                .filter { it.destination.prefixLength == 0 }
                .mapNotNull { it.gateway }
                .filterNot { it.isAnyLocalAddress }
                .mapNotNull { it.hostAddress }
            val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            result += newNetworkInterface(
                name = name,
                index = javaInterface.index,
                mtu = runCatching { javaInterface.mtu }.getOrDefault(1500),
                addresses = addresses,
                dnsServers = dnsServers,
                gateways = gateways,
                flags = flags,
                type = type,
                metered = metered
            )
        }
        return result
    }

    private fun newNetworkInterface(
        name: String,
        index: Int,
        mtu: Int,
        addresses: List<String>,
        dnsServers: List<String>,
        gateways: List<String>,
        flags: Int,
        type: Int,
        metered: Boolean
    ): Any {
        val cls = Class.forName(LIBBOX_NETWORK_INTERFACE)
        val boxInterface = cls.getDeclaredConstructor().newInstance()
        val stringIteratorClass = Class.forName(STRING_ITERATOR)
        cls.getMethod("setName", String::class.java).invoke(boxInterface, name)
        cls.getMethod("setIndex", Int::class.javaPrimitiveType).invoke(boxInterface, index)
        cls.getMethod("setMTU", Int::class.javaPrimitiveType).invoke(boxInterface, mtu)
        cls.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(boxInterface, flags)
        cls.getMethod("setType", Int::class.javaPrimitiveType).invoke(boxInterface, type)
        cls.getMethod("setMetered", Boolean::class.javaPrimitiveType).invoke(boxInterface, metered)
        cls.getMethod("setAddresses", stringIteratorClass)
            .invoke(boxInterface, stringIterator(addresses))
        cls.getMethod("setDNSServer", stringIteratorClass)
            .invoke(boxInterface, stringIterator(dnsServers))
        cls.getMethod("setGateway", stringIteratorClass)
            .invoke(boxInterface, stringIterator(gateways))
        return boxInterface
    }

    private fun libboxConstant(name: String, fallback: Int): Int {
        return runCatching {
            Class.forName(LIBBOX).getField(name).getInt(null)
        }.getOrDefault(fallback)
    }

    // ---------------------------------------------------------------------
    // CommandServerHandler 动态代理
    // ---------------------------------------------------------------------

    /**
     * 内核命令回调；仅记录日志，其余能力（系统代理、SSH Agent）由 App 层控制。
     */
    private inner class CommandHandler : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
            val safeArgs = args ?: emptyArray()
            return when (method.name) {
                "writeDebugMessage" -> {
                    val message = safeArgs.getOrNull(0)?.toString().orEmpty()
                    if (message.isNotBlank()) logSink(message)
                    null
                }
                "serviceReload", "serviceStop", "setSystemProxyEnabled",
                "triggerNativeCrash" -> null
                "connectSSHAgent" -> -1
                "getSystemProxyStatus" -> newSystemProxyStatus()
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun newSystemProxyStatus(): Any? {
        return runCatching {
            val cls = Class.forName("io.nekohasekai.libbox.SystemProxyStatus")
            val status = cls.getDeclaredConstructor().newInstance()
            cls.getMethod("setAvailable", Boolean::class.javaPrimitiveType).invoke(status, false)
            cls.getMethod("setEnabled", Boolean::class.javaPrimitiveType).invoke(status, false)
            status
        }.getOrNull()
    }

    // ---------------------------------------------------------------------
    // 反射工具
    // ---------------------------------------------------------------------

    private fun stringIterator(values: List<String>): Any {
        val iteratorInterface = Class.forName(STRING_ITERATOR)
        val snapshot = values.toList()
        var cursor = 0
        return Proxy.newProxyInstance(
            iteratorInterface.classLoader,
            arrayOf(iteratorInterface),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "hasNext" -> cursor < snapshot.size
                    "next" -> if (cursor < snapshot.size) snapshot[cursor++] else ""
                    "len" -> snapshot.size
                    else -> defaultValue(method.returnType)
                }
            }
        )
    }

    private fun iterator(interfaceName: String, values: List<Any>): Any {
        val cls = Class.forName(interfaceName)
        val snapshot = values.toList()
        var cursor = 0
        return Proxy.newProxyInstance(
            cls.classLoader,
            arrayOf(cls),
            InvocationHandler { _, method, _ ->
                when (method.name) {
                    "hasNext" -> cursor < snapshot.size
                    "next" -> if (cursor < snapshot.size) snapshot[cursor++] else null
                    "len" -> snapshot.size
                    else -> defaultValue(method.returnType)
                }
            }
        )
    }

    private fun unwrap(throwable: Throwable): Throwable {
        var current = throwable
        while (current is java.lang.reflect.InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    private companion object {
        private const val TAG = "LibboxBridge"

        const val LIBBOX = "io.nekohasekai.libbox.Libbox"
        const val COMMAND_SERVER = "io.nekohasekai.libbox.CommandServer"
        const val COMMAND_SERVER_HANDLER = "io.nekohasekai.libbox.CommandServerHandler"
        const val PLATFORM_INTERFACE = "io.nekohasekai.libbox.PlatformInterface"
        const val SETUP_OPTIONS = "io.nekohasekai.libbox.SetupOptions"
        const val OVERRIDE_OPTIONS = "io.nekohasekai.libbox.OverrideOptions"
        const val CONNECTION_OWNER = "io.nekohasekai.libbox.ConnectionOwner"
        const val STRING_ITERATOR = "io.nekohasekai.libbox.StringIterator"
        const val NETWORK_INTERFACE_ITERATOR = "io.nekohasekai.libbox.NetworkInterfaceIterator"
        const val LIBBOX_NETWORK_INTERFACE = "io.nekohasekai.libbox.NetworkInterface"
    }
}

/**
 * libbox `TunOptions` 的反射读取器。
 *
 * 内核根据配置中的 tun inbound 计算地址、MTU、路由与分应用列表，
 * 再通过该对象交回 App 建立 VpnService。
 */
object LibboxTunOptions {

    /** TUN MTU。 */
    fun mtu(options: Any): Int {
        return runCatching { options.javaClass.getMethod("getMTU").invoke(options) as Int }
            .getOrDefault(9000)
    }

    /** 是否由内核下发路由（对应 `auto_route`）。 */
    fun autoRoute(options: Any): Boolean {
        return runCatching { options.javaClass.getMethod("getAutoRoute").invoke(options) as Boolean }
            .getOrDefault(true)
    }

    /** 是否由内核下发 DNS（对应 `dns.mode` 不为 disabled）。 */
    fun dnsEnabled(options: Any): Boolean {
        return runCatching {
            val box = options.javaClass.getMethod("getDNSMode").invoke(options) ?: return false
            val mode = box.javaClass.getMethod("getValue").invoke(box)?.toString().orEmpty()
            mode.isNotEmpty() && mode != "disabled"
        }.getOrDefault(true)
    }

    /** TUN 自身地址（address + prefix）。 */
    fun addresses(options: Any): List<Pair<String, Int>> {
        return prefixList(invoke(options, "getInet4Address")) +
            prefixList(invoke(options, "getInet6Address"))
    }

    /** 需要下发的 DNS 服务器。 */
    fun dnsServers(options: Any): List<String> {
        return stringList(invoke(options, "getDNSServerAddress"))
    }

    /** 需要下发的 IPv4/IPv6 路由。 */
    fun routes(options: Any): List<Pair<String, Int>> {
        return prefixList(invoke(options, "getInet4RouteAddress")) +
            prefixList(invoke(options, "getInet6RouteAddress"))
    }

    /** 需要排除的路由。 */
    fun excludeRoutes(options: Any): List<Pair<String, Int>> {
        return prefixList(invoke(options, "getInet4RouteExcludeAddress")) +
            prefixList(invoke(options, "getInet6RouteExcludeAddress"))
    }

    /** 仅代理这些应用。 */
    fun includePackages(options: Any): List<String> {
        return stringList(invoke(options, "getIncludePackage"))
    }

    /** 排除这些应用。 */
    fun excludePackages(options: Any): List<String> {
        return stringList(invoke(options, "getExcludePackage"))
    }

    private fun invoke(options: Any, name: String): Any? {
        return runCatching { options.javaClass.getMethod(name).invoke(options) }.getOrNull()
    }

    private fun stringList(iterator: Any?): List<String> {
        if (iterator == null) return emptyList()
        val result = mutableListOf<String>()
        val cls = iterator.javaClass
        val hasNext = runCatching { cls.getMethod("hasNext") }.getOrNull() ?: return emptyList()
        val next = runCatching { cls.getMethod("next") }.getOrNull() ?: return emptyList()
        var guard = 0
        while (guard++ < 4096 && hasNext.invoke(iterator) == true) {
            val value = next.invoke(iterator) ?: break
            result += value.toString()
        }
        return result
    }

    private fun prefixList(iterator: Any?): List<Pair<String, Int>> {
        if (iterator == null) return emptyList()
        val result = mutableListOf<Pair<String, Int>>()
        val cls = iterator.javaClass
        val hasNext = runCatching { cls.getMethod("hasNext") }.getOrNull() ?: return emptyList()
        val next = runCatching { cls.getMethod("next") }.getOrNull() ?: return emptyList()
        var guard = 0
        while (guard++ < 4096 && hasNext.invoke(iterator) == true) {
            val prefix = next.invoke(iterator) ?: break
            val address = runCatching { prefix.javaClass.getMethod("address").invoke(prefix)?.toString() }
                .getOrNull() ?: continue
            val length = runCatching { prefix.javaClass.getMethod("prefix").invoke(prefix) as Int }
                .getOrDefault(32)
            result += address to length
        }
        return result
    }
}
