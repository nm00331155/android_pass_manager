package com.securevault.app.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
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
    private val csvImportParser: CsvImportParser,
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
        val csvContent = readTextFromUri(inputUri)
        val backupList = csvImportParser.parse(csvContent, ImportSource.SECUREVAULT)
        importCredentials(backupList, ImportStrategy.IMPORT_ALL)
    }

    /**
     * 対応サービスの CSV ファイルから認証情報をインポートする。
     */
    suspend fun importFromService(
        inputUri: Uri,
        source: ImportSource,
        strategy: ImportStrategy
    ): Int = withContext(Dispatchers.IO) {
        val csvContent = readTextFromUri(inputUri)
        val backupList = csvImportParser.parse(csvContent, source)
        importCredentials(backupList, strategy)
    }

    /**
     * バックアップデータを指定された重複処理方針に従って保存する。
     */
    private suspend fun importCredentials(
        backupList: List<BackupCredential>,
        strategy: ImportStrategy
    ): Int {
        if (backupList.isEmpty()) {
            return 0
        }

        val existingCredentials = credentialRepository.getAll().first()
        val existingMap = existingCredentials.groupBy { keyOf(it.serviceName, it.username) }

        val toInsert = mutableListOf<Credential>()
        val pendingInsertByKey = linkedMapOf<String, Credential>()
        val pendingUpdateById = linkedMapOf<Long, Credential>()

        for (backup in backupList) {
            val key = keyOf(backup.serviceName, backup.username)
            val existing = existingMap[key]?.firstOrNull()
            val candidate = backup.toCredential()

            when {
                existing == null -> {
                    when (strategy) {
                        ImportStrategy.IMPORT_ALL -> toInsert.add(candidate)
                        ImportStrategy.SKIP_DUPLICATES -> {
                            pendingInsertByKey.putIfAbsent(key, candidate)
                        }

                        ImportStrategy.OVERWRITE -> {
                            pendingInsertByKey[key] = candidate
                        }
                    }
                }

                strategy == ImportStrategy.SKIP_DUPLICATES -> {
                    // no-op
                }

                strategy == ImportStrategy.OVERWRITE -> {
                    pendingUpdateById[existing.id] = candidate.copy(
                        id = existing.id,
                        packageName = existing.packageName,
                        updatedAt = System.currentTimeMillis()
                    )
                }

                strategy == ImportStrategy.IMPORT_ALL -> {
                    toInsert.add(candidate)
                }
            }
        }

        toInsert.addAll(pendingInsertByKey.values)

        if (toInsert.isNotEmpty()) {
            credentialRepository.saveAll(toInsert)
        }

        for (update in pendingUpdateById.values) {
            credentialRepository.save(update)
        }

        Log.d(TAG, "Import completed: inserted=${toInsert.size}, updated=${pendingUpdateById.size}")
        return toInsert.size + pendingUpdateById.size
    }

    private fun keyOf(serviceName: String, username: String): String {
        return "$serviceName||$username"
    }

    private fun readTextFromUri(inputUri: Uri): String {
        return context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                reader.readText().removePrefix("\uFEFF")
            }
        } ?: throw java.io.IOException("入力ストリームを開けませんでした")
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

private const val TAG = "BackupManager"
