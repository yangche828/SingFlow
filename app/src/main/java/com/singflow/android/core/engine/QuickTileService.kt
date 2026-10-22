package com.singflow.android.core.engine

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.singflow.android.MainActivity
import com.singflow.android.R
import com.singflow.android.core.model.VpnStatus

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
        tile.state = when (state.status) {
            VpnStatus.CONNECTED -> Tile.STATE_ACTIVE
            VpnStatus.CONNECTING, VpnStatus.STOPPING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = when (state.status) {
            VpnStatus.CONNECTED -> "SingFlow · 已连接"
            VpnStatus.CONNECTING -> "SingFlow · 连接中"
            else -> "SingFlow"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_singflow_logo)
        runCatching { tile.updateTile() }
    }
}
