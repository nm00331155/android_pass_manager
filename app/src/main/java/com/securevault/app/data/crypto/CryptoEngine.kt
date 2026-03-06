package com.securevault.app.data.crypto

import android.content.Context
import com.securevault.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * マスターキーを利用した暗号化・復号と、Base64 変換ユーティリティを提供する。
 *
 * パフォーマンス最適化:
 * Keystore キーで保護された高速化キーを初回のみ復元し、以降の暗号化/復号では
 * メモリキャッシュ済みのソフトウェア AES キーを使う。
 */
@Singleton
class CryptoEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val masterKeyManager: MasterKeyManager,
    private val logger: AppLogger
) {

    @Volatile
    private var cachedKey: SecretKey? = null

    @Synchronized
    private fun getOrCreateFastKey(): SecretKey {
        cachedKey?.let { return it }

        val loaded = loadWrappedFastKey()
        if (loaded != null) {
            cachedKey = loaded
            return loaded
        }

        val generated = generateAndWrapFastKey()
        cachedKey = generated
        return generated
    }

    private fun loadWrappedFastKey(): SecretKey? {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val wrappedBase64 = preferences.getString(WRAPPED_KEY_PREF, null)
        val ivBase64 = preferences.getString(WRAPPED_KEY_IV_PREF, null)

        if (wrappedBase64.isNullOrBlank() || ivBase64.isNullOrBlank()) {
            return null
        }

        return runCatching {
            val wrappedBytes = fromBase64(wrappedBase64)
            val ivBytes = fromBase64(ivBase64)
            val unwrapCipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, ivBytes)
            unwrapCipher.init(Cipher.DECRYPT_MODE, masterKeyManager.getOrCreateMasterKey(), spec)
            val keyBytes = unwrapCipher.doFinal(wrappedBytes)
            SecretKeySpec(keyBytes, AES_ALGORITHM)
        }.onFailure { throwable ->
            logger.w(TAG, "loadWrappedFastKey failed, regenerate fast key", throwable)
            clearWrappedFastKey()
        }.getOrNull()
    }

    private fun generateAndWrapFastKey(): SecretKey {
        val keyBytes = ByteArray(FAST_KEY_BYTE_LENGTH)
        secureRandom.nextBytes(keyBytes)
        val fastKey = SecretKeySpec(keyBytes, AES_ALGORITHM)

        val wrapCipher = Cipher.getInstance(TRANSFORMATION)
        wrapCipher.init(Cipher.ENCRYPT_MODE, masterKeyManager.getOrCreateMasterKey())
        val wrappedBytes = wrapCipher.doFinal(keyBytes)

        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .putString(WRAPPED_KEY_PREF, toBase64(wrappedBytes))
            .putString(WRAPPED_KEY_IV_PREF, toBase64(wrapCipher.iv))
            .apply()

        logger.i(TAG, "fast key initialized")
        keyBytes.fill(0)
        return fastKey
    }

    private fun clearWrappedFastKey() {
        val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        preferences.edit()
            .remove(WRAPPED_KEY_PREF)
            .remove(WRAPPED_KEY_IV_PREF)
            .apply()
    }

    /**
     * 高速化キーで初期化した暗号化用 Cipher を返す。
     */
    fun getCipherForEncryption(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateFastKey())
        return cipher
    }

    /**
     * 高速化キーと IV で初期化した復号用 Cipher を返す。
     */
    fun getCipherForDecryption(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateFastKey(), spec)
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
        private const val TAG = "CryptoEngine"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val AES_ALGORITHM = "AES"
        private const val FAST_KEY_BYTE_LENGTH = 32
        private const val PREFERENCES_NAME = "securevault_crypto"
        private const val WRAPPED_KEY_PREF = "wrapped_fast_key"
        private const val WRAPPED_KEY_IV_PREF = "wrapped_fast_key_iv"

        private val secureRandom = SecureRandom()

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
