# SecureVault 仕様書

最終更新: 2026-03-06 13:08:58 +09:00

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
- Phase 1: 進行中
  - 実装済み: `MasterKeyManager`, `CryptoEngine`, `BiometricAuthManager`, `AutoLockManager`
  - 実装済み: `AuthScreen` 認証連携、`NavGraph` ロック時遷移ガード
  - 実装済み: `CryptoEngineTest`（1件、成功）
  - 未完了: 設定画面からの自動ロック秒数変更連携、詳細な認証失敗UX調整
- Phase 2: 完了
  - 実装済み: `CredentialEntity`, `CredentialDao`, `SecureVaultDatabase`
  - 実装済み: `DbKeyManager` による DB パスフレーズ管理
  - 実装済み: `CredentialRepository` / `CredentialRepositoryImpl`
  - 実装済み: `DatabaseModule`（Room + SQLCipher + Repository DI）

## 10. 開発運用ルール（ストレージ）
- 一時データと作業用キャッシュは `D:\temp` を優先使用する。
- Gradle 実行時は `TEMP/TMP/GRADLE_USER_HOME` を `D:\temp` 系へ固定して C ドライブ圧迫を回避する。
