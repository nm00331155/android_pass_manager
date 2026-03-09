package com.securevault.app.data.backup

import com.securevault.app.data.repository.model.CardData
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.data.repository.model.CredentialType
import com.securevault.app.data.repository.model.PasskeyData
import kotlinx.serialization.Serializable

@Serializable
data class BackupPasskeyData(
    val credentialId: String,
    val publicKey: String,
    val privateKey: String,
    val userHandle: String,
    val rpId: String,
    val origin: String? = null,
    val signCount: Long = 0,
    val userDisplayName: String? = null
)

@Serializable
data class BackupCardData(
    val cardholderName: String? = null,
    val cardNumber: String,
    val expirationMonth: Int? = null,
    val expirationYear: Int? = null,
    val securityCode: String? = null
)

/**
 * バックアップファイルに保存する認証情報のデータクラス。
 *
 * [Credential] モデルとの相互変換をサポートし、
 * JSON シリアライズ/デシリアライズに対応する。
 */
@Serializable
data class BackupCredential(
    /** サービス名 */
    val serviceName: String,
    /** サービスURL */
    val serviceUrl: String? = null,
    /** ユーザー名 */
    val username: String,
    /** パスワード */
    val password: String? = null,
    /** メモ */
    val notes: String? = null,
    /** カテゴリ */
    val category: String = "other",
    /** お気に入りフラグ */
    val isFavorite: Boolean = false,
    /** 作成日時（エポックミリ秒） */
    val createdAt: Long = 0,
    /** 更新日時（エポックミリ秒） */
    val updatedAt: Long = 0,
    /** 認証情報の種類 */
    val credentialType: String = CredentialType.PASSWORD.name,
    /** パスキー固有データ */
    val passkeyData: BackupPasskeyData? = null,
    /** クレジットカード固有データ */
    val cardData: BackupCardData? = null
)

/**
 * [Credential] を [BackupCredential] に変換する。
 */
fun Credential.toBackup(): BackupCredential {
    return BackupCredential(
        serviceName = serviceName,
        serviceUrl = serviceUrl,
        username = username,
        password = password,
        notes = notes,
        category = category,
        isFavorite = isFavorite,
        createdAt = createdAt,
        updatedAt = updatedAt,
        credentialType = credentialType.name,
        passkeyData = passkeyData?.toBackup(),
        cardData = cardData?.toBackup()
    )
}

/**
 * [BackupCredential] を [Credential] に変換する。
 *
 * ID は 0（自動採番）で生成される。
 */
fun BackupCredential.toCredential(): Credential {
    val resolvedType = CredentialType.values().firstOrNull { it.name == credentialType }
        ?: when {
            cardData != null -> CredentialType.CARD
            passkeyData != null -> CredentialType.PASSKEY
            password.isNullOrBlank() -> CredentialType.ID_ONLY
            else -> CredentialType.PASSWORD
        }

    return Credential(
        id = 0,
        serviceName = serviceName,
        serviceUrl = serviceUrl,
        packageName = null,
        username = username,
        password = password,
        notes = notes,
        category = category,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isFavorite = isFavorite,
        passkeyData = passkeyData?.toDomain(),
        cardData = cardData?.toDomain(),
        credentialType = resolvedType
    )
}

private fun PasskeyData.toBackup(): BackupPasskeyData {
    return BackupPasskeyData(
        credentialId = credentialId,
        publicKey = publicKey,
        privateKey = privateKey,
        userHandle = userHandle,
        rpId = rpId,
        origin = origin,
        signCount = signCount,
        userDisplayName = userDisplayName
    )
}

private fun BackupPasskeyData.toDomain(): PasskeyData {
    return PasskeyData(
        credentialId = credentialId,
        publicKey = publicKey,
        privateKey = privateKey,
        userHandle = userHandle,
        rpId = rpId,
        origin = origin,
        signCount = signCount,
        userDisplayName = userDisplayName
    )
}

private fun CardData.toBackup(): BackupCardData {
    return BackupCardData(
        cardholderName = cardholderName,
        cardNumber = normalizedCardNumber,
        expirationMonth = expirationMonth,
        expirationYear = expirationYear,
        securityCode = securityCode
    )
}

private fun BackupCardData.toDomain(): CardData {
    return CardData(
        cardholderName = cardholderName,
        cardNumber = cardNumber,
        expirationMonth = expirationMonth,
        expirationYear = expirationYear,
        securityCode = securityCode
    )
}
