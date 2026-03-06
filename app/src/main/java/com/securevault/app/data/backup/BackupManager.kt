package com.securevault.app.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.securevault.app.data.repository.CredentialRepository
import com.securevault.app.data.repository.model.Credential
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * 認証情報のバックアップ（エクスポート）と復元（インポート）を管理するクラス。
 *
 * 暗号化 JSON 形式（.securevault）と平文 CSV 形式の両方に対応する。
 */
@Singleton
class BackupManager @Inject constructor(
    private val credentialRepository: CredentialRepository,
    @ApplicationContext private val context: Context
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * 全認証情報を暗号化 JSON ファイルとしてエクスポートする。
     */
    suspend fun exportEncrypted(outputUri: Uri, password: String): Int = withContext(Dispatchers.IO) {
        val credentials = credentialRepository.getAll().first()
        if (credentials.isEmpty()) return@withContext 0

        val backupList = credentials.map { it.toBackup() }
        val plainBytes = json.encodeToString(backupList).toByteArray(Charsets.UTF_8)

        val salt = BackupCrypto.generateSalt()
        val key = BackupCrypto.deriveKey(password, salt)
        val (cipherBytes, iv) = BackupCrypto.encrypt(plainBytes, key)

        val envelope = JSONObject().apply {
            put("version", 1)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
        }

        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                writer.write(envelope.toString())
            }
        } ?: throw java.io.IOException("出力ストリームを開けませんでした")

        backupList.size
    }

    /**
     * 暗号化 JSON ファイルから認証情報をインポートする。
     */
    suspend fun importEncrypted(
        inputUri: Uri,
        password: String,
        strategy: ImportStrategy
    ): Int = withContext(Dispatchers.IO) {
        val envelopeString = context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } ?: throw java.io.IOException("入力ストリームを開けませんでした")

        val envelope = JSONObject(envelopeString)
        val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val cipherBytes = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)

        val key = BackupCrypto.deriveKey(password, salt)
        val plainBytes = BackupCrypto.decrypt(cipherBytes, iv, key)
        val backupList: List<BackupCredential> = json.decodeFromString(
            plainBytes.toString(Charsets.UTF_8)
        )

        importCredentials(backupList, strategy)
    }

    /**
     * 全認証情報を平文 CSV としてエクスポートする。
     */
    suspend fun exportCsv(outputUri: Uri): Int = withContext(Dispatchers.IO) {
        val credentials = credentialRepository.getAll().first()
        if (credentials.isEmpty()) return@withContext 0

        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                writer.write("serviceName,serviceUrl,username,password,notes,category\n")
                credentials.forEach { credential ->
                    val line = listOf(
                        credential.serviceName,
                        credential.serviceUrl.orEmpty(),
                        credential.username,
                        credential.password,
                        credential.notes.orEmpty(),
                        credential.category
                    ).joinToString(",") { escapeCsvField(it) }
                    writer.write("$line\n")
                }
            }
        } ?: throw java.io.IOException("出力ストリームを開けませんでした")

        credentials.size
    }

    /**
     * CSV ファイルから認証情報をインポートする。
     */
    suspend fun importCsv(inputUri: Uri): Int = withContext(Dispatchers.IO) {
        val lines = context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.readLines()
            }
        } ?: throw java.io.IOException("入力ストリームを開けませんでした")

        var importCount = 0
        val dataLines = if (lines.isNotEmpty() && lines[0].trim().lowercase().startsWith("servicename")) {
            lines.drop(1)
        } else {
            lines
        }

        for (line in dataLines) {
            if (line.isBlank()) continue
            val fields = parseCsvLine(line)
            if (fields.size < 4) continue

            val credential = BackupCredential(
                serviceName = fields[0],
                serviceUrl = fields.getOrElse(1) { "" }.ifBlank { null },
                username = fields[2],
                password = fields[3],
                notes = fields.getOrElse(4) { "" }.ifBlank { null },
                category = fields.getOrElse(5) { "other" }.ifBlank { "other" },
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            ).toCredential()

            credentialRepository.save(credential)
            importCount++
        }

        importCount
    }

    /**
     * バックアップデータを指定された重複処理方針に従って保存する。
     */
    private suspend fun importCredentials(
        backupList: List<BackupCredential>,
        strategy: ImportStrategy
    ): Int {
        var importCount = 0
        val existingCredentials = credentialRepository.getAll().first().toMutableList()

        for (backup in backupList) {
            val existing = existingCredentials.find { candidate ->
                candidate.serviceName == backup.serviceName && candidate.username == backup.username
            }

            when {
                existing == null -> {
                    val newCredential = backup.toCredential()
                    credentialRepository.save(newCredential)
                    existingCredentials.add(newCredential)
                    importCount++
                }

                strategy == ImportStrategy.SKIP_DUPLICATES -> {
                    // no-op
                }

                strategy == ImportStrategy.OVERWRITE -> {
                    val updated = backup.toCredential().copy(
                        id = existing.id,
                        packageName = existing.packageName,
                        updatedAt = System.currentTimeMillis()
                    )
                    credentialRepository.save(updated)
                    existingCredentials.removeAll { it.id == existing.id }
                    existingCredentials.add(updated)
                    importCount++
                }

                strategy == ImportStrategy.IMPORT_ALL -> {
                    val newCredential = backup.toCredential()
                    credentialRepository.save(newCredential)
                    existingCredentials.add(newCredential)
                    importCount++
                }
            }
        }

        return importCount
    }

    /**
     * CSV フィールドをエスケープする。
     */
    private fun escapeCsvField(field: String): String {
        return if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }

    /**
     * CSV 1 行をフィールド配列へパースする。
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> inQuotes = true
                ch == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                }
                ch == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        fields.add(current.toString())

        return fields
    }
}

/**
 * インポート時の重複データ処理方針。
 */
enum class ImportStrategy {
    /** 同一サービス名+ユーザー名が既存の場合は無視する。 */
    SKIP_DUPLICATES,
    /** 同一サービス名+ユーザー名が既存の場合は上書きする。 */
    OVERWRITE,
    /** 重複に関係なくすべて追加する。 */
    IMPORT_ALL
}
