package com.singflow.android.core.engine

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import com.singflow.android.core.database.JsonHolder
import com.singflow.android.core.model.AppSettings

/**
 * VPN 授权闸门。
 *
 * `VpnService.prepare()` 必须由 Activity 发起，本对象保存 Activity 注册的
 * `StartActivityForResult` 启动器，并在用户授权后继续启动服务。
 */
object VpnGate {

    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pending: (() -> Unit)? = null

    /** 绑定 MainActivity 的启动器（必须在其 RESUMED 前调用）。 */
    fun attach(launcher: ActivityResultLauncher<Intent>) {
        this.launcher = launcher
    }

    /** 解除绑定，避免 Activity 泄漏。 */
    fun detach() {
        launcher = null
        pending = null
    }

    /**
     * 请求 VPN 授权；已授权时立即执行 [onGranted]。
     *
     * @return 是否成功发起（或已直接执行）
     */
    fun request(activity: Activity, onGranted: () -> Unit): Boolean {
        val prepareIntent = runCatching { VpnService.prepare(activity) }.getOrNull()
        if (prepareIntent == null) {
            onGranted()
            return true
        }
        val activeLauncher = launcher ?: return false
        pending = onGranted
        return runCatching {
            activeLauncher.launch(prepareIntent)
            true
        }.getOrElse {
            pending = null
            false
        }
    }

    /** Activity 结果回调。 */
    fun onResult(granted: Boolean) {
        val action = pending
        pending = null
        if (granted) action?.invoke()
    }
}

/**
 * 面向 UI 的 VPN 控制入口。
 */
object VpnControl {

    private const val PREFS = "singflow_runtime"
    private const val KEY_CONFIG = "last_config"
    private const val KEY_SETTINGS = "last_settings"
    private const val KEY_NODE_ID = "last_node_id"
    private const val KEY_NODE_NAME = "last_node_name"
    private const val KEY_AUTO_CONNECT = "last_auto_connect"

    /**
     * 请求连接：必要时弹出系统 VPN 授权框，然后启动前台服务。
     */
    fun connect(
        context: Context,
        configJson: String,
        nodeId: String,
        nodeName: String,
        settings: AppSettings
    ) {
        persist(context, configJson, nodeId, nodeName, settings)
        val activity = context.findActivity()
        if (activity != null) {
            val issued = VpnGate.request(activity) {
                SingFlowVpnService.start(context, configJson, nodeId, nodeName, settings)
            }
            if (!issued) {
                // 没有可用的启动器时直接尝试，系统已授权则会成功。
                SingFlowVpnService.start(context, configJson, nodeId, nodeName, settings)
            }
        } else {
            SingFlowVpnService.start(context, configJson, nodeId, nodeName, settings)
        }
    }

    /** 已连接状态下热切换节点。 */
    fun reload(context: Context, configJson: String, nodeId: String, nodeName: String, settings: AppSettings) {
        persist(context, configJson, nodeId, nodeName, settings)
        SingFlowVpnService.reload(context, configJson, nodeId, nodeName, settings)
    }

    /** 断开连接。 */
    fun disconnect(context: Context) {
        SingFlowVpnService.stop(context)
    }

    /** 读取上次连接配置（磁贴 / 开机自启使用）。 */
    fun lastConfig(context: Context): LastSession? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val config = prefs.getString(KEY_CONFIG, null) ?: return null
        val settingsRaw = prefs.getString(KEY_SETTINGS, null) ?: return null
        val settings = runCatching {
            JsonHolder.json.decodeFromString(AppSettings.serializer(), settingsRaw)
        }.getOrNull() ?: return null
        return LastSession(
            configJson = config,
            settings = settings,
            nodeId = prefs.getString(KEY_NODE_ID, "").orEmpty(),
            nodeName = prefs.getString(KEY_NODE_NAME, "").orEmpty(),
            autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, false)
        )
    }

    private fun persist(
        context: Context,
        configJson: String,
        nodeId: String,
        nodeName: String,
        settings: AppSettings
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIG, configJson)
            .putString(KEY_SETTINGS, JsonHolder.json.encodeToString(AppSettings.serializer(), settings))
            .putString(KEY_NODE_ID, nodeId)
            .putString(KEY_NODE_NAME, nodeName)
            .putBoolean(KEY_AUTO_CONNECT, settings.autoConnectOnBoot)
            .apply()
    }

    /** 上次会话快照。 */
    data class LastSession(
        val configJson: String,
        val settings: AppSettings,
        val nodeId: String,
        val nodeName: String,
        val autoConnect: Boolean
    )
}

/**
 * 从 Context 链中查找 Activity。
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
