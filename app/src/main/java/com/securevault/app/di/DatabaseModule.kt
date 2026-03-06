package com.securevault.app.di

import android.content.Context
import androidx.room.Room
import com.securevault.app.data.crypto.CryptoEngine
import com.securevault.app.data.crypto.DbKeyManager
import com.securevault.app.data.db.SecureVaultDatabase
import com.securevault.app.data.db.dao.CredentialDao
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.CredentialRepositoryImpl
import com.securevault.app.util.AppLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Room + SQLCipher + Repository の DI 提供モジュール。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * SQLCipher で暗号化された Room データベースを提供する。
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        dbKeyManager: DbKeyManager
    ): SecureVaultDatabase {
        val passphraseChars = dbKeyManager.getOrCreatePassphrase()
        val passphraseBytes = String(passphraseChars).toByteArray(Charsets.UTF_8)
        passphraseChars.fill('\u0000')

        val factory = SupportOpenHelperFactory(passphraseBytes)
        return Room.databaseBuilder(
            context,
            SecureVaultDatabase::class.java,
            SecureVaultDatabase.DATABASE_NAME
        ).openHelperFactory(factory).build()
    }

    /**
     * CredentialDao を提供する。
     */
    @Provides
    fun provideCredentialDao(db: SecureVaultDatabase): CredentialDao {
        return db.credentialDao()
    }

    /**
     * CredentialRepository 実装を提供する。
     */
    @Provides
    @Singleton
    fun provideCredentialRepository(
        dao: CredentialDao,
        cryptoEngine: CryptoEngine,
        logger: AppLogger
    ): CredentialRepository {
        return CredentialRepositoryImpl(dao, cryptoEngine, logger)
    }
}
