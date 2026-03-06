package com.securevault.app.data.crypto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * マスターキーを利用した暗号化・復号と、Base64 変換ユーティリティを提供する。
 */
@Singleton
class CryptoEngine @Inject constructor(
    private val masterKeyManager: MasterKeyManager
) {

    /**
     * Keystore キーで初期化した暗号化用 Cipher を返す。
     */
    fun getCipherForEncryption(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val key = masterKeyManager.getOrCreateMasterKey()
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /**
     * Keystore キーと IV で初期化した復号用 Cipher を返す。
     */
    fun getCipherForDecryption(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, masterKeyManager.getOrCreateMasterKey(), spec)
        return cipher
    }

    /**
     * 平文を暗号化する。
     */
    fun encrypt(plainText: String, cipher: Cipher): EncryptedData = encryptStatic(plainText, cipher)

    /**
     * 暗号文を復号する。
     */
    fun decrypt(encryptedData: EncryptedData, cipher: Cipher): String = decryptStatic(encryptedData, cipher)

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128

        /**
         * 平文を暗号化し、暗号文と IV を返す。
         */
        fun encryptStatic(plainText: String, cipher: Cipher): EncryptedData {
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return EncryptedData(cipherText = cipherText, iv = cipher.iv)
        }

        /**
         * 暗号文を復号して平文を返す。
         */
        fun decryptStatic(encryptedData: EncryptedData, cipher: Cipher): String {
            val plainBytes = cipher.doFinal(encryptedData.cipherText)
            return String(plainBytes, Charsets.UTF_8)
        }

        /** ByteArray を Base64 に変換する。 */
        fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        /** Base64 を ByteArray に変換する。 */
        fun fromBase64(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}

/**
 * 暗号化データ本体。
 */
data class EncryptedData(
    val cipherText: ByteArray,
    val iv: ByteArray
)
