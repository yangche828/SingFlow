package com.singflow.android.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.singflow.android.core.model.AppSettings
import com.singflow.android.core.model.RoutingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** DataStore 实例（进程级单例）。 */
private val Context.singFlowDataStore: DataStore<Preferences> by preferencesDataStore(name = "singflow_settings")

/**
 * 应用设置持久化管理器，基于 DataStore<Preferences>。
 *
 * @param context 应用上下文
 * @param json JSON 编解码器
 */
class PreferencesManager(
    private val context: Context,
    private val json: Json
) {
    private val settingsKey = stringPreferencesKey("app_settings_json")

    /** 设置流，任何异常都会回退到默认设置。 */
    val settingsFlow: Flow<AppSettings> = context.singFlowDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs ->
            prefs[settingsKey]?.let { raw ->
                runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrNull()
            } ?: AppSettings.DEFAULT
        }

    /** 读取当前设置。 */
    suspend fun current(): AppSettings = settingsFlow.first()

    /** 覆盖保存设置。 */
    suspend fun save(settings: AppSettings) {
        context.singFlowDataStore.edit { prefs ->
            prefs[settingsKey] = json.encodeToString(AppSettings.serializer(), settings)
        }
    }

    /** 以事务方式更新设置。 */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val current = current()
        save(transform(current))
    }

    /** 记录当前选中节点。 */
    suspend fun setSelectedNode(nodeId: String) {
        update { it.copy(selectedNodeId = nodeId) }
    }

    /** 切换路由模式。 */
    suspend fun setRoutingMode(mode: RoutingMode) {
        update { it.copy(routingMode = mode) }
    }
}
