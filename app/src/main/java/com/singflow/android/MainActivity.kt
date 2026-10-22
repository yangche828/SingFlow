package com.singflow.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.singflow.android.core.engine.VpnGate
import com.singflow.android.ui.MainViewModel
import com.singflow.android.ui.SingFlowRoot
import com.singflow.android.ui.theme.SingFlowTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * SingFlow 主 Activity。
 *
 * 支持 ADB / 外部调试 Intent：
 * - `EXTRA_IMPORT_URI`：导入节点并选中；
 * - `EXTRA_AUTO_CONNECT`：导入后立即连接。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        VpnGate.onResult(result.resultCode == RESULT_OK)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // 通知权限被拒绝不阻塞 VPN 核心功能。
            android.util.Log.w(TAG, "POST_NOTIFICATIONS 未授权，VPN 仍可正常启动")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        VpnGate.attach(vpnPermissionLauncher)

        setContent {
            SingFlowTheme {
                SingFlowRoot(viewModel = viewModel)
            }
        }

        requestNotificationPermissionIfNeeded()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        VpnGate.detach()
        super.onDestroy()
    }

    /**
     * 处理 ADB / 外部调试 Intent。
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri = intent.getStringExtra(EXTRA_IMPORT_URI)
        if (!uri.isNullOrBlank()) {
            val autoConnect = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)
            viewModel.importText(
                text = uri,
                groupId = "adb",
                autoConnectContext = if (autoConnect) this else null
            )
            intent.removeExtra(EXTRA_IMPORT_URI)
            intent.removeExtra(EXTRA_AUTO_CONNECT)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /** 调试导入入口：节点链接或订阅文本。 */
        const val EXTRA_IMPORT_URI = "EXTRA_IMPORT_URI"

        /** 调试入口：导入后自动连接。 */
        const val EXTRA_AUTO_CONNECT = "EXTRA_AUTO_CONNECT"
    }
}
