package com.securevault.app.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * バックアップ専用の暗号化ユーティリティ。
 *
 * Android Keystore ではなく、パスワードベースの鍵導出（PBKDF2）と
 * AES-256-GCM 暗号化を使用する。
 */
object BackupCrypto {

    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 32
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val AES_ALGORITHM = "AES"

    /**
     * パスワードとソルトから AES-256 キーを導出する。
     */
    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, AES_ALGORITHM)
    }

    /**
     * 平文バイト列を AES-256-GCM で暗号化する。
     *
     * @return first: 暗号文, second: IV
     */
    fun encrypt(plainBytes: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainBytes)
        return Pair(cipherBytes, iv)
    }

    /**
     * AES-256-GCM で暗号化されたデータを復号する。
     */
    fun decrypt(cipherBytes: ByteArray, iv: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(cipherBytes)
    }

    /**
     * 暗号論的にセキュアなソルトを生成する。
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }
}
