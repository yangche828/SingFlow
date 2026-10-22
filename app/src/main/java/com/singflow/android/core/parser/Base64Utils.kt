package com.singflow.android.core.parser

import java.util.Base64

/**
 * 纯 Kotlin/JVM Base64 工具。
 *
 * 刻意不使用 `android.util.Base64`，以保证全部解析逻辑可以在 JVM 单元测试中直接运行。
 */
object Base64Utils {

    /**
     * 宽容解码：
     * 1. 清理所有空白字符；
     * 2. URL-Safe 的 `-` / `_` 转为标准 `+` / `/`；
     * 3. 自动补齐 `=`；
     * 4. 解码为 UTF-8 字符串。
     *
     * 解码失败时返回空字符串。
     */
    fun safeBase64Decode(input: String): String = safeBase64DecodeOrNull(input) ?: ""

    /**
     * 宽容解码，失败返回 null。
     */
    fun safeBase64DecodeOrNull(input: String): String? {
        val cleaned = input.filterNot { it.isWhitespace() || it == '\r' || it == '\n' || it == '\t' }
        if (cleaned.isEmpty()) return null
        val normalized = cleaned.replace('-', '+').replace('_', '/')
        val remainder = normalized.length % 4
        val padded = when (remainder) {
            0 -> normalized
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized.dropLast(1)
        }
        val decoder = runCatching { Base64.getDecoder() }.getOrNull() ?: return null
        val bytes = runCatching { decoder.decode(padded) }.getOrElse {
            runCatching { Base64.getUrlDecoder().decode(padded) }.getOrElse { return null }
        }
        return runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
    }

    /** 标准 Base64 编码。 */
    fun encode(input: String): String = Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))

    /** URL-Safe 无填充 Base64 编码。 */
    fun encodeUrlSafe(input: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(input.toByteArray(Charsets.UTF_8))

    /**
     * 判断文本是否“可能”是一段 Base64（用于订阅内容识别）。
     */
    fun looksLikeBase64(input: String): Boolean {
        val cleaned = input.filterNot { it.isWhitespace() }
        if (cleaned.length < 8) return false
        if (cleaned.any { it == ':' || it == '/' && !cleaned.contains("://") }) {
            if (cleaned.contains("://")) return false
        }
        if (cleaned.contains("://")) return false
        return cleaned.all { ch ->
            ch.isLetterOrDigit() || ch == '+' || ch == '/' || ch == '-' || ch == '_' || ch == '='
        }
    }
}
