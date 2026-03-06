package com.securevault.app.di

import com.securevault.app.biometric.BiometricAuthManager
import com.securevault.app.data.crypto.CryptoEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 生体認証関連コンポーネントの DI 提供モジュール。
 */
@Module
@InstallIn(SingletonComponent::class)
object BiometricModule {

    @Provides
    @Singleton
    fun provideBiometricAuthManager(
        cryptoEngine: CryptoEngine
    ): BiometricAuthManager {
        return BiometricAuthManager(cryptoEngine)
    }
}
