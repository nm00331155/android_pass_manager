# SecureVault 実装済み一覧

最終更新: 2026-03-12 09:52:06 +09:00

## 1. この文書の位置づけ
- `docs` 配下の旧仕様書、旧フェーズプロンプト、旧バグ修正指示書、旧作業ログを、現在の実装状態だけに整理し直した統合版です。
- この文書に載っている内容は、現行コードベースに反映済みのものとして扱います。
- アプリ名は docs 上では `SecureVault` を正式名称とし、UI や resource の一部には旧名称 `KeyPass` が残っています。

## 2. 現在地
- Phase 0〜6 は完了しています。
- 現在は新規フェーズ実装ではなく、実機 QA・運用検証・端末差分調整の段階です。

## 3. 基本方針
- Android 向けのオフライン完結型 password manager / passkey provider / autofill provider として構成済みです。
- `INTERNET` 権限は使わない方針で実装されています。
- 秘密情報は暗号化保存し、復号は Android Keystore と認証導線を前提にしています。
- Autofill、Credential Provider、OTP、Backup/Import/Export、Card 管理までを単一アプリに統合済みです。

## 4. 主要コンポーネント
- アプリ基盤: `SecureVaultApplication`, `MainActivity`
- 認証とロック: `BiometricAuthManager`, `AutoLockManager`, `AuthScreen`, `AuthViewModel`
- データ層: `CredentialEntity`, `CredentialDao`, `SecureVaultDatabase`, `DatabaseModule`, `CredentialRepository`, `CredentialRepositoryImpl`
- コア UI: `HomeScreen`, `AddEditScreen`, `DetailScreen`, `SettingsScreen`, `PasswordGeneratorScreen`, `BackupScreen`
- Autofill: `SecureVaultAutofillService`, `AutofillAuthActivity`, `SmartFieldDetector`, `AutofillCredentialMatcher`
- Credential Provider / passkey: `KeyPassCredentialProviderService`, `CredentialProviderAuthActivity`, `PasskeyWebAuthnHelper`, `provider.xml`
- OTP: `SmsOtpActivity`, `SmsOtpManager`, `SmsOtpReceiver`, `OtpNotificationListener`, `ClipboardOtpDetector`, `OtpManager`, `OtpListeningPolicy`
- バックアップとインポート: `BackupManager`, `BackupCrypto`, `BackupCredential`, `CsvImportParser`, `ImportSource`

## 5. 実装済み機能

### 5.1 コア機能
- 認証情報の CRUD
- 検索、カテゴリフィルタ、お気に入り
- ID-only credential と passwordless 運用
- パスワード生成
- パスワード強度判定
- クリップボード自動消去

### 5.2 セキュリティと保存
- Android Keystore ベースの鍵管理
- AES-256-GCM による秘密情報の暗号化保存
- SQLCipher による Room データベース暗号化
- 自動ロックと再認証導線
- フォアグラウンド復帰時の stale 認証状態リセット

### 5.3 コア UI
- 認証画面
- 一覧画面
- 追加・編集画面
- 詳細画面
- 設定画面
- パスワード生成画面
- バックアップ画面

### 5.4 Autofill
- Android Autofill Framework 対応
- username / password / OTP / card 項目のフィールド検出
- `autofillHints`、HTML 属性、InputType、日本語・英語キーワードの複合ヒューリスティック
- Dataset 認証ゲートと `AutofillAuthActivity` による認証後入力
- `onSaveRequest` による保存候補抽出と保存通知
- multi-step ログイン画面の複数 `FillContext` 集約
- Chromium 系ブラウザでの dataset-auth 優先化
- compat-mode proxy `focusedId` を考慮した候補表示
- ID-only / password-only credential の候補ラベル明示
- password-only save と既存 credential への更新保存マッチング補強
- ネイティブアプリ保存時に `activityComponent` と観測 package 群から framework `android` などの汎用値を除外して対象アプリ package を推定
- ネイティブアプリ保存時に app label 優先で `serviceName` を決定し、username と package / domain / service 一致スコアで既存 credential を更新保存する upsert 補強
- フォーカス中の入力欄ヒントを `AutofillAuthActivity` へ引き継ぎ、明示的な username / password id が欠ける native app でも認証後入力を継続
- Android 14+ Fill Dialog / dialog presentation 対応
- 汎用フォールバック一覧を廃止した高信頼一致スコアリング

### 5.5 OTP
- SMS API 優先の OTP 取得
- 通知からの OTP 抽出
- クリップボードからの OTP 抽出
- recent OTP キャッシュによる再利用
- OTP 待機 UI とキャンセル導線
- OTP 待機 UI からのメールアプリ起動導線
- メールアプリ復帰時の clipboard 再確認による OTP 補助
- OTP ソースの有効状態と待機可否判定の分離

### 5.6 Credential Provider / passkey
- Password credential の provider flow
- passkey get/create の provider flow
- WebAuthn 応答生成
- `clientDataHash` あり/なし両経路への対応
- `origin` からの RP ID 補完
- provider 管理画面への設定導線
- package URI 付き `ACTION_CREDENTIAL_PROVIDER` と段階フォールバック

### 5.7 バックアップ / インポート / エクスポート
- 暗号化バックアップ `.securevault`
- SecureVault CSV export / import
- 他サービス CSV import
- Brave, Chrome, Edge, Firefox, 1Password, Bitwarden, LastPass, Dashlane, Apple パスワード, KeePass / KeePassXC 対応
- 重複戦略: スキップ / 上書き / すべて追加
- PBKDF2WithHmacSHA256 + AES-256-GCM によるバックアップ暗号化

### 5.8 クレジットカード
- `CredentialType.CARD` と `CardData`
- カード番号、名義、有効期限、セキュリティコードの暗号化保存
- カードの作成、編集、表示、検索
- カード Autofill のフィールド検出と認証後入力
- バックアップ / CSV 往復でのカード項目保持

### 5.9 テストとビルド整備
- `CryptoEngineTest`
- `PasskeyWebAuthnHelperTest`
- `BackupCryptoTest`, `BackupManagerTest`, `CsvImportParserTest`, `PasswordStrengthCheckerTest`
- `SmartFieldDetectorTest`
- `NativeAppMetadataResolverTest`
- `OtpListeningPolicyTest`, `OtpManagerTest`
- `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleRelease` の通過実績

## 6. 反映済みの改善

### 6.1 初期フェーズで反映済みの土台整備
- SQLCipher ネイティブライブラリのロード
- `INTERNET` 権限の明示除去
- Phase 1〜6 の docs / テスト整備

### 6.2 復号と保存の安定化
- Keystore 依存による読み込み遅延と全件消失リスクの解消
- 復号処理の効率化
- インポート速度の改善

### 6.3 Autofill の検出精度改善
- 空文字判定や broad keyword に起因する誤検出の除去
- 非ログイン系 radio / button / checkbox / select 操作での誤発火抑止
- OTP 欄と ID 欄の誤認識抑止
- serviceName / package / domain / page title を横断した照合改善

### 6.4 Autofill UI と挙動の改善
- Fill Dialog 経路の追加
- Chrome / Brave / Edge 系での response-auth / dataset-auth の使い分け調整
- 認証後に入力されない、あるいは二度選択が必要になる挙動の緩和
- 保存確認が multi-step ログインで出ない問題の修正
- username 欠落の password-first 画面でも save 対象を組み立てる補強
- Chromium compat proxy や OTP ステップ由来の `focusedId` ずれで save 情報が落ちにくいよう補強
- Chromium compat proxy で dataset 候補を返す経路では、候補 UI を優先して `SaveInfo` の同時付与を再抑止
- 候補抑制時に filled / missing 状態がログで追える詳細化
- Autofill 候補 UI の視認性改善
- native app の save で `android` のような framework package が保存されにくいよう metadata 解決を共通化
- 既存 credential が汎用 package / service 名のままでも、認証後学習と次回 save で実アプリ情報へ更新しやすいよう補強
- `webDomain` を持たない native app ログインでは response-level authentication を避け、dataset-auth を使って認証後入力の反映率を優先

### 6.5 OTP フローの改善
- recent OTP の即時利用
- 待機 UI の可視化
- Amazon アプリでフリーズしたように見える UX の修正
- OTP 状態メッセージの復元と一貫化

### 6.6 Credential Provider / passkey 改善
- AndroidX passkey capability 文字列への修正
- passkey create request の `rp.id` 欠落時フォールバック
- WebAuthn 応答の組み立て不整合修正
- provider サービス列挙に失敗しないよう候補生成の安定化
- 空ユーザー名 credential のスキップ
- native app caller の署名取得を `PackageManager` 依存から `CallingAppInfo.signingInfoCompat` へ切り替え、Android 11+ package visibility に起因する passkey create origin 解決失敗を回避

### 6.7 認証 UI 改善
- 認証画面の再入時フリーズ修正
- 起動直後の自動認証プロンプト導入
- 否定ボタンの高コントラスト化
- Samsung 生体認証 UI での低コントラストな否定ボタン経路の回避
- API 30+ で strong biometric と device credential の両方が利用可能な場合は combined authenticator を優先し、Samsung 端末で `PINを使用` ボタン付き prompt へ遷移することを確認
- Backup 画面を含む Compose `AlertDialog` の否定ボタンを共通の高コントラストスタイルへ統一

### 6.8 実使用感とアイコンの改善
- Add/Edit、詳細、設定、パスワード生成、バックアップ画面の戻る導線を back arrow に統一
- Home 画面で検索欄フォーカス後も上部メニューが押し出されにくいレイアウトへ調整
- 他サービス CSV import ダイアログから SecureVault 形式の重複選択肢を除外
- launcher / round launcher / adaptive icon と splash foreground を `asset` 配下の新アイコンへ更新
- submit / button 系 UI で Autofill save prompt が出にくいよう trigger 判定を厳格化

## 7. 検証済み状態
- `Phase 1〜6 完了` と `実装済み機能（最終）` は docs 上で整理済みです。
- `:app:testDebugUnitTest` と `:app:assembleDebug` の成功実績があります。
- `install_debug.bat` / `install_debug_auto.bat` による実機反映実績があります。
- 2026-03-10 時点で `KeyPassCredentialProviderService` が OS の credential provider service 一覧に列挙されることを確認済みです。
- 2026-03-10 22:50 採取の `docs/securevault_post_patch_20260310_2250.xml` で、Samsung 認証 UI のタイトル `KeyPass`、subtitle `生体認証または端末認証でロックを解除してください`、`button_use_credential=PINを使用` を確認済みです。
- 2026-03-11 21:57 時点で `:app:testDebugUnitTest :app:assembleDebug` 成功、ルート APK `SecureVault-debug.apk` を更新（`LastWriteTime=2026-03-11 21:57:20 +09:00`、`Length=111063069`）しました。
- 2026-03-11 21:58 に端末 `RFCY2094T0V` へ `adb install -r` 成功、`versionName=0.1.0`、`lastUpdateTime=2026-03-11 21:58:15` を確認しました。
- 2026-03-11 21:58 採取の `docs/screenshots/securevault_native_autofill_20260311_215818.png` と `docs/securevault_native_autofill_20260311_215827.xml` で、起動直後に Samsung 生体認証 UI のタイトル `KeyPass`、subtitle `生体認証または端末認証でロックを解除してください`、`button_use_credential=PIN を使用` を確認しました。
- 2026-03-12 09:48 時点で `:app:testDebugUnitTest :app:assembleDebug` 成功、ルート APK `SecureVault-debug.apk` を更新（`LastWriteTime=2026-03-12 09:49:52 +09:00`、`Length=96777372`）しました。
- 2026-03-12 09:50 に端末 `RFCY2094T0V` へ再インストール成功。既存署名不一致のため一度 `adb uninstall com.securevault.app` を実行後、`adb install` で `versionName=0.1.0`、`lastUpdateTime=2026-03-12 09:50:30` を確認しました。
- 2026-03-12 09:52 採取の `docs/screenshots/securevault_post_patch_20260312_095206.png` と `docs/securevault_post_patch_20260312_095206.xml` で、起動直後画面の表示崩れがないことを確認しました。

## 8. 運用メモ
- 一時ファイルは `D:\temp` を標準運用とします。
- Debug APK の配備補助として `install_debug.bat` と `install_debug_auto.bat` を利用できます。
- 詳細な変更履歴やスクリーンショット列挙は旧 docs から本書へ統合済みです。以後はこの文書を実装済みの正本として扱います。