# 作業状況

最終更新: 2026-03-09 22:43:30 +09:00

## 現在のフェーズ
- Phase 1〜6: 全完了
- 現在はリリース前の実機QA・運用検証のみ継続

## 実装済み機能（最終）
1. 認証情報管理（CRUD、検索、カテゴリフィルタ、お気に入り）
2. セキュリティ（AES-256-GCM, Android Keystore, SQLCipher）
3. 生体認証（指紋/顔認証/デバイスPIN）
4. 自動ロック（バックグラウンド遷移時）
5. クリップボード保護（自動消去）
6. パスワード生成（カスタマイズ可能）
7. パスワード強度判定（リアルタイム）
8. 自動入力サービス（Android Autofill Framework）
9. バックアップ・復元（暗号化 JSON / CSV）
10. 他サービスインポート（Brave, Chrome, Edge, Firefox, 1Password, Bitwarden, LastPass, Dashlane, Apple パスワード, KeePass）
11. ダークモード（Material 3 ダイナミックカラー）
12. オフライン完結（INTERNET 権限なし）
13. クレジットカード管理（暗号化保存、編集、バックアップ/CSV 往復）
14. Passwords / passkeys / autofill の provider 管理導線

## 既知の制限事項
- Samsung Pass は独自 `.spass` 形式のため直接インポート非対応（Google パスワードマネージャー経由を推奨）
- Android 15+ では通知からの OTP 読取に制限あり
- ダイナミックカラーは Android 12（API 31）以上のみ
- `play-services-auth` 依存あり（SMS OTP 用）だが INTERNET 権限は Manifest で明示除去
- クレジットカード有効期限の Autofill はサイト実装差（`MM/YY` / `MM/YYYY` / month-year 分離）に依存するため、実サイトごとに最終 QA が必要

## 本セッションで完了した作業
- Autofill / passkey provider の回帰 ANR を調査し、サービス実行を非同期化
  - 調査結果: `adb logcat` で `Timeout executing service: ServiceRecord{...SecureVaultAutofillService}` と `ANR in com.securevault.app` を確認。`dumpsys autofill` では SecureVault 自体は既定 Autofill service のままで、登録解除やクラッシュではなく service 実行ブロックが主因と判明
  - 調査結果: `SecureVaultAutofillService.kt` の `onFillRequest` と `KeyPassCredentialProviderService.kt` の create/get request がメインプロセス上で同期的に credential 解決・復号を行っており、Samsung 端末での長時間待ちと相性が悪かった
  - 改修: `SecureVaultAutofillService.kt` の `onFillRequest` を `serviceScope.launch` ベースの非同期処理へ変更し、`CancellationSignal` を見ながら `FillCallback` を返すよう更新
  - 改修: `KeyPassCredentialProviderService.kt` の `onBeginCreateCredentialRequest` / `onBeginGetCredentialRequest` をバックグラウンド coroutine 化し、`runBlocking` を除去
  - 改修: `SmartFieldDetector.kt` の全ノード verbose ログを既定無効化し、巨大な `AssistStructure` を走査する際の logcat/buffer 負荷を抑制
  - 検証: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest` 成功、72 件の unit test 成功
  - 実機反映: `install_debug.bat` 成功、端末 `RFCY2094T0V` の `lastUpdateTime=2026-03-09 22:42:29`
  - 成果物: ルート APK `SecureVault-debug.apk` を更新（`LastWriteTime=2026-03-09 22:42:01`、`Length=111062901`）
  - 追加確認: `adb logcat -c` 後にアプリを再起動し、直後の新規ログに `SecureVaultAutofillService` / `KeyPassCredentialProviderService` の ANR 行が出ていないことを確認
  - スクリーンショット: `docs/screenshots/securevault_anr_fix_20260309_2242.png` を取得（`LastWriteTime=2026-03-09 22:42:58`、`Length=54330`）
  - 注意: 実サイト上での Autofill / passkey get-create の完全再現までは自動化しておらず、ブラウザ実地 QA は継続して必要

- Autofill ダイアログ視認性、Chrome 挿入不良、provider 管理導線を追加修正
  - 改修: `ConfirmDialog.kt` の否定ボタン色をテーマ依存の `onSurface` 任せにせず、dialog surface の輝度に応じて黒/白を明示適用するよう変更。白背景系テーマでも `いいえ` が白文字のまま残らないよう修正
  - 改修: `SecureVaultAutofillService.kt` の response-level authentication 判定を更新し、Chromium 系ブラウザでは単一 credential 時でも dataset-auth を使うよう変更。候補 UI は出るが認証後に Chrome へ値が入らない経路を回避
  - 改修: `SettingsScreen.kt` の provider 管理導線を更新し、`ACTION_CREDENTIAL_PROVIDER` に `package:<app>` URI を付与。加えて未対応端末向けに Default apps -> Autofill service -> app details の順でフォールバックするよう変更
  - 検証: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest` 成功、72 件の unit test 成功
  - 成果物: ルート APK `SecureVault-debug.apk` を更新（`LastWriteTime=2026-03-09 22:12:37`、`Length=111062901`）
  - 実機反映: `install_debug.bat` 成功、端末 `RFCY2094T0V` の `lastUpdateTime=2026-03-09 22:14:08`
  - 実機確認: `adb shell am start -W -a android.settings.CREDENTIAL_PROVIDER -d package:com.securevault.app` で `com.android.settings/.Settings$PasswordsAndAutofillPickerActivity` が起動し、`KeyPass` と `Samsung Pass` の provider 一覧表示を確認
  - スクリーンショット: `docs/screenshots/securevault_provider_settings_20260309_2220.png`、`docs/screenshots/securevault_post_fix_20260309_2215.png` を取得

- Autofill / Credential Provider からアプリへ戻った後に認証画面が固まる不具合を修正
  - 調査結果: `BiometricAuthManager` の `AuthUiState.Authenticating` が残留したまま `AuthScreen` に復帰すると、`prepareForEntry()` が状態をリセットせず、認証ボタンも無効化されたままになり再操作不能になっていた
  - 改修: `AuthViewModel.kt` の `prepareForEntry()` から `Authenticating` 時の早期 return を削除し、再入時は常に認証状態を初期化するよう変更
  - 改修: `AuthScreen.kt` に lifecycle observer を追加し、フォアグラウンド復帰時 (`ON_START`) に `autoPromptAttempted` と認証状態を再初期化するよう変更
  - 検証: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest` 成功、72 件の unit test 成功
  - 実機反映: `install_debug.bat` で再導入成功。途中で署名不一致により既存アプリを `adb uninstall com.securevault.app` してから再インストール
  - 端末反映: `lastUpdateTime=2026-03-09 21:53:33`
  - スクリーンショット: `docs/screenshots/securevault_auth_resume_fix_20260309.png` を取得

- 認証画面と Autofill UI の追加調整
  - 改修: `AuthScreen.kt`, `AuthViewModel.kt` を更新し、起動直後の認証画面で自動的に認証ダイアログを起動するよう変更。起動ロゴで止まって見える状態を減らし、手動ボタンはリトライ用途として維持
  - 改修: `AuthScreen.kt`, `AuthViewModel.kt`, `NavGraph.kt` を追加更新し、認証画面への再入時に前回の `AuthUiState` を初期化、`autoPromptAttempted` を saveable ではなく画面単位 state に変更、未ロック時は `Auth` に留まらず `Home` へ遷移するよう修正
  - 改修: `ConfirmDialog.kt` を更新し、`いいえ` 側を `OutlinedButton` 化して高コントラスト化。白背景上でも視認しやすい見た目へ変更
  - 改修: `SecureVaultAutofillService.kt` を更新し、ユーザー名/パスワードまたはカード項目が既に埋まっている場合は Autofill UI を再表示しないよう抑制
  - 改修: `SecureVaultAutofillService.kt` の response-level authentication を password 保持 credential のみに限定し、ID-only credential は dataset suggestion 経由で表示するよう変更
  - 調査: `adb logcat` では `AndroidRuntime` の致命例外は確認されず、`MainActivity` と splash 遷移までは到達していたため、クラッシュではなく認証待ち UX が主因と判断
  - 検証: `./gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug` 成功
  - 成果物: ルート APK `android_pass_manager-debug.apk` を更新（`LastWriteTime=2026-03-09 17:16:22`、`Length=96767073`）
  - 実機反映: `adb -s RFCY2094T0V install -r android_pass_manager-debug.apk` 成功、端末 `RFCY2094T0V` の `lastUpdateTime=2026-03-09 17:16:35`
  - スクリーンショット: `docs/screenshots/securevault_auth_autoprompt_20260309.png` を取得（`LastWriteTime=2026-03-09 16:52:30`、`Length=55662`）
  - 再起動確認: close -> relaunch の連続シーケンスで `MainActivity` 再表示と `BiometricPrompt` 再表示を logcat 上で確認し、追加スクリーンショット `docs/screenshots/securevault_auth_relaunch_20260309.png` を取得（`LastWriteTime=2026-03-09 17:17:23`、`Length=50682`）

- クレジットカード自動入力と Credential Provider 一元管理導線を追加実装
  - 改修: `Credential.kt`, `CredentialEntity.kt`, `CredentialRepositoryImpl.kt`, `DatabaseMigrations.kt`, `SecureVaultDatabase.kt`, `DatabaseModule.kt` を更新し、`CredentialType.CARD` と `CardData`、Room v3 migration、暗号化保存/復号を追加
  - 改修: `AddEditViewModel.kt`, `AddEditScreen.kt`, `HomeScreen.kt`, `HomeViewModel.kt`, `DetailScreen.kt` を更新し、カード種別の作成/編集/検索/表示、カード番号・名義・有効期限・セキュリティコード表示を追加
  - 改修: `SecureVaultAutofillService.kt`, `SmartFieldDetector.kt`, `AutofillAuthActivity.kt` を更新し、カード番号・名義・有効期限・セキュリティコードの検出、保存、認証後入力を追加
  - 改修: `BackupCredential.kt`, `BackupManager.kt`, `ImportSource.kt`, `CsvImportParser.kt` を更新し、暗号化バックアップと SecureVault CSV にカード項目を追加
  - 改修: `SettingsScreen.kt`, `strings.xml` を更新し、`android.settings.CREDENTIAL_PROVIDER` を開く導線と Samsung Pass 等の追加 provider を無効化する案内を追加
  - テスト: `SmartFieldDetectorTest.kt`, `BackupManagerTest.kt`, `CsvImportParserTest.kt` にカード対応ケースを追加
  - 検証: `./gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug` 成功、72 件の unit test 成功
  - 生成物: `app/schemas/com.securevault.app.data.db.SecureVaultDatabase/3.json` を生成（`LastWriteTime=2026-03-09 15:59:30`、`Length=6884`）
  - 成果物: ルート APK `android_pass_manager-debug.apk` を更新（`LastWriteTime=2026-03-09 16:02:21`、`Length=96767073`）
  - 実機反映: `adb -s RFCY2094T0V install -r android_pass_manager-debug.apk` 成功、端末 `RFCY2094T0V` の `lastUpdateTime=2026-03-09 16:03:39`
  - スクリーンショット: `docs/screenshots/securevault_cards_provider_20260309.png` を取得（`LastWriteTime=2026-03-09 16:11:19`、`Length=142058`）
  - 注意: スクリーンショットは起動直後の認証画面まで。カード作成画面・設定画面・実サイト Autofill は生体認証通過後の手動 QA が別途必要

- ID-only / passwordless / passkey / Credential Provider 対応を実装してビルド可能状態まで統合
  - 改修: `Credential` の nullable password / `credentialType` / `passkeyData` に合わせて Add/Edit, Home, Detail の UI を更新し、`serviceName + username` 必須、password 任意の扱いへ変更
  - 改修: `SecureVaultAutofillService.kt`, `AutofillAuthActivity.kt` を更新し、ID-only credential の候補化、passkey の通常 Autofill 候補除外、save 時の `username required / password optional`、password 非保持 credential の安全な入力を追加
  - 改修: `PasskeyWebAuthnHelper.kt`, `KeyPassCredentialProviderService.kt`, `CredentialProviderAuthActivity.kt`, `PrivilegedBrowserAllowlist.kt`, `provider.xml` を追加・更新し、password/passkey の get/create provider flow と WebAuthn software authenticator 応答生成を実装
  - 改修: `SmsOtpActivity.kt` を `ComponentActivity` 化し Hilt/KSP ビルドエラーを解消
  - 改修: `app/build.gradle.kts` に `testImplementation("org.json:json:20240303")` を追加し、passkey helper の JVM unit test 実行を安定化
  - テスト: `CsvImportParserTest.kt`, `BackupManagerTest.kt` を更新し、`PasskeyWebAuthnHelperTest.kt` を追加
  - 検証: `./gradlew.bat clean testDebugUnitTest assembleDebug` 成功
  - 生成物: `app/schemas/com.securevault.app.data.db.SecureVaultDatabase/2.json` を生成（`LastWriteTime=2026-03-09 14:40:36`、`Length=5430`）
  - 成果物: ルート APK `android_pass_manager-debug.apk` を更新（`LastWriteTime=2026-03-09 14:47:05`、`Length=95646061`）
  - 実機反映: `adb -s RFCY2094T0V install -r android_pass_manager-debug.apk` 成功、端末 `RFCY2094T0V` の `lastUpdateTime=2026-03-09 14:47:57`
  - スクリーンショット: `docs/screenshots/securevault_passkey_status_20260309.png` を取得（`LastWriteTime=2026-03-09 14:48:39`、`Length=85647`）
  - 注意: 取得したスクリーンショットは起動直後画面の保存まで確認。生体認証通過後の個別画面の目視確認は別途手動 QA が必要

- Brave で再び候補が出なくなった問題を compat-mode proxy `focusedId` の扱いとして追加修正
  - 調査結果: 最新ログでは Brave から `FillRequest` 自体は来ており、`SmartFieldDetector` も `usernameId` / `passwordId` と `webDomain` を検出していた一方、compat-mode の `focusedId` は `AssistStructure` 内に materialize されず `focusedNodeInfo=null` のため、`SecureVaultAutofillService.kt` の新しい guard が `buildFillResponse: skipping because focusedId is not a credential-like input` 側に倒れる構造になっていた
  - 改修: `SecureVaultAutofillService.kt` に `shouldAllowFocusedTrigger()` を追加し、`focusedId` が `allAutofillIds` に存在しない compat proxy だが credential targets 自体は検出できている場合は trigger を許可するよう変更
  - 期待効果: Brave / Chromium compat-mode では hidden username/password ids と別の proxy focusedId でも、候補UIを再度表示できる

- Chrome の単一 credential 認証後にもう一度候補を選ばないと入力されない UX を改善するため、response-auth の返却 dataset を簡素化
  - 調査結果: Android Autofill の response-level authentication は認証結果として `FillResponse` を返す仕様で、公式ドキュメントも「認証後に populated dataset を含む `FillResponse` を返す」流れになっており、端末実装によっては認証後に unlocked candidate が再表示されうる
  - 改修: `AutofillAuthActivity.kt` で `AUTH_RESULT_FILL_RESPONSE` の場合は presentation 付き `Dataset.Builder(RemoteViews)` ではなく `Dataset.Builder()` を使って実データのみを返すよう変更
  - 狙い: 単一 credential の response-auth で、認証後に二度目の候補選択UIを出さずそのまま autofill へ進める余地を広げる
  - 注意: これは framework / browser 実装依存の挙動も絡むため、実機で再確認が必要

- 検証と配備を更新
  - `:app:testDebugUnitTest :app:assembleDebug` 成功
  - 成果物: ルート APK `android_pass_manager-debug.apk` を更新（`LastWriteTime=2026-03-09 13:03:44`、`Length=96588297`）
  - 実機反映: `adb install -r` 成功、端末 `RFCY2094T0V` に反映済み（`lastUpdateTime=2026-03-09 13:03:54`）
  - ログ準備: `autofill_test_log.txt` を `SecureVaultAutofill` / `SmartFieldDetector` / `AutofillAuthActivity` タグ込みで再取得開始

- ログイン無関係な radio/button 操作でパスワード認証UIが出る誤発火を抑止し、サイト個別調整ではなく汎用 heuristic を強化
  - 調査結果: `SmartFieldDetector` が broad keyword と focused proxy id の扱いで過剰反応しており、`USERNAME_KEYWORDS` の bare `"id"` と、focused node を無条件に UI trigger に追加する処理が、申請フォーム等の非ログイン操作でも候補UIを開く要因になっていた
  - 改修: `SmartFieldDetector.kt` で username/password/OTP 判定に「実際に text input らしい node」であることを必須化し、`radio` / `checkbox` / `button` / `submit` / `select` / `toggle` 系 control を HTML 属性だけでなく `idEntry` / hint / text からも除外するよう変更
  - 改修: `SmartFieldDetector.kt` の username keyword から bare `"id"` を削除し、`loginid` / `memberid` / `customerid` / `ログインid` / `会員id` へ置き換え
  - 改修: focused node の `supportsAutofillTrigger` を検出結果に保持し、`SecureVaultAutofillService.kt` では credential-like な focused node でない限り `focusedId` を dataset trigger に使わず、mismatch 時は `FillResponse` 自体を返さないよう変更
  - テスト: `SmartFieldDetectorTest.kt` を追加し、`customer_id` のような非入力 node を username と誤検出しないこと、radio input が trigger にならないこと、`login_id` text input は引き続き検出・trigger 対象になることを固定
  - 検証: `:app:testDebugUnitTest :app:assembleDebug` 成功、ユニットテスト 63 件成功
  - 成果物: ルート APK `android_pass_manager-debug.apk` を更新（`LastWriteTime=2026-03-09 12:39:50`、`Length=96588297`）
  - 実機反映: `adb install -r` 成功、端末 `RFCY2094T0V` に反映済み（`lastUpdateTime=2026-03-09 12:40:07`）
  - スクリーンショット: 未取得（今回の変更は検出・表示条件のロジック調整であり、実機再現確認待ち）

- Brave で UI は出るが認証後に入力されずループする問題を追加修正
  - 調査結果: verbose logcat で `DialogFillUi` は表示されている一方、認証成功後 `onAuthenticationResult()` のたびに同じ focused proxy id (`4:i8135`) へ新しい `FillRequest` が発行され、`response-level authentication` が再度返されるループを確認
  - 原因: Chromium compat-mode の proxy field では、認証結果として `FillResponse` を返す方式だと framework が hidden の fillable ids (`4:i524288`, `4:i524289`) を持ったまま focused proxy id を未入力とみなし、再度同じ認証 UI を出していた
  - 改修: `SecureVaultAutofillService.kt` の `shouldUseResponseAuthentication()` を変更し、`focusedId` が検出した username/password id と一致しない場合は `response-level authentication` を使わず dataset authentication 経路に戻すよう修正
  - 期待効果: Brave など compat-mode browser では 1 度の認証で dataset 選択完了まで進み、同じ認証 UI の再表示ループを止める

- Chrome 145 で SecureVault に FillRequest 自体が来ない問題に対する compat 設定実験を追加
  - 調査結果: logcat で `Ignoring not allowed compat package com.android.chrome` を確認し、実機 Chrome の `versionCode=763215933` が `autofill_service_config.xml` の上限 `711900039` を超えて framework から除外されていた
  - 改修: `autofill_service_config.xml` の `com.android.chrome` / `com.chrome.beta` / `com.chrome.dev` / `com.chrome.canary` の `maxLongVersionCode` を `999999999` へ一時的に拡張
  - 目的: Chrome 側で SecureVault への FillRequest が届くかを検証し、ブラウザ設定不足なのか manifest compat 制限なのかを切り分ける

- Brave / Chromium 互換モードで Autofill UI が出ないアプリ側根本原因を特定して修正
  - 調査結果: verbose logcat で、framework の `focusedId` と `SmartFieldDetector` が返していた `usernameId` / `passwordId` が一致していないことを確認
  - 症状: `SecureVaultAutofillService` は正常に `FillResponse` を返していたが、応答対象 ID が現在フォーカス中の view を含まないため候補 UI 表示条件を満たさず、さらに hidden 側 ID で作成した `SaveInfo` により session が即 `finishSessionLocked(): ACTIVE` で終了していた
  - 改修: `SmartFieldDetector.detect()` に request の `focusedId` を渡し、検出結果へ保持するよう変更
  - 改修: `SecureVaultAutofillService.kt` で response-level authentication / dataset authentication / fill dialog trigger に `focusedId` も含めるよう変更し、UI 表示用 trigger ID と実際の入力対象 ID を分離
  - 改修: `focusedId` が検出した username/password ID に含まれないケースでは `SaveInfo` を付与しないよう変更し、互換モード browser で hidden ID 追跡により session が即終了する不具合を回避
  - 検証: `:app:assembleDebug`, `:app:testDebugUnitTest` 成功、`get_errors` で変更ファイルのエラー 0 件
  - 成果物: ルート APK `android_pass_manager-debug.apk` を更新（`LastWriteTime=2026-03-09 10:57:41`、`Length=96588297`）
  - 実機反映: `adb install -r` 成功、端末 `RFCY2094T0V` に反映済み
  - 端末設定: `autofill_service` を SecureVault に再設定済み
  - 実験設定: 次回の切り分け用に `default-augmented-service-enabled=false`, `max_visible_datasets=4` を維持
  - スクリーンショット: 未取得（修正版の UI 再確認待ち）

- Android 14+ Fill Dialog を使う Autofill 表示経路を追加
  - 調査結果: 実機 `dumpsys autofill` で `Inline Suggestions Enabled: false` かつ `Max visible datasets: 0` を確認し、通常の候補ドロップダウンだけでは UI が出ない端末状態と判断
  - 改修: `SecureVaultAutofillService.kt` の Dataset 生成を Android 14+ では `Presentations.Builder` ベースへ切替え、menu presentation に加えて dialog presentation も返すよう変更
  - 改修: `FillResponse.Builder.setDialogHeader()` / `setFillDialogTriggerIds()` / `setShowFillDialogIcon(true)` を追加し、ユーザー名・パスワード・OTP フィールドのフォーカス時に Fill Dialog が起動できるよう調整
  - 維持: 既存の `AutofillAuthActivity` 経由の認証ゲートはそのまま維持し、候補選択後に生体認証して入力するフローは継続
  - 検証: `:app:assembleDebug :app:testDebugUnitTest` 成功
  - 成果物: ルート APK `android_pass_manager-debug.apk` を更新（`LastWriteTime=2026-03-09 10:02:27`）
  - 実機反映: `adb install -r` 成功、`lastUpdateTime=2026-03-09 10:02:53`
  - 端末設定: `autofill_service` を `com.securevault.app/com.securevault.app.service.autofill.SecureVaultAutofillService` に再設定済み
  - ログ準備: `autofill_test_log.txt` の再取得を開始済み
  - スクリーンショット: 未取得（Fill Dialog の実表示確認待ち）

- Autofill を「一覧表示」から「高信頼一致 + 認証付き入力」へ再調整
  - 改修: `SecureVaultAutofillService.kt` の認証なし直接入力 Dataset を廃止し、`AutofillAuthActivity` を経由する認証ゲート付き Dataset に復帰
  - 改修: `AutofillCredentialMatcher.kt` を高信頼一致専用へ変更し、汎用フォールバック一覧を廃止
  - 改修: アプリ表示名も照合対象に追加し、`com.netflix.mediaclient` のような package 名と `Netflix` の保存名を結び付けやすくした
  - 改修: ブラウザ要求で `webDomain` が取得できない場合は `AssistStructure.WindowNode.title` も照合に使い、それでも特定できない場合のみ候補を返さないよう変更
  - 改修: 認証成功時に未保存の `packageName` / `serviceUrl` を学習保存し、次回以降の一致精度を改善
  - テスト: `AutofillCredentialMatcherTest.kt` を高信頼一致仕様に更新し、`assembleDebug`, `testDebugUnitTest` とも成功
  - 成果物: ルート APK `android_pass_manager-debug.apk` を更新（`LastWriteTime=2026-03-09 09:31:52`）
  - 実機反映: `adb install -r` 成功、`lastUpdateTime=2026-03-09 09:32:09`
  - 端末設定: `autofill_service` は `com.securevault.app/com.securevault.app.service.autofill.SecureVaultAutofillService` のまま維持を確認
  - スクリーンショット: `docs/screenshots/securevault_autofill_targeted_20260309.png` を取得

- 自動入力候補が表示されない根本原因を修正
  - 調査結果: 端末側では SecureVault の Autofill Service は有効だったが、`dumpsys autofill` 上で `mResponses: null` となっており、保存済み資格情報の照合失敗で候補 0 件になりやすい状態を確認
  - 改修: `AutofillCredentialMatcher.kt` を追加し、`packageName` / URL / ドメイン / サービス名断片を使った優先順位付き照合へ変更
  - 改修: `SecureVaultAutofillService.kt` の `resolveCredentials()` に完全一致失敗時のフォールバック候補返却を追加
  - 改修: `SmartFieldDetector.kt` に日本語のログインID / メール / パスワード / OTP キーワードを追加
  - 改修: `SettingsScreen.kt`, `strings.xml` に Chromium 系ブラウザ向け Autofill 設定案内を追加
  - 目的: 手入力保存データで `packageName` や URL が欠落していても候補表示を成立させ、日本語UIの入力欄でも検出率を改善
  - 検証: `./gradlew.bat --no-daemon :app:assembleDebug :app:testDebugUnitTest --console=plain` 成功
  - エラーチェック: `get_errors` で変更対象ファイルのエラー 0 件
  - 成果物: ルートへ `android_pass_manager-debug.apk` を出力（`LastWriteTime=2026-03-09 09:08:40`）
  - 実機反映: 署名不一致のため既存アプリを一度アンインストール後、`adb install` で再インストール成功
  - 端末反映: `lastUpdateTime=2026-03-09 09:10:12`
  - 端末設定: 再インストールで Autofill 既定サービスが Google 側へ戻ったため、`settings put secure autofill_service` で SecureVault を再選択
  - スクリーンショット: `docs/screenshots/securevault_autofill_fix_20260309.png` を取得

- 指示書10対応: Autofill / Credential Provider の候補表示不具合を修正
  - 改修: `KeyPassCredentialProviderService.kt`
    - 空ユーザー名 credential をスキップするガードを追加
    - `PasswordCredentialEntry` 生成を個別 try-catch 化し、1件の失敗で全体が失敗しないように修正
  - 改修: `SecureVaultAutofillService.kt`
    - `onFillRequest` を `runBlocking(Dispatchers.IO)` ベースの同期処理へ置換
    - `serviceScope.launch` / `CancellationSignal` 連動キャンセル / デバッグ用Toast を削除
    - `buildFillResponse` の username/password presentation を `R.layout.autofill_suggestion_item` へ変更
  - 目的: Brave 等の短時間連続リクエストでも callback 未達を防止し、候補表示を安定化

- Autofill 候補UI表示検証のため `buildFillResponse` を公式サンプル準拠へ修正
  - 改修: `SecureVaultAutofillService.kt`
    - `credentials.forEachIndexed` を `Dataset.Builder()` + 3引数 `setValue(AutofillId, AutofillValue, RemoteViews)` へ全面置換
    - presentation を `android.R.layout.simple_list_item_1` / `android.R.id.text1` に統一（username/password）
    - 認証ゲート（`setAuthentication` / `PendingIntent`）を一時無効化し、直接値入力へ変更
    - OTP Dataset も同様に no-auth + 3引数 `setValue` パターンへ変更
  - 期待効果: OS による認証付き Dataset の表示抑制要因を切り分け、まずドロップダウン候補の可視化を優先

- Android 15 / Galaxy S25 向け Autofill Inline 診断強化を実施
  - 改修: `SecureVaultAutofillService.kt`
    - `buildFillResponse` 冒頭に inlineRequest/specsCount/maxSuggestionCount ログを追加
    - `buildFillResponse` の dataset 構築で inline set/skip ログを追加
    - `createInlinePresentation` を全面更新（`PendingIntent.FLAG_MUTABLE or FLAG_UPDATE_CURRENT`、spec 診断ログ）
  - ビルド: `.\gradlew.bat :app:assembleDebug` 成功
  - 実機反映: `.\install_debug.bat` 実行成功
  - 端末反映: `lastUpdateTime=2026-03-08 13:26:48`
  - テスト: 未実施（今回依頼はビルド + インストール確認まで）

- Phase 7 残タスク（バグ修正・テスト補強・docs更新・最終検証）を完了
  - 改修: `SecureVaultApplication.kt`（`System.loadLibrary("sqlcipher")` を `onCreate` 先頭へ追加）
  - 改修: `AndroidManifest.xml`（`tools` namespace 追加 + `INTERNET` 権限の明示除去）
  - 改修: `BackupManagerTest.kt`（`packageName` 非含有の検証を追加）
  - 改修: `CsvImportParserTest.kt`（Firefox/1Password/KeePass/SecureVault CSV ケースを補強）
  - 改修: `specification.md`, `status.md`（Phase 1〜6 完了状態を反映）
  - 検証: `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease` 成功
  - テスト集計: 35 件中 35 成功 / 0 失敗 / 0 スキップ
  - 権限検証: `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk` に `android.permission.INTERNET` なし

- Phase 6 指示更新（他サービスCSVインポート対応）を実装
  - 追加: `data/backup/ImportSource.kt`, `CsvImportParser.kt`
  - 改修: `BackupManager.kt`（`importFromService` 追加、CSVパーサー共通化）
  - 改修: `BackupViewModel.kt`（`importFromService` 呼び出し追加）
  - 改修: `BackupScreen.kt`（サービス選択ダイアログ、他サービスCSV導線、重複戦略適用）
  - 改修: `strings.xml`（他サービスインポート文言、無効フォーマット文言追加）
  - 追加テスト: `CsvImportParserTest.kt`
  - 改修: `PasswordStrengthChecker.kt`（ブラックリスト判定を完全一致化）
  - 改修: `PasswordStrengthCheckerTest.kt`（`password -> WEAK` 等の新期待値へ調整）
- 指示更新反映後の再検証
  - ビルド/テスト: `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest` 成功
  - Release ビルド: `./gradlew :app:assembleRelease` 成功
  - エラーチェック: `get_errors` でエラー 0 件
- Phase 6（Export/Import・テスト・最終調整）を実装
  - 追加: `data/backup/BackupCredential.kt`, `BackupCrypto.kt`, `BackupManager.kt`
  - 追加: `ui/screen/backup/BackupScreen.kt`, `BackupViewModel.kt`
  - 改修: `NavRoutes.kt`, `NavGraph.kt`, `SettingsScreen.kt`（バックアップ導線追加）
  - 改修: `strings.xml`（バックアップ関連文言を追加）
  - 改修: `Theme.kt`（Material 3 dynamic color 対応）
  - 改修: `proguard-rules.pro`（SQLCipher/Tink/Room/Serialization/Hilt keep 追加）
  - 追加テスト: `BackupCryptoTest.kt`, `BackupManagerTest.kt`, `PasswordStrengthCheckerTest.kt`
  - 改修: `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`（serialization + release最適化 + test依存）
- Phase 6 実装後の検証
  - ビルド: `./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest` 成功
  - ビルド: `./gradlew :app:assembleRelease` 成功（`NullSafeMutableLiveData` lint を disable して lint analyzer クラッシュを回避）
  - エラーチェック: `get_errors` でエラー 0 件
  - 実機反映: `install_debug.bat` 実行成功
  - 端末反映: `lastUpdateTime=2026-03-06 16:15:30`
  - UI確認: `docs/screenshots/securevault_phase6_current.png` を追加（認証画面まで確認）
- Phase 5（日本語化 + OTP 自動入力）を実装
  - `res/values/strings.xml` を指定内容で全面日本語化（英語UI文言を除去）
  - 新規追加: `SmsOtpManager`, `SmsOtpReceiver`, `ClipboardOtpDetector`, `OtpManager`, `OtpModule`
  - 改修: `OtpNotificationListener`（本実装 + Hilt 連携 + Android 15 制約コメント）
  - 改修: `SmsOtpActivity`（SMS Code Retriever 起動、Broadcast 受信、OTP Dataset 返却）
  - 改修: `SecureVaultAutofillService`（OTP フィールド検出時の SMS リスナー開始、OTP Dataset 追加）
  - 改修: `SettingsViewModel` / `SettingsScreen`（OTP 3スイッチ + DataStore 保存）
  - 改修: `AndroidManifest.xml`（`SmsOtpReceiver` 追加）
- Phase 5 実装後の検証
  - ビルド: `./gradlew --no-daemon :app:assembleDebug` 成功
  - テスト: `./gradlew --no-daemon :app:testDebugUnitTest` 成功
  - エラーチェック: `get_errors` でエラー 0 件
  - 実機反映: `install_debug.bat` 実行成功
  - 端末反映: `lastUpdateTime=2026-03-06 15:51:02`
  - スクリーンショット: `docs/screenshots/securevault_phase5_auth.png` を追加
- Biometric 認証フローを Cipher 非依存へ修正
  - `BiometricAuthManager.authenticate()` の `onSuccess` を `() -> Unit` 化
  - `onAuthenticationSucceeded` で Cipher 取得処理を削除し、認証成功のみを通知
  - `prompt.authenticate(promptInfo)` へ統一し、CryptoObject の利用を廃止
  - `BiometricModule` を新コンストラクタ定義へ更新
- インストール運用を補助する BAT を追加
  - 追加: `install_debug.bat`
  - 実行内容: 端末確認 -> `:app:assembleDebug` -> `adb install -r` -> 起動確認 -> `lastUpdateTime` 表示
- Biometric 修正後の再検証
  - ビルド: `./gradlew --no-daemon :app:assembleDebug` 成功
  - テスト: `./gradlew --no-daemon :app:testDebugUnitTest` 成功
  - `install_debug.bat` 実行成功
  - 端末反映: `lastUpdateTime=2026-03-06 15:17:11`
- Phase 4 実装を継続
  - 追加: `SmartFieldDetector`
    - `autofillHints` / HTML属性 / idEntry・hint文言 / InputType を組み合わせた3段階検出
    - `usernameId`, `passwordId`, `otpId`, `webDomain`, `allAutofillIds` を返却
  - 改修: `SecureVaultAutofillService`
    - `com.securevault.app` への自己Autofillを除外
    - Dataset 表示を `autofill_suggestion_item.xml` に統一
    - 各 Dataset に `PendingIntent` 認証ゲートを追加（`AutofillAuthActivity` 起動）
    - `SaveInfo` を付与（`FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE`）
    - `onSaveRequest` を実装し、`CredentialRepository.save()` で保存
    - 保存成功時に通知表示（channel: `autofill_save`）
  - 改修: `AutofillAuthActivity`
    - `FragmentActivity` + `@AndroidEntryPoint` 化
    - `BiometricAuthManager.authenticate()` 連携
    - 認証成功時に `Dataset` を `EXTRA_AUTHENTICATION_RESULT` で返却
  - 更新: `AndroidManifest.xml`
    - `POST_NOTIFICATIONS` 権限を追加
    - `AutofillAuthActivity` テーマを `Theme.SecureVault.Transparent` へ変更
  - 更新: `res/values/themes.xml`
    - `Theme.SecureVault.Transparent` を追加
  - 更新: `res/xml/autofill_service_config.xml`
    - `maxLongVersionCode` 追加
    - 対応ブラウザを拡張（Chrome dev, Firefox beta, Samsung Internet, Opera, Vivaldi, DuckDuckGo など）
- アプリアイコンを `icon.png` に差し替え
  - 追加: `res/drawable-nodpi/ic_launcher_foreground_image.png`
  - 更新: `res/drawable/ic_launcher_foreground.xml`（bitmap + inset 参照へ変更）
- 画面確認用スクリーンショットを追加
  - `docs/screenshots/securevault_phase4_latest.png`
- 次ステップ候補 1-3 をすべて実施
  - Phase 1 残タスク完了
    - `SettingsScreen` に自動ロック秒数設定UIを追加（即時/30秒/1分/5分/無効）
    - `AuthScreen` / `AuthViewModel` を改善し、認証失敗時のリトライ導線と詳細可用性メッセージを追加
  - Phase 3 コアUI実装
    - 追加: `HomeViewModel`, `AddEditViewModel`, `DetailViewModel`, `PasswordGeneratorViewModel`, `SettingsViewModel`
    - 追加: `PasswordStrengthChecker`, `ClipboardSettingsManager`
    - 追加: 共通UI `ConfirmDialog`, `EmptyState`, `PasswordStrengthBar`
    - 置換: `HomeScreen`, `AddEditScreen`, `DetailScreen`, `PasswordGeneratorScreen`, `SettingsScreen`
    - 更新: `NavGraph`（生成画面から Add/Edit へのパスワード受け渡し、画面シグネチャ同期）
  - Autofill 導線追加
    - `SecureVaultAutofillService` を Repository 連携へ更新（`findByPackageName` / `findByUrl` / 全件フォールバック）
    - FillRequest で Dataset 生成を実装
- Phase 1 の主要実装を追加
  - `MasterKeyManager`（Keystore + StrongBox優先 + 再作成処理）
  - `CryptoEngine`（Cipher初期化、暗号化/復号、Base64変換）
  - `BiometricAuthManager`（生体認証優先、端末認証フォールバック、StateFlow公開）
  - `AutoLockManager`（ProcessLifecycleOwner + DataStore 連動自動ロック）
  - `AuthViewModel` / `NavGuardViewModel` を追加
  - `AuthScreen` を本認証フローへ接続
  - `NavGraph` にロック時の `Auth` 退避導線を追加
  - `SecureVaultApplication` で自動ロック監視を起動
  - `MainActivity` を `FragmentActivity` ベースへ更新
  - `CryptoModule` / `BiometricModule` を追加
- ユニットテスト追加
  - `CryptoEngineTest`（暗号化→復号の往復検証）
- ビルド/テスト実行
  - `:app:assembleDebug :app:testDebugUnitTest` 成功
- 実機更新
  - `adb shell am force-stop com.securevault.app`
  - `adb install -r app/build/outputs/apk/debug/app-debug.apk` 成功
  - `adb shell monkey -p com.securevault.app ...` 起動確認
  - `lastUpdateTime=2026-03-06 11:23:42`
- 作業終了時の再更新トライ
  - `adb` で `no devices/emulators found` を検出
  - 30秒待機後に再確認しても端末未検出
  - 原因候補: USB切断/デバッグ許可解除/端末側の接続状態変化
- 一時データの保存先を `D:\temp` に固定
  - `gradlew.bat` に `TEMP/TMP/GRADLE_USER_HOME` の固定化を追加
  - `gradlew`（MSYS/Cygwin）にも同等設定を追加
  - `gradle.properties` に `-Djava.io.tmpdir=D:/temp` を追加
  - メモリ安定化のため `org.gradle.jvmargs` を `-Xmx2048m` へ調整
  - `D:\temp\.gradle-user-home` への生成を確認
  - `--no-daemon :app:assembleDebug` 成功を確認
- 端末再接続後に最終 `adb` 更新を再実施
  - `adb devices` で端末 `RFCY2094T0V` を再認識
  - `adb shell am force-stop com.securevault.app`
  - `adb install -r app/build/outputs/apk/debug/app-debug.apk` 成功
  - `adb shell monkey -p com.securevault.app ...` 起動確認
  - `lastUpdateTime=2026-03-06 12:19:00`
- Phase 2（DB + Repository）実装
  - 追加: `CredentialEntity`, `CredentialDao`, `SecureVaultDatabase`
  - 追加: `DbKeyManager`（DataStore保存シード + HKDF導出）
  - 追加: `Credential` ドメインモデル
  - 追加: `CredentialRepository` / `CredentialRepositoryImpl`
  - 追加: `DatabaseModule`（Room + SQLCipher + Repository 提供）
  - 補完: `AuthViewModel` を復元（`AuthScreen` 参照解決）
  - ビルド: `./gradlew --no-daemon :app:assembleDebug` 成功
  - テスト: `./gradlew --no-daemon :app:testDebugUnitTest` 成功
  - ADB更新: `lastUpdateTime=2026-03-06 13:08:53`
- 今回のビルド/テスト/ADB更新
  - ビルド: `./gradlew --no-daemon :app:assembleDebug` 成功
  - テスト: `./gradlew --no-daemon :app:testDebugUnitTest` 成功
  - ADB更新: `adb install -r app/build/outputs/apk/debug/app-debug.apk` 成功
  - 起動確認: `adb shell monkey -p com.securevault.app ...` 成功
  - 端末反映: `lastUpdateTime=2026-03-06 14:08:27`
- Phase 4 継続実装後の再検証
  - ビルド: `./gradlew --no-daemon :app:assembleDebug` 成功
  - テスト: `./gradlew --no-daemon :app:testDebugUnitTest` 成功
  - ADB更新: `adb install -r app/build/outputs/apk/debug/app-debug.apk` 成功
  - 起動確認: `adb shell monkey -p com.securevault.app ...` 成功
  - 端末反映: `lastUpdateTime=2026-03-06 14:46:07`

## 進行中の作業
- Phase 6 の実機QA（バックアップ/復元導線と実ファイルI/Oの端末検証）

## 検出した課題
- VS Code 側の `get_errors` は旧 AGP (`9.0.1`) キャッシュ参照が残る
- Cドライブ空き不足により依存変換タスクが失敗
- `daemon` 実行時にネイティブメモリ不足でクラッシュする場合がある（`--no-daemon` では成功）
- いくつかの Compose/Autofill API で deprecation 警告あり（現時点はビルド成功を優先して維持）
- `lintVitalAnalyzeRelease` が `NullSafeMutableLiveData` で内部クラッシュするケースがあり、当面は lint 無効化で回避
- Samsung Pass は標準CSVエクスポート導線がないため、直接インポート対象外

## 対応方針
- ビルド時は `GRADLE_USER_HOME` と `TEMP` を D ドライブへ退避して継続
- `gradlew` 経由の実ビルド結果を正として運用
- 以降も作業終了時に `adb force-stop + install -r` を継続
- 一時データ保存先は継続して `D:\temp` を標準運用

## 次の実施項目
1. 実機で `設定 -> バックアップ・復元` から、Brave/Bitwarden CSV を使った他サービスインポートの手動E2Eを確認
2. 暗号化バックアップ復元・SecureVault CSV復元・重複戦略（スキップ/上書き/すべて追加）を端末上で確認
3. 実機検証スクリーンショットを追加し、結果を `docs/specification.md` / `docs/status.md` に反映
