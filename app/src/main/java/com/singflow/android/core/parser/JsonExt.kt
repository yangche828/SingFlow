package com.singflow.android.core.parser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 读取 JSON 字符串字段，兼容数字 / 布尔 / null。
 */
fun JsonObject.stringOrEmpty(key: String): String {
    val element: JsonElement = this[key] ?: return ""
    if (element is JsonNull) return ""
    return runCatching { (element as JsonPrimitive).content }.getOrElse { "" }
}

/**
 * 读取 JSON 整数字段，兼容 `"443"` 字符串与 `443` 数字。
 */
fun JsonObject.intOrDefault(key: String, default: Int): Int {
    val element: JsonElement = this[key] ?: return default
    if (element is JsonNull) return default
    val raw = runCatching { (element as JsonPrimitive).content }.getOrElse { return default }
    return raw.toIntOrNull() ?: default
}

/**
 * 读取 JSON 布尔字段，兼容 `true` / `"true"` / `1`。
 */
fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean {
    val element: JsonElement = this[key] ?: return default
    if (element is JsonNull) return default
    val raw = runCatching { (element as JsonPrimitive).content }.getOrElse { return default }
    return when (raw.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> default
    }
}

/**
 * 读取字符串数组字段。
 */
fun JsonObject.stringList(key: String): List<String> {
    val element: JsonElement = this[key] ?: return emptyList()
    if (element !is JsonArray) return emptyList()
    return element.mapNotNull { item ->
        runCatching { (item as JsonPrimitive).content }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}

/**
 * 读取嵌套对象字段。
 */
fun JsonObject.objOrNull(key: String): JsonObject? {
    val element: JsonElement = this[key] ?: return null
    return element as? JsonObject
}
