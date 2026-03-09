package com.securevault.app.data.repository.model

import java.util.Locale

/**
 * 認証情報の種類。
 */
enum class CredentialType {
    PASSWORD,
    ID_ONLY,
    PASSKEY,
    CARD
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
 * クレジットカード固有データ。
 */
data class CardData(
    val cardholderName: String? = null,
    val cardNumber: String,
    val expirationMonth: Int? = null,
    val expirationYear: Int? = null,
    val securityCode: String? = null
) {
    val normalizedCardNumber: String
        get() = cardNumber.filter(Char::isDigit)

    val lastFourDigits: String
        get() = normalizedCardNumber.takeLast(4)

    val maskedCardNumber: String
        get() = when {
            normalizedCardNumber.isBlank() -> ""
            normalizedCardNumber.length <= 4 -> normalizedCardNumber
            else -> "**** **** **** $lastFourDigits"
        }

    val formattedExpiration: String?
        get() = if (expirationMonth != null && expirationYear != null) {
            String.format(Locale.US, "%02d/%04d", expirationMonth, expirationYear)
        } else {
            null
        }

    val shortExpiration: String?
        get() = if (expirationMonth != null && expirationYear != null) {
            String.format(Locale.US, "%02d/%02d", expirationMonth, expirationYear % 100)
        } else {
            null
        }
}

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
    val cardData: CardData? = null,
    val credentialType: CredentialType = when {
        cardData != null -> CredentialType.CARD
        passkeyData != null -> CredentialType.PASSKEY
        password.isNullOrBlank() -> CredentialType.ID_ONLY
        else -> CredentialType.PASSWORD
    }
) {
    val hasPassword: Boolean
        get() = !password.isNullOrBlank()

    val isPasskey: Boolean
        get() = credentialType == CredentialType.PASSKEY && passkeyData != null

    val isCard: Boolean
        get() = credentialType == CredentialType.CARD && cardData != null

    val listSubtitle: String
        get() = if (isCard) {
            cardData?.cardholderName?.takeIf { it.isNotBlank() }
                ?: cardData?.maskedCardNumber.orEmpty()
        } else {
            username
        }
}
