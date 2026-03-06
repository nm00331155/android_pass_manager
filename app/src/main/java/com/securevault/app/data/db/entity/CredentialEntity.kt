package com.securevault.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 認証情報を永続化する Room エンティティ。
 * serviceName/serviceUrl/packageName は検索用途のため平文で保持する。
 */
@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serviceName: String,
    val serviceUrl: String?,
    val packageName: String?,
    val encryptedUsername: String,
    val usernameIv: String,
    val encryptedPassword: String,
    val passwordIv: String,
    val encryptedNotes: String?,
    val notesIv: String?,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean = false
)
