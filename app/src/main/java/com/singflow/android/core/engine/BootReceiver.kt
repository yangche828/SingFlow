package com.singflow.android.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用升级广播接收器。
 *
 * 仅在用户开启了「开机自动连接」时尝试恢复上次隧道。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            val session = VpnControl.lastConfig(context) ?: return
            if (!session.autoConnect) return
            Log.i(TAG, "开机自动连接: ${session.nodeName}")
            VpnControl.connect(
                context = context,
                configJson = session.configJson,
                nodeId = session.nodeId,
                nodeName = session.nodeName,
                settings = session.settings
            )
        }.onFailure { Log.w(TAG, "开机恢复失败", it) }
    }

    companion object {
        private const val TAG = "SingFlowBootReceiver"
    }
}
