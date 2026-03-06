package com.securevault.app.data.repository

import android.util.Log
import com.securevault.app.data.crypto.CryptoEngine
import com.securevault.app.data.crypto.EncryptedData
import com.securevault.app.data.db.dao.CredentialDao
import com.securevault.app.data.db.entity.CredentialEntity
import com.securevault.app.data.repository.model.Credential
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * CredentialRepository の暗号化対応実装。
 */
@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val credentialDao: CredentialDao,
    private val cryptoEngine: CryptoEngine
) : CredentialRepository {

    /** 全件を取得する。復号失敗レコードは除外する。 */
    override fun getAll(): Flow<List<Credential>> {
        return credentialDao.getAll().map { list -> list.mapNotNull { it.toDomainOrNull() } }
    }

    /** ID 指定で1件取得する。 */
    override fun getById(id: Long): Flow<Credential?> {
        return credentialDao.getById(id).map { entity -> entity?.toDomainOrNull() }
    }

    /** サービス名で検索する。 */
    override fun search(query: String): Flow<List<Credential>> {
        return credentialDao.searchByServiceName(query).map { list -> list.mapNotNull { it.toDomainOrNull() } }
    }

    /** カテゴリで取得する。 */
    override fun getByCategory(category: String): Flow<List<Credential>> {
        return credentialDao.getByCategory(category).map { list -> list.mapNotNull { it.toDomainOrNull() } }
    }

    /** お気に入りのみ取得する。 */
    override fun getFavorites(): Flow<List<Credential>> {
        return credentialDao.getFavorites().map { list -> list.mapNotNull { it.toDomainOrNull() } }
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

    /** 新規保存または更新する。 */
    override suspend fun save(credential: Credential) {
        val now = System.currentTimeMillis()
        val createdAt = if (credential.id == 0L) now else credential.createdAt
        val entity = credential.toEntity(createdAt = createdAt, updatedAt = now)

        if (credential.id == 0L) {
            credentialDao.insert(entity)
        } else {
            credentialDao.update(entity)
        }
    }

    /** 認証情報をバッチ保存する。 */
    override suspend fun saveAll(credentials: List<Credential>) {
        if (credentials.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val entities = credentials.map { credential ->
            val createdAt = if (credential.id == 0L) now else credential.createdAt
            credential.toEntity(createdAt = createdAt, updatedAt = now)
        }
        credentialDao.insertAll(entities)
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
        val passwordEncrypted = encryptValue(password)
        val notesEncrypted = notes?.let { encryptValue(it) }

        return CredentialEntity(
            id = id,
            serviceName = serviceName,
            serviceUrl = serviceUrl,
            packageName = packageName,
            encryptedUsername = usernameEncrypted.cipherText,
            usernameIv = usernameEncrypted.iv,
            encryptedPassword = passwordEncrypted.cipherText,
            passwordIv = passwordEncrypted.iv,
            encryptedNotes = notesEncrypted?.cipherText,
            notesIv = notesEncrypted?.iv,
            category = category,
            createdAt = createdAt,
            updatedAt = updatedAt,
            isFavorite = isFavorite
        )
    }

    private fun CredentialEntity.toDomainOrNull(): Credential? {
        return runCatching {
            val username = decryptValue(encryptedUsername, usernameIv)
            val password = decryptValue(encryptedPassword, passwordIv)
            val notes = if (!encryptedNotes.isNullOrBlank() && !notesIv.isNullOrBlank()) {
                decryptValue(encryptedNotes, notesIv)
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
                isFavorite = isFavorite
            )
        }.onFailure { throwable ->
            Log.e(TAG, "Credential decode FAILED for id=$id, serviceName=$serviceName", throwable)
        }.getOrNull()
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

    private data class EncryptedValue(
        val cipherText: String,
        val iv: String
    )

    private companion object {
        const val TAG: String = "CredentialRepository"
    }
}
