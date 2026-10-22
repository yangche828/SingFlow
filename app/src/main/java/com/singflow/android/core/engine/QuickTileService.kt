package com.singflow.android.core.engine

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.singflow.android.MainActivity
import com.singflow.android.R
import com.singflow.android.core.model.VpnStatus
import com.singflow.android.core.wifi.TrustedWifi

/**
 * 下拉通知栏 VPN 快捷磁贴。
 */
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (VpnStateRepository.snapshot().status) {
            VpnStatus.CONNECTED, VpnStatus.CONNECTING -> VpnControl.disconnect(this)
            else -> {
                val session = VpnControl.lastConfig(this)
                if (session != null) {
                    if (TrustedWifi.isTrusted(this, session.settings)) {
                        Toast.makeText(this, "当前为受信任 Wi-Fi，已跳过代理", Toast.LENGTH_SHORT).show()
                        updateTile()
                        return
                    }
                    VpnControl.connect(
                        context = this,
                        configJson = session.configJson,
                        nodeId = session.nodeId,
                        nodeName = session.nodeName,
                        settings = session.settings
                    )
                } else {
                    openApp()
                }
            }
        }
        updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = VpnStateRepository.snapshot()
        val session = VpnControl.lastConfig(this)
        val trusted = state.status != VpnStatus.CONNECTED &&
            session != null && TrustedWifi.isTrusted(this, session.settings)
        tile.state = when (state.status) {
            VpnStatus.CONNECTED -> Tile.STATE_ACTIVE
            VpnStatus.CONNECTING, VpnStatus.STOPPING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = when {
            state.status == VpnStatus.CONNECTED -> "SingFlow · 已连接"
            state.status == VpnStatus.CONNECTING -> "SingFlow · 连接中"
            trusted -> "SingFlow · 受信任 Wi-Fi"
            else -> "SingFlow"
        }
        tile.subtitle = when {
            state.status == VpnStatus.CONNECTED -> state.activeNodeName.ifBlank { "已连接" }
            trusted -> "已跳过代理"
            else -> "未连接"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_singflow_logo)
        runCatching { tile.updateTile() }
    }
}
