[versions]
# 既存に追加
kotlinxSerializationJson = "1.7.3"

[libraries]
# 既存に追加
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }

[plugins]
# 既存に追加
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
build.gradle.kts（プロジェクトレベル）に追加:

Copyplugins {
    // 既存の plugins に追加
    alias(libs.plugins.kotlin.serialization) apply false
}
app/build.gradle.kts（改修）:

Copyplugins {
    // 既存の plugins に追加
    alias(libs.plugins.kotlin.serialization)
}

android {
    // ... 既存設定 ...

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    // 既存 dependencies に追加
    implementation(libs.kotlinx.serialization.json)

    // テスト依存（既存にない場合追加）
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
}
A-2. data/backup/BackupCredential.kt
Copypackage com.securevault.app.data.backup

import com.securevault.app.data.db.entity.Credential
import kotlinx.serialization.Serializable

/**
 * バックアップファイルに保存する認証情報のデータクラス。
 *
 * [Credential] エンティティとの相互変換をサポートし、
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
 *
 * データベースエンティティからバックアップ用データモデルへの変換を行う。
 *
 * @return 変換された [BackupCredential]
 */
fun Credential.toBackup(): BackupCredential {
    return BackupCredential(
        serviceName = this.serviceName,
        serviceUrl = this.serviceUrl,
        username = this.username,
        password = this.password,
        notes = this.notes,
        category = this.category,
        isFavorite = this.isFavorite,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}

/**
 * [BackupCredential] を [Credential] に変換する。
 *
 * バックアップ用データモデルからデータベースエンティティへの変換を行う。
 * ID は 0（自動採番）で生成される。
 *
 * @return 変換された [Credential]
 */
fun BackupCredential.toCredential(): Credential {
    return Credential(
        id = 0,
        serviceName = this.serviceName,
        serviceUrl = this.serviceUrl,
        username = this.username,
        password = this.password,
        notes = this.notes,
        category = this.category,
        isFavorite = this.isFavorite,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}
Copy
A-3. data/backup/BackupCrypto.kt
Copypackage com.securevault.app.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * バックアップ専用の暗号化ユーティリティ。
 *
 * Android Keystore ではなくパスワードベースの鍵導出（PBKDF2）と
 * AES-256-GCM 暗号化を使用する。
 * エクスポート/インポート時にユーザーが指定するパスワードから暗号鍵を導出し、
 * 認証情報データを暗号化・復号する。
 */
object BackupCrypto {

    /** PBKDF2 のイテレーション回数 */
    private const val PBKDF2_ITERATIONS = 600_000

    /** 導出する鍵のビット長 */
    private const val KEY_LENGTH = 256

    /** ソルトのバイト長 */
    private const val SALT_LENGTH = 32

    /** AES-GCM 暗号化の変換名 */
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** GCM 認証タグのビット長 */
    private const val GCM_TAG_LENGTH = 128

    /** PBKDF2 のアルゴリズム名 */
    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

    /** AES のアルゴリズム名 */
    private const val AES_ALGORITHM = "AES"

    /**
     * パスワードとソルトから AES-256 キーを導出する。
     *
     * PBKDF2WithHmacSHA256 アルゴリズムを使用し、
     * 600,000 回のイテレーションで 256 ビットの鍵を導出する。
     *
     * @param password ユーザーが指定するパスワード
     * @param salt 暗号論的にセキュアなソルト
     * @return 導出された [SecretKey]
     */
    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, AES_ALGORITHM)
    }

    /**
     * 平文バイト列を AES-256-GCM で暗号化する。
     *
     * IV（初期化ベクトル）は内部で自動生成される。
     *
     * @param plainBytes 暗号化する平文バイト列
     * @param key [deriveKey] で導出した AES キー
     * @return [Pair] の first が暗号文バイト列、second が IV バイト列
     */
    fun encrypt(plainBytes: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainBytes)
        return Pair(cipherBytes, iv)
    }

    /**
     * AES-256-GCM で暗号化されたデータを復号する。
     *
     * @param cipherBytes 暗号文バイト列
     * @param iv 暗号化時に使用した IV
     * @param key [deriveKey] で導出した AES キー
     * @return 復号された平文バイト列
     * @throws javax.crypto.AEADBadTagException パスワードが不正な場合
     */
    fun decrypt(cipherBytes: ByteArray, iv: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(cipherBytes)
    }

    /**
     * 暗号論的にセキュアなソルトを生成する。
     *
     * [SecureRandom] を使用して 32 バイトのランダムなソルトを生成する。
     *
     * @return 生成されたソルトバイト列
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }
}
Copy
A-1. data/backup/BackupManager.kt
Copypackage com.securevault.app.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.securevault.app.data.repository.CredentialRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 認証情報のバックアップ（エクスポート）と復元（インポート）を管理するクラス。
 *
 * 暗号化 JSON 形式（.securevault）と平文 CSV 形式の両方に対応する。
 * 暗号化には PBKDF2 + AES-256-GCM を使用し、Android Keystore ではなく
 * ユーザー指定のパスワードベースで鍵を導出する。
 *
 * ファイルアクセスには Storage Access Framework（SAF）を使用するため、
 * ストレージ権限は不要。
 */
@Singleton
class BackupManager @Inject constructor(
    private val credentialRepository: CredentialRepository,
    @ApplicationContext private val context: Context
) {

    /** JSON シリアライザの設定 */
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * 全認証情報を暗号化 JSON ファイルとしてエクスポートする。
     *
     * 処理フロー:
     * 1. 全認証情報を取得し [BackupCredential] に変換
     * 2. JSON 配列としてシリアライズ
     * 3. パスワードから PBKDF2 で鍵を導出
     * 4. AES-256-GCM で暗号化
     * 5. バージョン、ソルト、IV、暗号文を含む JSON ファイルとして出力
     *
     * @param outputUri SAF で取得した出力先 URI
     * @param password ユーザーが指定するバックアップ用パスワード
     * @return エクスポート件数
     * @throws java.io.IOException ファイル書き込みに失敗した場合
     */
    suspend fun exportEncrypted(outputUri: Uri, password: String): Int = withContext(Dispatchers.IO) {
        val credentials = credentialRepository.getAllCredentials().first()
        if (credentials.isEmpty()) return@withContext 0

        val backupList = credentials.map { it.toBackup() }
        val jsonString = json.encodeToString(backupList)
        val plainBytes = jsonString.toByteArray(Charsets.UTF_8)

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
     *
     * 処理フロー:
     * 1. ファイルを読み込みエンベロープ JSON をパース
     * 2. ソルト・IV を取得しパスワードから鍵を導出
     * 3. 暗号文を復号し [BackupCredential] のリストにデシリアライズ
     * 4. 指定された [ImportStrategy] に従い認証情報を保存
     *
     * @param inputUri SAF で取得した入力元 URI
     * @param password バックアップ時に設定したパスワード
     * @param strategy 重複時の処理方針
     * @return インポート件数
     * @throws javax.crypto.AEADBadTagException パスワードが不正な場合
     * @throws java.io.IOException ファイル読み込みに失敗した場合
     */
    suspend fun importEncrypted(inputUri: Uri, password: String, strategy: ImportStrategy): Int = withContext(Dispatchers.IO) {
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
        val jsonString = plainBytes.toString(Charsets.UTF_8)

        val backupList: List<BackupCredential> = json.decodeFromString(jsonString)

        importCredentials(backupList, strategy)
    }

    /**
     * 全認証情報を平文 CSV としてエクスポートする。
     *
     * パスワードが平文で保存されるため、UI 側で警告を表示すること。
     * CSV のカラム順: serviceName, serviceUrl, username, password, notes, category
     *
     * @param outputUri SAF で取得した出力先 URI
     * @return エクスポート件数
     * @throws java.io.IOException ファイル書き込みに失敗した場合
     */
    suspend fun exportCsv(outputUri: Uri): Int = withContext(Dispatchers.IO) {
        val credentials = credentialRepository.getAllCredentials().first()
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
     *
     * カラム順: serviceName, serviceUrl, username, password, notes, category
     * 1行目がヘッダーの場合は自動的にスキップする。
     * 重複チェックは行わず、すべてのレコードを追加する。
     *
     * @param inputUri SAF で取得した入力元 URI
     * @return インポート件数
     * @throws java.io.IOException ファイル読み込みに失敗した場合
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

            credentialRepository.addCredential(credential)
            importCount++
        }

        importCount
    }

    /**
     * バックアップ認証情報リストを指定された戦略に従ってデータベースに保存する。
     *
     * @param backupList インポートする [BackupCredential] のリスト
     * @param strategy 重複時の処理方針
     * @return インポートされた件数
     */
    private suspend fun importCredentials(backupList: List<BackupCredential>, strategy: ImportStrategy): Int {
        var importCount = 0
        val existingCredentials = credentialRepository.getAllCredentials().first()

        for (backup in backupList) {
            val existing = existingCredentials.find { existing ->
                existing.serviceName == backup.serviceName && existing.username == backup.username
            }

            when {
                existing == null -> {
                    credentialRepository.addCredential(backup.toCredential())
                    importCount++
                }
                strategy == ImportStrategy.SKIP_DUPLICATES -> {
                    // 重複をスキップ
                }
                strategy == ImportStrategy.OVERWRITE -> {
                    val updated = backup.toCredential().copy(
                        id = existing.id,
                        updatedAt = System.currentTimeMillis()
                    )
                    credentialRepository.updateCredential(updated)
                    importCount++
                }
                strategy == ImportStrategy.IMPORT_ALL -> {
                    credentialRepository.addCredential(backup.toCredential())
                    importCount++
                }
            }
        }

        return importCount
    }

    /**
     * CSV フィールドをエスケープする。
     *
     * カンマ、ダブルクォート、改行を含むフィールドはダブルクォートで囲み、
     * 内部のダブルクォートは二重化する。
     *
     * @param field エスケープするフィールド値
     * @return エスケープされたフィールド値
     */
    private fun escapeCsvField(field: String): String {
        return if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }
    }

    /**
     * CSV 行をパースしてフィールドのリストを返す。
     *
     * ダブルクォートで囲まれたフィールド内のカンマや改行を正しく処理する。
     *
     * @param line パースする CSV 行
     * @return フィールド値のリスト
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && !inQuotes -> {
                    inQuotes = true
                }
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
                else -> {
                    current.append(ch)
                }
            }
            i++
        }
        fields.add(current.toString())

        return fields
    }
}

/**
 * インポート時の重複データ処理方針を定義する列挙型。
 */
enum class ImportStrategy {
    /** 同一サービス名+ユーザー名が既存の場合は無視する */
    SKIP_DUPLICATES,
    /** 同一サービス名+ユーザー名が既存の場合は上書きする */
    OVERWRITE,
    /** 重複に関係なくすべて追加する */
    IMPORT_ALL
}
Copy
A-5. UI – ui/screen/backup/BackupViewModel.kt
Copypackage com.securevault.app.ui.screen.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securevault.app.data.backup.BackupManager
import com.securevault.app.data.backup.ImportStrategy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * バックアップ画面の UI 状態を表すシールドインターフェース。
 */
sealed interface BackupUiState {
    /** アイドル状態 */
    data object Idle : BackupUiState

    /** 処理中 */
    data object InProgress : BackupUiState

    /**
     * 処理成功。
     *
     * @property count 処理された件数
     * @property isExport エクスポートの場合 true、インポートの場合 false
     */
    data class Success(val count: Int, val isExport: Boolean) : BackupUiState

    /**
     * エラー発生。
     *
     * @property message エラーメッセージ
     */
    data class Error(val message: String) : BackupUiState
}

/**
 * バックアップ画面の ViewModel。
 *
 * エクスポート（暗号化 / CSV）およびインポート（暗号化 / CSV）の
 * 各操作を [BackupManager] に委譲し、結果を [BackupUiState] として公開する。
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)

    /** バックアップ画面の現在の UI 状態 */
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /**
     * 暗号化バックアップをエクスポートする。
     *
     * @param uri SAF で取得した出力先 URI
     * @param password バックアップ用パスワード
     */
    fun exportEncrypted(uri: Uri, password: String) {
        viewModelScope.launch {
            _uiState.value = BackupUiState.InProgress
            try {
                val count = backupManager.exportEncrypted(uri, password)
                _uiState.value = BackupUiState.Success(count = count, isExport = true)
            } catch (e: Exception) {
                _uiState.value = BackupUiState.Error(e.message ?: "不明なエラー")
            }
        }
    }

    /**
     * CSV 形式でエクスポートする。
     *
     * @param uri SAF で取得した出力先 URI
     */
    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = BackupUiState.InProgress
            try {
                val count = backupManager.exportCsv(uri)
                _uiState.value = BackupUiState.Success(count = count, isExport = true)
            } catch (e: Exception) {
                _uiState.value = BackupUiState.Error(e.message ?: "不明なエラー")
            }
        }
    }

    /**
     * 暗号化バックアップからインポートする。
     *
     * @param uri SAF で取得した入力元 URI
     * @param password バックアップ時に設定したパスワード
     * @param strategy 重複時の処理方針
     */
    fun importEncrypted(uri: Uri, password: String, strategy: ImportStrategy) {
        viewModelScope.launch {
            _uiState.value = BackupUiState.InProgress
            try {
                val count = backupManager.importEncrypted(uri, password, strategy)
                _uiState.value = BackupUiState.Success(count = count, isExport = false)
            } catch (e: Exception) {
                _uiState.value = BackupUiState.Error(e.message ?: "不明なエラー")
            }
        }
    }

    /**
     * CSV ファイルからインポートする。
     *
     * @param uri SAF で取得した入力元 URI
     */
    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = BackupUiState.InProgress
            try {
                val count = backupManager.importCsv(uri)
                _uiState.value = BackupUiState.Success(count = count, isExport = false)
            } catch (e: Exception) {
                _uiState.value = BackupUiState.Error(e.message ?: "不明なエラー")
            }
        }
    }

    /**
     * UI 状態をアイドルにリセットする。
     *
     * Snackbar 表示後などに呼び出す。
     */
    fun resetState() {
        _uiState.value = BackupUiState.Idle
    }
}
Copy
A-5. UI – ui/screen/backup/BackupScreen.kt
Copypackage com.securevault.app.ui.screen.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.securevault.app.R
import com.securevault.app.data.backup.ImportStrategy

/**
 * バックアップ・復元画面のコンポーザブル。
 *
 * 暗号化バックアップ/CSV エクスポートおよびインポート機能を提供する。
 * ファイル選択には Storage Access Framework（SAF）を使用する。
 *
 * @param onNavigateBack 戻るボタン押下時のコールバック
 * @param viewModel バックアップ画面の ViewModel
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // ダイアログ状態
    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var showCsvWarningDialog by remember { mutableStateOf(false) }
    var showStrategyDialog by remember { mutableStateOf(false) }

    // パスワード入力状態
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var passwordMismatchError by remember { mutableStateOf(false) }

    // 選択されたインポート戦略
    var selectedStrategy by remember { mutableStateOf(ImportStrategy.SKIP_DUPLICATES) }

    // 一時的に保持する URI
    var pendingExportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // 文字列リソース
    val exportSuccessFormat = stringResource(R.string.backup_export_success)
    val importSuccessFormat = stringResource(R.string.backup_import_success)
    val errorFormat = stringResource(R.string.backup_error)

    // SAF ランチャー: 暗号化エクスポート
    val encryptedExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let {
            pendingExportUri = it
            showExportPasswordDialog = true
        }
    }

    // SAF ランチャー: CSV エクスポート
    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let { viewModel.exportCsv(it) }
    }

    // SAF ランチャー: 暗号化インポート
    val encryptedImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingImportUri = it
            showImportPasswordDialog = true
        }
    }

    // SAF ランチャー: CSV インポート
    val csvImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importCsv(it) }
    }

    // Snackbar 表示
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is BackupUiState.Success -> {
                val message = if (state.isExport) {
                    String.format(exportSuccessFormat, state.count)
                } else {
                    String.format(importSuccessFormat, state.count)
                }
                snackbarHostState.showSnackbar(message)
                viewModel.resetState()
            }
            is BackupUiState.Error -> {
                snackbarHostState.showSnackbar(String.format(errorFormat, state.message))
                viewModel.resetState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 処理中インジケーター
            if (uiState is BackupUiState.InProgress) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.backup_in_progress))
                }
            }

            // エクスポートセクション
            Text(
                text = stringResource(R.string.backup_export_section),
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = { encryptedExportLauncher.launch("securevault_backup.securevault") },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is BackupUiState.InProgress
            ) {
                Text(stringResource(R.string.backup_export_encrypted))
            }

            OutlinedButton(
                onClick = { showCsvWarningDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is BackupUiState.InProgress
            ) {
                Text(stringResource(R.string.backup_export_csv))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // インポートセクション
            Text(
                text = stringResource(R.string.backup_import_section),
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = { encryptedImportLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is BackupUiState.InProgress
            ) {
                Text(stringResource(R.string.backup_import_encrypted))
            }

            OutlinedButton(
                onClick = { csvImportLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is BackupUiState.InProgress
            ) {
                Text(stringResource(R.string.backup_import_csv))
            }
        }
    }

    // エクスポート用パスワード入力ダイアログ
    if (showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showExportPasswordDialog = false
                exportPassword = ""
                exportPasswordConfirm = ""
                passwordMismatchError = false
            },
            title = { Text(stringResource(R.string.backup_password_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = {
                            exportPassword = it
                            passwordMismatchError = false
                        },
                        label = { Text(stringResource(R.string.backup_password_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = exportPasswordConfirm,
                        onValueChange = {
                            exportPasswordConfirm = it
                            passwordMismatchError = false
                        },
                        label = { Text(stringResource(R.string.backup_password_confirm_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = passwordMismatchError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (passwordMismatchError) {
                        Text(
                            text = stringResource(R.string.backup_password_mismatch),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (exportPassword != exportPasswordConfirm) {
                            passwordMismatchError = true
                        } else if (exportPassword.isNotEmpty()) {
                            pendingExportUri?.let { uri ->
                                viewModel.exportEncrypted(uri, exportPassword)
                            }
                            showExportPasswordDialog = false
                            exportPassword = ""
                            exportPasswordConfirm = ""
                            passwordMismatchError = false
                        }
                    },
                    enabled = exportPassword.isNotEmpty()
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showExportPasswordDialog = false
                        exportPassword = ""
                        exportPasswordConfirm = ""
                        passwordMismatchError = false
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // インポート用パスワード入力ダイアログ
    if (showImportPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportPasswordDialog = false
                importPassword = ""
            },
            title = { Text(stringResource(R.string.backup_password_title)) },
            text = {
                OutlinedTextField(
                    value = importPassword,
                    onValueChange = { importPassword = it },
                    label = { Text(stringResource(R.string.backup_password_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (importPassword.isNotEmpty()) {
                            showImportPasswordDialog = false
                            showStrategyDialog = true
                        }
                    },
                    enabled = importPassword.isNotEmpty()
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportPasswordDialog = false
                        importPassword = ""
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // 重複処理戦略選択ダイアログ
    if (showStrategyDialog) {
        AlertDialog(
            onDismissRequest = {
                showStrategyDialog = false
                importPassword = ""
            },
            title = { Text(stringResource(R.string.backup_strategy_title)) },
            text = {
                Column {
                    ImportStrategy.entries.forEach { strategy ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedStrategy == strategy,
                                onClick = { selectedStrategy = strategy }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (strategy) {
                                    ImportStrategy.SKIP_DUPLICATES -> stringResource(R.string.backup_strategy_skip)
                                    ImportStrategy.OVERWRITE -> stringResource(R.string.backup_strategy_overwrite)
                                    ImportStrategy.IMPORT_ALL -> stringResource(R.string.backup_strategy_all)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportUri?.let { uri ->
                            viewModel.importEncrypted(uri, importPassword, selectedStrategy)
                        }
                        showStrategyDialog = false
                        importPassword = ""
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showStrategyDialog = false
                        importPassword = ""
                    }
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    // CSV エクスポート警告ダイアログ
    if (showCsvWarningDialog) {
        AlertDialog(
            onDismissRequest = { showCsvWarningDialog = false },
            title = { Text(stringResource(R.string.backup_export_csv)) },
            text = { Text(stringResource(R.string.backup_export_csv_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCsvWarningDialog = false
                        csvExportLauncher.launch("securevault_export.csv")
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCsvWarningDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
Copy
A-5. NavRoutes 改修 – ui/navigation/NavRoutes.kt
Copypackage com.securevault.app.ui.navigation

/**
 * アプリ内のナビゲーションルート定数を定義するオブジェクト。
 */
object NavRoutes {
    /** 認証画面 */
    const val Auth = "auth"
    /** ホーム画面（認証情報一覧） */
    const val Home = "home"
    /** 認証情報追加・編集画面 */
    const val AddEdit = "add_edit/{credentialId}"
    /** 認証情報詳細画面 */
    const val Detail = "detail/{credentialId}"
    /** 設定画面 */
    const val Settings = "settings"
    /** パスワード生成画面 */
    const val Generator = "generator"
    /** バックアップ・復元画面 */
    const val Backup = "backup"
}
A-5. NavGraph 改修 – ui/navigation/NavGraph.kt
NavGraph に Backup ルートを追加します。既存コードに以下の composable ブロックを追加してください：

Copy// 既存の import に追加
import com.securevault.app.ui.screen.backup.BackupScreen

// NavHost 内に追加
composable(NavRoutes.Backup) {
    BackupScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
A-5. SettingsScreen 改修
SettingsScreen に以下のボタンを追加します（既存の設定項目の後に追加）：

Copy// SettingsScreen の設定項目リストに追加
// onNavigateToBackup パラメータを SettingsScreen に追加

// SettingsScreen のシグネチャ変更:
// fun SettingsScreen(onNavigateBack: () -> Unit, onNavigateToBackup: () -> Unit, ...)

// ボタンを追加:
OutlinedButton(
    onClick = onNavigateToBackup,
    modifier = Modifier.fillMaxWidth()
) {
    Text(stringResource(R.string.settings_backup))
}
NavGraph 側の SettingsScreen 呼び出しも更新：

Copycomposable(NavRoutes.Settings) {
    SettingsScreen(
        onNavigateBack = { navController.popBackStack() },
        onNavigateToBackup = { navController.navigate(NavRoutes.Backup) }
    )
}
A-6. res/values/strings.xml に追加
Copy<!-- バックアップ -->
<string name="backup_title">バックアップ・復元</string>
<string name="backup_export_section">エクスポート</string>
<string name="backup_export_encrypted">暗号化バックアップ (.securevault)</string>
<string name="backup_export_csv">CSV エクスポート（平文）</string>
<string name="backup_export_csv_warning">パスワードが平文で保存されます。\n信頼できる場所にのみ保存してください。\n\n本当にエクスポートしますか？</string>
<string name="backup_import_section">インポート</string>
<string name="backup_import_encrypted">暗号化バックアップから復元</string>
<string name="backup_import_csv">CSV からインポート</string>
<string name="backup_password_title">バックアップパスワード</string>
<string name="backup_password_hint">パスワードを入力してください</string>
<string name="backup_password_confirm_hint">パスワードを再入力してください</string>
<string name="backup_password_mismatch">パスワードが一致しません</string>
<string name="backup_strategy_title">重複データの処理</string>
<string name="backup_strategy_skip">重複をスキップ</string>
<string name="backup_strategy_overwrite">上書き</string>
<string name="backup_strategy_all">すべて追加</string>
<string name="backup_export_success">%1$d 件エクスポートしました</string>
<string name="backup_import_success">%1$d 件インポートしました</string>
<string name="backup_error">エラーが発生しました: %1$s</string>
<string name="backup_in_progress">処理中…</string>
<string name="settings_backup">バックアップ・復元</string>
Part B: テスト
B-1. app/src/test/java/com/securevault/app/data/backup/BackupCryptoTest.kt
Copypackage com.securevault.app.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

/**
 * [BackupCrypto] のユニットテスト。
 *
 * パスワードベースの鍵導出、暗号化・復号の往復、
 * 異なるパスワードでの復号失敗、ソルト生成の一意性を検証する。
 */
class BackupCryptoTest {

    /**
     * パスワードから鍵を導出し、暗号化→復号が正しく往復できることを検証する。
     */
    @Test
    fun `encrypt and decrypt roundtrip succeeds with correct password`() {
        val password = "testPassword123!"
        val salt = BackupCrypto.generateSalt()
        val key = BackupCrypto.deriveKey(password, salt)

        val plainText = "Hello, SecureVault! これはテストデータです。"
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)

        val (cipherBytes, iv) = BackupCrypto.encrypt(plainBytes, key)
        val decryptedBytes = BackupCrypto.decrypt(cipherBytes, iv, key)

        assertArrayEquals(plainBytes, decryptedBytes)
        assertTrue(String(decryptedBytes, Charsets.UTF_8) == plainText)
    }

    /**
     * 異なるパスワードで復号を試みた場合に例外が発生することを検証する。
     */
    @Test(expected = AEADBadTagException::class)
    fun `decrypt fails with wrong password`() {
        val correctPassword = "correctPassword"
        val wrongPassword = "wrongPassword"
        val salt = BackupCrypto.generateSalt()

        val correctKey = BackupCrypto.deriveKey(correctPassword, salt)
        val wrongKey = BackupCrypto.deriveKey(wrongPassword, salt)

        val plainBytes = "Secret data".toByteArray(Charsets.UTF_8)
        val (cipherBytes, iv) = BackupCrypto.encrypt(plainBytes, correctKey)

        // 異なるパスワードで復号 → AEADBadTagException が発生するはず
        BackupCrypto.decrypt(cipherBytes, iv, wrongKey)
    }

    /**
     * [BackupCrypto.generateSalt] が毎回異なるソルトを生成することを検証する。
     */
    @Test
    fun `generateSalt produces different salts each time`() {
        val salt1 = BackupCrypto.generateSalt()
        val salt2 = BackupCrypto.generateSalt()
        val salt3 = BackupCrypto.generateSalt()

        assertNotNull(salt1)
        assertNotNull(salt2)
        assertNotNull(salt3)
        assertTrue(salt1.size == 32)
        assertTrue(salt2.size == 32)
        assertTrue(salt3.size == 32)
        assertFalse(salt1.contentEquals(salt2))
        assertFalse(salt2.contentEquals(salt3))
        assertFalse(salt1.contentEquals(salt3))
    }

    /**
     * 空のデータの暗号化・復号が正しく動作することを検証する。
     */
    @Test
    fun `encrypt and decrypt empty data succeeds`() {
        val password = "password"
        val salt = BackupCrypto.generateSalt()
        val key = BackupCrypto.deriveKey(password, salt)

        val plainBytes = ByteArray(0)
        val (cipherBytes, iv) = BackupCrypto.encrypt(plainBytes, key)
        val decryptedBytes = BackupCrypto.decrypt(cipherBytes, iv, key)

        assertArrayEquals(plainBytes, decryptedBytes)
    }

    /**
     * 大きなデータの暗号化・復号が正しく動作することを検証する。
     */
    @Test
    fun `encrypt and decrypt large data succeeds`() {
        val password = "strongP@ssw0rd!"
        val salt = BackupCrypto.generateSalt()
        val key = BackupCrypto.deriveKey(password, salt)

        val plainBytes = ByteArray(1_000_000) { (it % 256).toByte() }
        val (cipherBytes, iv) = BackupCrypto.encrypt(plainBytes, key)
        val decryptedBytes = BackupCrypto.decrypt(cipherBytes, iv, key)

        assertArrayEquals(plainBytes, decryptedBytes)
    }

    /**
     * 同じパスワードとソルトから同じ鍵が導出されることを検証する。
     */
    @Test
    fun `deriveKey produces same key for same password and salt`() {
        val password = "deterministic"
        val salt = BackupCrypto.generateSalt()

        val key1 = BackupCrypto.deriveKey(password, salt)
        val key2 = BackupCrypto.deriveKey(password, salt)

        assertArrayEquals(key1.encoded, key2.encoded)
    }
}
Copy
B-1. app/src/test/java/com/securevault/app/data/backup/BackupManagerTest.kt
Copypackage com.securevault.app.data.backup

import com.securevault.app.data.db.entity.Credential
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackupManager] 関連のユニットテスト。
 *
 * [BackupCredential] と [Credential] の相互変換、
 * JSON シリアライズ/デシリアライズの往復を検証する。
 */
class BackupManagerTest {

    /** テスト用 JSON インスタンス */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * [Credential] から [BackupCredential] への変換が正しく行われることを検証する。
     */
    @Test
    fun `Credential to BackupCredential conversion preserves all fields`() {
        val credential = Credential(
            id = 42,
            serviceName = "GitHub",
            serviceUrl = "https://github.com",
            username = "testuser",
            password = "secretPass123",
            notes = "メモです",
            category = "development",
            isFavorite = true,
            createdAt = 1700000000000L,
            updatedAt = 1700001000000L
        )

        val backup = credential.toBackup()

        assertEquals("GitHub", backup.serviceName)
        assertEquals("https://github.com", backup.serviceUrl)
        assertEquals("testuser", backup.username)
        assertEquals("secretPass123", backup.password)
        assertEquals("メモです", backup.notes)
        assertEquals("development", backup.category)
        assertTrue(backup.isFavorite)
        assertEquals(1700000000000L, backup.createdAt)
        assertEquals(1700001000000L, backup.updatedAt)
    }

    /**
     * [BackupCredential] から [Credential] への変換が正しく行われることを検証する。
     * 変換後の ID は 0（自動採番用）になる。
     */
    @Test
    fun `BackupCredential to Credential conversion sets id to 0`() {
        val backup = BackupCredential(
            serviceName = "Twitter",
            serviceUrl = "https://twitter.com",
            username = "user1",
            password = "pass1",
            notes = null,
            category = "social",
            isFavorite = false,
            createdAt = 1600000000000L,
            updatedAt = 1600001000000L
        )

        val credential = backup.toCredential()

        assertEquals(0, credential.id)
        assertEquals("Twitter", credential.serviceName)
        assertEquals("https://twitter.com", credential.serviceUrl)
        assertEquals("user1", credential.username)
        assertEquals("pass1", credential.password)
        assertNull(credential.notes)
        assertEquals("social", credential.category)
        assertFalse(credential.isFavorite)
        assertEquals(1600000000000L, credential.createdAt)
        assertEquals(1600001000000L, credential.updatedAt)
    }

    /**
     * Credential → BackupCredential → Credential の往復変換で
     * データが保持されることを検証する（ID を除く）。
     */
    @Test
    fun `roundtrip conversion preserves data except id`() {
        val original = Credential(
            id = 99,
            serviceName = "Amazon",
            serviceUrl = "https://amazon.co.jp",
            username = "buyer",
            password = "sh0pp1ng!",
            notes = "プライム会員",
            category = "shopping",
            isFavorite = true,
            createdAt = 1650000000000L,
            updatedAt = 1650001000000L
        )

        val roundTripped = original.toBackup().toCredential()

        assertEquals(0, roundTripped.id) // ID は 0 になる
        assertEquals(original.serviceName, roundTripped.serviceName)
        assertEquals(original.serviceUrl, roundTripped.serviceUrl)
        assertEquals(original.username, roundTripped.username)
        assertEquals(original.password, roundTripped.password)
        assertEquals(original.notes, roundTripped.notes)
        assertEquals(original.category, roundTripped.category)
        assertEquals(original.isFavorite, roundTripped.isFavorite)
        assertEquals(original.createdAt, roundTripped.createdAt)
        assertEquals(original.updatedAt, roundTripped.updatedAt)
    }

    /**
     * [BackupCredential] のリストを JSON にシリアライズし、
     * デシリアライズして元のデータと一致することを検証する。
     */
    @Test
    fun `JSON serialization and deserialization roundtrip succeeds`() {
        val backupList = listOf(
            BackupCredential(
                serviceName = "Google",
                serviceUrl = "https://google.com",
                username = "user@gmail.com",
                password = "g00gleP@ss",
                notes = "メインアカウント",
                category = "email",
                isFavorite = true,
                createdAt = 1700000000000L,
                updatedAt = 1700001000000L
            ),
            BackupCredential(
                serviceName = "Slack",
                serviceUrl = "https://slack.com",
                username = "worker",
                password = "sl@ckP@ss",
                notes = null,
                category = "work",
                isFavorite = false,
                createdAt = 1700002000000L,
                updatedAt = 1700003000000L
            )
        )

        val jsonString = json.encodeToString(backupList)
        val deserialized: List<BackupCredential> = json.decodeFromString(jsonString)

        assertEquals(backupList.size, deserialized.size)
        for (i in backupList.indices) {
            assertEquals(backupList[i], deserialized[i])
        }
    }

    /**
     * デフォルト値を持つ [BackupCredential] が正しくシリアライズされることを検証する。
     */
    @Test
    fun `BackupCredential with defaults serializes correctly`() {
        val backup = BackupCredential(
            serviceName = "Test",
            username = "user",
            password = "pass"
        )

        val jsonString = json.encodeToString(backup)
        val deserialized: BackupCredential = json.decodeFromString(jsonString)

        assertEquals("Test", deserialized.serviceName)
        assertNull(deserialized.serviceUrl)
        assertEquals("user", deserialized.username)
        assertEquals("pass", deserialized.password)
        assertNull(deserialized.notes)
        assertEquals("other", deserialized.category)
        assertFalse(deserialized.isFavorite)
        assertEquals(0L, deserialized.createdAt)
        assertEquals(0L, deserialized.updatedAt)
    }

    /**
     * 不明なフィールドを含む JSON からでもデシリアライズが成功することを検証する。
     */
    @Test
    fun `deserialization ignores unknown fields`() {
        val jsonString = """
            {
                "serviceName": "Test",
                "username": "user",
                "password": "pass",
                "unknownField": "value",
                "anotherUnknown": 123
            }
        """.trimIndent()

        val deserialized: BackupCredential = json.decodeFromString(jsonString)

        assertEquals("Test", deserialized.serviceName)
        assertEquals("user", deserialized.username)
        assertEquals("pass", deserialized.password)
    }
}
Copy
B-1. app/src/test/java/com/securevault/app/util/PasswordStrengthCheckerTest.kt
Copypackage com.securevault.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PasswordStrengthChecker] のユニットテスト。
 *
 * 各種パスワードパターンに対して期待される強度レベルが返されることを検証する。
 */
class PasswordStrengthCheckerTest {

    /** テスト対象のインスタンス */
    private val checker = PasswordStrengthChecker()

    /**
     * 空文字列のパスワードが VERY_WEAK と判定されることを検証する。
     */
    @Test
    fun `empty password returns VERY_WEAK`() {
        val result = checker.check("")
        assertEquals(PasswordStrength.VERY_WEAK, result)
    }

    /**
     * 短い数字のみのパスワードが VERY_WEAK と判定されることを検証する。
     */
    @Test
    fun `short numeric password 1234 returns VERY_WEAK`() {
        val result = checker.check("1234")
        assertEquals(PasswordStrength.VERY_WEAK, result)
    }

    /**
     * 一般的な単語のパスワードが WEAK と判定されることを検証する。
     */
    @Test
    fun `common word password returns WEAK`() {
        val result = checker.check("password")
        assertEquals(PasswordStrength.WEAK, result)
    }

    /**
     * 大文字・小文字・数字を含むパスワードが MEDIUM と判定されることを検証する。
     */
    @Test
    fun `mixed case with number returns MEDIUM`() {
        val result = checker.check("Password1")
        assertEquals(PasswordStrength.MEDIUM, result)
    }

    /**
     * 大文字・小文字・数字・記号を含む長いパスワードが STRONG 以上と判定されることを検証する。
     */
    @Test
    fun `complex password returns STRONG or VERY_STRONG`() {
        val result = checker.check("P@ssw0rd!2024")
        assertTrue(
            "Expected STRONG or VERY_STRONG but was $result",
            result == PasswordStrength.STRONG || result == PasswordStrength.VERY_STRONG
        )
    }

    /**
     * 20文字以上のランダム文字列が VERY_STRONG と判定されることを検証する。
     */
    @Test
    fun `long random password returns VERY_STRONG`() {
        val result = checker.check("aB3#xY9!kL2@mN5&pQ8*")
        assertEquals(PasswordStrength.VERY_STRONG, result)
    }

    /**
     * 数字のみの長いパスワードが STRONG 未満であることを検証する。
     */
    @Test
    fun `long numeric only password is not STRONG`() {
        val result = checker.check("12345678901234567890")
        assertTrue(
            "Expected WEAK or MEDIUM for numeric-only password but was $result",
            result == PasswordStrength.WEAK || result == PasswordStrength.MEDIUM
                    || result == PasswordStrength.VERY_WEAK
        )
    }
}
Copy
注: PasswordStrengthChecker と PasswordStrength の正確なクラス名・パッケージ・メソッド名は Phase 3〜4 での実装に依存します。上記のテストコードは仕様書に基づく想定名で記述しています。実際のクラスに合わせて import とメソッド呼び出しを調整してください。

Part C: 最終調整
C-1. app/proguard-rules.pro
# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# SQLCipher
-keep class net.zetetic.** { *; }

# Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Room
-keep class com.securevault.app.data.db.entity.** { *; }
-keep class com.securevault.app.data.db.dao.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.securevault.app.**$$serializer { *; }
-keepclassmembers class com.securevault.app.** {
    *** Companion;
}

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# BackupCredential (Serializable)
-keep class com.securevault.app.data.backup.BackupCredential { *; }
C-4. ui/theme/Theme.kt 改修
Copypackage com.securevault.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * SecureVault アプリのテーマ。
 *
 * Material 3 のダイナミックカラーに対応し、
 * Android 12 以上ではシステムのウォールペーパーに基づくカラースキームを使用する。
 * Android 12 未満ではデフォルトのライト/ダークカラースキームを使用する。
 *
 * @param darkTheme ダークモードかどうか（デフォルトはシステム設定に従う）
 * @param dynamicColor ダイナミックカラーを使用するかどうか（デフォルトは true）
 * @param content テーマが適用されるコンポーザブルコンテンツ
 */
@Composable
fun SecureVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
Copy
C-6. docs/status.md 最終更新
Copy# SecureVault - 開発ステータス

## 完了フェーズ

### Phase 1: プロジェクト基盤 ✅
- Hilt DI セットアップ
- Room + SQLCipher データベース
- Credential エンティティ / DAO / Repository
- CryptoEngine (Tink AES-256-GCM)
- MasterKeyManager (Android Keystore)

### Phase 2: 認証・セキュリティ ✅
- BiometricAuthManager (生体認証 / デバイス認証)
- AuthScreen (ロック画面)
- 自動ロック (AppLifecycleObserver)
- クリップボード自動消去 (ClipboardManager)

### Phase 3: CRUD・UI ✅
- HomeScreen (認証情報一覧 / 検索 / カテゴリフィルタ / お気に入り)
- DetailScreen (認証情報詳細)
- AddEditScreen (認証情報追加・編集)
- PasswordGeneratorScreen (パスワード生成)
- NavGraph (画面遷移)

### Phase 4: 追加機能 ✅
- PasswordStrengthChecker (パスワード強度判定)
- アプリアイコン / スプラッシュスクリーン
- SettingsScreen (設定画面)

### Phase 5: 自動入力 ✅
- AutofillService (SecureVaultAutofillService)
- SmartFieldDetector (入力フィールド検出)
- AutofillResponseBuilder (自動入力応答生成)

### Phase 6: エクスポート/インポート・テスト・最終調整 ✅
- BackupManager (暗号化 JSON / CSV エクスポート・インポート)
- BackupCrypto (PBKDF2 + AES-256-GCM パスワードベース暗号化)
- BackupScreen / BackupViewModel (バックアップ UI)
- ユニットテスト (BackupCrypto, BackupManager, PasswordStrengthChecker)
- ProGuard / R8 ルール
- Release ビルド設定
- ダークモード対応
- NavGraph 最終整理

## 実装済み機能一覧

1. **認証情報管理**: CRUD 操作、検索、カテゴリフィルタ、お気に入り
2. **セキュリティ**: AES-256-GCM 暗号化、Android Keystore、SQLCipher
3. **生体認証**: 指紋 / 顔認証 / デバイス認証によるアプリロック
4. **自動ロック**: バックグラウンド遷移時の自動ロック
5. **クリップボード保護**: コピー後の自動消去
6. **パスワード生成**: カスタマイズ可能なパスワードジェネレーター
7. **パスワード強度判定**: リアルタイム強度表示
8. **自動入力サービス**: Android Autofill Framework 対応
9. **バックアップ・復元**: 暗号化 JSON (.securevault) / CSV エクスポート・インポート
10. **ダークモード**: Material 3 ダイナミックカラー対応
11. **オフライン完結**: INTERNET 権限不要

## 既知の制限事項

- Android 15+ では通知からの OTP 読み取りに制限がある
- 自動入力サービスはアプリによって対応状況が異なる
- ダイナミックカラーは Android 12 (API 31) 以上でのみ有効
- SQLCipher のパスフレーズはアプリ初回起動時に Keystore で生成・保存される
- CSV エクスポートはパスワードが平文で保存されるため、取り扱いに注意が必要

## ビルド情報

- minSdk: 26
- targetSdk: 35
- Kotlin: 2.0.x
- Compose BOM: 2024.x
- Hilt: 2.52
Copy
C-6. docs/specification.md 最終更新部分
specification.md の Phase 6 セクションに以下を追加（既存の Phase 1-5 仕様の後に追記）：

Copy## Phase 6: エクスポート/インポート・テスト・最終調整

### バックアップ機能
- **暗号化バックアップ** (.securevault): PBKDF2WithHmacSHA256 (600,000 iterations) + AES-256-GCM
- **CSV エクスポート/インポート**: 平文（警告表示あり）
- **インポート戦略**: 重複スキップ / 上書き / すべて追加
- **ファイルアクセス**: Storage Access Framework (SAF)

### テスト
- BackupCrypto: 暗号化・復号往復、パスワード不一致検証、ソルト一意性
- BackupManager: Credential変換、JSONシリアライズ往復
- PasswordStrengthChecker: 各種パターンの強度判定

### ProGuard / R8
- SQLCipher, Tink, Room, Kotlinx Serialization, Hilt の keep ルール
- Release ビルドで minify + shrinkResources 有効

### ダークモード
- Material 3 dynamicDarkColorScheme / dynamicLightColorScheme
- Android 12未満: デフォルト darkColorScheme / lightColorScheme
全ファイル一覧サマリー
以上で Phase 6 の全ファイルの実装が完了しました。作成・改修したファイルの一覧は以下の通りです：

ファイル	操作	完了
data/backup/BackupCredential.kt	新規作成	✅
data/backup/BackupCrypto.kt	新規作成	✅
data/backup/BackupManager.kt	新規作成	✅
ui/screen/backup/BackupScreen.kt	新規作成	✅
ui/screen/backup/BackupViewModel.kt	新規作成	✅
ui/screen/settings/SettingsScreen.kt	改修（バックアップ導線追加）	✅
ui/navigation/NavGraph.kt	改修（Backup ルート追加）	✅
ui/navigation/NavRoutes.kt	改修（Backup 定数追加）	✅
ui/theme/Theme.kt	改修（ダークモード対応）	✅
res/values/strings.xml	追加（バックアップ関連文字列）	✅
build.gradle.kts (app)	改修（serialization + release設定）	✅
build.gradle.kts (project)	改修（serialization plugin）	✅
gradle/libs.versions.toml	改修（serialization バージョン）	✅
proguard-rules.pro	改修（難読化ルール）	✅
test/.../BackupCryptoTest.kt	新規作成	✅
test/.../BackupManagerTest.kt	新規作成	✅
test/.../PasswordStrengthCheckerTest.kt	新規作成	✅
docs/specification.md	最終更新	✅
docs/status.md	最終更新	✅
実装上の注意点：

CredentialRepository の API は一切変更していません。getAllCredentials() が Flow<List<Credential>> を返し、addCredential() / updateCredential() が存在することを前提としています。実際のメソッドシグネチャに差異がある場合は BackupManager.kt 内の呼び出しを調整してください。

Credential エンティティのフィールド名（serviceName, serviceUrl, username, password, notes, category, isFavorite, createdAt, updatedAt）は Phase 1 の仕様に基づいています。実際のフィールド名が異なる場合は BackupCredential.kt の変換関数を調整してください。

PasswordStrengthCheckerTest.kt は PasswordStrengthChecker クラスに check(password: String): PasswordStrength メソッドがあることを前提としています。実際のクラス構造に合わせて調整してください。

SmartFieldDetectorTest.kt は AssistStructure のモックが困難なため、仕様書に記載の通りスキップしています。