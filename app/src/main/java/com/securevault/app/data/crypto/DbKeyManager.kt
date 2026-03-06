package com.securevault.app.data.crypto

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.subtle.Hkdf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dbKeyStoreDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "securevault_db_key_store"
)

/**
 * SQLCipher 用パスフレーズを DataStore と Keystore で保護しながら管理する。
 */
@Singleton
class DbKeyManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val cryptoEngine: CryptoEngine
) {

    /**
     * 暗号化済みシードを元に DB パスフレーズを取得または生成する。
     * 引数の Cipher は初回シード保存時に利用する。
     */
    fun getOrCreatePassphrase(cipher: Cipher): CharArray {
        val seedBytes = getOrCreateSeed(cipher)
        val saltBytes = getOrCreateHkdfSalt()

        val derivedBytes = Hkdf.computeHkdf(
            HKDF_ALGORITHM,
            seedBytes,
            saltBytes,
            HKDF_INFO.toByteArray(Charsets.UTF_8),
            PASSPHRASE_BYTE_LENGTH
        )

        seedBytes.fill(0)
        saltBytes.fill(0)

        val passphraseString = CryptoEngine.toBase64(derivedBytes)
        derivedBytes.fill(0)
        return passphraseString.toCharArray()
    }

    /**
     * アプリ側で Cipher を明示しない取得用オーバーロード。
     */
    fun getOrCreatePassphrase(): CharArray {
        val cipher = cryptoEngine.getCipherForEncryption()
        return getOrCreatePassphrase(cipher)
    }

    private fun getOrCreateSeed(initialCipher: Cipher): ByteArray {
        val preferences = runBlocking { appContext.dbKeyStoreDataStore.data.first() }
        val encryptedSeed = preferences[ENCRYPTED_SEED_KEY]
        val seedIv = preferences[SEED_IV_KEY]

        if (encryptedSeed.isNullOrBlank() || seedIv.isNullOrBlank()) {
            val plainSeed = ByteArray(SEED_BYTE_LENGTH)
            secureRandom.nextBytes(plainSeed)
            saveEncryptedSeed(plainSeed, initialCipher)
            return plainSeed
        }

        return decryptStoredSeed(encryptedSeed, seedIv)
    }

    private fun decryptStoredSeed(encryptedSeedBase64: String, ivBase64: String): ByteArray {
        val encryptedSeedBytes = CryptoEngine.fromBase64(encryptedSeedBase64)
        val ivBytes = CryptoEngine.fromBase64(ivBase64)

        return try {
            val decryptCipher = cryptoEngine.getCipherForDecryption(ivBytes)
            val decryptedSeedBase64 = cryptoEngine.decrypt(
                EncryptedData(cipherText = encryptedSeedBytes, iv = ivBytes),
                decryptCipher
            )
            CryptoEngine.fromBase64(decryptedSeedBase64)
        } finally {
            encryptedSeedBytes.fill(0)
            ivBytes.fill(0)
        }
    }

    private fun saveEncryptedSeed(seedBytes: ByteArray, cipher: Cipher) {
        val seedBase64 = CryptoEngine.toBase64(seedBytes)
        val encrypted = cryptoEngine.encrypt(seedBase64, cipher)
        val encryptedSeedBase64 = CryptoEngine.toBase64(encrypted.cipherText)
        val seedIvBase64 = CryptoEngine.toBase64(encrypted.iv)

        runBlocking {
            appContext.dbKeyStoreDataStore.edit { settings ->
                settings[ENCRYPTED_SEED_KEY] = encryptedSeedBase64
                settings[SEED_IV_KEY] = seedIvBase64
            }
        }
    }

    private fun getOrCreateHkdfSalt(): ByteArray {
        val preferences = runBlocking { appContext.dbKeyStoreDataStore.data.first() }
        val saltBase64 = preferences[HKDF_SALT_KEY]
        if (!saltBase64.isNullOrBlank()) {
            return CryptoEngine.fromBase64(saltBase64)
        }

        val saltBytes = ByteArray(HKDF_SALT_BYTE_LENGTH)
        secureRandom.nextBytes(saltBytes)
        runBlocking {
            appContext.dbKeyStoreDataStore.edit { settings ->
                settings[HKDF_SALT_KEY] = CryptoEngine.toBase64(saltBytes)
            }
        }
        return saltBytes
    }

    private companion object {
        const val HKDF_ALGORITHM: String = "HmacSha256"
        const val HKDF_INFO: String = "securevault.db.passphrase.v1"
        const val PASSPHRASE_BYTE_LENGTH: Int = 32
        const val SEED_BYTE_LENGTH: Int = 32
        const val HKDF_SALT_BYTE_LENGTH: Int = 32

        val ENCRYPTED_SEED_KEY = stringPreferencesKey("db_encrypted_seed")
        val SEED_IV_KEY = stringPreferencesKey("db_seed_iv")
        val HKDF_SALT_KEY = stringPreferencesKey("db_hkdf_salt")

        val secureRandom: SecureRandom = SecureRandom()
    }
}
