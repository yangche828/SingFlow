package com.singflow.android.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.singflow.android.MainActivity
import com.singflow.android.R
import com.singflow.android.core.database.JsonHolder
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.PerAppMode
import com.singflow.android.core.model.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetAddress

/**
 * SingFlow VPN 前台服务。
 *
 * 适配 Android 13 ~ 17：
 * - 前台服务类型 `systemExempted|specialUse`；
 * - TUN 建立后通过 `addDisallowedApplication` 与 `protect` 防回环；
 * - 支持 `ACTION_RELOAD` 节点热切换。
 */
class SingFlowVpnService : VpnService() {

    private var tunInterface: ParcelFileDescriptor? = null
    private var bridge: LibboxBridge? = null
    private var monitor: TrafficAndLogMonitor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var currentConfig: String = ""
    private var currentNodeId: String = ""
    private var currentNodeName: String = ""
    private var currentSettings: AppSettings = AppSettings.DEFAULT
    private var engineMode: String = "IDLE"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    VpnStateRepository.addLog("WARN", "热切换缺少配置，忽略")
                } else {
                    currentNodeId = intent.getStringExtra(EXTRA_NODE_ID).orEmpty()
                    currentNodeName = intent.getStringExtra(EXTRA_NODE_NAME).orEmpty()
                    currentSettings = parseSettings(intent.getStringExtra(EXTRA_SETTINGS))
                    reloadEngine(config)
                }
                START_STICKY
            }
            else -> {
                startTunnel(intent)
                START_STICKY
            }
        }
    }

    override fun onRevoke() {
        VpnStateRepository.addLog("WARN", "系统撤销了 VPN 授权")
        shutdown()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 建立隧道并启动内核（原生或诊断回退）。
     */
    private fun startTunnel(intent: Intent?) {
        val config = intent?.getStringExtra(EXTRA_CONFIG).orEmpty()
        if (config.isBlank()) {
            VpnStateRepository.setError("启动失败：未提供 sing-box 配置")
            stopSelf()
            return
        }
        currentConfig = config
        currentNodeId = intent?.getStringExtra(EXTRA_NODE_ID).orEmpty()
        currentNodeName = intent?.getStringExtra(EXTRA_NODE_NAME).orEmpty()
        currentSettings = parseSettings(intent?.getStringExtra(EXTRA_SETTINGS))

        VpnStateRepository.setStatus(
            status = VpnStatus.CONNECTING,
            nodeId = currentNodeId,
            nodeName = currentNodeName,
            engineMode = "STARTING"
        )
        startForegroundCompat(VpnStatus.CONNECTING)

        // 原生模式由 libbox 回调 openTun；诊断模式直接建立 TUN。
        val libbox = LibboxBridge(this) { message -> VpnStateRepository.addLog("INFO", message) }
        bridge = libbox

        // 内核初始化会阻塞到 TUN 建立完成，必须离开主线程，避免 ANR。
        scope.launch(Dispatchers.IO) {
            val started = if (libbox.isNativeAvailable) {
                libbox.start(
                    configJson = config,
                    baseDir = com.singflow.android.SingFlowApp.baseDir.absolutePath,
                    workingDir = com.singflow.android.SingFlowApp.workingDir.absolutePath,
                    tempDir = com.singflow.android.SingFlowApp.tempDir.absolutePath,
                    openTun = { options -> establishTunFromOptions(options) },
                    protectFd = { fd -> protect(fd) }
                )
            } else {
                false
            }
            engineMode = if (started) "NATIVE" else "DIAGNOSTIC"

            if (engineMode == "DIAGNOSTIC") {
                if (tunInterface == null) {
                    tunInterface = establishTun(currentSettings)
                }
                if (tunInterface == null) {
                    VpnStateRepository.setError("无法建立 TUN 网卡：请确认已授予 VPN 权限")
                    stopSelf()
                    return@launch
                }
                VpnStateRepository.addLog("WARN", "未加载可用的 libbox 内核，当前为 TUN 诊断模式（不转发流量）")
                VpnStateRepository.addLog("INFO", "已建立系统 TUN 网卡，sing-box 配置如下：")
                config.lines().forEach { line ->
                    if (line.isNotBlank()) VpnStateRepository.addLog("DEBUG", line)
                }
            } else {
                VpnStateRepository.addLog("INFO", "libbox 原生内核已接管 TUN 转发")
            }

            monitor?.stop()
            monitor = TrafficAndLogMonitor(this@SingFlowVpnService).also { it.start(scope) }

            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(2_000)
                    val snapshot = VpnStateRepository.snapshot()
                    if (snapshot.status == VpnStatus.CONNECTED) {
                        updateNotification()
                    }
                }
            }

            VpnStateRepository.setStatus(
                status = VpnStatus.CONNECTED,
                nodeId = currentNodeId,
                nodeName = currentNodeName,
                engineMode = engineMode
            )
            updateNotification()
        }
    }

    /**
     * 热切换节点：不关闭 TUN，仅重启内核。
     */
    private fun reloadEngine(config: String) {
        currentConfig = config
        VpnStateRepository.setStatus(
            status = VpnStatus.CONNECTING,
            nodeId = currentNodeId,
            nodeName = currentNodeName,
            engineMode = engineMode
        )
        VpnStateRepository.addLog("INFO", "热切换节点：$currentNodeName")
        bridge?.stop()
        val libbox = LibboxBridge(this) { message -> VpnStateRepository.addLog("INFO", message) }
        bridge = libbox
        scope.launch(Dispatchers.IO) {
            val started = libbox.isNativeAvailable && libbox.start(
                configJson = config,
                baseDir = com.singflow.android.SingFlowApp.baseDir.absolutePath,
                workingDir = com.singflow.android.SingFlowApp.workingDir.absolutePath,
                tempDir = com.singflow.android.SingFlowApp.tempDir.absolutePath,
                openTun = { options -> establishTunFromOptions(options) },
                protectFd = { fd -> protect(fd) }
            )
            engineMode = if (started) "NATIVE" else "DIAGNOSTIC"
            if (engineMode == "DIAGNOSTIC") {
                VpnStateRepository.addLog("WARN", "诊断模式：配置已更新，未加载转发内核")
            }
            VpnStateRepository.setStatus(
                status = VpnStatus.CONNECTED,
                nodeId = currentNodeId,
                nodeName = currentNodeName,
                engineMode = engineMode
            )
            updateNotification()
        }
    }

    /**
     * 建立系统 TUN 虚拟网卡。
     */
    private fun establishTun(settings: AppSettings): ParcelFileDescriptor? {
        return runCatching {
            val builder = Builder()
                .setSession("SingFlow")
                .setMtu(settings.tunMtu)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .setMetered(false)

            if (settings.enableIpv6) {
                builder.addAddress("fdfe:dcba:9876::1", 126)
                builder.addRoute("::", 0)
                builder.addDnsServer("2606:4700:4700::1111")
            }

            // 首先排除自身，防止代理回环。
            runCatching { builder.addDisallowedApplication(packageName) }

            if (settings.perAppProxyEnabled && settings.perAppPackages.isNotEmpty()) {
                when (settings.perAppProxyMode) {
                    PerAppMode.ALLOW_LIST -> settings.perAppPackages.forEach { pkg ->
                        runCatching { builder.addAllowedApplication(pkg) }
                            .onFailure { Log.w(TAG, "addAllowedApplication 失败: $pkg", it) }
                    }
                    PerAppMode.DISALLOW_LIST -> settings.perAppPackages.forEach { pkg ->
                        runCatching { builder.addDisallowedApplication(pkg) }
                            .onFailure { Log.w(TAG, "addDisallowedApplication 失败: $pkg", it) }
                    }
                }
            }

            val fd = builder.establish()
                ?: return@runCatching null

            runCatching {
                val protected = protect(fd.fd)
                VpnStateRepository.addLog(
                    if (protected) "DEBUG" else "WARN",
                    "protect(tun.fd=${fd.fd}) = $protected"
                )
            }
            VpnStateRepository.addLog("INFO", "TUN 已建立: 172.19.0.1/30, MTU=${settings.tunMtu}")
            tunInterface?.let { previous -> runCatching { previous.close() } }
            tunInterface = fd
            fd
        }.getOrElse { throwable ->
            Log.e(TAG, "establishTun failed", throwable)
            VpnStateRepository.addLog("ERROR", "建立 TUN 失败: ${throwable.message}")
            null
        }
    }

    /**
     * 由 libbox 下发的 `TunOptions` 建立系统 TUN（原生模式）。
     *
     * 地址、MTU、路由与分应用列表均由内核根据配置计算，App 只负责落到 `VpnService.Builder`；
     * DNS 优先使用内核下发的地址，内核未下发时用 [fallbackTunDnsServers] 兜底（否则客户端 DNS 会绕过隧道）。
     *
     * @return TUN fd；失败返回 -1
     */
    private fun establishTunFromOptions(options: Any): Int {
        return runCatching {
            if (VpnService.prepare(this) != null) {
                VpnStateRepository.addLog("ERROR", "缺少 VPN 授权，无法建立 TUN")
                return -1
            }

            val builder = Builder()
                .setSession("SingFlow")
                .setMtu(LibboxTunOptions.mtu(options).coerceAtLeast(1280))
                .setMetered(false)

            val addresses = LibboxTunOptions.addresses(options)
            addresses.forEach { (address, prefix) ->
                runCatching { builder.addAddress(address, prefix) }
                    .onFailure { Log.w(TAG, "addAddress($address/$prefix) 失败", it) }
            }

            if (LibboxTunOptions.autoRoute(options)) {
                // 客户端（浏览器等）的 DNS 必须由 TUN 承载：内核没有下发 DNS 服务器时
                // 必须补一个兜底地址，否则 Android 会退回底层网络的解析器，
                // 客户端 DNS 将完全绕过隧道（既不经过 hijack-dns，也拿不到远程 DoH 的结果）。
                val kernelDns = if (LibboxTunOptions.dnsEnabled(options)) {
                    LibboxTunOptions.dnsServers(options)
                } else {
                    emptyList()
                }
                val appliedDns = kernelDns.ifEmpty { fallbackTunDnsServers(currentSettings.enableIpv6) }
                appliedDns.forEach { server ->
                    runCatching { builder.addDnsServer(server) }
                        .onFailure { Log.w(TAG, "addDnsServer($server) 失败", it) }
                }
                VpnStateRepository.addLog(
                    "INFO",
                    "TUN DNS: 内核下发=${kernelDns.ifEmpty { listOf("无") }.joinToString(",")}, " +
                        "实际下发=${appliedDns.joinToString(",")}"
                )

                val routes = LibboxTunOptions.routes(options)
                if (routes.isNotEmpty()) {
                    routes.forEach { (address, prefix) ->
                        runCatching { builder.addRoute(address, prefix) }
                            .onFailure { Log.w(TAG, "addRoute($address/$prefix) 失败", it) }
                    }
                } else {
                    builder.addRoute("0.0.0.0", 0)
                    if (currentSettings.enableIpv6) {
                        runCatching { builder.addRoute("::", 0) }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    LibboxTunOptions.excludeRoutes(options).forEach { (address, prefix) ->
                        runCatching {
                            builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
                        }.onFailure { Log.w(TAG, "excludeRoute($address/$prefix) 失败", it) }
                    }
                }

                val includePackages = LibboxTunOptions.includePackages(options)
                if (includePackages.isNotEmpty()) {
                    includePackages.forEach { pkg ->
                        runCatching { builder.addAllowedApplication(pkg) }
                            .onFailure { Log.w(TAG, "addAllowedApplication($pkg) 失败", it) }
                    }
                }

                LibboxTunOptions.excludePackages(options).forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "addDisallowedApplication($pkg) 失败", it) }
                }
            }

            // 始终排除自身，避免代理流量绕回 TUN。
            runCatching { builder.addDisallowedApplication(packageName) }
                .onFailure { Log.w(TAG, "addDisallowedApplication(self) 失败", it) }

            val pfd = builder.establish() ?: return -1
            val previous = tunInterface
            tunInterface = pfd
            previous?.let { old -> if (old !== pfd) runCatching { old.close() } }

            val summary = addresses.joinToString(", ") { (address, prefix) -> "$address/$prefix" }
            VpnStateRepository.addLog(
                "INFO",
                "TUN 已建立: ${summary.ifBlank { "未下发地址" }}, MTU=${LibboxTunOptions.mtu(options)}"
            )
            pfd.fd
        }.getOrElse { throwable ->
            Log.e(TAG, "establishTunFromOptions failed", throwable)
            VpnStateRepository.addLog("ERROR", "建立 TUN 失败: ${throwable.message}")
            -1
        }
    }

    /**
     * 内核未下发 DNS 服务器时的兜底地址。
     *
     * TUN 上的 DNS 地址只是「把客户端查询拉进隧道」的锚点：真正的解析由
     * `route.rules` 的 `sniff` + `hijack-dns` 交给 sing-box 的 DNS 模块完成，
     * 所以这里不能使用域名形式的远程 DoH 地址，只放一个稳定的 IP 锚点。
     *
     * @param ipv6Enabled 是否同时下发 IPv6 DNS
     */
    private fun fallbackTunDnsServers(ipv6Enabled: Boolean): List<String> {
        val servers = mutableListOf("1.1.1.1")
        if (ipv6Enabled) servers += "2606:4700:4700::1111"
        return servers
    }

    /**
     * 关闭隧道与内核。
     */
    private fun shutdown() {
        if (VpnStateRepository.snapshot().status != VpnStatus.IDLE) {
            VpnStateRepository.setStatus(VpnStatus.STOPPING)
        }
        runCatching { monitor?.stop() }
        monitor = null
        runCatching { bridge?.stop() }
        bridge = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        VpnStateRepository.addLog("INFO", "VPN 已断开")
        VpnStateRepository.reset()
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun parseSettings(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings.DEFAULT
        return runCatching {
            JsonHolder.json.decodeFromString(AppSettings.serializer(), raw)
        }.getOrElse { AppSettings.DEFAULT }
    }

    // ---------------------------------------------------------------------
    // 前台通知
    // ---------------------------------------------------------------------

    private fun startForegroundCompat(status: VpnStatus) {
        val notification = buildNotification(status, currentNodeName, 0L, 0L)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun updateNotification() {
        val state = VpnStateRepository.snapshot()
        val notification = buildNotification(
            status = state.status,
            nodeName = state.activeNodeName,
            upSpeed = state.uploadSpeed,
            downSpeed = state.downloadSpeed
        )
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "更新通知失败", it) }
    }

    private fun buildNotification(
        status: VpnStatus,
        nodeName: String,
        upSpeed: Long,
        downSpeed: Long
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SingFlowVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (status) {
            VpnStatus.CONNECTED -> getString(R.string.notification_title_connected)
            VpnStatus.CONNECTING -> getString(R.string.notification_title_connecting)
            VpnStatus.ERROR -> "SingFlow 连接异常"
            else -> "SingFlow"
        }
        val text = when (status) {
            VpnStatus.CONNECTED -> buildString {
                append(nodeName.ifBlank { "已连接" })
                if (upSpeed > 0 || downSpeed > 0) {
                    append(" · ↑").append(formatSpeed(upSpeed)).append(" ↓").append(formatSpeed(downSpeed))
                }
                if (engineMode == "DIAGNOSTIC") append(" · 诊断模式")
            }
            VpnStatus.CONNECTING -> "正在建立安全隧道…"
            else -> nodeName.ifBlank { "未连接" }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_singflow_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(status == VpnStatus.CONNECTED || status == VpnStatus.CONNECTING)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_vpn_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSecond / 1024.0 / 1024.0)
            bytesPerSecond >= 1024 -> String.format("%.1f KB/s", bytesPerSecond / 1024.0)
            else -> "$bytesPerSecond B/s"
        }
    }

    companion object {
        private const val TAG = "SingFlowVpnService"
        private const val CHANNEL_ID = "singflow_vpn"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.singflow.android.action.START"
        const val ACTION_STOP = "com.singflow.android.action.STOP"
        const val ACTION_RELOAD = "com.singflow.android.action.RELOAD"
        const val EXTRA_CONFIG = "EXTRA_CONFIG"
        const val EXTRA_NODE_ID = "EXTRA_NODE_ID"
        const val EXTRA_NODE_NAME = "EXTRA_NODE_NAME"
        const val EXTRA_SETTINGS = "EXTRA_SETTINGS"

        /** 启动 VPN 服务（调用方需已完成 VpnService.prepare）。 */
        fun start(context: Context, configJson: String, nodeId: String, nodeName: String, settings: AppSettings) {
            val intent = Intent(context, SingFlowVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, configJson)
                putExtra(EXTRA_NODE_ID, nodeId)
                putExtra(EXTRA_NODE_NAME, nodeName)
                putExtra(EXTRA_SETTINGS, JsonHolder.json.encodeToString(AppSettings.serializer(), settings))
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { VpnStateRepository.addLog("ERROR", "启动服务失败: ${it.message}") }
        }

        /** 热切换节点。 */
        fun reload(context: Context, configJson: String, nodeId: String, nodeName: String, settings: AppSettings) {
            val intent = Intent(context, SingFlowVpnService::class.java).apply {
                action = ACTION_RELOAD
                putExtra(EXTRA_CONFIG, configJson)
                putExtra(EXTRA_NODE_ID, nodeId)
                putExtra(EXTRA_NODE_NAME, nodeName)
                putExtra(EXTRA_SETTINGS, JsonHolder.json.encodeToString(AppSettings.serializer(), settings))
            }
            runCatching { context.startService(intent) }
                .onFailure { VpnStateRepository.addLog("ERROR", "热切换失败: ${it.message}") }
        }

        /** 停止 VPN 服务。 */
        fun stop(context: Context) {
            val intent = Intent(context, SingFlowVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure {
                    VpnStateRepository.addLog("WARN", "停止服务失败: ${it.message}")
                    VpnStateRepository.reset()
                }
        }
    }
}
