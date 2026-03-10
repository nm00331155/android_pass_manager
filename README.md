# android_pass_manager

SecureVault は、端末内完結を前提とした Android 向けパスワードマネージャーです。

現状は雛形段階ではなく、Phase 1〜6 の実装が完了し、現在は実機 QA・Autofill / Credential Provider 調整・運用検証フェーズです。

注意:
- docs 上の正式名称は SecureVault ですが、現行 UI / resource の一部には旧名称 KeyPass が残っています。
- 最新状況は README より [docs/implemented.md](docs/implemented.md) と [docs/pending.md](docs/pending.md) を優先してください。

## 主な機能
- 認証情報の CRUD、検索、カテゴリ分類、お気に入り
- 生体認証 / 端末認証、自動ロック、クリップボード自動消去
- Android Autofill Framework によるログイン情報入力補助
- OTP 補助（SMS / 通知 / クリップボード）
- Android Credential Provider による password / passkey フロー
- クレジットカード情報の暗号化保存と Autofill 補助
- 暗号化バックアップ（`.securevault`）と CSV export/import
- Brave / Chrome / Edge / Firefox / 1Password / Bitwarden などの CSV import

## 技術スタック
- Kotlin
- Jetpack Compose / Material 3 / Navigation Compose
- Hilt
- Room + SQLCipher
- Tink
- BiometricPrompt
- DataStore
- Android Autofill Framework
- Android Credential Provider

## ドキュメント
- 実装済み一覧: [docs/implemented.md](docs/implemented.md)
- 未実装・未完了一覧: [docs/pending.md](docs/pending.md)

## ビルドと検証
- Debug ビルド: `./gradlew.bat :app:assembleDebug`
- Unit test: `./gradlew.bat :app:testDebugUnitTest`
- 実機反映: `./install_debug.bat`

Gradle の一時ファイルは `D:\temp` 優先で運用する前提です。詳細は [docs/implemented.md](docs/implemented.md) を参照してください。

## 現在の重点領域
- Autofill のブラウザ互換差分と実機依存の最終調整
- Credential Provider / passkey の実機 QA
- カード Autofill のサイト差分確認
- Backup / Import / Export の実データ検証