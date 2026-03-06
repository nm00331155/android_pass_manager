# Phase 5 – 日本語化 + OTP 自動入力

## 前提
- `docs/specification.md` と `docs/status.md` を必ず読んでから作業すること
- 既存ファイルの API（CredentialRepository, CryptoEngine, BiometricAuthManager 等）は変更しない
- パッケージ: `com.securevault.app`
- ビルド確認: `./gradlew --no-daemon :app:assembleDebug` が成功すること

---

## Part A: 全面日本語化

### A-1. `res/values/strings.xml` を全面書き換え

以下の内容で **完全に置き換え** てください（英語は一切残さない）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">SecureVault</string>
    <string name="autofill_service_name">SecureVault 自動入力</string>

    <!-- 認証画面 -->
    <string name="auth_title">認証が必要です</string>
    <string name="auth_subtitle">生体認証または端末認証でロックを解除してください</string>
    <string name="auth_button">認証を開始</string>
    <string name="auth_button_progress">認証中…</string>
    <string name="auth_error">認証に失敗しました。もう一度お試しください。</string>
    <string name="auth_fallback">生体認証が利用できません。端末認証に切り替えます。</string>
    <string name="auth_unavailable">この端末では認証機能が利用できません。</string>
    <string name="auth_activity_not_found">認証画面を初期化できませんでした。</string>
    <string name="auth_retry">再試行</string>

    <!-- 自動入力 -->
    <string name="autofill_auth_title">SecureVault のロックを解除</string>
    <string name="autofill_auth_subtitle">保存済みの認証情報を入力するには認証が必要です</string>
    <string name="autofill_save_channel_name">自動入力の保存</string>
    <string name="autofill_save_channel_description">自動入力で認証情報を保存したときに表示される通知です。</string>
    <string name="autofill_save_notification_title">認証情報を保存しました</string>
    <string name="autofill_save_notification_message">%1$s を SecureVault に保存しました</string>

    <!-- ホーム画面 -->
    <string name="home_title">パスワード一覧</string>
    <string name="add_edit_title_add">パスワードを追加</string>
    <string name="add_edit_title_edit">パスワードを編集</string>
    <string name="detail_title">詳細</string>
    <string name="settings_title">設定</string>
    <string name="generator_title">パスワード生成</string>

    <!-- ナビゲーション -->
    <string name="open_settings">設定</string>
    <string name="go_add">追加画面へ</string>
    <string name="go_generator">生成画面へ</string>
    <string name="go_detail">詳細画面へ</string>
    <string name="back_home">ホームへ戻る</string>

    <!-- 検索・フィルタ -->
    <string name="search_action">検索</string>
    <string name="search_placeholder">サービス名やユーザー名で検索</string>
    <string name="password_count">%1$d 件のパスワード</string>
    <string name="home_empty_message">最初のパスワードを追加しましょう</string>
    <string name="home_filter_empty_message">条件に一致するパスワードはありません</string>
    <string name="filter_reset">フィルタをリセット</string>
    <string name="home_swipe_hint">右スワイプ: お気に入り / 左スワイプ: 削除</string>

    <!-- カテゴリ -->
    <string name="category_all">すべて</string>
    <string name="category_login">ログイン</string>
    <string name="category_finance">金融</string>
    <string name="category_social">SNS</string>
    <string name="category_other">その他</string>
    <string name="favorite_only_label">★ お気に入り</string>

    <!-- 入力フィールド -->
    <string name="field_service_name_required">サービス名（必須）</string>
    <string name="field_url">URL</string>
    <string name="field_username">ユーザー名</string>
    <string name="field_password_required">パスワード（必須）</string>
    <string name="field_notes">メモ</string>
    <string name="field_category">カテゴリ</string>

    <!-- 保存・操作 -->
    <string name="save_action">保存</string>
    <string name="save_in_progress">保存中…</string>
    <string name="generate_password_action">パスワードを生成</string>
    <string name="show_password">表示</string>
    <string name="hide_password">非表示</string>
    <string name="back_label">戻る</string>

    <!-- 詳細画面 -->
    <string name="edit_action">編集</string>
    <string name="copy_action">コピー</string>
    <string name="delete_action">削除</string>
    <string name="open_in_browser">ブラウザで開く</string>
    <string name="created_at">作成日時</string>
    <string name="updated_at">更新日時</string>

    <!-- パスワード生成 -->
    <string name="regenerate_action">再生成</string>
    <string name="password_length_label">文字数: %1$d</string>
    <string name="option_lowercase">小文字 (a-z)</string>
    <string name="option_uppercase">大文字 (A-Z)</string>
    <string name="option_digits">数字 (0-9)</string>
    <string name="option_symbols">記号 (!@#$…)</string>
    <string name="exclude_ambiguous">紛らわしい文字を除外</string>
    <string name="use_generated_password">このパスワードを使う</string>

    <!-- 設定画面 -->
    <string name="settings_security">セキュリティ</string>
    <string name="settings_auto_lock">自動ロック</string>
    <string name="settings_clipboard_clear">クリップボード自動消去</string>
    <string name="settings_data">データ管理</string>
    <string name="settings_delete_all">すべてのデータを削除</string>
    <string name="settings_app_info">アプリ情報</string>
    <string name="settings_version">バージョン: %1$s</string>
    <string name="open_autofill_settings">自動入力の設定を開く</string>
    <string name="settings_otp">OTP（ワンタイムパスワード）</string>
    <string name="settings_sms_otp">SMS ワンタイム自動入力</string>
    <string name="settings_notification_otp">通知からの OTP 読取</string>
    <string name="settings_clipboard_otp">クリップボードの OTP 検出</string>

    <!-- タイムアウト -->
    <string name="timeout_immediate">即時</string>
    <string name="timeout_15_seconds">15秒</string>
    <string name="timeout_30_seconds">30秒</string>
    <string name="timeout_1_minute">1分</string>
    <string name="timeout_5_minutes">5分</string>
    <string name="timeout_disabled">無効</string>

    <!-- コピー通知 -->
    <string name="password_copied">パスワード</string>
    <string name="username_copied">ユーザー名</string>
    <string name="clipboard_auto_clear">%1$d 秒後にクリップボードを消去します</string>

    <!-- 確認ダイアログ -->
    <string name="confirm_delete_title">削除の確認</string>
    <string name="confirm_delete_message">このパスワードを削除しますか？この操作は取り消せません。</string>
    <string name="confirm_delete_all_title">全データの削除</string>
    <string name="confirm_delete_all_message">すべてのパスワードを削除しますか？この操作は取り消せません。</string>
    <string name="confirm_delete_all_final">本当に削除してよろしいですか？元に戻すことはできません。</string>
    <string name="confirm_yes">はい</string>
    <string name="confirm_no">いいえ</string>

    <!-- パスワード強度 -->
    <string name="strength_very_weak">非常に弱い</string>
    <string name="strength_weak">弱い</string>
    <string name="strength_medium">普通</string>
    <string name="strength_strong">強い</string>
    <string name="strength_very_strong">非常に強い</string>

    <!-- OTP -->
    <string name="otp_sms_suggestion">SMS から認証コードを自動入力</string>
    <string name="otp_detected">認証コードを検出しました: %1$s</string>
    <string name="otp_clipboard_detected">クリップボードから認証コードを検出しました</string>
</resources>
```

### A-2. コード内のハードコードされた日本語/英語文字列を確認

以下のファイルにハードコードされた文字列があれば `strings.xml` 参照に置き換える：
- `BiometricAuthManager.kt` 内の `mapBiometricError()` → 既に日本語なのでそのまま維持
- `SmartFieldDetector.kt` 内の日本語キーワード（検出用）→ ロジック用なのでそのまま維持
- `SecureVaultAutofillService.kt` 内の通知テキスト → `getString(R.string.xxx)` に置き換え
- `AutofillAuthActivity.kt` 内の BiometricPrompt タイトル → `getString(R.string.autofill_auth_title)` に置き換え

---

## Part B: OTP 自動入力

### B-1. `service/otp/SmsOtpManager.kt` を新規作成

```
パッケージ: com.securevault.app.service.otp
```

Google Play Services の `SmsCodeAutofillClient` を使用して SMS OTP を自動取得する。

```kotlin
@Singleton
class SmsOtpManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client: SmsCodeAutofillClient = SmsCodeBrowserClient.getClient(context)

    private val _otpResult = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val otpResult: SharedFlow<String> = _otpResult.asSharedFlow()

    /** SMS OTP 監視を開始する */
    suspend fun startListening(): SmsOtpStatus { ... }

    /** 権限状態を確認する */
    suspend fun checkPermission(): SmsCodeAutofillClient.PermissionState { ... }

    /** 受信 SMS からOTPコードを抽出する正規表現 */
    private fun extractOtp(message: String): String? {
        val pattern = Regex("\\b(\\d{4,8})\\b")
        return pattern.find(message)?.groupValues?.get(1)
    }
}

enum class SmsOtpStatus { LISTENING, ALREADY_IN_PROGRESS, PERMISSION_DENIED, UNAVAILABLE }
```

実装の注意:
- `SmsCodeAutofillClient` は `com.google.android.gms.auth.api.phone` パッケージ
- `hasOngoingSmsRequest` → `checkPermissionState` → `startSmsCodeRetriever` の順で呼ぶ
- Google Play Services がない端末では `UNAVAILABLE` を返す
- `startSmsCodeRetriever` の結果は `Task<Void>` で、SMS 受信は別途 `BroadcastReceiver` で取得

### B-2. `service/otp/SmsOtpReceiver.kt` を新規作成

```kotlin
class SmsOtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // SmsRetriever.SMS_RETRIEVED_ACTION を処理
        // Status.SUCCESS の場合、メッセージからOTPを正規表現で抽出
        // 抽出した OTP を LocalBroadcastManager または SharedFlow で SmsOtpManager に通知
    }
}
```

AndroidManifest.xml に追加：
```xml
<receiver
    android:name=".service.otp.SmsOtpReceiver"
    android:exported="true"
    android:permission="com.google.android.gms.auth.api.phone.permission.SEND">
    <intent-filter>
        <action android:name="com.google.android.gms.auth.api.phone.SMS_RETRIEVED" />
    </intent-filter>
</receiver>
```

### B-3. `service/otp/OtpNotificationListener.kt` を改修

現在スタブの場合、以下を実装：

```kotlin
@AndroidEntryPoint
class OtpNotificationListener : NotificationListenerService() {
    
    @Inject lateinit var otpManager: OtpManager

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Android 15+ では sensitive 通知が隠蔽される可能性がある
        val extras = sbn.notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return

        val otp = extractOtp(text) ?: return
        otpManager.onOtpDetected(otp, OtpSource.NOTIFICATION)
    }

    private fun extractOtp(text: String): String? {
        // 4〜8桁の数字を検出
        val pattern = Regex("(?:認証コード|確認コード|ワンタイム|OTP|code|verify)[\\s:：]*?(\\d{4,8})")
        val contextMatch = pattern.find(text)
        if (contextMatch != null) return contextMatch.groupValues[1]

        // フォールバック: 単独の4〜8桁
        val fallback = Regex("\\b(\\d{4,8})\\b")
        return fallback.find(text)?.groupValues?.get(1)
    }
}
```

### B-4. `service/otp/ClipboardOtpDetector.kt` を新規作成

```kotlin
@Singleton
class ClipboardOtpDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val _detectedOtp = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val detectedOtp: SharedFlow<String> = _detectedOtp.asSharedFlow()

    /** クリップボード変更リスナーを開始する */
    fun startMonitoring() {
        clipboardManager.addPrimaryClipChangedListener {
            val clip = clipboardManager.primaryClip ?: return@addPrimaryClipChangedListener
            val text = clip.getItemAt(0)?.text?.toString() ?: return@addPrimaryClipChangedListener
            val otp = extractOtp(text)
            if (otp != null) {
                _detectedOtp.tryEmit(otp)
            }
        }
    }

    fun stopMonitoring() { /* リスナー解除 */ }

    private fun extractOtp(text: String): String? {
        if (text.length > 20) return null  // 長すぎるテキストは無視
        val pattern = Regex("^\\d{4,8}$")
        return if (pattern.matches(text.trim())) text.trim() else null
    }
}
```

### B-5. `service/otp/OtpManager.kt` を新規作成

統合マネージャー。各ソースからの OTP を統合して公開する。

```kotlin
@Singleton
class OtpManager @Inject constructor(
    private val smsOtpManager: SmsOtpManager,
    private val clipboardOtpDetector: ClipboardOtpDetector
) {
    private val _otpEvents = MutableSharedFlow<OtpEvent>(replay = 0, extraBufferCapacity = 3)
    val otpEvents: SharedFlow<OtpEvent> = _otpEvents.asSharedFlow()

    /** 外部（NotificationListener 等）から OTP を通知する */
    fun onOtpDetected(code: String, source: OtpSource) {
        _otpEvents.tryEmit(OtpEvent(code = code, source = source, timestamp = System.currentTimeMillis()))
    }

    /** SMS OTP 監視を開始する */
    suspend fun startSmsListening() {
        smsOtpManager.startListening()
        // smsOtpManager.otpResult を collect して otpEvents に転送
    }

    /** クリップボード監視を開始する */
    fun startClipboardMonitoring() {
        clipboardOtpDetector.startMonitoring()
        // clipboardOtpDetector.detectedOtp を collect して otpEvents に転送
    }
}

data class OtpEvent(
    val code: String,
    val source: OtpSource,
    val timestamp: Long
)

enum class OtpSource { SMS, NOTIFICATION, CLIPBOARD }
```

### B-6. `di/OtpModule.kt` を新規作成

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object OtpModule {
    // SmsOtpManager, ClipboardOtpDetector, OtpManager は @Inject constructor で自動提供されるため
    // 追加バインディングが必要な場合のみここに記述
}
```

### B-7. `SecureVaultAutofillService.kt` に OTP 自動入力を統合

`onFillRequest` 内で `SmartFieldDetector` が `otpId` を検出した場合：
1. `SmsOtpManager` を経由して SMS OTP リトリーバーを起動
2. OTP 用の Dataset を生成し、`autofill_otp_item.xml` の RemoteViews を使用
3. Dataset の値は「SMS から自動入力」の表示のみ（実際の値は SMS 受信後に確定）

### B-8. `SettingsScreen` / `SettingsViewModel` に OTP 設定を追加

設定画面に「OTP（ワンタイムパスワード）」セクションを追加：
- SMS ワンタイム自動入力（スイッチ）
- 通知からの OTP 読取（スイッチ）→ NotificationListenerService の権限確認 + 設定画面への誘導
- クリップボードの OTP 検出（スイッチ）

設定値は DataStore に保存する。キー名：
- `otp_sms_enabled` (Boolean, デフォルト true)
- `otp_notification_enabled` (Boolean, デフォルト false)
- `otp_clipboard_enabled` (Boolean, デフォルト true)

### B-9. `SmsOtpActivity.kt` を改修

現在スタブの場合、SMS OTP 認可フローのUI を実装：
- `SmsCodeAutofillClient` の結果を表示
- ユーザー同意が必要な場合のダイアログ表示
- 結果を `SecureVaultAutofillService` に返す

---

## 変更対象ファイル一覧

| ファイル | 操作 |
|---|---|
| `res/values/strings.xml` | 全面書き換え（日本語化） |
| `service/otp/SmsOtpManager.kt` | 新規作成 |
| `service/otp/SmsOtpReceiver.kt` | 新規作成 |
| `service/otp/OtpNotificationListener.kt` | 改修（本実装） |
| `service/otp/ClipboardOtpDetector.kt` | 新規作成 |
| `service/otp/OtpManager.kt` | 新規作成 |
| `service/otp/SmsOtpActivity.kt` | 改修（本実装） |
| `di/OtpModule.kt` | 新規作成 |
| `service/autofill/SecureVaultAutofillService.kt` | 改修（OTP Dataset 追加） |
| `ui/screen/settings/SettingsScreen.kt` | 改修（OTP セクション追加） |
| `ui/screen/settings/SettingsViewModel.kt` | 改修（OTP 設定追加） |
| `AndroidManifest.xml` | SmsOtpReceiver 追加 |
| `SecureVaultAutofillService.kt` 内の通知テキスト | `getString()` に置き換え |
| `AutofillAuthActivity.kt` 内のタイトル | `getString()` に置き換え |

## 注意事項
- `CredentialRepository` の API は変更しない
- `BiometricAuthManager` の API は変更しない（onSuccess は `() -> Unit`）
- `CryptoEngine` の API は変更しない
- @Query アノテーション内の SQL は改行せず1行で書く
- すべての public クラス/メソッドに KDoc を付与する
- import はワイルドカード不可
- Google Play Services がない端末でも SMS OTP 以外の機能は正常動作すること
- OtpNotificationListener は Android 15+ で通知内容が隠蔽される可能性がある旨をKDocに明記