package com.singflow.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singflow.android.core.backup.BackupManager
import com.singflow.android.core.backup.BackupRestoreResult
import com.singflow.android.core.backup.WebDavClient
import com.singflow.android.core.backup.WebDavCrypto
import com.singflow.android.core.database.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 导出与备份 ViewModel。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
    private val preferencesManager: PreferencesManager,
    private val webDavClient: WebDavClient
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性操作提示。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 生成备份 JSON（IO 线程）。 */
    suspend fun buildBackup(): String = withContext(Dispatchers.IO) {
        backupManager.buildBackup()
    }

    /** 从备份 JSON 恢复。 */
    fun restore(raw: String) {
        viewModelScope.launch {
            val result: BackupRestoreResult = withContext(Dispatchers.IO) {
                backupManager.restore(raw)
            }
            _message.value = result.message
        }
    }

    /** 直接提示（复制/分享等）。 */
    fun notify(message: String) {
        _message.value = message
    }

    /**
     * 保存 WebDAV 地址与账号；密码经 Android Keystore 加密后落盘。
     *
     * 密码留空表示保留原密码。
     */
    fun saveWebDav(url: String, username: String, password: String) {
        viewModelScope.launch {
            val current = preferencesManager.current()
            val cipher = if (password.isNotBlank()) {
                WebDavCrypto.encrypt(password)
            } else {
                current.webdavPasswordCipher
            }
            preferencesManager.update {
                it.copy(
                    webdavUrl = url.trim(),
                    webdavUsername = username.trim(),
                    webdavPasswordCipher = cipher
                )
            }
            _message.value = "WebDAV 配置已保存"
        }
    }

    /** 上传当前备份到 WebDAV。 */
    fun uploadWebDav() {
        viewModelScope.launch {
            val current = preferencesManager.current()
            val payload = withContext(Dispatchers.IO) { backupManager.buildBackup() }
            val result = webDavClient.upload(
                url = current.webdavUrl,
                username = current.webdavUsername,
                password = WebDavCrypto.decrypt(current.webdavPasswordCipher),
                content = payload
            )
            _message.value = result.message
        }
    }

    /** 从 WebDAV 下载并恢复备份。 */
    fun downloadWebDav() {
        viewModelScope.launch {
            val current = preferencesManager.current()
            val result = webDavClient.download(
                url = current.webdavUrl,
                username = current.webdavUsername,
                password = WebDavCrypto.decrypt(current.webdavPasswordCipher)
            )
            if (!result.success || result.content.isNullOrBlank()) {
                _message.value = result.message
                return@launch
            }
            val restored = withContext(Dispatchers.IO) { backupManager.restore(result.content) }
            _message.value = "${result.message}；${restored.message}"
        }
    }

    /** 消费提示。 */
    fun clearMessage() {
        _message.value = null
    }
}
