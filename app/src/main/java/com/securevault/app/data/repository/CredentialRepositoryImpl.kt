package com.securevault.app.data.repository

import com.securevault.app.data.crypto.CryptoEngine
import com.securevault.app.data.crypto.EncryptedData
import com.securevault.app.data.db.dao.CredentialDao
import com.securevault.app.data.db.entity.CredentialEntity
import com.securevault.app.data.repository.model.CardData
import com.securevault.app.data.repository.model.Credential
import com.securevault.app.data.repository.model.CredentialType
import com.securevault.app.data.repository.model.PasskeyData
import com.securevault.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * CredentialRepository の暗号化対応実装。
 */
@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val credentialDao: CredentialDao,
    private val cryptoEngine: CryptoEngine,
    private val logger: AppLogger
) : CredentialRepository {

    /** 全件を取得する。復号失敗レコードは除外する。 */
    override fun getAll(): Flow<List<Credential>> {
        return credentialDao.getAll()
            .map { list ->
                logger.d(TAG, "getAll: raw entity count=${list.size}")
                val decoded = list.mapNotNull { it.toDomainOrNull() }
                logger.d(TAG, "getAll: decoded count=${decoded.size}, failed=${list.size - decoded.size}")
                decoded
            }
            .flowOn(Dispatchers.Default)
    }

    /** ID 指定で1件取得する。 */
    override fun getById(id: Long): Flow<Credential?> {
        return credentialDao.getById(id)
            .map { entity ->
                val decoded = entity?.toDomainOrNull()
                logger.d(TAG, "getById: id=$id, found=${decoded != null}")
                decoded
            }
            .flowOn(Dispatchers.Default)
    }

    /** サービス名で検索する。 */
    override fun search(query: String): Flow<List<Credential>> {
        return credentialDao.searchByServiceName(query)
            .map { list ->
                val decoded = list.mapNotNull { it.toDomainOrNull() }
                logger.d(TAG, "search: query=$query, raw=${list.size}, decoded=${decoded.size}")
                decoded
            }
            .flowOn(Dispatchers.Default)
    }

    /** カテゴリで取得する。 */
    override fun getByCategory(category: String): Flow<List<Credential>> {
        return credentialDao.getByCategory(category)
            .map { list ->
                val decoded = list.mapNotNull { it.toDomainOrNull() }
                logger.d(TAG, "getByCategory: category=$category, raw=${list.size}, decoded=${decoded.size}")
                decoded
            }
            .flowOn(Dispatchers.Default)
    }

    /** お気に入りのみ取得する。 */
    override fun getFavorites(): Flow<List<Credential>> {
        return credentialDao.getFavorites()
            .map { list ->
                val decoded = list.mapNotNull { it.toDomainOrNull() }
                logger.d(TAG, "getFavorites: raw=${list.size}, decoded=${decoded.size}")
                decoded
            }
            .flowOn(Dispatchers.Default)
    }

    /** 件数を取得する。 */
    override fun getCount(): Flow<Int> = credentialDao.getCount()

    /** パッケージ名一致で取得する。 */
    override suspend fun findByPackageName(packageName: String): List<Credential> {
        return credentialDao.findByPackageName(packageName).mapNotNull { it.toDomainOrNull() }
    }

    /** URL 部分一致で取得する。 */
    override suspend fun findByUrl(url: String): List<Credential> {
        return credentialDao.findByUrl(url).mapNotNull { it.toDomainOrNull() }
    }

    /** ドメインで serviceName / serviceUrl を横断検索する。 */
    override suspend fun findByDomain(domain: String): List<Credential> {
        return credentialDao.findByDomain(domain).mapNotNull { it.toDomainOrNull() }
    }

    /** 新規保存または更新する。 */
    override suspend fun save(credential: Credential) {
        logger.d(TAG, "save: id=${credential.id}, service=${credential.serviceName}")
        val now = System.currentTimeMillis()
        val createdAt = if (credential.id == 0L) now else credential.createdAt
        val entity = credential.toEntity(createdAt = createdAt, updatedAt = now)

        if (credential.id == 0L) {
            val insertedId = credentialDao.insert(entity)
            logger.d(TAG, "save: inserted newId=$insertedId")
        } else {
            credentialDao.update(entity)
            logger.d(TAG, "save: updated id=${credential.id}")
        }
    }

    /** 認証情報をバッチ保存する。 */
    override suspend fun saveAll(credentials: List<Credential>) {
        logger.d(TAG, "saveAll: count=${credentials.size}")
        if (credentials.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val entities = credentials.map { credential ->
            val createdAt = if (credential.id == 0L) now else credential.createdAt
            credential.toEntity(createdAt = createdAt, updatedAt = now)
        }
        credentialDao.insertAll(entities)
        logger.d(TAG, "saveAll: inserted ${entities.size} entities")
    }

    /** ID 指定で削除する。 */
    override suspend fun delete(id: Long) {
        credentialDao.deleteById(id)
    }

    /** 全件削除する。 */
    override suspend fun deleteAll() {
        credentialDao.deleteAll()
    }

    /** お気に入り状態を反転する。 */
    override suspend fun toggleFavorite(id: Long) {
        val entity = credentialDao.getById(id).first() ?: return
        credentialDao.update(
            entity.copy(
                isFavorite = !entity.isFavorite,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun Credential.toEntity(createdAt: Long, updatedAt: Long): CredentialEntity {
        val usernameEncrypted = encryptValue(username)
        val normalizedType = normalizedCredentialType()
        val passwordEncrypted = password
            ?.takeIf { it.isNotBlank() }
            ?.let { encryptValue(it) }
        val notesEncrypted = notes?.let { encryptValue(it) }
        val passkeyPrivateKeyEncrypted = passkeyData?.privateKey?.let { encryptValue(it) }
        val passkeyUserHandleEncrypted = passkeyData?.userHandle?.let { encryptValue(it) }
        val passkeyDisplayNameEncrypted = passkeyData?.userDisplayName
            ?.takeIf { it.isNotBlank() }
            ?.let { encryptValue(it) }
        val cardholderNameEncrypted = cardData?.cardholderName
            ?.takeIf { it.isNotBlank() }
            ?.let { encryptValue(it) }
        val cardNumberEncrypted = cardData?.normalizedCardNumber
            ?.takeIf { it.isNotBlank() }
            ?.let { encryptValue(it) }
        val cardSecurityCodeEncrypted = cardData?.securityCode
            ?.takeIf { it.isNotBlank() }
            ?.let { encryptValue(it) }

        return CredentialEntity(
            id = id,
            serviceName = serviceName,
            serviceUrl = serviceUrl,
            packageName = packageName,
            encryptedUsername = usernameEncrypted.cipherText,
            usernameIv = usernameEncrypted.iv,
            encryptedPassword = passwordEncrypted?.cipherText,
            passwordIv = passwordEncrypted?.iv,
            encryptedNotes = notesEncrypted?.cipherText,
            notesIv = notesEncrypted?.iv,
            category = category,
            createdAt = createdAt,
            updatedAt = updatedAt,
            isFavorite = isFavorite,
            credentialType = normalizedType.name,
            passkeyCredentialId = passkeyData?.credentialId,
            passkeyPublicKey = passkeyData?.publicKey,
            encryptedPasskeyPrivateKey = passkeyPrivateKeyEncrypted?.cipherText,
            passkeyPrivateKeyIv = passkeyPrivateKeyEncrypted?.iv,
            encryptedPasskeyUserHandle = passkeyUserHandleEncrypted?.cipherText,
            passkeyUserHandleIv = passkeyUserHandleEncrypted?.iv,
            passkeyRpId = passkeyData?.rpId,
            passkeyOrigin = passkeyData?.origin,
            passkeySignCount = passkeyData?.signCount ?: 0,
            encryptedPasskeyDisplayName = passkeyDisplayNameEncrypted?.cipherText,
            passkeyDisplayNameIv = passkeyDisplayNameEncrypted?.iv,
            encryptedCardholderName = cardholderNameEncrypted?.cipherText,
            cardholderNameIv = cardholderNameEncrypted?.iv,
            encryptedCardNumber = cardNumberEncrypted?.cipherText,
            cardNumberIv = cardNumberEncrypted?.iv,
            cardExpirationMonth = cardData?.expirationMonth,
            cardExpirationYear = cardData?.expirationYear,
            encryptedCardSecurityCode = cardSecurityCodeEncrypted?.cipherText,
            cardSecurityCodeIv = cardSecurityCodeEncrypted?.iv
        )
    }

    private fun CredentialEntity.toDomainOrNull(): Credential? {
        return runCatching {
            val username = decryptValue(encryptedUsername, usernameIv)
            val password = decryptOptionalValue(encryptedPassword, passwordIv)
            val notes = if (!encryptedNotes.isNullOrBlank() && !notesIv.isNullOrBlank()) {
                decryptValue(encryptedNotes, notesIv)
            } else {
                null
            }
            val resolvedType = credentialType.toCredentialType(
                hasPassword = !encryptedPassword.isNullOrBlank(),
                hasPasskey = !passkeyCredentialId.isNullOrBlank(),
                hasCard = !encryptedCardNumber.isNullOrBlank()
            )
            val passkeyData = if (!passkeyCredentialId.isNullOrBlank()) {
                PasskeyData(
                    credentialId = passkeyCredentialId,
                    publicKey = passkeyPublicKey
                        ?: error("passkeyPublicKey is missing for id=$id"),
                    privateKey = decryptValue(
                        encryptedPasskeyPrivateKey
                            ?: error("encryptedPasskeyPrivateKey is missing for id=$id"),
                        passkeyPrivateKeyIv
                            ?: error("passkeyPrivateKeyIv is missing for id=$id")
                    ),
                    userHandle = decryptValue(
                        encryptedPasskeyUserHandle
                            ?: error("encryptedPasskeyUserHandle is missing for id=$id"),
                        passkeyUserHandleIv
                            ?: error("passkeyUserHandleIv is missing for id=$id")
                    ),
                    rpId = passkeyRpId
                        ?: error("passkeyRpId is missing for id=$id"),
                    origin = passkeyOrigin,
                    signCount = passkeySignCount,
                    userDisplayName = decryptOptionalValue(
                        encryptedPasskeyDisplayName,
                        passkeyDisplayNameIv
                    )
                )
            } else {
                null
            }
            val cardData = if (!encryptedCardNumber.isNullOrBlank()) {
                CardData(
                    cardholderName = decryptOptionalValue(
                        encryptedCardholderName,
                        cardholderNameIv
                    ),
                    cardNumber = decryptValue(
                        encryptedCardNumber
                            ?: error("encryptedCardNumber is missing for id=$id"),
                        cardNumberIv
                            ?: error("cardNumberIv is missing for id=$id")
                    ),
                    expirationMonth = cardExpirationMonth,
                    expirationYear = cardExpirationYear,
                    securityCode = decryptOptionalValue(
                        encryptedCardSecurityCode,
                        cardSecurityCodeIv
                    )
                )
            } else {
                null
            }

            Credential(
                id = id,
                serviceName = serviceName,
                serviceUrl = serviceUrl,
                packageName = packageName,
                username = username,
                password = password,
                notes = notes,
                category = category,
                createdAt = createdAt,
                updatedAt = updatedAt,
                isFavorite = isFavorite,
                passkeyData = passkeyData,
                cardData = cardData,
                credentialType = resolvedType
            )
        }.onFailure { throwable ->
            logger.e(TAG, "DECRYPT FAILED id=$id service=$serviceName", throwable)
        }.getOrNull()
    }

    private fun Credential.normalizedCredentialType(): CredentialType {
        return when {
            cardData != null -> CredentialType.CARD
            passkeyData != null -> CredentialType.PASSKEY
            password.isNullOrBlank() -> CredentialType.ID_ONLY
            else -> credentialType
        }
    }

    private fun encryptValue(value: String): EncryptedValue {
        val cipher = cryptoEngine.getCipherForEncryption()
        val encrypted = cryptoEngine.encrypt(value, cipher)
        return EncryptedValue(
            cipherText = CryptoEngine.toBase64(encrypted.cipherText),
            iv = CryptoEngine.toBase64(encrypted.iv)
        )
    }

    private fun decryptValue(cipherTextBase64: String, ivBase64: String): String {
        val ivBytes = CryptoEngine.fromBase64(ivBase64)
        val cipherTextBytes = CryptoEngine.fromBase64(cipherTextBase64)
        val decryptCipher = cryptoEngine.getCipherForDecryption(ivBytes)

        return cryptoEngine.decrypt(
            EncryptedData(cipherText = cipherTextBytes, iv = ivBytes),
            decryptCipher
        )
    }

    private fun decryptOptionalValue(cipherTextBase64: String?, ivBase64: String?): String? {
        if (cipherTextBase64.isNullOrBlank() || ivBase64.isNullOrBlank()) {
            return null
        }
        return decryptValue(cipherTextBase64, ivBase64)
    }

    private fun String.toCredentialType(
        hasPassword: Boolean,
        hasPasskey: Boolean,
        hasCard: Boolean
    ): CredentialType {
        return CredentialType.values().firstOrNull { it.name == this }
            ?: when {
                hasCard -> CredentialType.CARD
                hasPasskey -> CredentialType.PASSKEY
                hasPassword -> CredentialType.PASSWORD
                else -> CredentialType.ID_ONLY
            }
    }

    private data class EncryptedValue(
        val cipherText: String,
        val iv: String
    )

    private companion object {
        const val TAG: String = "CredentialRepository"
    }
}
