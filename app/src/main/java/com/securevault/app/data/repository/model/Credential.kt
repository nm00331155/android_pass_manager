package com.securevault.app.data.repository.model

/**
 * UI/アプリ層で扱う平文の認証情報モデル。
 */
data class Credential(
    val id: Long = 0,
    val serviceName: String,
    val serviceUrl: String? = null,
    val packageName: String? = null,
    val username: String,
    val password: String,
    val notes: String? = null,
    val category: String = "other",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)
