package com.singflow.android.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份 Gzip 压缩测试。
 */
class BackupCompressionTest {

    @Test
    fun `gzip round trip`() {
        val text = """{"nodes":[{"name":"测试","server":"127.0.0.1"}]}"""
        val compressed = BackupCompression.gzip(text)
        assertTrue(BackupCompression.isGzip(compressed))
        assertEquals(text, BackupCompression.gunzip(compressed))
    }

    @Test
    fun `plain text is not gzip`() {
        val plain = "{\"version\":1}".toByteArray()
        assertFalse(BackupCompression.isGzip(plain))
        assertNull(BackupCompression.gunzip(plain))
    }

    @Test
    fun `corrupted gzip returns null`() {
        val broken = byteArrayOf(0x1f, 0x8b.toByte(), 0x00, 0x01)
        assertTrue(BackupCompression.isGzip(broken))
        assertNull(BackupCompression.gunzip(broken))
    }
}
