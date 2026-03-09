package com.securevault.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.securevault.app.data.db.dao.CredentialDao
import com.securevault.app.data.db.entity.CredentialEntity

/**
 * SecureVault の Room データベース定義。
 */
@Database(
    entities = [CredentialEntity::class],
    version = 3,
    exportSchema = true
)
abstract class SecureVaultDatabase : RoomDatabase() {

    /** 認証情報 DAO を返す。 */
    abstract fun credentialDao(): CredentialDao

    companion object {
        const val DATABASE_NAME: String = "securevault.db"
    }
}
