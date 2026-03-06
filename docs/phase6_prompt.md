Phase 6 – エクスポート/インポート・他サービス対応・テスト・最終調整
前提
docs/specification.md と docs/status.md を必ず読んでから作業すること
Phase 1〜5 が完了していること
既存 API（CredentialRepository, CryptoEngine, BiometricAuthManager 等）は変更しない
パッケージ: com.securevault.app
ビルド確認: ./gradlew --no-daemon :app:assembleDebug が成功すること
Part A: エクスポート / インポート基盤
A-1. Kotlinx Serialization の依存追加
gradle/libs.versions.toml に追加：

Copy[versions]
kotlinxSerializationJson = "1.7.3"

[libraries]
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
プロジェクトレベル build.gradle.kts の plugins に追加：

Copyalias(libs.plugins.kotlin.serialization) apply false
app/build.gradle.kts に追加：

Copyplugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
}
A-2. data/backup/BackupCredential.kt を新規作成
パッケージ: com.securevault.app.data.backup

Copy@Serializable
data class BackupCredential(
    val serviceName: String,
    val serviceUrl: String? = null,
    val username: String,
    val password: String,
    val notes: String? = null,
    val category: String = "other",
    val isFavorite: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)
同ファイルに Credential ⇔ BackupCredential の変換 extension を定義：

Copyfun Credential.toBackup(): BackupCredential
fun BackupCredential.toCredential(): Credential   // id = 0 で生成
A-3. data/backup/BackupCrypto.kt を新規作成
バックアップ専用の暗号化ユーティリティ（Keystore ではなくパスワードベース）。

Copyobject BackupCrypto {
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 32
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    fun deriveKey(password: String, salt: ByteArray): SecretKey
    fun encrypt(plainBytes: ByteArray, key: SecretKey): Pair<ByteArray, ByteArray>  // (cipherText, iv)
    fun decrypt(cipherBytes: ByteArray, iv: ByteArray, key: SecretKey): ByteArray
    fun generateSalt(): ByteArray
}
A-4. data/backup/BackupManager.kt を新規作成
パッケージ: com.securevault.app.data.backup

Copy@Singleton
class BackupManager @Inject constructor(
    private val credentialRepository: CredentialRepository,
    @ApplicationContext private val context: Context
) {
    suspend fun exportEncrypted(outputUri: Uri, password: String): Int
    suspend fun importEncrypted(inputUri: Uri, password: String, strategy: ImportStrategy): Int
    suspend fun exportCsv(outputUri: Uri): Int
    suspend fun importCsv(inputUri: Uri): Int
}

enum class ImportStrategy {
    SKIP_DUPLICATES,
    OVERWRITE,
    IMPORT_ALL
}
暗号化ファイルフォーマット (.securevault):

Copy{
  "version": 1,
  "salt": "<Base64>",
  "iv": "<Base64>",
  "data": "<Base64 encrypted JSON array of BackupCredential>"
}
暗号化方式:

PBKDF2WithHmacSHA256, 600,000 iterations, 256bit
AES-256-GCM
CSV フォーマット（SecureVault 独自エクスポート用）:

ヘッダー行: serviceName,serviceUrl,username,password,notes,category
CSV パース時はダブルクォートのエスケープに対応すること
インポート時、1行目がヘッダーなら自動スキップ
Part B: 他サービスからのインポート対応
B-1. data/backup/ImportSource.kt を新規作成
パッケージ: com.securevault.app.data.backup

対応するインポート元の定義。各ソースの CSV ヘッダーとカラムマッピングを持つ。

Copyenum class ImportSource(
    val displayName: String,
    val fileExtension: String,
    val columnMapping: CsvColumnMapping
) {
    BRAVE(
        displayName = "Brave",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "note"
        )
    ),
    CHROME(
        displayName = "Google Chrome",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "note"
        )
    ),
    EDGE(
        displayName = "Microsoft Edge",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "note"
        )
    ),
    FIREFOX(
        displayName = "Firefox",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "url",        // Firefox はサービス名カラムがないので url を使う
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = null                 // Firefox は notes カラムなし
        )
    ),
    ONE_PASSWORD(
        displayName = "1Password",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "Title",
            serviceUrl = "Website",
            username = "Username",
            password = "Password",
            notes = "Notes"
        )
    ),
    BITWARDEN(
        displayName = "Bitwarden",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "login_uri",
            username = "login_username",
            password = "login_password",
            notes = "notes"
        )
    ),
    LASTPASS(
        displayName = "LastPass",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "name",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "extra"
        )
    ),
    DASHLANE(
        displayName = "Dashlane",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "title",
            serviceUrl = "url",
            username = "username",
            password = "password",
            notes = "note"
        )
    ),
    APPLE_PASSWORDS(
        displayName = "Apple パスワード（iCloud キーチェーン）",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "Title",
            serviceUrl = "URL",
            username = "Username",
            password = "Password",
            notes = "Notes"
        )
    ),
    KEEPASS(
        displayName = "KeePass / KeePassXC",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "Title",
            serviceUrl = "URL",
            username = "Username",
            password = "Password",
            notes = "Notes"
        )
    ),
    SECUREVAULT(
        displayName = "SecureVault CSV",
        fileExtension = "csv",
        columnMapping = CsvColumnMapping(
            serviceName = "serviceName",
            serviceUrl = "serviceUrl",
            username = "username",
            password = "password",
            notes = "notes"
        )
    );
}

data class CsvColumnMapping(
    val serviceName: String,
    val serviceUrl: String?,
    val username: String,
    val password: String,
    val notes: String?
)
Copy
B-2. data/backup/CsvImportParser.kt を新規作成
パッケージ: com.securevault.app.data.backup

ヘッダー行ベースで任意の CSV フォーマットをパースし、BackupCredential のリストに変換する汎用パーサー。

Copy@Singleton
class CsvImportParser @Inject constructor() {

    /**
     * CSV 文字列を指定された ImportSource のマッピングに従いパースする。
     * @param csvContent CSV ファイルの全文字列
     * @param source インポート元
     * @return パース結果の BackupCredential リスト
     * @throws IllegalArgumentException ヘッダーに必須カラムが見つからない場合
     */
    fun parse(csvContent: String, source: ImportSource): List<BackupCredential>

    /**
     * ヘッダー行を解析し、各カラムのインデックスを返す。
     * カラム名の比較は case-insensitive で行う。
     */
    internal fun resolveColumnIndices(
        headerFields: List<String>,
        mapping: CsvColumnMapping
    ): ResolvedColumns

    /**
     * CSV 行をフィールドのリストにパースする。
     * ダブルクォートのエスケープ、カンマ含有フィールドに対応。
     */
    internal fun parseCsvLine(line: String): List<String>
}

internal data class ResolvedColumns(
    val serviceNameIndex: Int,
    val serviceUrlIndex: Int?,
    val usernameIndex: Int,
    val passwordIndex: Int,
    val notesIndex: Int?
)
Copy
処理フロー：

CSV を行分割し、1行目をヘッダーとして解析
ImportSource.columnMapping と照合し、各カラムのインデックスを特定（case-insensitive）
2行目以降のデータ行を BackupCredential に変換
空行やパスワード空欄の行はスキップ
ヘッダー自動検出のフォールバック:

ヘッダー行に password (case-insensitive) を含まない場合は IllegalArgumentException をスロー
Bitwarden の login_password も password の一部として検出
B-3. BackupManager にサービス別インポートメソッドを追加
Copy// BackupManager に追加
suspend fun importFromService(
    inputUri: Uri,
    source: ImportSource,
    strategy: ImportStrategy
): Int
内部処理：

URI からファイル内容を読み込み
CsvImportParser.parse(content, source) で BackupCredential リストを取得
strategy に従い CredentialRepository に保存
インポート件数を返す
Part C: エクスポート / インポート UI
C-1. ui/screen/backup/BackupViewModel.kt を新規作成
Copy@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager
) : ViewModel() {
    // UI State: Idle, InProgress, Success(count, isExport), Error(message)
    val uiState: StateFlow<BackupUiState>

    fun exportEncrypted(uri: Uri, password: String)
    fun exportCsv(uri: Uri)
    fun importEncrypted(uri: Uri, password: String, strategy: ImportStrategy)
    fun importCsv(uri: Uri)                                              // SecureVault CSV
    fun importFromService(uri: Uri, source: ImportSource, strategy: ImportStrategy)  // 他サービス
    fun resetState()
}

sealed interface BackupUiState {
    data object Idle : BackupUiState
    data object InProgress : BackupUiState
    data class Success(val count: Int, val isExport: Boolean) : BackupUiState
    data class Error(val message: String) : BackupUiState
}
C-2. ui/screen/backup/BackupScreen.kt を新規作成
画面構成：

エクスポートセクション

「暗号化バックアップ (.securevault)」ボタン → パスワード入力ダイアログ（入力＋確認の2フィールド、不一致エラー表示） → SAF ファイル保存ピッカー
「CSV エクスポート（平文・注意）」ボタン → 警告ダイアログ（「パスワードが平文で保存されます。本当にエクスポートしますか？」） → SAF ファイル保存ピッカー
インポートセクション

「暗号化バックアップから復元」ボタン → SAF ファイル選択ピッカー → パスワード入力ダイアログ → 重複処理選択（スキップ / 上書き / すべて追加）
「CSV からインポート（SecureVault 形式）」ボタン → SAF ファイル選択ピッカー
「他のサービスからインポート」ボタン → サービス選択ダイアログ → SAF ファイル選択ピッカー → 重複処理選択
サービス選択ダイアログ

ImportSource の全エントリをラジオボタンのリストで表示
表示順: Brave, Google Chrome, Microsoft Edge, Firefox, 1Password, Bitwarden, LastPass, Dashlane, Apple パスワード, KeePass / KeePassXC
各項目に短い説明テキスト: 「設定 → パスワードマネージャー → エクスポートで取得した CSV ファイル」程度
結果表示: Snackbar で「○件エクスポートしました」「○件インポートしました」

SAF ピッカーは rememberLauncherForActivityResult で:

エクスポート: ActivityResultContracts.CreateDocument("application/octet-stream") (暗号化) / CreateDocument("text/csv") (CSV)
インポート: ActivityResultContracts.OpenDocument() で arrayOf("text/csv", "text/comma-separated-values", "*/*")
C-3. NavGraph にバックアップ画面を追加
Copyobject NavRoutes {
    // 既存に追加
    const val Backup = "backup"
}
NavGraph に composable 追加。SettingsScreen に「データのバックアップ・復元」ボタンを追加し、Backup 画面へ遷移。SettingsScreen のシグネチャに onNavigateToBackup: () -> Unit を追加。

C-4. res/values/strings.xml にバックアップ関連の文字列を追加
Copy<!-- バックアップ -->
<string name="backup_title">バックアップ・復元</string>
<string name="backup_export_section">エクスポート</string>
<string name="backup_export_encrypted">暗号化バックアップ (.securevault)</string>
<string name="backup_export_csv">CSV エクスポート（平文）</string>
<string name="backup_export_csv_warning">パスワードが平文で保存されます。\n信頼できる場所にのみ保存してください。\n\n本当にエクスポートしますか？</string>
<string name="backup_import_section">インポート</string>
<string name="backup_import_encrypted">暗号化バックアップから復元</string>
<string name="backup_import_csv">CSV からインポート（SecureVault 形式）</string>
<string name="backup_import_service">他のサービスからインポート</string>
<string name="backup_import_service_title">インポート元を選択</string>
<string name="backup_import_service_hint">設定 → パスワード → エクスポートで取得した CSV ファイルを選択してください</string>
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
<string name="backup_import_invalid_format">ファイル形式が正しくありません。選択したサービスの CSV ファイルか確認してください。</string>
<string name="settings_backup">バックアップ・復元</string>
Part D: テスト
D-1. ユニットテスト
以下のテストファイルを app/src/test/java/com/securevault/app/ に新規作成：

data/backup/BackupCryptoTest.kt
パスワードから鍵導出 → 暗号化 → 復号の往復テスト
異なるパスワードで復号失敗（AEADBadTagException）の検証
ソルトが毎回異なることの検証
空データの暗号化・復号
同一パスワード＋同一ソルトから同一鍵の導出
data/backup/BackupManagerTest.kt
BackupCredential ⇔ Credential 変換テスト（全フィールド保持の確認）
変換後の id が 0 であることの確認
往復変換（Credential → BackupCredential → Credential）の検証
JSON シリアライズ → デシリアライズの往復テスト
デフォルト値を持つ BackupCredential のシリアライズ
不明なフィールドを含む JSON のデシリアライズ（ignoreUnknownKeys）
data/backup/CsvImportParserTest.kt
Brave 形式の CSV パーステスト:
name,url,username,password,note
GitHub,https://github.com,user1,pass1,メモ
Bitwarden 形式の CSV パーステスト:
folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp
,1,login,GitHub,,,,https://github.com,user1,pass1,
LastPass 形式の CSV パーステスト:
url,username,password,totp,extra,name,grouping,fav
https://github.com,user1,pass1,,メモ,GitHub,,0
Firefox 形式の CSV パーステスト:
url,username,password,httpRealm,formActionOrigin,guid,timeCreated,timeLastUsed,timePasswordChanged
https://github.com,user1,pass1,,https://github.com,{uuid},1700000000000,1700001000000,1700001000000
1Password 形式のパーステスト
Apple パスワード形式のパーステスト
KeePass 形式のパーステスト
Dashlane 形式のパーステスト
ヘッダーの大文字小文字が異なるケース（case-insensitive 検証）
必須カラム不足時の IllegalArgumentException
ダブルクォート内にカンマを含むフィールドの正しいパース
空行のスキップ
パスワード空欄行のスキップ
util/PasswordStrengthCheckerTest.kt
空文字 → VERY_WEAK
"1234" → VERY_WEAK
"password" → WEAK
"Password1" → MEDIUM
"P@ssw0rd!2024" → STRONG or VERY_STRONG
20文字以上のランダム文字列 → VERY_STRONG
Part E: 最終調整
E-1. ProGuard / R8 ルール
app/proguard-rules.pro に以下を追加：

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
E-2. Release ビルド設定
app/build.gradle.kts の release ブロック：

Copyrelease {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
E-3. ダークモード対応確認・追加
Theme.kt でダークモード分岐がなければ追加：

Copy@Composable
fun SecureVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
E-4. NavGraph 最終整理
全ルートが正しく定義されていることを確認： Auth, Home, AddEdit(credentialId), Detail(credentialId), Settings, Generator, Backup

E-5. specification.md と status.md の最終更新
Phase 6 完了後に以下を反映：

全フェーズ完了
実装済み機能一覧の最終版（他サービスインポート対応を含む）
対応インポート元の一覧: Brave, Chrome, Edge, Firefox, 1Password, Bitwarden, LastPass, Dashlane, Apple パスワード, KeePass
既知の制限事項（Android 15+ の通知 OTP 制限、Samsung Pass 非対応等）
変更対象ファイル一覧
ファイル	操作
data/backup/BackupCredential.kt	新規作成
data/backup/BackupCrypto.kt	新規作成
data/backup/BackupManager.kt	新規作成
data/backup/ImportSource.kt	新規作成
data/backup/CsvImportParser.kt	新規作成
ui/screen/backup/BackupScreen.kt	新規作成
ui/screen/backup/BackupViewModel.kt	新規作成
ui/screen/settings/SettingsScreen.kt	改修（バックアップ導線追加）
ui/navigation/NavGraph.kt	改修（Backup ルート追加）
ui/navigation/NavRoutes.kt	改修（Backup 定数追加）
ui/theme/Theme.kt	改修（ダークモード対応確認・追加）
res/values/strings.xml	追加（バックアップ・インポート関連文字列）
build.gradle.kts (app)	改修（serialization plugin + dependency, release設定）
build.gradle.kts (project)	改修（serialization plugin 追加）
gradle/libs.versions.toml	改修（serialization バージョン追加）
proguard-rules.pro	改修（難読化ルール追加）
test/.../BackupCryptoTest.kt	新規作成
test/.../BackupManagerTest.kt	新規作成
test/.../CsvImportParserTest.kt	新規作成
test/.../PasswordStrengthCheckerTest.kt	新規作成
docs/specification.md	最終更新
docs/status.md	最終更新
各サービスの CSV カラム対応表（実装時の参照用）
サービス	ヘッダー行のカラム	マッピング先
Brave / Chrome / Edge	name, url, username, password, note	serviceName=name, serviceUrl=url, username=username, password=password, notes=note
Firefox	url, username, password, httpRealm, formActionOrigin, guid, timeCreated, timeLastUsed, timePasswordChanged	serviceName=url（URLからドメイン抽出が望ましい）, serviceUrl=url, username=username, password=password, notes=なし
1Password	Title, Website, Username, Password, OTPAuth, Favorite, Archived, Tags, Notes	serviceName=Title, serviceUrl=Website, username=Username, password=Password, notes=Notes
Bitwarden	folder, favorite, type, name, notes, fields, reprompt, login_uri, login_username, login_password, login_totp	serviceName=name, serviceUrl=login_uri, username=login_username, password=login_password, notes=notes
LastPass	url, username, password, totp, extra, name, grouping, fav	serviceName=name, serviceUrl=url, username=username, password=password, notes=extra
Dashlane	title, url, username, password, note, otpSecret, category （credentials.csv）	serviceName=title, serviceUrl=url, username=username, password=password, notes=note
Apple パスワード	Title, URL, Username, Password, Notes, OTPAuth	serviceName=Title, serviceUrl=URL, username=Username, password=Password, notes=Notes
KeePass / KeePassXC	Group, Title, Username, Password, URL, Notes, TOTP, Icon, Last Modified, Created (KeePassXC) / Title, User Name, Password, URL, Notes (KeePass)	serviceName=Title, serviceUrl=URL, username=Username or User Name, password=Password, notes=Notes
SecureVault	serviceName, serviceUrl, username, password, notes, category	そのまま
Firefox の serviceName 補足
Firefox の CSV は name / title カラムがないため、url カラムの値からドメイン名を抽出して serviceName に使う。例：

https://github.com/login → github.com
https://accounts.google.com/signin → accounts.google.com
java.net.URI でパースし、host を取得する。パース失敗時は URL 文字列をそのまま使う。

KeePass のヘッダー揺れ対応
KeePass (2.x) は User Name（スペースあり）、KeePassXC は Username（スペースなし）。 resolveColumnIndices で正規化（スペース除去・小文字化）して照合すること。

注意事項
CredentialRepository の API は変更しない
CryptoEngine / MasterKeyManager の API は変更しない（バックアップは独自のパスワードベース暗号化）
BiometricAuthManager の API は変更しない（onSuccess は () -> Unit）
SAF を使うので WRITE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE 権限は不要
エクスポートファイルの拡張子は .securevault（暗号化）と .csv（平文）
@Query アノテーション内の SQL は改行せず1行で書く
すべての public クラス/メソッドに KDoc を付与する
import はワイルドカード不可
CsvImportParser のカラム名照合は case-insensitive かつスペース正規化して行う
Samsung Pass は .spass 独自形式のため非対応。status.md に「Samsung Pass をお使いの方は Google パスワードマネージャー経由でのインポートを推奨」と記載
最終チェックリスト（Phase 6 完了時に確認）
 ./gradlew --no-daemon :app:assembleDebug 成功
 ./gradlew --no-daemon :app:testDebugUnitTest 成功
 ./gradlew --no-daemon :app:assembleRelease 成功
 INTERNET 権限が AndroidManifest.xml に存在しないこと
 ダークモード切替で UI が崩れないこと
 暗号化バックアップ → 全削除 → 復元 が成功すること
 CSV エクスポート → インポート が成功すること
 Brave 形式の CSV インポートが成功すること
 Bitwarden 形式の CSV インポートが成功すること
 サービス選択ダイアログに全10サービスが表示されること
 自動ロック（バックグラウンド → フォアグラウンド復帰）が動作すること
 クリップボードコピー後の自動消去が動作すること
 Chrome でパスワード自動入力の候補が表示されること
 strings.xml が全て日本語であること
 docs/specification.md と docs/status.md が最新であること