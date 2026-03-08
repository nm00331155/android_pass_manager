package com.securevault.app.data.repository

import com.securevault.app.data.repository.model.Credential
import kotlinx.coroutines.flow.Flow

/**
 * 認証情報の取得・保存・削除を行うリポジトリ契約。
 */
interface CredentialRepository {

    /** 全件を取得する。 */
    fun getAll(): Flow<List<Credential>>

    /** ID 指定で1件取得する。 */
    fun getById(id: Long): Flow<Credential?>

    /** サービス名で検索する。 */
    fun search(query: String): Flow<List<Credential>>

    /** カテゴリで絞り込んで取得する。 */
    fun getByCategory(category: String): Flow<List<Credential>>

    /** お気に入りのみ取得する。 */
    fun getFavorites(): Flow<List<Credential>>

    /** 件数を取得する。 */
    fun getCount(): Flow<Int>

    /** パッケージ名一致で検索する。 */
    suspend fun findByPackageName(packageName: String): List<Credential>

    /** URL 部分一致で検索する。 */
    suspend fun findByUrl(url: String): List<Credential>

    /** ドメインで serviceName / serviceUrl を横断検索する（Autofill 用）。 */
    suspend fun findByDomain(domain: String): List<Credential>

    /** 新規保存または更新する。 */
    suspend fun save(credential: Credential)

    /** 認証情報をまとめて保存する。 */
    suspend fun saveAll(credentials: List<Credential>)

    /** ID 指定で削除する。 */
    suspend fun delete(id: Long)

    /** 全件削除する。 */
    suspend fun deleteAll()

    /** お気に入りフラグを反転する。 */
    suspend fun toggleFavorite(id: Long)
}
