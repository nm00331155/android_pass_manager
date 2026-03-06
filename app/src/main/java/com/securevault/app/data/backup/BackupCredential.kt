package com.securevault.app.data.backup

import com.securevault.app.data.repository.model.Credential
import kotlinx.serialization.Serializable

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
    val password: String,
    /** メモ */
    val notes: String? = null,
    /** カテゴリ */
    val category: String = "other",
    /** お気に入りフラグ */
    val isFavorite: Boolean = false,
    /** 作成日時（エポックミリ秒） */
    val createdAt: Long = 0,
    /** 更新日時（エポックミリ秒） */
    val updatedAt: Long = 0
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
        updatedAt = updatedAt
    )
}

/**
 * [BackupCredential] を [Credential] に変換する。
 *
 * ID は 0（自動採番）で生成される。
 */
fun BackupCredential.toCredential(): Credential {
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
        isFavorite = isFavorite
    )
}
