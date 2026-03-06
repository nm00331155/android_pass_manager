Phase 2 — DB + リポジトリ（リポジトリ現状に合わせて調整済み）
# Phase 2: Room + SQLCipher データベースとリポジトリ層の実装

## 前提
このプロジェクトは以下が実装済みです。変更しないでください:
- パッケージ: com.securevault.app
- MasterKeyManager（data/crypto/MasterKeyManager.kt）— Keystore管理、完成済み
- CryptoEngine（data/crypto/CryptoEngine.kt）— AES-GCM暗号化/復号、Base64変換、完成済み
- BiometricAuthManager（biometric/BiometricAuthManager.kt）— 生体認証フロー、完成済み
- AutoLockManager（util/AutoLockManager.kt）— 自動ロック、完成済み
- AuthScreen / AuthViewModel — 認証画面、完成済み
- NavGraph / NavGuardViewModel / NavRoutes — 画面遷移、完成済み
- di/CryptoModule.kt, di/BiometricModule.kt — DI、完成済み
- build.gradle.kts — Room, SQLCipher, Tink, Hilt 等の依存関係設定済み
- libs.versions.toml — room = "2.7.0", sqlcipher = "4.6.1", tink = "1.19.0"
- AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2025.12.00

## 既存の CryptoEngine API（変更しないこと）
```kotlin
// CryptoEngine の公開API（既存、参照のみ）
fun getCipherForEncryption(): Cipher
fun getCipherForDecryption(iv: ByteArray): Cipher
fun encrypt(plainText: String, cipher: Cipher): EncryptedData
fun decrypt(encryptedData: EncryptedData, cipher: Cipher): String

// companion object
fun toBase64(bytes: ByteArray): String
fun fromBase64(value: String): ByteArray

data class EncryptedData(val cipherText: ByteArray, val iv: ByteArray)
作成するファイル一覧
1. data/db/entity/CredentialEntity.kt
Copy@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serviceName: String,           // 平文（検索用）
    val serviceUrl: String?,           // 平文
    val packageName: String?,          // 平文（Autofill マッチング用）
    val encryptedUsername: String,      // Base64(暗号文)
    val usernameIv: String,            // Base64(IV)
    val encryptedPassword: String,     // Base64(暗号文)
    val passwordIv: String,            // Base64(IV)
    val encryptedNotes: String?,       // Base64(暗号文) nullable
    val notesIv: String?,              // Base64(IV) nullable
    val category: String,              // "login" | "finance" | "social" | "other"
    val createdAt: Long,
    val updatedAt: Long,
    val isFavorite: Boolean = false
)
2. data/db/dao/CredentialDao.kt
以下のメソッドをすべて実装:

getAll(): Flow<List> — serviceNameのASCソート
getById(id: Long): Flow<CredentialEntity?>
searchByServiceName(query: String): Flow<List> — LIKE '%query%'
findByPackageName(packageName: String): List — suspend
findByUrl(url: String): List — suspend, LIKE '%url%'
getByCategory(category: String): Flow<List>
getFavorites(): Flow<List>
insert(credential: CredentialEntity): Long
update(credential: CredentialEntity)
delete(credential: CredentialEntity)
deleteById(id: Long)
deleteAll() — suspend
getCount(): Flow
3. data/db/SecureVaultDatabase.kt
@Database(entities = [CredentialEntity::class], version = 1, exportSchema = true)
abstract fun credentialDao(): CredentialDao
SQLCipher で暗号化する
DB暗号化のパスフレーズは DbKeyManager から取得
4. data/crypto/DbKeyManager.kt
DB暗号化用のパスフレーズを導出するクラス:

Tink の HKDF (com.google.crypto.tink.subtle.Hkdf) を使用
MasterKeyManager から Keystore の SecretKey を取得
SecretKey から exportKey() はできないので、代替戦略として:
初回起動時に SecureRandom で 32バイトのランダムシードを生成
このシードを CryptoEngine で暗号化（Keystore のマスターキーで保護）して DataStore に保存
使用時: DataStore から暗号化シードを読み取り → CryptoEngine で復号 → HKDF でDBパスフレーズを導出
これにより、生体認証を経なければDBパスフレーズを取得できない設計になる
ただしPhase 2ではまず簡易実装として、アプリ起動時にランダム生成したパスフレーズを暗号化してDataStoreに保存する方式とする
getOrCreatePassphrase(cipher: Cipher): CharArray メソッドを提供
パスフレーズは使用後に CharArray.fill('\u0000') でクリア推奨
5. data/repository/model/Credential.kt
UIで扱う平文のドメインモデル:

Copydata class Credential(
    val id: Long = 0,
    val serviceName: String,
    val serviceUrl: String? = null,
    val packageName: String? = null,
    val username: String,        // 平文
    val password: String,        // 平文
    val notes: String? = null,   // 平文
    val category: String = "other",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)
6. data/repository/CredentialRepository.kt（interface）
Copyinterface CredentialRepository {
    fun getAll(): Flow<List<Credential>>
    fun getById(id: Long): Flow<Credential?>
    fun search(query: String): Flow<List<Credential>>
    fun getByCategory(category: String): Flow<List<Credential>>
    fun getFavorites(): Flow<List<Credential>>
    fun getCount(): Flow<Int>
    suspend fun findByPackageName(packageName: String): List<Credential>
    suspend fun findByUrl(url: String): List<Credential>
    suspend fun save(credential: Credential)
    suspend fun delete(id: Long)
    suspend fun deleteAll()
    suspend fun toggleFavorite(id: Long)
}
7. data/repository/CredentialRepositoryImpl.kt
CredentialRepository の実装
注入: CredentialDao, CryptoEngine
保存時の処理:
CryptoEngine.getCipherForEncryption() で Cipher 取得
username, password, notes をそれぞれ encrypt()
Base64エンコードして CredentialEntity に変換
DAOで insert / update（id == 0 なら insert、0以外なら update）
取得時の処理:
DAOから CredentialEntity の Flow を取得
各エンティティの暗号化フィールドを fromBase64 → getCipherForDecryption(iv) → decrypt
Credential ドメインモデルに変換
復号失敗時（KeyPermanentlyInvalidatedException等）は空のCredentialを返すかログ出力して skip
toggleFavorite: getById → isFavorite 反転 → update
8. di/DatabaseModule.kt
Copy@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        dbKeyManager: DbKeyManager
    ): SecureVaultDatabase

    @Provides
    fun provideCredentialDao(db: SecureVaultDatabase): CredentialDao

    @Provides
    @Singleton
    fun provideCredentialRepository(
        dao: CredentialDao,
        cryptoEngine: CryptoEngine
    ): CredentialRepository = CredentialRepositoryImpl(dao, cryptoEngine)
}
重要な注意事項
既存ファイル（MasterKeyManager, CryptoEngine, BiometricAuthManager 等）は一切変更しない
既存の di/CryptoModule.kt, di/BiometricModule.kt も変更しない
CryptoEngine の getCipherForEncryption() は Keystore キーを使うため、 端末認証（生体/PIN）済みの状態でないと KeyPermanentlyInvalidatedException が発生する → Repository の暗号化/復号では try-catch で適切にハンドリング
Room の @Query 文字列は改行せずに1行で書く
KDoc コメントをすべてのクラス・publicメソッドに記述
ファイル先頭に package 宣言を正しく書く
全ファイルをフルで出力してください。プレースホルダーやTODOは残さないでください。
vs