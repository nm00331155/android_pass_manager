package com.securevault.app.data.repository.model

/**
 * 認証情報の種類。
 */
enum class CredentialType {
    PASSWORD,
    ID_ONLY,
    PASSKEY
}

/**
 * パスキー固有データ。
 */
data class PasskeyData(
    val credentialId: String,
    val publicKey: String,
    val privateKey: String,
    val userHandle: String,
    val rpId: String,
    val origin: String? = null,
    val signCount: Long = 0,
    val userDisplayName: String? = null
)

/**
 * UI/アプリ層で扱う平文の認証情報モデル。
 */
data class Credential(
    val id: Long = 0,
    val serviceName: String,
    val serviceUrl: String? = null,
    val packageName: String? = null,
    val username: String,
    val password: String? = null,
    val notes: String? = null,
    val category: String = "other",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val passkeyData: PasskeyData? = null,
    val credentialType: CredentialType = when {
        passkeyData != null -> CredentialType.PASSKEY
        password.isNullOrBlank() -> CredentialType.ID_ONLY
        else -> CredentialType.PASSWORD
    }
) {
    val hasPassword: Boolean
        get() = !password.isNullOrBlank()

    val isPasskey: Boolean
        get() = credentialType == CredentialType.PASSKEY && passkeyData != null
}
