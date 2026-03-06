# Phase 4 – AutofillService 本実装

## 前提
- `docs/specification.md` と `docs/status.md` を必ず読んでから作業すること
- 既存ファイルのうち変更不要なものは変更しない
- パッケージ: `com.securevault.app`
- ビルド確認: `./gradlew --no-daemon :app:assembleDebug` が成功すること

## 既存ファイルの現状（確認済み）
- `SecureVaultAutofillService.kt` – onFillRequest に基本的な Dataset 生成あり。onSaveRequest は空。
- `AutofillAuthActivity.kt` – スタブ（即 RESULT_CANCELED で finish）。
- `autofill_suggestion_item.xml` – サービス名 + ユーザー名の2行レイアウトあり。
- `autofill_otp_item.xml` – OTP 用の1行レイアウトあり。
- `autofill_service_config.xml` – Chrome/Firefox/Brave/Edge の互換パッケージ設定あり。
- `BiometricAuthManager.kt` – authenticate() メソッドが FragmentActivity を要求する。
- `CredentialRepository.kt` – save(), findByPackageName(), findByUrl(), getAll() 等が利用可能。
- `CryptoEngine.kt` – getCipherForEncryption(), getCipherForDecryption() が利用可能。

## 実装タスク

### 4-1. `SecureVaultAutofillService.kt` を改修

#### onFillRequest の改善
現在の実装を以下の点で拡張する：

1. **自パッケージ除外**: `packageName == "com.securevault.app"` の場合は `callback.onSuccess(null)` で即リターン
2. **InputType によるパスワードフィールド検出強化**: `findAutofillTargets()` 内で、既存のヒント/ID/hintテキスト判定に加え、以下を追加：
   - `node.inputType` に `TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD` が含まれる場合は passwordId 候補とする
   - `node.inputType` に `TYPE_TEXT_VARIATION_EMAIL_ADDRESS`, `TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS` が含まれる場合は usernameId 候補とする
3. **OTP フィールド検出**: `AutofillTargets` に `otpId: AutofillId?` を追加。以下で判定：
   - autofillHints に "smsOTPCode", "otp", "verification", "code" を含む
   - idEntry/hint に "otp", "code", "token", "verify", "認証", "確認コード" を含む
   - inputType が `TYPE_CLASS_NUMBER` かつフィールドの `maxLength` が 4〜8 の範囲
4. **Dataset に認証ゲートを追加**: 各 Dataset に `setAuthentication(PendingIntent)` を設定し、ユーザーが選択したときに `AutofillAuthActivity` が起動して生体認証を要求してから値が入力されるようにする
   - `PendingIntent` は `AutofillAuthActivity` を起動し、Extra に `credentialId`, `usernameAutofillId`, `passwordAutofillId` を渡す
   - PendingIntent の requestCode は credential.id.toInt() を使用して一意にする
5. **RemoteViews を `autofill_suggestion_item.xml` に切り替え**: 現在の `android.R.layout.simple_list_item_1` を `R.layout.autofill_suggestion_item` に変更し、`R.id.service_name` と `R.id.username` にテキストを設定
6. **SaveInfo の追加**: `FillResponse.Builder` に `SaveInfo` を設定する
   - `SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(検出された usernameId, passwordId))` で構築
   - `.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)` を設定

#### onSaveRequest の実装
1. `AssistStructure` を解析して username と password の値（`AutofillValue.textValue`）を取得
2. パッケージ名と webDomain も取得
3. `CredentialRepository.save()` で新規保存する（`Credential` を構築。serviceName はパッケージ名またはドメインから推定）
4. 保存完了後に通知を表示する（`NotificationManager` を使用、チャネル ID: `autofill_save`）
5. `callback.onSuccess()` を呼ぶ
6. エラー時は `callback.onFailure("保存に失敗しました")` を呼ぶ

以下のヘルパーメソッドを追加：
```kotlin
private fun extractSavedValues(structure: AssistStructure): SavedCredentialData?
private fun createNotificationChannel()
private fun showSaveNotification(serviceName: String)
```

`SavedCredentialData` は内部データクラス：
```kotlin
private data class SavedCredentialData(
    val username: String?,
    val password: String?,
    val packageName: String,
    val webDomain: String?
)
```

### 4-2. `AutofillAuthActivity.kt` を本実装

この Activity は FragmentActivity を継承する必要がある（BiometricPrompt が FragmentActivity を要求するため）。

```
AutofillAuthActivity : FragmentActivity
```

処理フロー：
1. `onCreate` で Intent の Extra から `credentialId: Long`, `usernameAutofillId: AutofillId?`, `passwordAutofillId: AutofillId?` を取得
2. `BiometricAuthManager.authenticate()` を呼び出して生体認証を実行
3. 認証成功時:
   - `CredentialRepository.getById(credentialId)` で認証情報を取得
   - `Dataset.Builder` で usernameId/passwordId に値をセット
   - `Intent` に `EXTRA_AUTHENTICATION_RESULT` として Dataset をセット
   - `setResult(RESULT_OK, intent)` → `finish()`
4. 認証失敗/キャンセル時:
   - `setResult(RESULT_CANCELED)` → `finish()`

Hilt で `@AndroidEntryPoint` を付与し、`BiometricAuthManager` と `CredentialRepository` を `@Inject` する。

AndroidManifest.xml で `AutofillAuthActivity` のテーマを変更：
```xml
<activity
    android:name=".service.autofill.AutofillAuthActivity"
    android:exported="false"
    android:theme="@style/Theme.SecureVault.Transparent" />
```

`themes.xml` に追加：
```xml
<style name="Theme.SecureVault.Transparent" parent="Theme.MaterialComponents.DayNight.NoActionBar">
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowNoTitle">true</item>
</style>
```

### 4-3. `SmartFieldDetector.kt` を新規作成

パッケージ: `com.securevault.app.service.autofill`

`SecureVaultAutofillService` の `findAutofillTargets()` のロジックを独立クラスに抽出する。

```kotlin
@Singleton
class SmartFieldDetector @Inject constructor() {

    fun detect(structure: AssistStructure): DetectedFields { ... }

    data class DetectedFields(
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val otpId: AutofillId?,
        val webDomain: String?,
        val allAutofillIds: List<AutofillId>  // SaveInfo 用
    )
}
```

3段階フォールバック:
1. **公式 autofillHints** (AUTOFILL_HINT_USERNAME, AUTOFILL_HINT_PASSWORD, AUTOFILL_HINT_EMAIL_ADDRESS 等)
2. **HTML 属性 + idEntry + hint テキスト** (英語 + 日本語キーワード: "ユーザー", "メール", "パスワード", "暗証番号")
3. **InputType ベース** (TYPE_TEXT_VARIATION_PASSWORD 等)

### 4-4. `autofill_service_config.xml` の更新

maxLongVersionCode を追加し、追加ブラウザ（Samsung Internet, Opera）を含める：

```xml
<?xml version="1.0" encoding="utf-8"?>
<autofill-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="com.securevault.app.ui.MainActivity">
    <compatibility-package android:name="com.android.chrome" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="com.chrome.beta" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="com.chrome.dev" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="org.mozilla.firefox" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="org.mozilla.firefox_beta" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="com.brave.browser" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="com.microsoft.emmx" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="com.sec.android.app.sbrowser" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="com.opera.browser" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="com.opera.mini.native" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="com.vivaldi.browser" android:maxLongVersionCode="999999999"/>
    <compatibility-package android:name="com.duckduckgo.mobile.android" android:maxLongVersionCode="999999999"/>
</autofill-service>
```

### 4-5. AndroidManifest.xml の更新

AutofillAuthActivity の変更のみ：
```xml
<activity
    android:name=".service.autofill.AutofillAuthActivity"
    android:exported="false"
    android:theme="@style/Theme.SecureVault.Transparent" />
```

POST_NOTIFICATIONS 権限を追加（Android 13+ の通知用）：
```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 4-6. テスト・デバッグ用メモ

ビルド成功後の動作確認手順：
1. `./gradlew --no-daemon :app:assembleDebug`
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. 設定 → システム → 自動入力サービス → SecureVault を選択
4. アプリ内でテスト用のパスワードを1件登録（例: サービス名「テスト」、URL「example.com」、ユーザー名「user@test.com」、パスワード「Test1234!」）
5. Chrome で example.com にアクセスし、ログインフォームで SecureVault の候補が表示されるか確認
6. 候補タップ → 生体認証ダイアログ → 認証成功 → フィールドに値が入力されるか確認
7. 新しいサイトでログインフォームに入力 → 「SecureVault に保存しますか？」ダイアログ表示を確認

## 変更対象ファイル一覧
| ファイル | 操作 |
|---|---|
| `service/autofill/SecureVaultAutofillService.kt` | 改修 |
| `service/autofill/AutofillAuthActivity.kt` | 全面書き換え |
| `service/autofill/SmartFieldDetector.kt` | 新規作成 |
| `res/xml/autofill_service_config.xml` | 更新 |
| `res/values/themes.xml` | Transparent テーマ追加 |
| `AndroidManifest.xml` | AutofillAuthActivity テーマ変更、POST_NOTIFICATIONS 追加 |

## 注意事項
- `CredentialRepository` の API は変更しない
- `BiometricAuthManager` の API は変更しない
- `CryptoEngine` の API は変更しない
- `AutofillAuthActivity` で `runBlocking` は使わない。コルーチンは `lifecycleScope.launch` を使う
- 通知チャネルの作成は `onConnected()` または `onCreate()` で行う（Android 8+ 必須）
- `PendingIntent` には `PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT` を設定する
- @Query アノテーション内の SQL は改行せず1行で書く
- すべての public クラス/メソッドに KDoc を付与する
- import は明示的に記述する（ワイルドカード不可）