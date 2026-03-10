# SecureVault 未実装・未完了一覧

最終更新: 2026-03-10 22:50:06 +09:00

## 1. この文書の位置づけ
- `docs` 配下の旧仕様書、旧フェーズプロンプト、旧バグ修正指示書、旧作業ログから、まだ残っている未実装・未完了・未対応・要 QA 項目だけを抜き出した統合版です。
- 現時点で大きなフェーズ機能の未実装は原則ありません。
- 残件の中心は、端末差分を伴う実機 QA、サイト差分確認、OS 制約による未対応事項です。

## 2. 結論
- Phase 0〜6 の機能実装は完了しています。
- したがって、ここでいう「未実装」は主に次の 3 種類です。
  - 端末やブラウザ実地での未完了 QA
  - OS や相手サービスの仕様により未対応の項目
  - 実装済みだが挙動差分の再確認が必要な項目

## 3. 未完了の実機 QA
- Chrome / Brave / Edge / Firefox の実サイトで、Autofill 候補表示から認証後入力までを再確認すること
- 実サイトで passkey get / passkey create を発火し、`KeyPass` が live chooser に出ることを確認すること
- Amazon / ネイティブアプリ由来の passkey create で、`CallingAppInfo.signingInfoCompat` を使う修正版により登録完了まで進むか再確認すること
- Amazon など multi-step ログインで、保存確認から最終保存まで自然に通ることを確認すること
- クレジットカード Autofill を主要サイトで確認し、特に有効期限形式 `MM/YY` / `MM/YYYY` / month-year 分離の差分を確認すること
- バックアップ / 復元 / SecureVault CSV / 他サービス CSV / 重複戦略の端末 E2E を確認すること
- Brave / Bitwarden CSV を使った他サービスインポートの手動 E2E を確認すること
- 生体認証通過後のカード作成画面、設定画面、バックアップ画面、実サイト Autofill 画面の目視確認を完了すること
- OTP の SMS / 通知 / クリップボード各経路を、実機条件ごとに再確認すること
- response-auth / dataset-auth の切り替えが、対象ブラウザで二度選択や未入力を再発しないか確認すること
- Inline Suggestions が無効な端末、`Max visible datasets = 0` の端末で Fill Dialog 表示が安定するか確認すること
- Samsung の combined authenticator prompt で `button_negative` が再び出ず、`PINを使用` 経路が継続するか目視確認すること

## 4. 未完了の端末差分確認
- Samsung / API 36 実機では `Settings.ACTION_CREDENTIAL_PROVIDER` と `android.settings.AUTOFILL_SETTINGS` が adb から直接 resolve されない場合があり、設定画面の「追加サービス」を手元操作で最終確認する必要があります
- Samsung / API 36 実機では provider discoverability を package manager query で代替確認済みですが、設定 UI 側の最終目視は継続タスクです
- ブラウザ実装差と framework 実装差が大きいため、OS 更新やブラウザ更新ごとの回帰確認が必要です

## 5. 明確に未対応の項目
- Samsung Pass の直接インポート
  - 理由: 標準 CSV エクスポート導線がなく、独自 `.spass` 形式のため
- Android 15+ での通知由来 OTP の完全対応
  - 理由: OS 制約があるため、ベストエフォート運用のみ

## 6. 既知制限として残る項目
- ダイナミックカラーは Android 12 以上のみ
- クレジットカード有効期限の Autofill はサイト実装差に依存します
- Chromium 137 以降では、ブラウザ側で「別のサービスを使用して自動入力」を有効にするユーザー操作が必要です
- 一部端末では Credential Provider / Autofill の設定 deep link が OS 実装依存で動作しません

## 7. 自動化されていない確認領域
- 実サイト上での Autofill / passkey get-create の完全再現テスト
- OTP の実受信を伴う一連のシナリオ
- 端末内ファイル I/O を伴うバックアップ / 復元の完全自動検証
- 端末 vendor UI を含む認証ダイアログの視認性確認

## 8. 今後新しく足すなら別管理にすべきもの
- ここに載せるのは、既存 docs に出ていたが未完了のものだけです
- これ以外の新機能要望は、別途 issue か新しい要件定義として起票してください

## 9. 統合方針メモ
- 旧フェーズプロンプト、旧指示書、旧ステータス履歴に書かれていた内容のうち、ここに載っていないものは `implemented.md` に統合済みです
- 今後 `docs` 配下では、この `pending.md` だけを未完了事項の正本として更新してください