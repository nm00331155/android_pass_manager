Phase 3: コアUI画面のDB接続と詳細実装
前提
Phase 2 で以下が追加されています:

CredentialEntity / CredentialDao / SecureVaultDatabase（Room + SQLCipher）
Credential ドメインモデル（平文）
CredentialRepository（interface） / CredentialRepositoryImpl
DbKeyManager / DatabaseModule
Phase 0-1 の以下は変更しないでください:

NavRoutes.kt（Auth, Home, Settings, Generator, AddEditPattern, DetailPattern）
NavGraph.kt の構造（Auth→Home→各画面の遷移）
AuthScreen / AuthViewModel
MasterKeyManager / CryptoEngine / BiometricAuthManager / AutoLockManager
AndroidManifest.xml
build.gradle.kts / libs.versions.toml
作成・置換するファイル一覧
既存のスタブ画面を「置換」します。新規ファイルは「新規」と明記。

1. 【置換】ui/screen/home/HomeViewModel.kt（新規）
@HiltViewModel、以下の状態を管理:

credentials: StateFlow<List> — Repository.getAll() を collect
searchQuery: StateFlow — リアルタイムフィルタ
selectedCategory: StateFlow<String?> — null=全て
showFavoritesOnly: StateFlow
credentialCount: StateFlow
uiState: StateFlow — Loading / Success / Empty / Error
フィルタリングロジック:

searchQuery, selectedCategory, showFavoritesOnly を combine して credentials をフィルタ → filteredCredentials: StateFlow<List> として公開
メソッド:

updateSearchQuery(query: String)
updateCategoryFilter(category: String?)
toggleFavoritesOnly()
deleteCredential(id: Long) — 確認後に Repository.delete()
toggleFavorite(id: Long) — Repository.toggleFavorite()
2. 【置換】ui/screen/home/HomeScreen.kt
既存のスタブを完全に置換。以下のUI:

TopAppBar: "SecureVault" タイトル + 検索アイコン(IconButton) + 設定アイコン(IconButton)
検索: SearchBar（Material3, DockedSearchBar）展開式
カテゴリフィルタ: 横スクロール FilterChip 行
"すべて", "ログイン", "金融", "SNS", "その他"
対応する category 値: null, "login", "finance", "social", "other"
お気に入りフィルタ: FilterChip "★ お気に入り"
件数表示: "N 件のパスワード" テキスト
LazyColumn で一覧表示
各アイテム: CredentialListItem composable
Row: カテゴリアイコン + Column(serviceName太字, username小さめ灰色) + お気に入り星
タップ → onDetailClick(credential.id)
SwipeToDismiss:
左スワイプ → 削除（赤背景 + ゴミ箱アイコン、ConfirmDialog 表示後に実行）
右スワイプ → お気に入りトグル（黄色背景 + 星アイコン）
FAB: Material3 FloatingActionButton "+" → onAddClick
空状態: credentials が空の時、中央に EmptyState composable
アイコン（Icons.Outlined.Lock）+ "パスワードを追加しましょう" + "+" ボタン
ローディング: CircularProgressIndicator
onAddClick, onSettingsClick, onGeneratorClick, onDetailClick のコールバックは既存NavGraphと一致させる
3. 【置換】ui/screen/addedit/AddEditViewModel.kt（新規）
@HiltViewModel

credentialId: Long を SavedStateHandle から取得
isEditMode: Boolean = credentialId >= 0 かつ credentialId != -1L （NavRoutes.addEdit() のデフォルト値が -1L）
フォーム状態:
serviceName, serviceUrl, username, password, notes: StateFlow
category: StateFlow — デフォルト "other"
isFavorite: StateFlow
passwordVisible: StateFlow
passwordStrength: StateFlow
編集モード時: init で Repository.getById(credentialId) を collect → フォームに反映
バリデーション: isFormValid: StateFlow = serviceName.isNotBlank() && password.isNotBlank()
save(): Repository.save(Credential(...))。id は editMode なら既存id、新規なら 0
updatePassword 時に PasswordStrengthChecker で強度を計算
4. 【置換】ui/screen/addedit/AddEditScreen.kt
既存のスタブを完全に置換:

TopAppBar: "パスワードを追加" / "パスワードを編集"（isEditMode で切替）
navigationIcon: IconButton(Icons.AutoMirrored.Filled.ArrowBack) → onNavigateBack
Column (verticalScroll):
サービス名 OutlinedTextField（必須マーク、バリデーションエラー表示）
URL OutlinedTextField
ユーザー名 OutlinedTextField
パスワード OutlinedTextField:
visualTransformation: passwordVisible で切替
trailingIcon: 目のアイコン（表示/非表示トグル）
supportingText: PasswordStrengthBar composable
「パスワードを生成」TextButton → onGeneratorClick
PasswordGeneratorScreen の結果を受け取る仕組み: NavGraph で SavedStateHandle の "generated_password" キーを監視
メモ OutlinedTextField（minLines = 3）
カテゴリ ExposedDropdownMenuBox
お気に入り Row + Switch
保存 Button（画面下部、enabled = isFormValid）
ViewModel は hiltViewModel() で取得
引数 credentialId は NavGraph から渡される（既存のNavGraph構造を変更しない）
ただし AddEditScreen のシグネチャを変更: credentialId 引数は削除し、ViewModel が SavedStateHandle から直接取得する → NavGraph 側の呼び出しも修正が必要。composable 内で hiltViewModel() するだけに変更
5. 【置換】ui/screen/detail/DetailViewModel.kt（新規）
@HiltViewModel

credentialId: Long を SavedStateHandle から取得
credential: StateFlow<Credential?> — Repository.getById() を collect
passwordVisible: StateFlow
togglePasswordVisibility()
copyToClipboard(context: Context, text: String, label: String):
ClipboardManager にコピー
設定の秒数後にクリア（AutoLockManager or DataStore からタイムアウト取得）
Snackbar用のイベント発行
deleteCredential(): Repository.delete() → 完了後 navigateBack イベント
6. 【置換】ui/screen/detail/DetailScreen.kt
既存のスタブを完全に置換:

TopAppBar: credential.serviceName
actions: IconButton(Icons.Default.Edit) → onEditClick
navigationIcon: 戻るアイコン
Column:
URL行: テキスト + 外部ブラウザで開くアイコン（Intent.ACTION_VIEW、ただしINTERNET権限不要 — ブラウザアプリが処理する）
ユーザー名行: Row(Text + IconButton コピー)
パスワード行: Row(Text(mask/表示切替) + 目アイコン + コピーアイコン)
メモ行（notes != null の場合のみ表示）
Divider
カテゴリ、作成日時、更新日時（フォーマット: yyyy/MM/dd HH:mm）
Spacer
削除ボタン（OutlinedButton, containerColor = Error, 確認ダイアログ付き）
Snackbar: 「コピーしました」表示（SnackbarHostState）
7. 【新規】util/PasswordStrengthChecker.kt
Copyenum class PasswordStrength(val label: String, val score: Int) {
    VERY_WEAK("非常に弱い", 0),
    WEAK("弱い", 25),
    MEDIUM("普通", 50),
    STRONG("強い", 75),
    VERY_STRONG("非常に強い", 100)
}

object PasswordStrengthChecker {
    fun check(password: String): PasswordStrength
}
判定ロジック:

長さスコア: 8未満=0, 8-11=1, 12-15=2, 16+=3
文字種スコア: 小文字=+1, 大文字=+1, 数字=+1, 記号=+1
ペナルティ: 同じ文字3連続=−1, "password","123456","qwerty"等のブラックリスト=0点
合計: 0-1=VERY_WEAK, 2-3=WEAK, 4-5=MEDIUM, 6=STRONG, 7+=VERY_STRONG
8. 【新規】ui/component/PasswordStrengthBar.kt
LinearProgressIndicator でスコアを表現
色: VERY_WEAK=赤, WEAK=オレンジ, MEDIUM=黄, STRONG=ライム, VERY_STRONG=緑
下にラベルテキスト表示
9. 【新規】ui/component/ConfirmDialog.kt
汎用確認ダイアログ:

Copy@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "削除",
    dismissText: String = "キャンセル",
    isDestructive: Boolean = false,  // true なら confirmButton を赤色に
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
)
10. 【新規】ui/component/EmptyState.kt
Copy@Composable
fun EmptyState(
    icon: ImageVector = Icons.Outlined.Lock,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
)
11. 【置換】ui/screen/generator/PasswordGeneratorViewModel.kt（新規）
@HiltViewModel

length: StateFlow = 16
useLowercase: StateFlow = true
useUppercase: StateFlow = true
useDigits: StateFlow = true
useSymbols: StateFlow = true
excludeAmbiguous: StateFlow = false（0,O,l,1,I を除外）
generatedPassword: StateFlow
strength: StateFlow
generate(): SecureRandom を使用して生成 → strength も更新
init で generate() を1回呼ぶ
12. 【置換】ui/screen/generator/PasswordGeneratorScreen.kt
既存のスタブを完全に置換:

生成されたパスワード（大きな MonoSpace フォント、中央）
「再生成」IconButton（Icons.Default.Refresh）
「コピー」IconButton
Slider: パスワード長（8-64）
Switch行: 小文字 / 大文字 / 数字 / 記号
Checkbox: 紛らわしい文字を除外
PasswordStrengthBar
「このパスワードを使う」Button → onNavigateBack 時に結果を previousBackStackEntry に設定
navController.previousBackStackEntry?.savedStateHandle?.set("generated_password", password)
13. 【修正】ui/navigation/NavGraph.kt
最小限の修正のみ:

AddEditScreen の composable 内で、previousBackStackEntry から "generated_password" を監視
AddEditScreen のコンストラクタから credentialId 引数を削除（ViewModel が SavedStateHandle から取得）
PasswordGeneratorScreen のコンストラクタに onUsePassword: (String) -> Unit コールバック追加 → NavGraph 内で savedStateHandle への書き込みを行う
それ以外の遷移ロジックは変更しない
14. 【置換】ui/screen/settings/SettingsScreen.kt
既存のスタブに以下を追加（既存の自動入力設定ボタンは維持）:

「セキュリティ」セクション
自動ロック時間（即時/30秒/1分/5分/無効）— DataStore 読み書き
クリップボード自動クリア時間（15秒/30秒/1分/無効）
「データ管理」セクション
すべてのデータを削除（赤色 Button、ConfirmDialog 2段階確認）
「アプリ情報」セクション
バージョン: BuildConfig.VERSION_NAME
NavGraph 修正の詳細
NavGraph.kt の AddEditScreen composable を以下のように修正:

Copycomposable(
    route = NavRoutes.AddEditPattern,
    arguments = listOf(navArgument("credentialId") { type = NavType.LongType })
) {
    AddEditScreen(
        onNavigateBack = { navController.popBackStack() },
        onGeneratorClick = { navController.navigate(NavRoutes.Generator) }
    )
}
AddEditScreen 側で savedStateHandle["generated_password"] を LaunchedEffect で監視。

strings.xml への追加
既存のものに追加:

home_empty_message = "パスワードを追加しましょう"
category_all = "すべて"
category_login = "ログイン"
category_finance = "金融"
category_social = "SNS"
category_other = "その他"
password_copied = "パスワードをコピーしました"
username_copied = "ユーザー名をコピーしました"
clipboard_auto_clear = "%d秒後に自動クリアされます"
confirm_delete_title = "削除の確認"
confirm_delete_message = "このパスワードを削除しますか？この操作は取り消せません。"
confirm_delete_all_title = "すべてのデータを削除"
confirm_delete_all_message = "すべてのパスワードを削除しますか？この操作は取り消せません。"
confirm_delete_all_final = "本当に削除しますか？この操作は元に戻せません。"
strength_very_weak = "非常に弱い"
strength_weak = "弱い"
strength_medium = "普通"
strength_strong = "強い"
strength_very_strong = "非常に強い"
settings_security = "セキュリティ"
settings_auto_lock = "自動ロック"
settings_clipboard_clear = "クリップボード自動クリア"
settings_data = "データ管理"
settings_delete_all = "すべてのデータを削除"
settings_app_info = "アプリ情報"
settings_version = "バージョン"
重要な注意事項
既存の Phase 0-1 ファイルは明示的に「修正」と書いた箇所以外は変更しない
すべての ViewModel は @HiltViewModel + @Inject constructor
状態管理は StateFlow + sealed interface パターン
Cipher の取得は CryptoEngine 経由。try-catch で KeyPermanentlyInvalidatedException をハンドリング
ファイル先頭の package 宣言を正しく書く
import 文は使用するもののみ記述
KDoc コメントをクラスと public メソッドに記述
全ファイルをフルで出力してください。プレースホルダーやTODOは残さないでください。 既存ファイルの置換対象は、ファイル全体を出力してください。


---

## 使い方

この2つのプロンプトを以下の順序でCopilotに投入してください。

**ステップ 1:** Phase 2 のプロンプトを投入 → 生成されたファイルをプロジェクトに配置 → `./gradlew assembleDebug` でビルド確認

**ステップ 2:** Phase 2 のビルドが通ったら、Phase 3 のプロンプトを投入 → 生成されたファイルで既存スタブを置換 → ビルド確認 → 実機で動作確認

Phase 3 まで完了すれば、「パスワードを追加 → 暗号化して保存 → 一覧表示 → 詳細閲覧 → 編集 → 削除」が一通り動くようになります。

ビルドエラーが出た場合は、エラーメッセージをそのままこちらに貼っていただければ修正を指示します。
今全体的な指示をedit.mdに記載して作業させているのですが、これを都度全部書き換えた方がいいのか、これは残して別途mdを用意した方がいいのかどちらでしょう。
まずedit.mdの中身を確認させてください。

ツールを使用する
|
読み取り
https://raw.githubusercontent.com/nm00331155/android_pass_manager/main/docs/edit.md
エージェントを表示
中身を把握しました。edit.md にはこの会話の内容がほぼそのまま入っていますね。ドキュメント構成について回答します。

結論：edit.md は残して、フェーズごとに別ファイルを用意してください
理由は明確で、Copilot はプロンプトが長すぎると精度が落ちるからです。今の edit.md は約70KBあり、全体を毎回読ませると核心部分がぼやけます。

推奨するdocsフォルダ構成
docs/
├── edit.md                    ← 触らない（経緯・設計思想のアーカイブ）
├── specification.md           ← 触らない（既存、仕様の要約）
├── status.md                  ← 既存（作業状況、都度更新を継続）
│
├── phase2_prompt.md           ← 新規：Phase 2 のCopilotプロンプト
├── phase3_prompt.md           ← 新規：Phase 3 のCopilotプロンプト
├── phase4_prompt.md           ← 新規：Phase 4（Autofill）
├── phase5_prompt.md           ← 新規：Phase 5（OTP）
└── phase6_prompt.md           ← 新規：Phase 6（Export/仕上げ）
各ファイルの役割
ファイル	用途	Copilotに読ませるか
edit.md	設計の経緯、会話ログのアーカイブ。人間が振り返る用	読ませない
specification.md	アプリ全体の仕様サマリ。変更方針が出たら更新	毎回読ませる（短いので）
status.md	現在の進捗。作業開始・終了時に更新	毎回読ませる
phase*_prompt.md	そのフェーズの実装指示書	該当フェーズのみ読ませる