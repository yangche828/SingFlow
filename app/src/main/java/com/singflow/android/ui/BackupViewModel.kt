package com.singflow.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.singflow.android.core.backup.BackupManager
import com.singflow.android.core.backup.BackupRestoreResult
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
    private val backupManager: BackupManager
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

    /** 消费提示。 */
    fun clearMessage() {
        _message.value = null
    }
}
