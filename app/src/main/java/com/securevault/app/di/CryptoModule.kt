package com.securevault.app.di

import android.content.Context
import com.securevault.app.data.crypto.CryptoEngine
import com.securevault.app.data.crypto.MasterKeyManager
import com.securevault.app.util.AppLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 暗号化関連コンポーネントの DI 提供モジュール。
 */
@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideMasterKeyManager(
        @ApplicationContext context: Context
    ): MasterKeyManager {
        return MasterKeyManager(context)
    }

    @Provides
    @Singleton
    fun provideCryptoEngine(
        @ApplicationContext context: Context,
        masterKeyManager: MasterKeyManager,
        logger: AppLogger
    ): CryptoEngine {
        return CryptoEngine(context, masterKeyManager, logger)
    }
}
