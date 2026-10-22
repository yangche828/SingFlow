package com.singflow.android.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加密备份口令编解码测试。
 */
class BackupCipherTest {

    private val plain = """{"version":1,"nodes":[{"name":"测试","server":"127.0.0.1"}]}"""

    @Test
    fun `encrypt and decrypt round trip`() {
        val encrypted = BackupCipher.encrypt(plain, "correct horse")
        assertTrue(BackupCipher.isEncrypted(encrypted))
        assertFalse(encrypted.contains("127.0.0.1"))
        assertEquals(plain, BackupCipher.decrypt(encrypted, "correct horse"))
    }

    @Test
    fun `wrong passphrase returns null`() {
        val encrypted = BackupCipher.encrypt(plain, "right")
        assertNull(BackupCipher.decrypt(encrypted, "wrong"))
        assertNull(BackupCipher.decrypt(encrypted, ""))
    }

    @Test
    fun `plain json is not detected as encrypted`() {
        assertFalse(BackupCipher.isEncrypted(plain))
        assertFalse(BackupCipher.isEncrypted("not json"))
    }
}
