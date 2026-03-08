package com.securevault.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.securevault.app.data.db.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

/**
 * 認証情報テーブルへのアクセスを提供する DAO。
 */
@Dao
interface CredentialDao {

    /** サービス名の昇順で全件を取得する。 */
    @Query("SELECT * FROM credentials ORDER BY serviceName ASC")
    fun getAll(): Flow<List<CredentialEntity>>

    /** ID 指定で1件取得する。 */
    @Query("SELECT * FROM credentials WHERE id = :id LIMIT 1")
    fun getById(id: Long): Flow<CredentialEntity?>

    /** サービス名で部分一致検索する。 */
    @Query("SELECT * FROM credentials WHERE serviceName LIKE '%' || :query || '%' ORDER BY serviceName ASC")
    fun searchByServiceName(query: String): Flow<List<CredentialEntity>>

    /** パッケージ名で一致検索する。 */
    @Query("SELECT * FROM credentials WHERE packageName = :packageName ORDER BY serviceName ASC")
    suspend fun findByPackageName(packageName: String): List<CredentialEntity>

    /** URL を部分一致で検索する。 */
    @Query("SELECT * FROM credentials WHERE serviceUrl LIKE '%' || :url || '%' ORDER BY serviceName ASC")
    suspend fun findByUrl(url: String): List<CredentialEntity>

    /** serviceName / serviceUrl をドメイン部分一致で検索する（Autofill 用）。 */
    @Query("SELECT * FROM credentials WHERE serviceName LIKE '%' || :domain || '%' OR serviceUrl LIKE '%' || :domain || '%' ORDER BY serviceName ASC")
    suspend fun findByDomain(domain: String): List<CredentialEntity>

    /** カテゴリで絞り込んで取得する。 */
    @Query("SELECT * FROM credentials WHERE category = :category ORDER BY serviceName ASC")
    fun getByCategory(category: String): Flow<List<CredentialEntity>>

    /** お気に入りのみ取得する。 */
    @Query("SELECT * FROM credentials WHERE isFavorite = 1 ORDER BY serviceName ASC")
    fun getFavorites(): Flow<List<CredentialEntity>>

    /** 認証情報を新規追加する。 */
    @Insert
    suspend fun insert(credential: CredentialEntity): Long

    /** 認証情報をバッチ保存する。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<CredentialEntity>)

    /** 認証情報を更新する。 */
    @Update
    suspend fun update(credential: CredentialEntity)

    /** 認証情報を削除する。 */
    @Delete
    suspend fun delete(credential: CredentialEntity)

    /** ID 指定で削除する。 */
    @Query("DELETE FROM credentials WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 全件削除する。 */
    @Query("DELETE FROM credentials")
    suspend fun deleteAll()

    /** 件数を取得する。 */
    @Query("SELECT COUNT(*) FROM credentials")
    fun getCount(): Flow<Int>
}
