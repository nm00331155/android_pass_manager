Android パスワード管理アプリ設計ガイド
十分に実現可能です。以下に、iPhoneのキーチェーン相当の精度をシンプルに実現するための全体設計をまとめます。

1. アーキテクチャ概要
┌─────────────────────────────────────────────────┐
│                  ユーザーインターフェース              │
│          (Jetpack Compose / Material 3)          │
├─────────────────────────────────────────────────┤
│              BiometricPrompt (生体認証)            │
│         指紋 / 顔認証 / PIN フォールバック            │
├─────────────────────────────────────────────────┤
│              AutofillService (自動入力)            │
│    他アプリ・ブラウザのログインフォームに自動入力         │
├─────────────────────────────────────────────────┤
│              暗号化ストレージ層                      │
│   Android Keystore + Tink + DataStore            │
├─────────────────────────────────────────────────┤
│              ローカルDB (Room / SQLCipher)         │
│         パスワードデータの永続化                      │
└─────────────────────────────────────────────────┘
2. 必要な権限（最小限）
Copy<!-- AndroidManifest.xml -->

<!-- 自動入力サービスとして動作するために必須 -->
<uses-permission android:name="android.permission.BIND_AUTOFILL_SERVICE" />

<!-- 生体認証（権限宣言不要、BiometricPrompt APIが処理） -->

<!-- インターネット権限は不要（完全オフライン動作） -->
ポイントとして、INTERNET権限すら不要です。すべてのデータをデバイス内に閉じることで、プライバシーとセキュリティを最大化できます。

3. コア技術スタック
3-A. 暗号化（最重要）
EncryptedSharedPreferencesは2025年に非推奨になりました。現在の推奨構成は以下です：

Copy// build.gradle.kts
dependencies {
    // 暗号化
    implementation("com.google.crypto.tink:tink-android:1.15.0")
    
    // データ永続化
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    
    // データベース（暗号化DB）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.6.0")
    
    // 生体認証
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    
    // UI
    implementation("androidx.compose.material3:material3:1.3.0")
}
3-B. マスターキーの管理
Copyimport android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object MasterKeyManager {
    private const val KEY_ALIAS = "password_manager_master_key"
    
    /**
     * Android Keystore にハードウェアバックドのマスターキーを生成
     * - 生体認証が必要な設定にすることで、認証なしでは復号不可
     */
    fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }
        
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        )
        
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)           // 生体認証必須
            .setUserAuthenticationParameters(300, // 5分間有効
                KeyProperties.AUTH_BIOMETRIC_STRONG 
                    or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .build()
        
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
Copy
3-C. パスワードの暗号化・復号
Copyimport javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoEngine {
    private const val GCM_TAG_LENGTH = 128
    
    fun encrypt(plainText: String, key: SecretKey): EncryptedData {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return EncryptedData(
            cipherText = cipherText, 
            iv = cipher.iv
        )
    }
    
    fun decrypt(encryptedData: EncryptedData, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, encryptedData.iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val plainText = cipher.doFinal(encryptedData.cipherText)
        return String(plainText, Charsets.UTF_8)
    }
}

data class EncryptedData(val cipherText: ByteArray, val iv: ByteArray)
4. データベース設計（Room + SQLCipher）
Copy@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serviceName: String,           // 例: "Google", "Amazon"
    val serviceUrl: String?,           // 例: "https://accounts.google.com"
    val username: String,              // 暗号化済み
    val encryptedPassword: ByteArray,  // AES-GCM暗号化済み
    val iv: ByteArray,                 // 初期化ベクトル
    val notes: String? = null,         // 暗号化済みメモ
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface CredentialDao {
    @Query("SELECT * FROM credentials ORDER BY serviceName ASC")
    fun getAll(): Flow<List<CredentialEntity>>
    
    @Query("SELECT * FROM credentials WHERE serviceUrl LIKE :url")
    suspend fun findByUrl(url: String): List<CredentialEntity>
    
    @Insert
    suspend fun insert(credential: CredentialEntity)
    
    @Update
    suspend fun update(credential: CredentialEntity)
    
    @Delete
    suspend fun delete(credential: CredentialEntity)
}
Copy
5. 自動入力サービス（iPhoneキーチェーン相当の体験）
ここが iPhoneのパスワード管理と同等の精度 を実現するための核心部分です：

Copyclass MyAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val context = request.fillContexts
        val structure = context.last().structure
        
        // 1. フォーム内のユーザー名/パスワードフィールドを探索
        val fields = AutofillFieldParser.parse(structure)
        
        if (fields.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        
        // 2. 対象アプリのパッケージ名 or URLでDB検索
        val packageName = structure.activityComponent.packageName
        val matchedCredentials = runBlocking {
            credentialRepository.findByPackageOrUrl(packageName)
        }
        
        if (matchedCredentials.isEmpty()) {
            // 保存候補として SaveInfo をセット
            val response = buildSaveOnlyResponse(fields)
            callback.onSuccess(response)
            return
        }
        
        // 3. 生体認証付きで候補を提示
        val response = FillResponse.Builder()
        
        matchedCredentials.forEach { cred ->
            val presentation = RemoteViews(
                this.packageName, R.layout.autofill_item
            ).apply {
                setTextViewText(R.id.service_name, cred.serviceName)
                setTextViewText(R.id.username, cred.username)
            }
            
            // 認証Intentを設定（生体認証を経てからデータを返す）
            val authIntent = Intent(this, AutofillAuthActivity::class.java).apply {
                putExtra("credential_id", cred.id)
            }
            val intentSender = PendingIntent.getActivity(
                this, cred.id.toInt(), authIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            ).intentSender
            
            response.addDataset(
                Dataset.Builder()
                    .setValue(fields.usernameId!!, null, presentation)
                    .setValue(fields.passwordId!!, null, presentation)
                    .setAuthentication(intentSender)
                    .build()
            )
        }
        
        // SaveInfo で新規パスワードの保存にも対応
        response.setSaveInfo(
            SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                arrayOf(fields.usernameId!!, fields.passwordId!!)
            ).build()
        )
        
        callback.onSuccess(response.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.last().structure
        val fields = AutofillFieldParser.parse(structure)
        
        // ユーザーが入力した新しい認証情報を暗号化して保存
        val username = fields.extractedUsername ?: ""
        val password = fields.extractedPassword ?: ""
        val packageName = structure.activityComponent.packageName
        
        runBlocking {
            credentialRepository.saveNewCredential(
                packageName = packageName,
                username = username, 
                password = password
            )
        }
        
        callback.onSuccess()
    }
}
Copy
6. 生体認証の実装
Copyclass BiometricAuthHelper(private val activity: FragmentActivity) {
    
    fun authenticate(onSuccess: (Cipher) -> Unit, onError: (String) -> Unit) {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("パスワードマネージャー")
            .setSubtitle("認証してパスワードにアクセス")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                    or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        
        // Keystore のキーにバインドした Cipher を使う
        val cipher = getCipherForDecryption()
        val cryptoObject = BiometricPrompt.CryptoObject(cipher)
        
        val biometricPrompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    result.cryptoObject?.cipher?.let { onSuccess(it) }
                }
                
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
            }
        )
        
        biometricPrompt.authenticate(promptInfo, cryptoObject)
    }
}
Copy
7. AndroidManifest.xml（最終形）
Copy<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 権限: 自動入力のバインドのみ。INTERNET不要 -->
    
    <application
        android:allowBackup="false"
        android:fullBackupContent="false"
        android:dataExtractionRules="@xml/data_extraction_rules">
        
        <!-- メインActivity -->
        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        
        <!-- 自動入力サービス -->
        <service
            android:name=".autofill.MyAutofillService"
            android:label="MyPassword Manager"
            android:permission="android.permission.BIND_AUTOFILL_SERVICE"
            android:exported="true">
            <intent-filter>
                <action android:name="android.service.autofill.AutofillService" />
            </intent-filter>
            <meta-data
                android:name="android.autofill"
                android:resource="@xml/autofill_service_config" />
        </service>
        
        <!-- 自動入力時の生体認証Activity -->
        <activity android:name=".autofill.AutofillAuthActivity" />
        
    </application>
</manifest>
Copy
8. iPhoneキーチェーンとの機能比較
機能	iPhone Keychain	本アプリ（実装可能）
生体認証でロック解除	Face ID / Touch ID	BiometricPrompt（指紋/顔）
アプリ内自動入力	対応	AutofillService で対応
ブラウザ自動入力	Safari自動連携	Chrome連携（設定変更が必要）
AES-256暗号化	対応	Android Keystore + AES-GCM
パスワード生成	対応	カスタム実装で対応
漏洩チェック	対応	Have I Been Pwned API等で拡張可能
クラウド同期	iCloud	なし（オフライン設計）
ハードウェアキー保護	Secure Enclave	StrongBox / TEE
9. 開発ロードマップ（推奨順序）
Phase 1 — 基盤（1〜2週間） として、Room + SQLCipherでの暗号化DB構築、Android Keystore によるマスターキー管理、BiometricPrompt による認証フローを実装します。

Phase 2 — コアUI（1〜2週間） として、Jetpack Compose でのパスワード一覧・追加・編集画面、パスワード生成機能（長さ・文字種のカスタマイズ）、クリップボードへのコピー（自動クリア付き）を作ります。

Phase 3 — 自動入力（2〜3週間） として、AutofillService の実装、フォームフィールドのヒューリスティック解析、パッケージ名/URLベースのマッチング、新規パスワードの自動保存（onSaveRequest）を組み込みます。

Phase 4 — 仕上げ（1週間） として、エクスポート/インポート（暗号化済みJSON）、検索・フィルタ機能、自動ロック（バックグラウンド移行時）を追加します。

まとめ
iPhoneのキーチェーン相当の体験は、Androidの標準APIだけで実現可能です。核心は3つで、Android Keystore（ハードウェアレベルの鍵保護）、AutofillService（他アプリへの自動入力）、BiometricPrompt（生体認証）です。これらはすべてAndroid標準APIであり、余計な権限を一切要求しません。

まずPhase 1の暗号化基盤から始めることをお勧めします。何か具体的なフェーズで詳しく知りたい部分はありますか？

アプリ記録、自動入力はどの範囲まで動作するものですか。
可能な限りすべてのアプリに対応してほしいと考えています。
またSMS通知のワンタイムやメールに来るワンタイムも自動で拾って入力する機能があれば最高です
いい質問です。自動入力の対応範囲とワンタイムコード自動入力は、ユーザー体験を大きく左右する部分なので、現実的にどこまでできるかを正確にお伝えします。

まず最新の対応状況を確認させてください。

ツールを使用する
|
検索
Android AutofillService compatibility all apps limitations
エージェントを表示
ツールを使用する
|
検索
Android SMS OTP autofill SmsRetriever API third party app
エージェントを表示
ツールを使用する
|
検索
Android NotificationListenerService read OTP email notification
エージェントを表示
ツールを使用する
|
読み取り
https://developer.android.com/about/versions/15/behavior-changes-all
エージェントを表示
ツールを使用する
|
検索
Android 15 OTP notification sensitive content restriction trusted app workaround
エージェントを表示
ツールを使用する
|
検索
Android SmsCodeAutofillClient autofill service OTP implementation
エージェントを表示
ツールを使用する
|
検索
Android Credential Manager autofill provider OTP passkey support scope
エージェントを表示
調査が完了しました。自動入力の対応範囲とOTP自動入力について、現実的な制約も含めて正確に整理します。

自動入力の対応範囲とOTP自動取得の全体像
1. 自動入力（パスワード）の対応範囲
対応レベル別の整理
┌─────────────────────────────────────────────────────────────┐
│                    対応レベル A（ほぼ完璧）                      │
│  autofillHints を正しく設定しているアプリ                        │
│  → 銀行アプリ、大手SNS、Google系、主要ECサイトなど               │
│  → フィールドを正確に識別でき、自動入力がスムーズに動作            │
├─────────────────────────────────────────────────────────────┤
│                    対応レベル B（高確率で動作）                   │
│  autofillHints 未設定だが標準的なView構造のアプリ                │
│  → ヒューリスティック解析（ヒントテキスト、ID名等）で対応          │
│  → "password", "email", "username" 等のヒントから推測            │
├─────────────────────────────────────────────────────────────┤
│                    対応レベル C（互換モードで対応）                │
│  ブラウザ内のWebフォーム（Chrome, Firefox等）                    │
│  → AutofillServiceの「互換モード」で対応                         │
│  → Chrome は設定で「別のサービスで自動入力」を有効にする必要あり    │
├─────────────────────────────────────────────────────────────┤
│                    対応レベル D（対応困難）                       │
│  独自描画（ゲームエンジン、Flutter/React Nativeの一部、            │
│  カスタムView でフォームを構築しているアプリ）                     │
│  → AssistStructure にフィールドが露出しない                      │
│  → 手動コピー＆ペーストにフォールバック                           │
└─────────────────────────────────────────────────────────────┘
現実的なカバー率
カテゴリ	対応率の目安	具体例
ネイティブAndroidアプリ（大手）	90〜95%	Gmail, Amazon, 銀行, SNS
ネイティブAndroidアプリ（中小）	70〜85%	autofillHints未設定のものが多い
Chrome内のWebサイト	85〜95%	ユーザーが設定変更すれば高精度
Firefox / 他ブラウザ	60〜80%	互換モードの対応状況に依存
Flutter / React Native アプリ	50〜70%	フレームワーク側の対応状況による
ゲーム / カスタム描画UI	ほぼ0%	構造的に不可能
カバー率を最大化する実装戦略
Copyclass SmartFieldDetector {
    
    /**
     * 3段階のフォールバック戦略でフィールドを検出
     */
    fun detectFields(node: AssistStructure.ViewNode): FieldType? {
        // ===== 戦略1: 公式 autofillHints（最も信頼性が高い）=====
        node.autofillHints?.forEach { hint ->
            when (hint) {
                View.AUTOFILL_HINT_USERNAME,
                View.AUTOFILL_HINT_EMAIL_ADDRESS -> return FieldType.USERNAME
                View.AUTOFILL_HINT_PASSWORD      -> return FieldType.PASSWORD
                View.AUTOFILL_HINT_SMS_OTP        -> return FieldType.OTP
            }
        }
        
        // ===== 戦略2: HTML属性（ブラウザ互換モード）=====
        node.htmlInfo?.let { html ->
            html.attributes?.forEach { attr ->
                if (attr.first == "type") {
                    when (attr.second) {
                        "password" -> return FieldType.PASSWORD
                        "email"    -> return FieldType.USERNAME
                    }
                }
                if (attr.first == "autocomplete") {
                    when (attr.second) {
                        "username", "email"      -> return FieldType.USERNAME
                        "current-password",
                        "new-password"           -> return FieldType.PASSWORD
                        "one-time-code"          -> return FieldType.OTP
                    }
                }
            }
        }
        
        // ===== 戦略3: ヒューリスティック（最後の手段）=====
        val hintText = listOfNotNull(
            node.hint,
            node.text?.toString(),
            node.idEntry
        ).joinToString(" ").lowercase()
        
        val usernamePatterns = listOf(
            "user", "ユーザー", "メール", "email", "mail",
            "login", "account", "アカウント", "id", "ログイン"
        )
        val passwordPatterns = listOf(
            "pass", "パスワード", "暗証", "secret", "pwd", "pin"
        )
        val otpPatterns = listOf(
            "otp", "code", "verify", "認証", "確認コード",
            "one-time", "ワンタイム", "sms"
        )

        return when {
            usernamePatterns.any { hintText.contains(it) } -> FieldType.USERNAME
            passwordPatterns.any { hintText.contains(it) } -> FieldType.PASSWORD
            otpPatterns.any { hintText.contains(it) }      -> FieldType.OTP
            // inputType による判定（テキストフィールドの種類）
            node.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 
                -> FieldType.PASSWORD
            node.inputType and InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS != 0 
                -> FieldType.USERNAME
            else -> null
        }
    }
    
    enum class FieldType { USERNAME, PASSWORD, OTP }
}
Copy
Chrome対応の設定（ユーザー側で必要な操作）
Copy<!-- res/xml/autofill_service_config.xml -->
<autofill-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="com.example.app.SettingsActivity">

    <!-- Chrome互換モード（Chrome 137以降はAndroid Autofillをネイティブサポート）-->
    <compatibility-package 
        android:name="com.android.chrome" 
        android:maxLongVersionCode="711900039" />
    <compatibility-package 
        android:name="com.chrome.beta" 
        android:maxLongVersionCode="711900039" />
    
    <!-- 他のブラウザも必要に応じて追加 -->
    <compatibility-package android:name="org.mozilla.firefox" />
    <compatibility-package android:name="com.brave.browser" />
    <compatibility-package android:name="com.microsoft.emmx" />
</autofill-service>
2. SMS ワンタイムコード自動入力
これはAndroidの SmsCodeAutofillClient APIで公式にサポートされています。

Copyclass OtpAutofillHandler(private val context: Context) {
    
    private val smsClient: SmsCodeAutofillClient = 
        SmsCodeRetriever.getAutofillClient(context)
    
    /**
     * SMS OTP の自動入力フローを開始
     * - READ_SMS権限は不要（Google Play Servicesが仲介）
     * - ユーザーの同意ダイアログが1回だけ表示される
     */
    suspend fun startSmsOtpRetrieval(
        targetPackageName: String
    ): SmsOtpResult {
        
        // 1. 既に別のリクエストが進行中か確認
        val hasOngoing = smsClient
            .hasOngoingSmsRequest(targetPackageName)
            .await()
        
        if (hasOngoing) {
            return SmsOtpResult.AlreadyInProgress
        }
        
        // 2. 権限状態の確認
        val permState = smsClient.checkPermissionState().await()
        if (permState == SmsCodeAutofillClient.PermissionState.DENIED) {
            return SmsOtpResult.PermissionDenied
        }
        
        // 3. SMS監視を開始（直近1〜5分のSMSも対象）
        return try {
            smsClient.startSmsCodeRetriever().await()
            SmsOtpResult.Listening
        } catch (e: ResolvableApiException) {
            // ユーザーに同意を求める必要がある
            SmsOtpResult.NeedsConsent(e)
        }
    }
    
    sealed class SmsOtpResult {
        object Listening : SmsOtpResult()
        object AlreadyInProgress : SmsOtpResult()
        object PermissionDenied : SmsOtpResult()
        data class NeedsConsent(val exception: ResolvableApiException) : SmsOtpResult()
    }
}
Copy
Copy/**
 * AutofillService内でOTPフィールドを検出した場合の処理
 */
override fun onFillRequest(
    request: FillRequest,
    cancellationSignal: CancellationSignal,
    callback: FillCallback
) {
    val structure = request.fillContexts.last().structure
    val fields = SmartFieldDetector().parseStructure(structure)
    
    // OTPフィールドが見つかった場合
    if (fields.otpId != null) {
        val otpPresentation = RemoteViews(packageName, R.layout.autofill_otp_item).apply {
            setTextViewText(R.id.label, "SMSから認証コードを自動入力")
        }
        
        // SMS OTP取得用の認証Intentを作成
        val otpIntent = Intent(this, SmsOtpActivity::class.java)
        val intentSender = PendingIntent.getActivity(
            this, 2001, otpIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ).intentSender
        
        val response = FillResponse.Builder()
            .addDataset(
                Dataset.Builder()
                    .setValue(fields.otpId, null, otpPresentation)
                    .setAuthentication(intentSender)
                    .build()
            )
            .build()
        
        callback.onSuccess(response)
        return
    }
    // ... 通常のパスワード自動入力処理 ...
}
Copy
SMS OTP 対応の要件と制約
✅ 対応できること:
   - Android 9 (API 28) 以上で動作
   - READ_SMS 権限は不要（Google Play Services経由）
   - ユーザー同意後は自動で SMS から OTP を抽出
   - 直近 1〜5分以内に届いた SMS もキャッチ可能

⚠️ 制約:
   - Google Play Services が必要（中国向け端末などでは動かない）
   - 対象アプリが独自に SmsRetriever API を使っている場合は競合
   - SMS のフォーマットによっては抽出精度が下がる場合あり
3. メール OTP の自動取得 — ここが最大の壁
Android 15 での重大な制限
Android 15 以降、OTPを含む通知は「機密コンテンツ」として分類され、信頼されていないアプリからは内容が隠蔽されます。

┌─────────────────────────────────────────────────────────────────┐
│               メール OTP 取得方法の比較                            │
├──────────────────────┬────────────┬──────────────────────────────┤
│ 方法                  │ 実現可能？  │ 問題点                       │
├──────────────────────┼────────────┼──────────────────────────────┤
│ NotificationListener │ ▲ 制限あり  │ Android 15+ で OTP通知の      │
│ Service              │            │ 内容が隠蔽される               │
│                      │            │ 「信頼されたアプリ」以外は      │
│                      │            │ 読み取り不可                   │
├──────────────────────┼────────────┼──────────────────────────────┤
│ メールAPI直接読み取り  │ ✕ 非推奨   │ Gmail APIにOAuth認証が必要     │
│ (Gmail API等)        │            │ Google審査が厳しい             │
│                      │            │ INTERNET権限が必要になる       │
├──────────────────────┼────────────┼──────────────────────────────┤
│ Accessibility        │ ▲ 制限あり  │ Google Play ポリシー違反の     │
│ Service              │            │ リスクが高い                   │
│                      │            │ 審査で却下される可能性大        │
├──────────────────────┼────────────┼──────────────────────────────┤
│ 手動コピー支援        │ ✅ 確実     │ クリップボード監視 +            │
│ (現実的な妥協案)      │            │ OTP検出 → 自動入力提案         │
└──────────────────────┴────────────┴──────────────────────────────┘
現実的なメールOTP対応策
完全な自動化は困難ですが、ユーザー体験を最大限よくする方法はあります。

Copy/**
 * 現実的なアプローチ: クリップボード監視 + OTP自動検出
 * 
 * ユーザーがメール内のOTPをコピー → 自動的に検出して入力候補に表示
 */
class ClipboardOtpDetector(private val context: Context) {

    private val clipboardManager = context.getSystemService(
        Context.CLIPBOARD_SERVICE
    ) as ClipboardManager
    
    // OTPのパターン（4〜8桁の数字）
    private val otpPattern = Regex("""(?<!\d)\d{4,8}(?!\d)""")
    
    fun startMonitoring(onOtpDetected: (String) -> Unit) {
        clipboardManager.addPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip ?: return@addPrimaryClipChangedListener
            val text = clip.getItemAt(0)?.text?.toString() ?: return@addPrimaryClipChangedListener
            
            // クリップボードの内容からOTPパターンを検出
            otpPattern.find(text)?.let { match ->
                onOtpDetected(match.value)
                
                // セキュリティ: 検出後にクリップボードをクリア
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboardManager.clearPrimaryClip()
                }
            }
        }
    }
}

/**
 * NotificationListenerService（Android 14以下では有効）
 * Android 15+では OTP通知は隠蔽されるため、フォールバックとして実装
 */
class OtpNotificationListener : NotificationListenerService() {
    
    private val otpPattern = Regex("""(?:コード|code|OTP|認証|verify)[^\d]*(\d{4,8})""", 
        RegexOption.IGNORE_CASE)
    
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Android 15+ では OTP含む通知は隠蔽される可能性あり
        val text = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: return
        
        otpPattern.find(text)?.let { match ->
            val otpCode = match.groupValues[1]
            // 検出したOTPをAutofillServiceに伝達
            broadcastOtpDetected(otpCode, sbn.packageName)
        }
    }
}
Copy
4. 全体の対応範囲マトリクス
                        SMS OTP    メールOTP   アプリ自動入力  ブラウザ自動入力
                        ────────   ─────────  ────────────  ──────────────
Android 9-13            ✅ 完全     ✅ 通知読取   ✅ 標準API     ✅ 互換モード
Android 14              ✅ 完全     ✅ 通知読取   ✅ 標準API     ✅ 互換モード
Android 15+             ✅ 完全     ⚠️ 制限あり  ✅ 標準API     ✅ ネイティブ

⚠️ = NotificationListener でのOTP読取が隠蔽される
     → クリップボード監視方式にフォールバック
5. 追加で必要になる権限
Copy<manifest>
    <!-- 自動入力サービス（必須）-->
    <!-- BIND_AUTOFILL_SERVICE は service宣言側で指定 -->

    <!-- 通知読み取り（メールOTP検出用・ユーザーが手動で許可）-->
    <service
        android:name=".otp.OtpNotificationListener"
        android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
        android:exported="true">
        <intent-filter>
            <action android:name="android.service.notification.NotificationListenerService" />
        </intent-filter>
    </service>
    
    <!-- 
      ★ 追加される権限は以下のみ:
         - BIND_AUTOFILL_SERVICE    → 自動入力
         - BIND_NOTIFICATION_LISTENER_SERVICE → OTP通知読取
         - FOREGROUND_SERVICE       → OTP待受中の表示
         
      ★ 不要な権限:
         - INTERNET          → 不要（完全オフライン）
         - READ_SMS          → 不要（SmsCodeAutofillClient経由）
         - READ_CONTACTS     → 不要
         - CAMERA            → 不要
    -->
</manifest>
6. まとめ：何ができて何ができないか
高い精度で実現できるもの： ネイティブアプリへのパスワード自動入力（カバー率約90%）、Chrome内Webサイトへの自動入力（Chrome 137以降はネイティブ対応）、SMS ワンタイムコードの自動取得と入力（READ_SMS権限不要）、そしてクリップボード経由のOTP自動検出です。

制限があるもの： メールOTPの自動取得はAndroid 15以降で通知内容が隠蔽されるため、完全な自動化は難しくなっています。Flutter/React Nativeアプリの一部もフレームワーク側の実装次第です。

対応不可能なもの： ゲームエンジンやカスタム描画による独自UIのログインフォーム、および中国向け端末など Google Play Services非搭載端末でのSMS OTP機能です。

SMS OTP は公式APIで問題なく対応できます。メールOTPについては、Android 15の制限を踏まえてクリップボード監視方式をメインに据えるのが現実的な設計判断になります。次にどの部分を詳しく実装していきますか？

これから作成に向けて進むので、フェーズ単位でvscode/copilotへのプロンプトをコードブロック内で作成してください
各フェーズのプロンプトを作成する前に、現在のAndroid開発のベストプラクティス（Compose、Gradle、ライブラリバージョン等）を最新情報で確認させてください。

ツールを使用する
|
検索
Android Jetpack Compose Material3 latest stable version 2025
エージェントを表示
ツールを使用する
|
検索
Android Room SQLCipher latest version Kotlin 2025
エージェントを表示
ツールを使用する
|
検索
Android Gradle plugin latest stable version AGP
エージェントを表示
ツールを使用する
|
検索
Google Tink Android latest version 2025 2026
エージェントを表示
ツールを使用する
|
検索
androidx.biometric latest stable version 2025
エージェントを表示
ツールを使用する
|
検索
Compose BOM 2025.12.00 dependencies list
エージェントを表示
最新のライブラリバージョンが確認できました。それでは、フェーズごとのプロンプトを作成します。

VSCode/Copilot 向けプロンプト集 — Android パスワードマネージャー
以下のプロンプトをフェーズ順にCopilotに投入してください。各プロンプトは前フェーズの成果物を前提としています。

Phase 0 — プロジェクト初期構築
# Android パスワードマネージャーアプリ — プロジェクト初期構築

## 概要
「SecureVault」という名前の Android パスワードマネージャーアプリのプロジェクトを新規作成してください。
完全オフライン動作（INTERNET権限不要）、最小権限設計のシンプルなアプリです。

## 技術スタック（必ずこのバージョンを使用）
- 言語: Kotlin
- UI: Jetpack Compose + Material 3
- AGP: 9.0.1
- Kotlin: 2.1.0
- Compose BOM: 2025.12.00（Material3 1.4.0 を含む）
- minSdk: 28（Android 9 — SMS OTP対応のため）
- targetSdk: 35
- compileSdk: 35

## プロジェクト構成

### パッケージ構造
com.securevault.app/ ├── data/ │ ├── db/ # Room + SQLCipher │ ├── repository/ # リポジトリ層 │ └── crypto/ # 暗号化エンジン ├── service/ │ ├── autofill/ # AutofillService │ └── otp/ # OTP検出（SMS・通知・クリップボード） ├── ui/ │ ├── theme/ # Material3テーマ │ ├── screen/ # 各画面（一覧、詳細、追加、設定） │ ├── component/ # 共通UIコンポーネント │ └── navigation/ # Navigation Compose ├── biometric/ # 生体認証 ├── util/ # ユーティリティ └── di/ # Hilt DI


### build.gradle.kts (app)の依存関係
```kotlin
dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Room + SQLCipher
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")
    implementation("net.zetetic:sqlcipher-android:4.6.1")

    // 暗号化
    implementation("com.google.crypto.tink:tink-android:1.19.0")

    // 生体認証
    implementation("androidx.biometric:biometric:1.4.0-alpha04")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Google Play Services（SMS OTP用）
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.android.gms:play-services-auth-api-phone:18.1.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // テスト
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
AndroidManifest.xml
以下の方針で作成してください:

INTERNET権限は宣言しない
allowBackup="false", fullBackupContent="false"
データ抽出ルールを設定
AutofillService、NotificationListenerService のservice宣言を含める
各serviceの intent-filter と permission を正しく設定
メインActivityは singleTask launchMode
作成してほしいファイル
build.gradle.kts (project-level) — Version Catalog (libs.versions.toml) を使用
libs.versions.toml — 上記すべての依存関係を定義
build.gradle.kts (app-level) — KSP, Hilt, Compose プラグイン設定含む
AndroidManifest.xml — 上記の方針に従う
data_extraction_rules.xml
autofill_service_config.xml — Chrome, Firefox, Brave, Edge の互換モード設定含む
MainActivity.kt — Hilt, Compose, Navigation の初期セットアップ
ui/theme/ — Color.kt, Type.kt, Theme.kt（Material3ダークモード対応）
SecureVaultApplication.kt — Hiltの@HiltAndroidApp
ui/navigation/NavGraph.kt — 以下の画面への遷移を定義
AuthScreen（生体認証/PIN入力）
HomeScreen（パスワード一覧）
AddEditScreen（追加/編集）
DetailScreen（詳細表示）
SettingsScreen（設定）
PasswordGeneratorScreen（パスワード生成）
すべてのファイルをフルで出力してください。プレースホルダーやTODOは残さず、コンパイル可能な状態にしてください。


---

## Phase 1 — 暗号化基盤と生体認証

Phase 1: 暗号化基盤と生体認証の実装
Phase 0 で作成したプロジェクト構造を前提として、暗号化とセキュリティの基盤を実装してください。

1. マスターキー管理（data/crypto/MasterKeyManager.kt）
Android Keystore を使い、以下の仕様で AES-256 マスターキーを生成・管理するクラスを作成:

キーエイリアス: "securevault_master_key"
アルゴリズム: AES-256-GCM
setUserAuthenticationRequired(true)
setUserAuthenticationParameters(300秒, BIOMETRIC_STRONG | DEVICE_CREDENTIAL)
StrongBox が利用可能ならStrongBox を使用（isStrongBoxBacked）
キーが既に存在する場合は既存キーを返す
キーが無効化された場合（生体情報変更時等）の復旧ロジックを含める
2. 暗号化エンジン（data/crypto/CryptoEngine.kt）
encrypt(plainText: String, cipher: Cipher): EncryptedData
decrypt(encryptedData: EncryptedData, cipher: Cipher): String
getCipherForEncryption(): Cipher（Keystoreのキーでinit）
getCipherForDecryption(iv: ByteArray): Cipher
EncryptedData は cipherText: ByteArray と iv: ByteArray を保持
Base64エンコード/デコードのユーティリティメソッドも含める
3. 生体認証マネージャー（biometric/BiometricAuthManager.kt）
BiometricPrompt + CryptoObject を使った認証フロー
BiometricManager.canAuthenticate() による事前チェック
3種類のコールバック: onSuccess(cipher: Cipher), onError(message: String), onFallback()
認証ダイアログのタイトル・サブタイトルはパラメータで変更可能
BIOMETRIC_STRONG を優先し、非対応端末では DEVICE_CREDENTIAL にフォールバック
Compose から利用できるよう、State/Flow ベースのAPI を公開
4. 認証画面（ui/screen/AuthScreen.kt）
アプリ起動時に表示される生体認証画面
アプリロゴ + 「認証してください」メッセージ
生体認証ボタン（タップで BiometricPrompt 起動）
認証成功時に HomeScreen に遷移
認証失敗時のエラーメッセージ表示
生体認証非対応端末ではPIN/パターン認証にフォールバック
アニメーション付き（ロゴのフェードイン等）
5. 自動ロック機能（util/AutoLockManager.kt）
アプリがバックグラウンドに移行してから一定時間（デフォルト60秒）で自動ロック
ProcessLifecycleOwner を使用してアプリのフォアグラウンド/バックグラウンド状態を監視
ロック時は AuthScreen に強制遷移
タイムアウト時間は設定画面から変更可能（DataStore に保存）
即時ロックオプションも提供
6. Hilt DIモジュール（di/CryptoModule.kt, di/BiometricModule.kt）
MasterKeyManager, CryptoEngine, BiometricAuthManager を @Singleton で提供
Application コンテキストの注入
注意事項
try-catch で KeyPermanentlyInvalidatedException を適切にハンドリング
すべてのクラスに KDoc コメントを記述
UserAuthenticationParameters の秒数は定数で管理
テスト用に暗号化/復号のユニットテストも1ファイル作成

---

## Phase 2 — データベースとリポジトリ

Phase 2: Room + SQLCipher データベースとリポジトリ層の実装
Phase 1 の暗号化基盤を使って、パスワードデータの永続化層を構築してください。

1. データベーススキーマ
CredentialEntity（data/db/entity/CredentialEntity.kt）
@Entity(tableName = "credentials")

id: Long (PrimaryKey, autoGenerate)
serviceName: String — サービス名（例: "Google"）※暗号化しない（検索用）
serviceUrl: String? — URL（例: "https://accounts.google.com"）※暗号化しない
packageName: String? — 対応するAndroidアプリのパッケージ名 ※暗号化しない
encryptedUsername: String — Base64エンコード済み暗号化ユーザー名
usernameIv: String — Base64エンコード済みIV
encryptedPassword: String — Base64エンコード済み暗号化パスワード
passwordIv: String — Base64エンコード済みIV
encryptedNotes: String? — Base64エンコード済み暗号化メモ
notesIv: String? — Base64エンコード済みIV
category: String — カテゴリ（"login", "finance", "social", "other"）
faviconUrl: String? — ファビコンURL（将来の拡張用、現時点では未使用）
createdAt: Long — 作成日時（Unix timestamp）
updatedAt: Long — 更新日時（Unix timestamp）
isFavorite: Boolean — お気に入りフラグ
CategoryEntity は作らず、category フィールドで管理
2. DAO（data/db/dao/CredentialDao.kt）
getAll(): Flow<List> — 全件取得（serviceNameのASCソート）
getById(id: Long): Flow<CredentialEntity?> — ID指定取得
searchByServiceName(query: String): Flow<List> — サービス名でLIKE検索
findByPackageName(packageName: String): List — パッケージ名で検索（suspend）
findByUrl(url: String): List — URLでLIKE検索（suspend）
getByCategory(category: String): Flow<List> — カテゴリフィルタ
getFavorites(): Flow<List> — お気に入りのみ
insert(credential: CredentialEntity): Long — 挿入（IDを返す）
update(credential: CredentialEntity) — 更新
delete(credential: CredentialEntity) — 削除
deleteById(id: Long) — ID指定削除
getCount(): Flow — 総件数
3. データベース（data/db/SecureVaultDatabase.kt）
Room.databaseBuilder で構築
SQLCipher の SupportOpenHelperFactory を使用して暗号化
DBの暗号化パスフレーズは Phase 1 の MasterKeyManager で生成したキーから派生
Tink の HKDF を使ってマスターキーから DB 用パスフレーズを導出
バージョン: 1
exportSchema = true（マイグレーション対応用）
4. リポジトリ（data/repository/CredentialRepository.kt）
interface と実装クラスを作成:

CredentialRepository（interface）
CredentialRepositoryImpl（実装）
リポジトリ層の責務:

DAOへのアクセスをラップ
平文の Credential ドメインモデルとEntityの変換
保存時: CryptoEngine で username, password, notes を暗号化 → Entity に変換 → DAOで保存
取得時: DAOからEntity取得 → CryptoEngine で復号 → Credential ドメインモデルに変換
暗号化/復号に必要な Cipher は引数で受け取る（生体認証と連携するため）
Credential ドメインモデル（data/repository/model/Credential.kt）
data class Credential( val id: Long = 0, val serviceName: String, val serviceUrl: String? = null, val packageName: String? = null, val username: String, // 平文 val password: String, // 平文 val notes: String? = null, // 平文 val category: String = "other", val createdAt: Long, val updatedAt: Long, val isFavorite: Boolean = false )

5. DB暗号化パスフレーズ管理（data/crypto/DbKeyManager.kt）
Tink の Hkdf.computeHkdf() を使ってマスターキーから32バイトのDB用キーを導出
ソルトは固定値ではなく、初回起動時に SecureRandom で生成し DataStore に保存
導出したキーを SQLCipher のパスフレーズとして使用
6. Hilt DIモジュール（di/DatabaseModule.kt）
SecureVaultDatabase を @Singleton で提供
CredentialDao を提供
CredentialRepository（interface）に CredentialRepositoryImpl をバインド
注意事項
Cipher オブジェクトのスレッドセーフティに注意（Cipher は再利用しない）
Flow を使ってリアクティブにUIへデータを流す
SQLCipherのパスフレーズはメモリ上で char[] として扱い、使用後はクリア
KDoc コメントを全クラスに記述

---

## Phase 3 — コアUI（一覧・追加・編集・詳細・パスワード生成）

Phase 3: コアUI画面の実装
Phase 2 までのデータ層と暗号化を使い、Material 3 + Jetpack Compose で全画面を実装してください。

共通方針
Material 3 のデザイントークンに従う
ダークモード完全対応（Theme.ktのisSystemInDarkTheme利用）
日本語UIをデフォルトとし、文字列はstrings.xmlで管理
すべてのViewModelは @HiltViewModel
状態管理は StateFlow + UiState sealed interface パターン
エラー/ローディング/空状態の表示を含める
1. HomeScreen（一覧画面）— ui/screen/home/
HomeViewModel.kt
CredentialRepository から全件取得（Flow）
検索クエリ（StateFlow）でリアルタイムフィルタリング
カテゴリフィルタ（StateFlow<String?>）
お気に入りフィルタ（StateFlow）
削除処理（スワイプ削除用）
HomeScreen.kt
TopAppBar: アプリ名 + 検索アイコン + 設定アイコン
検索バー: 展開式（SearchBar composable）
カテゴリフィルタ: 横スクロールの FilterChip 行
"すべて", "ログイン", "金融", "SNS", "その他"
パスワード一覧: LazyColumn
各アイテム: サービス名、ユーザー名（マスク可能）、カテゴリアイコン、お気に入り星
タップで DetailScreen に遷移
左スワイプで削除（確認ダイアログ付き）
右スワイプでお気に入りトグル
FAB: "+" ボタンで AddEditScreen に遷移
空状態: イラスト付きの「パスワードを追加しましょう」メッセージ
件数表示: リスト上部に「N件のパスワード」
2. AddEditScreen（追加・編集画面）— ui/screen/addedit/
AddEditViewModel.kt
新規作成モード / 編集モード（credential ID の有無で判定）
フォームバリデーション（サービス名とパスワードは必須）
保存処理（CryptoEngine で暗号化 → Repository で保存）
パスワード生成画面への遷移イベント
AddEditScreen.kt
サービス名入力（TextField, 必須, バリデーション付き）
URL入力（TextField, 任意, URL形式バリデーション）
ユーザー名入力（TextField, 任意）
パスワード入力（TextField, 必須）
表示/非表示トグルボタン（目のアイコン）
「パスワードを生成」ボタン（PasswordGeneratorScreenへ遷移し結果を受け取る）
パスワード強度インジケーター（弱/中/強/非常に強い + カラーバー）
メモ入力（TextField, 任意, 複数行）
カテゴリ選択（ExposedDropdownMenu）
お気に入りトグル（Switch）
保存ボタン（FilledButton, 画面下部固定）
編集モードでは既存データをフォームに反映
パスワード強度判定ロジック（util/PasswordStrengthChecker.kt）
長さ（8未満: 弱, 12未満: 中, 16未満: 強, 16以上: 非常に強い）
文字種の多様性（小文字/大文字/数字/記号）
連続文字・繰り返しの検出
一般的な弱いパスワードのブラックリストチェック
スコア0-100 と StrengthLevel enum を返す
3. DetailScreen（詳細画面）— ui/screen/detail/
DetailViewModel.kt
ID指定で Credential を取得
パスワード表示/非表示の状態管理
クリップボードコピー機能（ユーザー名、パスワード）
コピー後30秒でクリップボード自動クリア
削除処理
DetailScreen.kt
サービス名を大きく表示
URL（タップでブラウザ起動）
ユーザー名行: 値 + コピーボタン
パスワード行: マスク表示 + 表示/非表示ボタン + コピーボタン
メモ行（ある場合のみ表示）
カテゴリ、作成日、更新日
上部に「編集」ボタン（AddEditScreenへ遷移）
下部に「削除」ボタン（確認ダイアログ付き, 赤色）
コピー時にSnackbarで「コピーしました（30秒後に自動クリア）」表示
4. PasswordGeneratorScreen（パスワード生成）— ui/screen/generator/
PasswordGeneratorViewModel.kt
パスワード長（StateFlow, デフォルト16, 範囲8-64）
文字種オプション（各StateFlow）:
小文字 (a-z) デフォルトON
大文字 (A-Z) デフォルトON
数字 (0-9) デフォルトON
記号 (!@#$%^&*...) デフォルトON
除外文字（StateFlow）— 紛らわしい文字 (0,O,l,1,I) を除外するオプション
生成ロジック: SecureRandom を使用
生成されたパスワードの強度表示
「再生成」機能
PasswordGeneratorScreen.kt
生成されたパスワードを大きなフォントで中央表示
「再生成」ボタン（リフレッシュアイコン）
パスワード長スライダー（Slider）+ 現在の値表示
文字種トグル（各Switch）
紛らわしい文字除外チェックボックス
パスワード強度インジケーター
「このパスワードを使う」ボタン → 結果を前画面に返す
「コピー」ボタン
5. SettingsScreen（設定画面）— ui/screen/settings/
SettingsViewModel.kt
DataStore から設定値の読み書き
自動ロック時間の変更
クリップボード自動クリア時間の変更
アプリデータの全削除（確認付き）
SettingsScreen.kt
「セキュリティ」セクション
自動ロック時間: 即時 / 30秒 / 1分 / 5分 / 無効
クリップボード自動クリア: 15秒 / 30秒 / 1分 / 無効
「自動入力」セクション
自動入力サービスの有効/無効ステータス表示
「自動入力設定を開く」ボタン（ACTION_REQUEST_SET_AUTOFILL_SERVICE）
SMS OTP の権限ステータス
「データ管理」セクション
エクスポート（暗号化JSONファイル出力）
インポート（暗号化JSONファイル読み込み）
すべてのデータを削除（赤色, 2段階確認ダイアログ）
「アプリ情報」セクション
バージョン
ライセンス情報
6. 共通コンポーネント（ui/component/）
PasswordStrengthBar.kt — 強度に応じたカラーバー
ConfirmDialog.kt — 汎用確認ダイアログ
EmptyState.kt — 空状態表示
CredentialListItem.kt — 一覧のアイテムUI
SectionHeader.kt — 設定画面用セクションヘッダー
全ファイルをフルで出力してください。strings.xml の日本語文字列定義も含めてください。


---

## Phase 4 — AutofillService（自動入力サービス）

Phase 4: AutofillService の実装
他のアプリやブラウザのログインフォームにパスワードを自動入力するサービスを実装してください。

1. AutofillService 本体（service/autofill/SecureVaultAutofillService.kt）
AutofillService を継承し、以下を実装:

onFillRequest()
FillRequest から AssistStructure を取得
AssistStructure をトラバースし、username/password/OTPフィールドを検出
フィールド検出は3段階フォールバック戦略: a. autofillHints の直接参照（AUTOFILL_HINT_USERNAME, AUTOFILL_HINT_PASSWORD 等） b. HTML属性の解析（type="password", autocomplete="username" 等） c. ヒューリスティック: hint, text, idEntry を日英両方のパターンでマッチング
ユーザー名系: "user", "email", "mail", "login", "account", "id", "ユーザー", "メール", "アカウント", "ログイン"
パスワード系: "pass", "pwd", "secret", "pin", "パスワード", "暗証"
OTP系: "otp", "code", "verify", "token", "sms", "認証", "確認コード", "ワンタイム" d. InputType による判定（TYPE_TEXT_VARIATION_PASSWORD等）
検出したフィールドに対して:
対象アプリのパッケージ名で CredentialRepository を検索
URLベースの検索も実施（ブラウザの場合）
マッチするパスワードが見つかった場合: → 認証付き Dataset を構築（生体認証を経てからデータを返す）
見つからない場合: → SaveInfo のみをセットして、新規保存に備える
Dataset の presentation は RemoteViews で構築
レイアウト: autofill_suggestion_item.xml（サービス名 + ユーザー名を表示）
onSaveRequest()
SaveRequest から AssistStructure を取得
フィールドをパースしてユーザー名とパスワードを抽出
パッケージ名を記録
暗号化して CredentialRepository に保存
通知でユーザーに「新しいパスワードを保存しました」と表示
onConnected() / onDisconnected()
ログ出力とリソース管理
2. フィールド解析エンジン（service/autofill/AutofillFieldParser.kt）
AutofillFieldParser
parse(structure: AssistStructure): ParsedFields
traverseNode(node: ViewNode, fields: MutableParsedFields)
上記の3段階フォールバック戦略を実装
複数の候補がある場合はスコアリングで最適なフィールドを選択
ParsedFields
data class ParsedFields( val usernameId: AutofillId?, val passwordId: AutofillId?, val otpId: AutofillId?, val webDomain: String?, // ブラウザの場合のドメイン val packageName: String, val isWebForm: Boolean )

3. 自動入力認証 Activity（service/autofill/AutofillAuthActivity.kt）
AutofillServiceからの認証リクエストを受けるActivity
BiometricPrompt を表示して生体認証を要求
認証成功時:
CryptoEngine で対象のパスワードを復号
FillResponse に Dataset を含めて EXTRA_AUTHENTICATION_RESULT として返す
認証失敗時:
RESULT_CANCELED を返す
テーマは透明背景（BiometricPromptのみ表示）
4. 自動入力用レイアウト
res/layout/autofill_suggestion_item.xml
RemoteViews用の簡素なレイアウト
サービス名（TextView）+ ユーザー名（TextView, 小さめフォント）
res/layout/autofill_otp_item.xml
「SMSからコードを自動入力」テキスト + アイコン
5. URL/パッケージ名マッチング（service/autofill/CredentialMatcher.kt）
マッチング戦略
パッケージ名完全一致（ネイティブアプリ）
webDomain からベースドメイン抽出 → serviceUrl と比較
サービス名の部分一致（フォールバック）
マッチスコアを計算し、スコア順にソート
既知のパッケージ名マッピング（オプション）
よく使われるアプリのパッケージ名とドメインの対応表:

"com.google.android.gm" → "google.com"
"com.amazon.mShop.android.shopping" → "amazon.co.jp"
"jp.naver.line.android" → "line.me" 等（拡張可能な Map として定義）
6. 新規パスワード保存通知
NotificationChannel の作成
保存完了時に通知を表示
通知タップでアプリの詳細画面を開く
AndroidManifest.xml への追記事項
AutofillAuthActivity の宣言（theme: @android:style/Theme.Translucent.NoTitleBar）
AutofillService は Phase 0 で宣言済みだが、内容を確認・補完
全ファイルをフルで出力してください。


---

## Phase 5 — OTP 自動入力（SMS・通知・クリップボード）

Phase 5: OTP自動取得と自動入力の実装
SMS、通知、クリップボードからワンタイムコードを自動検出し入力する機能を実装してください。

1. SMS OTP 取得（service/otp/SmsOtpManager.kt）
Google Play Services の SmsCodeAutofillClient を使用
startSmsCodeRetrieval(): SMS OTP の監視開始
hasOngoingSmsRequest(packageName): 既存リクエストの確認
checkPermissionState(): 権限状態の確認
BroadcastReceiver でSMSコード受信をリッスン
SmsOtpBroadcastReceiver（service/otp/SmsOtpBroadcastReceiver.kt）
SmsRetriever.SMS_CODE_RETRIEVED_ACTION を受信
Status.SUCCESS の場合、SMS本文からOTPを正規表現で抽出
抽出したOTPをコールバックまたはBroadcastで通知
OTP抽出の正規表現パターン:
日本語: "認証コード[は:：\s]*(\d{4,8})"
日本語: "確認コード[は:：\s]*(\d{4,8})"
日本語: "ワンタイムパスワード[は:：\s]*(\d{4,8})"
英語: "(?:code|otp|token|verification)[^\d]*(\d{4,8})"
汎用: 文脈に関係なく独立した4-8桁の数字列
2. 通知からのOTP検出（service/otp/OtpNotificationListener.kt）
NotificationListenerService を継承
onNotificationPosted(sbn: StatusBarNotification)
sbn.notification.extras から EXTRA_TEXT, EXTRA_BIG_TEXT を取得
OTP パターンマッチングを実行
Android 15+では OTP含む通知がredactされる可能性があるため:
読み取れた場合のみ処理
読み取れなかった場合はログに記録し、クリップボード方式にフォールバック
対象アプリの通知のみ処理するためのフィルタリング:
メールアプリ（Gmail, Outlook, Yahoo等）の通知パッケージ名リスト
SMSアプリの通知パッケージ名リスト
OTP検出時の処理:
AutofillServiceへの伝達:
LocalBroadcast で OTPコードを送信
AutofillService側で受信し、待機中のOTPリクエストがあれば自動入力
通知での表示:
「認証コード XXXX を検出しました。タップでコピー」通知を表示
通知のアクションボタン: 「コピー」「自動入力」
3. クリップボード OTP 検出（service/otp/ClipboardOtpDetector.kt）
ClipboardManager.OnPrimaryClipChangedListener を使用
クリップボードの変更を監視
OTPパターンにマッチした場合:
AutofillServiceに通知
ユーザーへの通知表示（「コードを検出しました」）
セキュリティ対策:
OTPコピー後、設定に応じた秒数後にクリップボードをクリア
Build.VERSION.SDK_INT >= P で clearPrimaryClip() を使用
4. OTP統合マネージャー（service/otp/OtpManager.kt）
3つのOTP検出手段を統合管理:

優先順位: SMS (SmsCodeAutofillClient) > 通知 > クリップボード
OTP検出イベントを統一的な Flow として公開
AutofillService との連携インターフェース
重複検出の排除（同じOTPを複数経路で検出した場合）
OtpDetectionEvent
data class OtpDetectionEvent( val code: String, val source: OtpSource, // SMS, NOTIFICATION, CLIPBOARD val packageName: String?, val timestamp: Long )

5. AutofillService へのOTP統合
Phase 4 の SecureVaultAutofillService を拡張:

OTPフィールドを検出した場合:
SmsOtpManager を起動してSMS監視を開始
「SMSから認証コードを自動入力」というDatasetを表示
OTP取得成功時に自動的にフィールドへ入力
OTP取得用のActivity（service/otp/SmsOtpActivity.kt）:
BiometricPromptは不要（OTPは一時的な値のため）
SmsCodeAutofillClient の startSmsCodeRetriever() を呼び出し
権限要求が必要な場合は ResolvableApiException を処理
OTP受信後、FillResponse に含めて返却
6. 設定画面への統合
SettingsScreen に以下を追加:

「OTP自動入力」セクション
SMS OTP自動入力: ON/OFF
通知からのOTP検出: ON/OFF（NotificationListenerServiceの権限状態を表示）
「通知アクセス設定を開く」ボタン
クリップボードOTP検出: ON/OFF
注意事項
Android 15+ の OTP redaction を意識し、NotificationListenerは「ベストエフォート」として実装
SmsCodeAutofillClient は Google Play Services 依存。非搭載端末ではグレースフルに無効化
OTPコードは永続化しない（メモリ上のみ、使用後即破棄）
すべてのOTP検出ロジックにタイムアウト（5分）を設ける
全ファイルをフルで出力してください。


---

## Phase 6 — エクスポート/インポートと最終仕上げ

Phase 6: エクスポート/インポート、テスト、最終仕上げ
アプリの完成度を上げる残りの機能を実装してください。

1. エクスポート機能（data/backup/ExportManager.kt）
暗号化エクスポート
すべての Credential を JSON にシリアライズ
ユーザーが指定したパスワードから PBKDF2 で鍵を導出
イテレーション: 600,000回
ソルト: SecureRandom で32バイト生成
鍵長: 256ビット
AES-256-GCM で JSON を暗号化
出力フォーマット:
Copy{
  "version": 1,
  "app": "SecureVault",
  "exportedAt": "2026-02-25T12:00:00Z",
  "salt": "<Base64>",
  "iv": "<Base64>",
  "data": "<Base64 encrypted JSON>"
}
SAF（Storage Access Framework）を使ってファイル保存先をユーザーに選択させる
ファイル名: "securevault_backup_YYYYMMDD_HHmmss.svault"
平文CSVエクスポート（他アプリ移行用）
警告ダイアログ: 「パスワードが暗号化されずに保存されます」
CSV形式: name,url,username,password,notes
出力後に注意喚起: 「CSVファイルは使用後すぐに削除してください」
2. インポート機能（data/backup/ImportManager.kt）
SecureVault形式（.svault）
パスワード入力を求める
PBKDF2で鍵を導出し、AES-GCMで復号
JSONをパースして Credential に変換
重複チェック: serviceName + username の組み合わせで判定
重複時の選択: 上書き / スキップ / 両方保持
CSV インポート
RFC4180準拠のCSVパーサー（外部ライブラリ不要、自前実装）
ヘッダー行の自動検出
カラムマッピング: name/url/username/password/notes を柔軟にマッチング
1Password, Bitwarden, Chrome, LastPass のCSV形式に対応
インポートプレビュー画面: 件数と最初の数件を表示
3. エクスポート/インポートUI
ExportScreen（ui/screen/backup/ExportScreen.kt）
エクスポート形式選択: 暗号化(.svault) / CSV
暗号化の場合: パスワード入力（2回）+ 強度チェック
CSVの場合: 警告表示
エクスポート進捗表示
完了時に共有/保存先の表示
ImportScreen（ui/screen/backup/ImportScreen.kt）
ファイル選択（SAF）
ファイル形式の自動判定
パスワード入力（.svault の場合）
プレビュー表示
重複処理の選択
インポート進捗表示
結果表示: 「N件インポート、M件スキップ」
4. アプリ内検索の強化（ui/screen/home/SearchHelper.kt）
ローマ字入力でのひらがなマッチング（簡易）
サービス名、ユーザー名、URLを横断検索
検索結果のハイライト表示
5. ProGuard / R8 ルール（proguard-rules.pro）
Room, SQLCipher, Tink, Hilt のkeepルール
暗号化関連クラスの難読化除外
6. アプリアイコンとスプラッシュ
アプリアイコンの定義
res/mipmap-*/ 用の Adaptive Icon 設定
ic_launcher.xml: 盾（シールド）+ 鍵のシンプルなアイコン
foreground: ベクターの盾と鍵
background: アプリのプライマリカラー
アイコン自体の画像ファイルは不要。XMLベクターで定義可能なものを ic_launcher_foreground.xml として作成
スプラッシュスクリーン
Core Splashscreen API を使用
アイコンのアニメーション（不要なら静止画でOK）
7. Navigation の最終調整
ディープリンク対応（通知からの遷移用）
バックスタックの正しい管理
認証状態に応じた画面遷移ガード
8. テスト
ユニットテスト
CryptoEngineTest.kt: 暗号化→復号の往復テスト
PasswordStrengthCheckerTest.kt: 各強度レベルの判定テスト
AutofillFieldParserTest.kt: フィールド検出のテスト（モック）
CredentialMatcherTest.kt: URL/パッケージ名マッチングのテスト
CsvParserTest.kt: 各形式のCSVパースのテスト
Instrumented テスト
CredentialDaoTest.kt: Room DAOの各クエリテスト
ExportImportTest.kt: エクスポート→インポートの往復テスト
最終チェックリスト
 INTERNET権限がないことを確認
 allowBackup="false" を確認
 すべての画面でダークモードを確認
 横画面対応（最低限崩れない）
 メモリリークがないこと（Lifecycle対応）
 クリップボードの自動クリアが動作すること
 自動ロックが動作すること
 strings.xml の全文字列が日本語であること
全ファイルをフルで出力してください。


---

## 使い方のガイド

各フェーズの進め方は以下のとおりです。

**Phase 0** でプロジェクトが生成されたら、まず Android Studio でビルドが通ることを確認してください。依存関係のバージョン競合が出た場合は、エラーメッセージをそのまま Copilot に貼り付けて修正を依頼します。

**Phase 1** の完了後は、実機またはエミュレータで AuthScreen が表示され、生体認証が動作することを確認してください。`adb shell` で `keytool` コマンドを使い、Keystore にキーが生成されていることも検証できます。

**Phase 2** は DB が正しく暗号化されているかを確認するため、デバイスの `/data/data/com.securevault.app/databases/` にあるDBファイルを直接開こうとして読めないことをテストしてください。

**Phase 3** が最もコード量が多くなります。画面ごとに分割して Copilot に投入しても構いません。その場合、プロンプトの先頭に「Phase 3 の以下の部分のみを実装してください」と前置きしてください。

**Phase 4** はテストが難しい部分です。Android 公式の `AutofillFramework` サンプルアプリ（GitHub上に公開されている）を参考にデバッグしてください。

**Phase 5** は SMS OTP のテストに実機が必要です。エミュレータでは `SmsCodeAutofillClient` が動作しない場合があります。