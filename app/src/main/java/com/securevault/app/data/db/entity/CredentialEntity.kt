package com.securevault.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.securevault.app.data.repository.model.CredentialType

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
    val encryptedPassword: String?,
    val passwordIv: String?,
    val encryptedNotes: String?,
    val notesIv: String?,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean = false,
    val credentialType: String = CredentialType.PASSWORD.name,
    val passkeyCredentialId: String? = null,
    val passkeyPublicKey: String? = null,
    val encryptedPasskeyPrivateKey: String? = null,
    val passkeyPrivateKeyIv: String? = null,
    val encryptedPasskeyUserHandle: String? = null,
    val passkeyUserHandleIv: String? = null,
    val passkeyRpId: String? = null,
    val passkeyOrigin: String? = null,
    val passkeySignCount: Long = 0,
    val encryptedPasskeyDisplayName: String? = null,
    val passkeyDisplayNameIv: String? = null,
    val encryptedCardholderName: String? = null,
    val cardholderNameIv: String? = null,
    val encryptedCardNumber: String? = null,
    val cardNumberIv: String? = null,
    val cardExpirationMonth: Int? = null,
    val cardExpirationYear: Int? = null,
    val encryptedCardSecurityCode: String? = null,
    val cardSecurityCodeIv: String? = null
)
