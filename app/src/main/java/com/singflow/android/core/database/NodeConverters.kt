package com.singflow.android.core.database

import androidx.room.TypeConverter
import com.singflow.android.core.model.TlsConfig
import com.singflow.android.core.model.TransportConfig

/**
 * Room 类型转换器：TransportConfig / TlsConfig 以 JSON 字符串存储。
 */
class NodeConverters {
    @TypeConverter
    fun transportToString(value: TransportConfig): String = JsonHolder.json.encodeToString(TransportConfig.serializer(), value)

    @TypeConverter
    fun stringToTransport(value: String): TransportConfig = runCatching {
        JsonHolder.json.decodeFromString(TransportConfig.serializer(), value)
    }.getOrElse { TransportConfig.DEFAULT }

    @TypeConverter
    fun tlsToString(value: TlsConfig): String = JsonHolder.json.encodeToString(TlsConfig.serializer(), value)

    @TypeConverter
    fun stringToTls(value: String): TlsConfig = runCatching {
        JsonHolder.json.decodeFromString(TlsConfig.serializer(), value)
    }.getOrElse { TlsConfig.DEFAULT }
}

/**
 * 全局共享的 JSON 实例持有者，供 Room 转换器等无法注入的场景使用。
 */
object JsonHolder {
    val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }
}
