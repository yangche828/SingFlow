package com.singflow.android.core.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WebDAV 密码的 Android Keystore 加密存储。
 *
 * 明文密码不落盘；DataStore 仅保存 `Base64(IV + AES-GCM 密文)`。
 * 密钥位于 AndroidKeyStore，卸载应用或清除数据后不可恢复。
 */
object WebDavCrypto {

    private const val ALIAS = "singflow_webdav_password"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /**
     * 加密密码；空字符串返回空。
     */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = ByteArray(iv.size + encrypted.size)
            iv.copyInto(payload, 0)
            encrypted.copyInto(payload, iv.size)
            Base64.getEncoder().encodeToString(payload)
        }.getOrDefault("")
    }

    /**
     * 解密；空或失败返回空字符串。
     */
    fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return runCatching {
            val payload = Base64.getDecoder().decode(encoded)
            if (payload.size <= IV_BYTES) return@runCatching ""
            val iv = payload.copyOfRange(0, IV_BYTES)
            val encrypted = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
