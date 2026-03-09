package com.securevault.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room データベースのマイグレーション定義。
 */
object DatabaseMigrations {

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `credentials_new` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `serviceName` TEXT NOT NULL,
                    `serviceUrl` TEXT,
                    `packageName` TEXT,
                    `encryptedUsername` TEXT NOT NULL,
                    `usernameIv` TEXT NOT NULL,
                    `encryptedPassword` TEXT,
                    `passwordIv` TEXT,
                    `encryptedNotes` TEXT,
                    `notesIv` TEXT,
                    `category` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `isFavorite` INTEGER NOT NULL,
                    `credentialType` TEXT NOT NULL,
                    `passkeyCredentialId` TEXT,
                    `passkeyPublicKey` TEXT,
                    `encryptedPasskeyPrivateKey` TEXT,
                    `passkeyPrivateKeyIv` TEXT,
                    `encryptedPasskeyUserHandle` TEXT,
                    `passkeyUserHandleIv` TEXT,
                    `passkeyRpId` TEXT,
                    `passkeyOrigin` TEXT,
                    `passkeySignCount` INTEGER NOT NULL,
                    `encryptedPasskeyDisplayName` TEXT,
                    `passkeyDisplayNameIv` TEXT
                )
                """.trimIndent()
            )

            database.execSQL(
                """
                INSERT INTO `credentials_new` (
                    `id`,
                    `serviceName`,
                    `serviceUrl`,
                    `packageName`,
                    `encryptedUsername`,
                    `usernameIv`,
                    `encryptedPassword`,
                    `passwordIv`,
                    `encryptedNotes`,
                    `notesIv`,
                    `category`,
                    `createdAt`,
                    `updatedAt`,
                    `isFavorite`,
                    `credentialType`,
                    `passkeyCredentialId`,
                    `passkeyPublicKey`,
                    `encryptedPasskeyPrivateKey`,
                    `passkeyPrivateKeyIv`,
                    `encryptedPasskeyUserHandle`,
                    `passkeyUserHandleIv`,
                    `passkeyRpId`,
                    `passkeyOrigin`,
                    `passkeySignCount`,
                    `encryptedPasskeyDisplayName`,
                    `passkeyDisplayNameIv`
                )
                SELECT
                    `id`,
                    `serviceName`,
                    `serviceUrl`,
                    `packageName`,
                    `encryptedUsername`,
                    `usernameIv`,
                    `encryptedPassword`,
                    `passwordIv`,
                    `encryptedNotes`,
                    `notesIv`,
                    `category`,
                    `createdAt`,
                    `updatedAt`,
                    `isFavorite`,
                    'PASSWORD',
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    0,
                    NULL,
                    NULL
                FROM `credentials`
                """.trimIndent()
            )

            database.execSQL("DROP TABLE `credentials`")
            database.execSQL("ALTER TABLE `credentials_new` RENAME TO `credentials`")
        }
    }
}