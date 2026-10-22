package com.singflow.android.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 备份 Gzip 压缩工具。
 *
 * 备份 JSON 可能包含大量节点与应用规则，Gzip 可显著减小本地文件体积；
 * 恢复时通过魔数自动识别压缩文件，兼容旧版明文备份。
 */
object BackupCompression {

    private const val GZIP_MAGIC_0 = 0x1f
    private const val GZIP_MAGIC_1 = 0x8b

    /**
     * 压缩 UTF-8 文本。
     */
    fun gzip(text: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(text.toByteArray(Charsets.UTF_8))
        }
        return output.toByteArray()
    }

    /**
     * 解压为 UTF-8 文本；非 Gzip 数据返回 null。
     */
    fun gunzip(bytes: ByteArray): String? {
        if (!isGzip(bytes)) return null
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /**
     * 判断是否 Gzip 文件。
     */
    fun isGzip(bytes: ByteArray): Boolean {
        return bytes.size >= 2 &&
            (bytes[0].toInt() and 0xFF) == GZIP_MAGIC_0 &&
            (bytes[1].toInt() and 0xFF) == GZIP_MAGIC_1
    }
}
