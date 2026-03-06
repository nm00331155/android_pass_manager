発見したバグ・修正が必要な箇所
バグ1: SQLCipher ネイティブライブラリ未ロード（致命的）
ファイル: SecureVaultApplication.kt

原因: net.zetetic:sqlcipher-android:4.6.1（新ライブラリ）は、アプリ側で明示的にネイティブライブラリをロードする必要がある。旧ライブラリ（android-database-sqlcipher）は自動ロードだったが、新ライブラリではこれが廃止された。

修正:

Copy@HiltAndroidApp
class SecureVaultApplication : Application() {

    @Inject
    lateinit var autoLockManager: AutoLockManager

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        autoLockManager.start()
    }
}
System.loadLibrary("sqlcipher") を autoLockManager.start() より前に追加。これが画面に表示されていた赤文字エラーの直接原因です。

バグ2: Kotlinx Serialization の非推奨API使用
ファイル: BackupManager.kt

問題: Json.decodeFromString の呼び出しで型パラメータを明示的に指定しているが、kotlinx.serialization.decodeFromString はインライン関数のため import kotlinx.serialization.decodeFromString が必要。現在の import は kotlinx.serialization.json.Json のみ。

コンパイルは通る可能性がありますが、もし decodeFromString の import で警告やエラーが出ている場合は以下を確認：

Copy// import 行にこれがあるか確認
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
バグ3: play-services 依存が不要（潜在的問題）
ファイル: app/build.gradle.kts

問題:

Copyimplementation(libs.play.services.auth)
implementation(libs.play.services.auth.api.phone)
完全オフラインアプリなのに Google Play Services の依存が入っています。これ自体はビルドエラーにはなりませんが、以下の懸念があります：

INTERNET 権限が Google Play Services 経由で暗黙的に追加される可能性がある
APK サイズが不必要に増加する
有償販売時に「完全オフライン」の主張と矛盾する
OTP の SMS 自動読み取り機能で使用していると思われますが、AndroidManifest.xml の最終ビルド成果物で INTERNET 権限が混入していないか確認が必要です。

確認方法:

Copy./gradlew :app:assembleDebug
aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
もし android.permission.INTERNET が出力に含まれていたら、AndroidManifest.xml に以下を追加して明示的に除去：

Copy<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
未実装: テストファイル（Part D）
以下のテストファイルがリポジトリに見当たりません：

test/.../BackupCryptoTest.kt
test/.../BackupManagerTest.kt
test/.../CsvImportParserTest.kt
test/.../PasswordStrengthCheckerTest.kt
未実装: docs 最終更新（Part E-5）
docs/specification.md — Phase 6 完了の反映
docs/status.md — 全フェーズ完了の記載
修正指示書（Copilot に渡す用）
以下をそのまま Copilot に渡してください：

# Phase 6 バグ修正 + テスト追加 + docs 更新

## 前提
- docs/specification.md と docs/status.md を必ず読んでから作業すること
- 既存 API（CredentialRepository, CryptoEngine, BiometricAuthManager 等）は変更しない
- パッケージ: com.securevault.app
- ビルド確認: ./gradlew --no-daemon :app:assembleDebug が成功すること

---

## 修正1: SQLCipher ネイティブライブラリのロード（致命的バグ）

ファイル: app/src/main/java/com/securevault/app/SecureVaultApplication.kt

onCreate() の先頭（autoLockManager.start() より前）に以下を追加:

    System.loadLibrary("sqlcipher")

これがないと net.zetetic:sqlcipher-android:4.6.1 のネイティブ関数が見つからず、
データベースアクセス時に UnsatisfiedLinkError が発生する。

---

## 修正2: INTERNET 権限の暗黙追加を防止

ファイル: app/src/main/AndroidManifest.xml

<manifest> 直下に以下を追加（既存の uses-permission があればその近くに）:

    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />

xmlns:tools がなければ <manifest> タグに追加:

    xmlns:tools="http://schemas.android.com/tools"

play-services-auth が暗黙的に INTERNET 権限を要求する可能性があるため、
明示的に除去してオフラインアプリの保証を維持する。

修正後に以下のコマンドで INTERNET 権限が含まれていないことを確認:

    ./gradlew :app:assembleDebug
    aapt dump permissions app/build/outputs/apk/debug/app-debug.apk

出力に android.permission.INTERNET が含まれていなければ OK。

---

## 修正3: テストファイル新規作成

以下の4ファイルを app/src/test/java/com/securevault/app/ 配下に新規作成する。

### 3-1. data/backup/BackupCryptoTest.kt

テスト内容:
- パスワードから鍵導出 → 暗号化 → 復号の往復テスト（正しいパスワードで元のデータに戻ること）
- 異なるパスワードで復号を試みて javax.crypto.AEADBadTagException が発生すること
- generateSalt() が毎回異なるバイト列を返すこと（3回生成して全て異なること）
- 空データ（ByteArray(0)）の暗号化→復号が成功すること
- 同一パスワード＋同一ソルトから deriveKey() が同一の鍵を導出すること
- ソルト長が32バイトであること

### 3-2. data/backup/BackupManagerTest.kt

テスト内容:
- Credential → BackupCredential 変換で全フィールドが保持されること
  （Credential の packageName は BackupCredential に含まれないことも確認）
- BackupCredential → Credential 変換で id が 0 になること
- BackupCredential → Credential 変換で packageName が null になること
- 往復変換（Credential → BackupCredential → Credential）でデータ保持確認（id, packageName を除く）
- BackupCredential リストの JSON シリアライズ → デシリアライズ往復テスト
- デフォルト値を持つ BackupCredential のシリアライズが正しく動作すること
- 不明なフィールドを含む JSON からのデシリアライズが成功すること（ignoreUnknownKeys）

テスト用の Credential は以下のモデルを import:
    com.securevault.app.data.repository.model.Credential

JSON は以下の設定で:
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

### 3-3. data/backup/CsvImportParserTest.kt

CsvImportParser のインスタンスを直接生成してテストする:
    private val parser = CsvImportParser()

テスト内容:

Brave 形式:
    name,url,username,password,note
    GitHub,https://github.com,user1,pass1,メモ
→ serviceName="GitHub", serviceUrl="https://github.com", username="user1", password="pass1", notes="メモ"

Bitwarden 形式:
    folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp
    ,1,login,GitHub,,,,https://github.com,user1,pass1,
→ serviceName="GitHub", serviceUrl="https://github.com", username="user1", password="pass1"

LastPass 形式:
    url,username,password,totp,extra,name,grouping,fav
    https://github.com,user1,pass1,,メモ,GitHub,,0
→ serviceName="GitHub", serviceUrl="https://github.com", username="user1", password="pass1", notes="メモ"

Firefox 形式:
    url,username,password,httpRealm,formActionOrigin,guid,timeCreated,timeLastUsed,timePasswordChanged
    https://github.com/login,user1,pass1,,https://github.com,{uuid},1700000000000,1700001000000,1700001000000
→ serviceName="github.com"（URLからホスト名抽出）, serviceUrl="https://github.com/login", username="user1", password="pass1"

1Password 形式:
    Title,Website,Username,Password,OTPAuth,Favorite,Archived,Tags,Notes
    GitHub,https://github.com,user1,pass1,,,,,Important
→ serviceName="GitHub", serviceUrl="https://github.com", username="user1", password="pass1", notes="Important"

Apple パスワード形式:
    Title,URL,Username,Password,Notes,OTPAuth
    GitHub,https://github.com,user1,pass1,メモ,
→ 正しく変換されること

KeePass 形式:
    Title,Username,Password,URL,Notes
    GitHub,user1,pass1,https://github.com,メモ
→ 正しく変換されること（KeePass は "User Name" のスペースあり表記もテスト）

KeePass "User Name" 表記:
    Title,User Name,Password,URL,Notes
    GitHub,user1,pass1,https://github.com,メモ
→ normalizeColumnName でスペースが除去され正しくマッピングされること

Dashlane 形式:
    title,url,username,password,note,otpSecret,category
    GitHub,https://github.com,user1,pass1,メモ,,development
→ serviceName="GitHub", notes="メモ"

ヘッダーの大文字小文字混在:
    NAME,URL,Username,PASSWORD,Note
    GitHub,https://github.com,user1,pass1,test
→ Brave マッピングで正しくパースされること（case-insensitive）

必須カラム不足（password カラムなし）:
    name,url,username,notes
    GitHub,https://github.com,user1,メモ
→ IllegalArgumentException（CsvImportParser.INVALID_FORMAT_ERROR）がスローされること

空行スキップ:
    name,url,username,password,note
    GitHub,https://github.com,user1,pass1,memo

    Twitter,https://twitter.com,user2,pass2,
→ 空行を除いて2件パースされること

パスワード空欄スキップ:
    name,url,username,password,note
    GitHub,https://github.com,user1,,memo
    Twitter,https://twitter.com,user2,pass2,
→ GitHub はスキップされ、Twitter の1件のみ返されること

ダブルクォート内カンマ:
    name,url,username,password,note
    GitHub,https://github.com,user1,pass1,"memo, with comma"
→ notes="memo, with comma" であること

SecureVault CSV 形式:
    serviceName,serviceUrl,username,password,notes,category
    GitHub,https://github.com,user1,pass1,メモ,login
→ serviceName="GitHub", category は BackupCredential のデフォルト "other" であること
  （CsvImportParser は category をマッピングしないため）

### 3-4. util/PasswordStrengthCheckerTest.kt

PasswordStrengthChecker のクラスとメソッドの実際のシグネチャを確認してからテストを書くこと。
ファイルの場所: app/src/main/java/com/securevault/app/util/PasswordStrengthChecker.kt

テスト内容（クラスのメソッド名に合わせて調整すること）:
- 空文字 → 最も弱い判定
- "1234" → 最も弱い判定
- "password" → 弱い判定
- "Password1" → 中程度の判定
- "P@ssw0rd!2024" → 強い or 非常に強い判定
- 20文字以上の大文字小文字数字記号混在文字列 → 非常に強い判定

---

## 修正4: docs の最終更新

### 4-1. docs/status.md

Phase 6 を完了として記載。以下の内容を反映:

- Phase 1〜6 全完了
- 実装済み機能:
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
- 既知の制限事項:
  - Samsung Pass は独自 .spass 形式のため直接インポート非対応（Google パスワードマネージャー経由を推奨）
  - Android 15+ では通知からの OTP 読取に制限あり
  - ダイナミックカラーは Android 12 (API 31) 以上のみ
  - play-services-auth 依存あり（SMS OTP 用）だが INTERNET 権限は manifest で明示除去

### 4-2. docs/specification.md

Phase 6 セクションを追加。以下を含む:
- バックアップ機能の仕様（暗号化方式、ファイルフォーマット）
- 他サービスインポートの対応一覧と CSV カラムマッピング
- テスト一覧
- ProGuard ルール

---

## 注意事項
- CredentialRepository の API は変更しない（メソッド名は getAll(), save() 等）
- Credential モデルは com.securevault.app.data.repository.model.Credential を使用
- Credential には packageName フィールドがある（BackupCredential にはない）
- @Query アノテーション内の SQL は改行せず1行で書く
- すべての public クラス/メソッドに KDoc を付与する
- import はワイルドカード不可

## 最終チェックリスト
- [ ] ./gradlew --no-daemon :app:assembleDebug 成功
- [ ] ./gradlew --no-daemon :app:testDebugUnitTest 成功
- [ ] ./gradlew --no-daemon :app:assembleRelease 成功
- [ ] aapt dump permissions でINTERNET権限が存在しないこと
- [ ] strings.xml が全て日本語であること
- [ ] docs/specification.md と docs/status.md が最新であること