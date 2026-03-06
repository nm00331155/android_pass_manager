package com.securevault.app.data.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore 上のマスターキーを生成・取得する。
 * StrongBox が利用可能な端末では優先して利用し、失敗時は通常 Keystore にフォールバックする。
 */
@Singleton
class MasterKeyManager @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    /** Keystore 内のキーエイリアス。 */
    val keyAlias: String = KEY_ALIAS

    /**
     * マスターキーを取得し、存在しない場合は新規作成する。
     */
    @Synchronized
    fun getOrCreateMasterKey(): SecretKey {
        val keyStore = loadKeyStore()
        val existingKey = runCatching {
            keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        }.getOrNull()

        if (existingKey != null) {
            return existingKey
        }

        return generateKey(preferStrongBox = supportsStrongBox())
    }

    /**
     * 既存キーを削除して再作成する。
     */
    @Synchronized
    fun recreateMasterKey(): SecretKey {
        deleteMasterKey()
        return getOrCreateMasterKey()
    }

    /**
     * 既存キーを削除する。存在しない場合は何もしない。
     */
    @Synchronized
    fun deleteMasterKey() {
        val keyStore = loadKeyStore()
        runCatching {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        }
    }

    private fun generateKey(preferStrongBox: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val strongBoxKey = runCatching {
                keyGenerator.init(buildKeySpec(useStrongBox = true))
                keyGenerator.generateKey()
            }.getOrNull()

            if (strongBoxKey != null) {
                return strongBoxKey
            }
        }

        keyGenerator.init(buildKeySpec(useStrongBox = false))
        return keyGenerator.generateKey()
    }

    private fun buildKeySpec(useStrongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(useStrongBox)
        }

        return builder.build()
    }

    private fun supportsStrongBox(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        return appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    private fun loadKeyStore(): KeyStore {
        return KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private companion object {
        const val KEY_ALIAS = "securevault_master_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
