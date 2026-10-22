package com.singflow.android.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * 加密备份信封。
 *
 * @property format 固定标识 `singflow-encrypted-backup`
 * @property version 信封版本
 * @property iterations PBKDF2 迭代次数
 * @property salt Base64 盐
 * @property iv Base64 GCM IV
 * @property ciphertext Base64 密文
 */
@Serializable
data class EncryptedBackupEnvelope(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val iterations: Int = DEFAULT_ITERATIONS,
    val salt: String,
    val iv: String,
    val ciphertext: String
) {
    companion object {
        /** 信封标识。 */
        const val FORMAT = "singflow-encrypted-backup"

        /** 信封版本。 */
        const val VERSION = 1

        /** 默认 PBKDF2 迭代次数。 */
        const val DEFAULT_ITERATIONS = 120_000
    }
}

/**
 * 备份口令加密（PBKDF2-HMAC-SHA256 + AES-256-GCM）。
 *
 * 使用纯 JVM 实现，便于单元测试；口令不落盘，仅用于本次导出/恢复。
 */
object BackupCipher {

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val AAD = "singflow-backup-v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 判断文本是否为加密备份信封。
     */
    fun isEncrypted(raw: String): Boolean {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return false
        val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return false
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return false
        val format = (obj["format"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return format == EncryptedBackupEnvelope.FORMAT
    }

    /**
     * 加密 JSON 备份。
     *
     * @param plain 明文 JSON
     * @param passphrase 口令
     */
    fun encrypt(plain: String, passphrase: String): String {
        require(passphrase.isNotEmpty()) { "口令不能为空" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, EncryptedBackupEnvelope.DEFAULT_ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val envelope = EncryptedBackupEnvelope(
            salt = Base64.getEncoder().encodeToString(salt),
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext)
        )
        return json.encodeToString(EncryptedBackupEnvelope.serializer(), envelope)
    }

    /**
     * 解密备份；口令错误或数据损坏返回 null。
     */
    fun decrypt(raw: String, passphrase: String): String? {
        if (passphrase.isEmpty()) return null
        return runCatching {
            val envelope = json.decodeFromString(EncryptedBackupEnvelope.serializer(), raw.trim())
            if (envelope.format != EncryptedBackupEnvelope.FORMAT) return@runCatching null
            val salt = Base64.getDecoder().decode(envelope.salt)
            val iv = Base64.getDecoder().decode(envelope.iv)
            val ciphertext = Base64.getDecoder().decode(envelope.ciphertext)
            val key = deriveKey(passphrase, salt, envelope.iterations.coerceIn(10_000, 1_000_000))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val encoded = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(encoded, "AES")
    }
}
