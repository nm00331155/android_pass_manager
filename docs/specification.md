# SecureVault 仕様書

最終更新: 2026-03-08 13:47:22 +09:00

## 1. アプリ概要
- アプリ名: SecureVault（Android パスワードマネージャー）
- 目的: オフラインで安全に認証情報を管理し、Android Autofill で他アプリ/ブラウザへ入力支援する
- 方針: 最小権限、端末内完結、暗号化前提

## 2. 必須要件
- INTERNET 権限を宣言しない
- 生体認証/端末認証を前提に復号する
- Android Keystore を中心に鍵管理を行う
- AutofillService でユーザー名/パスワード入力を支援する
- OTP は SMS を第一優先で対応し、通知/クリップボードはベストエフォートで補完する
- docs 内の仕様書と作業状況を常に最新化する

## 3. 権限・サービス方針
- 使用する Android サービス
  - `android.service.autofill.AutofillService`
  - `android.service.notification.NotificationListenerService`
- 使用しない権限
  - `android.permission.INTERNET`
  - `android.permission.READ_SMS`（SMS OTP は Play Services API 優先）
- 備考
  - `AndroidManifest.xml` で `uses-permission android.permission.INTERNET` を `tools:node="remove"` で明示除去

## 4. 技術スタック（現行方針）
- Kotlin + Jetpack Compose + Material 3
- Navigation Compose
- Hilt
- Room + SQLCipher（フェーズ2で本実装）
- Tink（フェーズ1/2で本実装）
- BiometricPrompt（フェーズ1で本実装）
- DataStore（設定永続化）

## 5. 画面要件（フェーズ3で詳細実装）
- AuthScreen
- HomeScreen
- AddEditScreen
- DetailScreen
- SettingsScreen
- PasswordGeneratorScreen

## 6. Autofill と OTP の対応方針
- Autofill フィールド検出は段階的フォールバック
  - autofillHints
  - HTML 属性
  - ヒューリスティック（和英キーワード + InputType）
- OTP 対応優先順位
  - SMS API
  - 通知読取（Android 15+ は制約あり）
  - クリップボード検出

## 7. セキュリティ設計方針
- 秘密情報は暗号化して保存
- 復号に必要な鍵は Keystore で保護
- クリップボード利用時は自動クリア
- バックグラウンド遷移後の自動ロックを実装

## 8. フェーズ計画
- Phase 0: プロジェクト土台、画面遷移、Manifest/Service宣言
- Phase 1: Keystore + 暗号化エンジン + 生体認証 + 自動ロック
- Phase 2: Room + SQLCipher + Repository
- Phase 3: コアUI詳細実装
- Phase 4: AutofillService本実装
- Phase 5: OTP統合
- Phase 6: Export/Import、テスト、最終調整

## 9. 実装状況（スナップショット）
- Phase 0: 完了
- Phase 1: 完了
  - 実装済み: `MasterKeyManager`, `CryptoEngine`, `BiometricAuthManager`, `AutoLockManager`
  - 実装済み: `BiometricAuthManager` は認証成功を `() -> Unit` で通知し、CryptoObject に依存しない設計へ更新
  - 実装済み: `AuthScreen` 認証連携、`NavGraph` ロック時遷移ガード
  - 実装済み: 設定画面からの自動ロック秒数変更連携、認証失敗UXリトライ導線
  - 実装済み: `CryptoEngineTest`（1件、成功）
- Phase 2: 完了
  - 実装済み: `CredentialEntity`, `CredentialDao`, `SecureVaultDatabase`
  - 実装済み: `DbKeyManager` による DB パスフレーズ管理
  - 実装済み: `CredentialRepository` / `CredentialRepositoryImpl`
  - 実装済み: `DatabaseModule`（Room + SQLCipher + Repository DI）
- Phase 3: 完了
  - 実装済み: `HomeScreen` 一覧/検索/フィルタ/スワイプ操作
  - 実装済み: `AddEditScreen` + `AddEditViewModel`（保存/編集/カテゴリ/強度表示）
  - 実装済み: `DetailScreen` + `DetailViewModel`（コピー/削除/表示切替）
  - 実装済み: `PasswordGeneratorScreen` + `PasswordGeneratorViewModel`
  - 実装済み: `SettingsScreen` + `SettingsViewModel`（自動ロック/クリップボード設定、全件削除）
  - 実装済み: `NavGraph` の生成パスワード受け渡し導線
- Phase 4: 完了
  - 実装済み: `SecureVaultAutofillService` の Repository 連携と FillResponse 生成
  - 実装済み: `SmartFieldDetector`（hint/HTML/InputType ベース検出、OTP候補検出）
  - 実装済み: Dataset 認証ゲート（`PendingIntent` -> `AutofillAuthActivity`）
  - 実装済み: `AutofillAuthActivity` の生体認証連携と認証成功時 Dataset 返却
  - 実装済み: `onSaveRequest` から `CredentialRepository.save()` 保存 + 保存通知
  - 実装済み: `SaveInfo` 付与、`autofill_service_config.xml` 互換パッケージ拡張
  - 実装済み: `POST_NOTIFICATIONS` 権限、Transparent テーマ適用
  - 実装済み: `buildFillResponse` に Inline 診断ログを追加（`inlineRequest` / `specsCount` / `maxSuggestionCount`、inline set/skip 判定）
  - 実装済み: `createInlinePresentation` を Android 15 向けに調整（`PendingIntent.FLAG_MUTABLE or FLAG_UPDATE_CURRENT`、spec 詳細ログ追加）
  - 実装済み: `buildFillResponse` の credentials Dataset 構築を Android 公式サンプル準拠へ変更（`Dataset.Builder()` + `setValue(id, value, presentation)`）
  - 実装済み: 自動入力候補UI検証のため、認証ゲート（`setAuthentication`）を一時無効化して直接値入力へ変更
  - 実装済み: ドロップダウン表示安定化検証として presentation を `android.R.layout.simple_list_item_1` / `android.R.id.text1` に統一（OTP Dataset 含む）
- Phase 5: 完了
  - 実装済み: `strings.xml` の全面日本語化（指定文言へ置換）
  - 実装済み: `SmsOtpManager`（`hasOngoingSmsRequest` -> `checkPermissionState` -> `startSmsCodeRetriever`）
  - 実装済み: `SmsOtpReceiver` + Manifest 連携（`SMS_CODE_RETRIEVED` 受信）
  - 実装済み: `OtpNotificationListener`（通知本文から OTP 抽出、`OtpManager` 通知）
  - 実装済み: `ClipboardOtpDetector`（4-8 桁 OTP のクリップボード監視）
  - 実装済み: `OtpManager`（SMS/通知/クリップボード統合イベント）
  - 実装済み: `SmsOtpActivity`（OTP 取得結果を `EXTRA_AUTHENTICATION_RESULT` で返却）
  - 実装済み: `SecureVaultAutofillService` OTP Dataset 追加と SMS 監視起動
  - 実装済み: `SettingsScreen` / `SettingsViewModel` OTP 設定 3 項目を DataStore 永続化
- Phase 6: 完了
  - 実装済み: `BackupManager`（暗号化JSON `.securevault` / CSV の Export・Import）
  - 実装済み: `ImportSource`（Brave, Google Chrome, Microsoft Edge, Firefox, 1Password, Bitwarden, LastPass, Dashlane, Apple パスワード, KeePass / KeePassXC, SecureVault）
  - 実装済み: `CsvImportParser`（ヘッダー照合 case-insensitive、スペース正規化、ダブルクォートCSV対応、Firefox URL からの serviceName 抽出）
  - 実装済み: `BackupManager.importFromService`（他サービスCSVインポート + 重複戦略対応）
  - 実装済み: `BackupCrypto`（PBKDF2WithHmacSHA256 600,000 iterations + AES-256-GCM）
  - 実装済み: `BackupCredential`（バックアップモデルとドメインモデルの相互変換）
  - 実装済み: `BackupScreen` / `BackupViewModel`（SAF 選択、パスワード入力、重複戦略、他サービスインポート導線）
  - 実装済み: `NavRoutes` / `NavGraph` / `SettingsScreen` のバックアップ導線追加
  - 実装済み: `strings.xml` バックアップ関連文言追加
  - 実装済み: `Theme.kt` の dynamic color 対応
  - 実装済み: `proguard-rules.pro` の SQLCipher/Tink/Room/Serialization/Hilt keep ルール更新
  - 実装済み: ユニットテスト追加（`BackupCryptoTest`, `BackupManagerTest`, `CsvImportParserTest`, `PasswordStrengthCheckerTest`）
  - 検証済み: `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest` 成功
  - 検証済み: `./gradlew :app:assembleRelease` 成功（`NullSafeMutableLiveData` lint 無効化で lint analyzer クラッシュ回避）
  - 既知制限: Samsung Pass の直接インポートは非対応（標準CSVエクスポート導線がないため）

## 10. リソース更新（UI）
- ランチャーアイコンを `icon.png` ベースへ差し替え
  - `res/drawable-nodpi/ic_launcher_foreground_image.png` を追加
  - `res/drawable/ic_launcher_foreground.xml` を bitmap + inset 構成へ変更

## 11. 開発運用ルール（ストレージ）
- 一時データと作業用キャッシュは `D:\temp` を優先使用する。
- Gradle 実行時は `TEMP/TMP/GRADLE_USER_HOME` を `D:\temp` 系へ固定して C ドライブ圧迫を回避する。
- インストール補助として `install_debug.bat` を利用可能（ビルド -> `adb install -r` -> 起動確認を自動実行）。

## 12. Phase 6 詳細仕様

### 12.1 バックアップ暗号化仕様
- 暗号化バックアップ拡張子: `.securevault`
- エンベロープ形式: JSON（`version`, `salt`, `iv`, `data`）
- 文字コード: UTF-8
- 鍵導出: `PBKDF2WithHmacSHA256`
  - 反復回数: 600,000
  - ソルト長: 32 bytes
  - 導出鍵長: 256 bits
- 暗号化方式: `AES-256-GCM`
  - IV 長: 12 bytes
  - 認証タグ: GCM 標準（128 bits）

### 12.2 バックアップファイルフォーマット
- 暗号化 JSON（`.securevault`）
  - `version`: 形式バージョン（現在 `1`）
  - `salt`: Base64（NO_WRAP）
  - `iv`: Base64（NO_WRAP）
  - `data`: Base64（NO_WRAP）
- CSV（平文）
  - ヘッダー: `serviceName,serviceUrl,username,password,notes,category`
  - 文字列内カンマ・改行・ダブルクォートを考慮してエスケープ処理を実施

### 12.3 他サービスインポート対応とカラムマッピング
| サービス | serviceName | serviceUrl | username | password | notes |
|---|---|---|---|---|---|
| Brave / Chrome / Edge | `name` | `url` | `username` | `password` | `note` |
| Firefox | `url`（ホスト抽出） | `url` | `username` | `password` | なし |
| 1Password | `Title` | `Website` | `Username` | `Password` | `Notes` |
| Bitwarden | `name` | `login_uri` | `login_username` | `login_password` | `notes` |
| LastPass | `name` | `url` | `username` | `password` | `extra` |
| Dashlane | `title` | `url` | `username` | `password` | `note` |
| Apple パスワード | `Title` | `URL` | `Username` | `Password` | `Notes` |
| KeePass / KeePassXC | `Title` | `URL` | `Username` / `User Name` | `Password` | `Notes` |
| SecureVault CSV | `serviceName` | `serviceUrl` | `username` | `password` | `notes` |

### 12.4 テスト一覧
- `BackupCryptoTest`
  - 正常パスワードの暗号化/復号往復
  - 誤パスワードで `AEADBadTagException`
  - ソルトの 32 bytes 長検証と重複なし検証
  - 空データ暗号化/復号
  - 同一パスワード+同一ソルトで同一鍵導出
- `BackupManagerTest`
  - `Credential` <-> `BackupCredential` 変換整合性
  - `packageName` 非含有、`id=0`、`packageName=null`
  - JSON シリアライズ/デシリアライズ往復
  - デフォルト値と未知フィールド許容
- `CsvImportParserTest`
  - 対応サービス CSV の各マッピング検証
  - ヘッダー大文字小文字差異
  - 必須カラム不足時エラー
  - 空行スキップ、空パスワード行スキップ
  - クォート内カンマ解析
  - SecureVault CSV の category デフォルト化
- `PasswordStrengthCheckerTest`
  - 弱い入力から強い入力まで閾値検証

### 12.5 ProGuard ルール方針
- `proguard-rules.pro` で以下を keep 対象に設定
  - SQLCipher
  - Google Tink
  - Room（Entity / DAO / Database）
  - Kotlinx Serialization 生成コード
  - Hilt 生成コード
