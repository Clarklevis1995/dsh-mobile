<div align="center">

[English](../../README.md) · [简体中文](../../README.zh-CN.md) · **日本語** · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português (Brasil)](README.pt-BR.md)

<img src="../../Design/whale-girl-ios-app-promo-16x9-v3.png" alt="Whaleness iOS アプリの紹介" width="100%">

# Whaleness — DeepSeek Harness のサードパーティー製モバイルクライアント

**Kotlin Multiplatform でコアロジックを共有する、Android と iOS のネイティブアプリ。**

Mobile Gateway 経由で DeepSeek Harness に接続し、スマートフォン、タブレット、折りたたみ端末からワークスペース、リアルタイム会話、Agent の実行履歴、プラグイン、定期実行タスク、ファイルにアクセスできます。

ウェブサイト：[Whaleness](https://clarklevis1995.github.io/dsh-mobile/)

<p>
  <a href="https://testflight.apple.com/join/1Euwby6r"><img src="../images/badges/testflight.svg" alt="iOS TestFlight ベータに参加" width="180" height="60"></a>
  &nbsp;&nbsp;
  <a href="https://github.com/Clarklevis1995/dsh-mobile/releases"><img src="../images/badges/android-github.svg" alt="GitHub Releases から Android APK をダウンロード" width="180" height="60"></a>
</p>

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![iOS 16.1+](https://img.shields.io/badge/iOS-16.1%2B-111827?logo=apple&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![SwiftUI](https://img.shields.io/badge/iOS-SwiftUI-F05138?logo=swift&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Realtime-2563EB)

</div>

## 概要

Whaleness は独立して開発された DeepSeek Harness のサードパーティー製ネイティブモバイルクライアントです。Android と iOS の両アプリで Kotlin Multiplatform（KMP）を使用しています。

- `shared` は共有ビジネス状態の唯一の情報源であり、プロトコル処理、状態機械、Reducer、投影、同期を担当します。
- `androidApp` は Kotlin、Jetpack Compose、OkHttp で Android アプリを実装しています。
- `DeepSeekHarnessMobile` は SwiftUI/UIKit で iOS UI を構築し、Kotlin/Native フレームワーク経由で共有ロジックを利用します。

両アプリは [`dsh-plugin-mobile-gateway`](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) を介して WebSocket で Harness に接続します。ネットワーク、安全なストレージ、ファイル、画像、ライフサイクル、バックグラウンド実行は各プラットフォームのネイティブ API を使用し、ビジネスルールは可能な限り `shared/commonMain` に集約しています。

## ダウンロードと前提条件

- **iOS**：[TestFlight ベータに参加](https://testflight.apple.com/join/1Euwby6r)。iOS / iPadOS 16.1 以降の iPhone と iPad に対応します。
- **Android**：[GitHub Releases から APK をダウンロード](https://github.com/Clarklevis1995/dsh-mobile/releases)。Android 7.0（API 24）以降が必要です。アプリ内の更新確認では高速ダウンロード元を優先し、取得したパッケージを検証します。
- **デスクトップホスト**：DeepSeek Harness を起動し、**`dsh-plugin-mobile-gateway` 0.11.1 以降**をインストールして有効にしてください。最低バージョンは [`AppRelease/release-info.json`](../../AppRelease/release-info.json) で定義されています。
- **音声入力**：ホストのプラグイン画面で公式の音声入力プラグインを有効にし、モデルのダウンロードと初期化を完了して、モバイル端末のマイクへのアクセスを許可してください。

## ベータコミュニティ

Whaleness の WeChat ベータグループ 2では、体験の共有、不具合の報告、サポートの相談ができます。WeChat でスキャンするか、画像を保存して WeChat で読み取ってください。

<p align="center">
  <a href="../../website/assets/support-wechat-2026-10-28.png"><img src="../../website/assets/support-wechat-2026-10-28.png" alt="Whaleness ベータ WeChat グループ 2の QR コード、2026 年 11 月 4 日より前まで有効（原画像の表記）" width="320"></a>
</p>

> 2026 年 10 月 28 日更新。原画像には「2026 年 11 月 4 日前まで有効」と記載されています。期限切れの場合は[製品ページ](https://clarklevis1995.github.io/dsh-mobile/)で最新の QR コードを確認してください。

## プラットフォームの対応状況

| プラットフォーム / モジュール | 状況 | 主な実装 |
| --- | --- | --- |
| KMP `shared` | Android と iOS で利用中 | Gateway プロトコル、Store/Reducer、会話と実行履歴の投影、履歴同期、質問と承認、セッション制御、ワークスペースのファイル状態、コードプレビュー |
| Android | ネイティブクライアントを提供 | Jetpack Compose、OkHttp WebSocket、DataStore（機密性のない設定）、Keystore（認証情報）、CameraX/ML Kit の QR 読み取り、フォアグラウンドサービス、添付ファイルキャッシュ、ファイルのダウンロードとプレビュー |
| iOS | ネイティブクライアントを提供 | SwiftUI/UIKit、URLSession WebSocket、UserDefaults（機密性のない設定）、Keychain（認証情報）、QR ペアリング、バックグラウンド実行、添付ファイル、ワークスペースのファイル |

> Android と iOS は、それぞれのネイティブ UI とプラットフォーム機能を使用します。機能と操作を揃えていますが、システムレベルの一部の動作は異なります。

## 主な機能

### 最近の追加・改善

- **Agent チーム**：メンバーを確認し、Lead とメンバーの会話を切り替えます。共有タスクの状態、担当者、関連会話へのリンクも確認できます。
- **ファイルのアップロード**：会話からファイルを選択し、進捗の確認、添付の削除、失敗したアップロードの再試行ができます。送信済み・生成済みファイルはカードで表示され、開いたりダウンロードしたりできます。
- **文字選択と表の閲覧**：専用画面で本文の選択、全選択、コピーを行い、閉じた後も会話の閲覧位置を保持します。Markdown の表は全画面表示、回転、コピー、CSV 書き出しに対応し、列が少ない表は幅に合わせ、広い表は横にスクロールできます。
- **会話履歴とストレージ**：会話をローカルにキャッシュし、よく使う会話をオフラインで検索できます。画像、会話履歴、ファイルプレビュー、一時ファイルの使用量を確認し、種類別にローカルキャッシュを削除できます。会話を開き直すと履歴を復元し、以前の記録を段階的に読み込みます。
- **iPhone Duo と大画面**：展開、折りたたみ、回転に応じてホームの分割表示、セーフエリア、入力欄、アニメーション背景を調整します。広い会話画面にはワークスペース別の常設セッションサイドバーを表示し、ナビゲーションとタスク操作も利用可能な領域に適応します。
- **iOS 16.1 対応**：最小対応バージョンを iOS 16.1 に拡張し、旧システム向けの互換 UI を提供します。システム依存の効果や機能は iOS のバージョンと端末の能力に応じて利用できます。
- **ネイティブ操作の改善**：Android のすりガラス調メニュー、セッション名変更、スワイプナビゲーションと、iOS のバックグラウンド復帰時の接続、ファイル閲覧、画像互換性を改善しました。

### 会話と Agent の実行

- **ネイティブのリアルタイム会話**：WebSocket の増分イベントを処理し、Markdown、コード、推論、ツール呼び出し、ツールの結果、画像の添付を表示します。
- **音声・テキスト入力**：専用エリアを長押しして録音し、指を離すと編集可能な下書きに文字起こしします。上にスライドするとキャンセルできます。文字起こしにはホストの音声入力機能を使用します。
- **履歴・キュー・コマンド**：ページ単位の履歴とリアルタイムの末尾を統合し、イベントの重複を除去します。待機中のメッセージを確認・編集し、ホストのスラッシュコマンドを利用できます。iOS では過去の履歴を読み込んでも閲覧位置を保持します。
- **Agent の実行履歴**：User、Assistant、Tool のイベント、引数、結果、スキーマ、トークン使用量、所要時間を確認できます。
- **対話型 Agent フロー**：Human Question やツールの承認要求に対応し、送信、キャンセル、拒否、今回のみ許可を行えます。

### ホスト・ワークスペース・拡張機能

- **複数ホストとセッション**：認証情報を個別に管理して Gateway ホストを保存・切り替えできます。ワークスペースの閲覧・作成・切り替え、セッションの検索・作成・切り替え・アーカイブに対応します。
- **ファイルカードとプレビュー**：会話内のファイルカードを直接開き、リモートディレクトリの閲覧や検証付きの分割ダウンロードを行えます。PDF、画像、コードのプレビュー、システムアプリでの共有・表示に対応します。
- **Markdown のソース / プレビュー**：構文ハイライト、行番号、長い行の横スクロールに対応します。Markdown のソース表示とレンダリング表示を切り替え、それぞれの閲覧位置を保持します。
- **プラグイン管理**：公式・インストール済みプラグインの詳細と実行状態を確認できます。ホストが対応する範囲でインストール、有効化、無効化、設定を行えます。
- **定期実行タスク**：会話で作成したタスクの閲覧、検索、絞り込み、ルール・次回実行時刻・配信情報の確認、編集、削除ができます。
- **セッションと既定の設定**：Agent プリセット、プロバイダー / モデル、推論レベル、Harness Agent の権限 `read-only`、`workspace-write`、`danger-full-access` を管理できます。これらはホスト上の Agent の権限であり、スマートフォンのローカルファイルシステムの権限ではありません。

### ネイティブ端末での体験

- **タブレットと折りたたみ端末**：iPad、Android タブレット、折りたたみ端末のウィンドウサイズに応じてレイアウトを調整します。広いホーム画面は分割表示を使い、会話は読みやすい幅を保ち、ファイルプレビューも伸縮します。Android は折りたたみヒンジの領域も考慮します。
- **テーマと文字サイズ**：ライト・ダーク表示、システム連動または手動の文字サイズ調整に対応し、Harness の海・グリッド・クジラのビジュアルを受け継いでいます。
- **iOS のライブアクティビティと通知**：ロック画面や対応する Dynamic Island で、タスクの進捗、承認、完了を確認できます。重要な更新はシステム通知で知らせます。
- **安全な端末ペアリング**：WebUI の QR コードを読み取るか、情報を手入力します。長期認証情報はプラットフォームの安全なストレージに保存されます。
- **モバイルのライフサイクル対応**：バックグラウンド移行時に正常な接続を意図的に切断しません。切断後はフォアグラウンド復帰時に再接続し、手動切断では自動再接続しません。Android は Agent のターン実行中のみフォアグラウンドサービスを使います。iOS はシステムが許可する有限のバックグラウンド実行時間を使用し、WebSocket の無期限維持は保証しません。

## スクリーンショット

> 最新の iOS 製品画像：1.7.10 の素材は 2026 年 10 月 22～23 日、iPhone Duo の画像は 2026 年 10 月 26 日に撮影しました。UI は中国語です。[製品画像の出典](../images/screenshots/2026-10-23/SOURCES.md) · [Duo 画像の出典](../images/screenshots/2026-10-26-duo/SOURCES.md)。

### iPhone Duo：横向き・縦向き・ナビゲーション

<table>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home.webp" alt="展開時の横向きホーム" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-home-alt.webp" alt="横向きホームの背景" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>展開時の横向きホーム</strong></td>
    <td align="center"><strong>横向きホームの背景</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-settings.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-settings.webp" alt="横向きの設定" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-conversation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-conversation.webp" alt="横向きの会話" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>横向きの設定</strong></td>
    <td align="center"><strong>横向きの会話</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/landscape-navigation.webp"><img src="../images/screenshots/2026-10-26-duo/landscape-navigation.webp" alt="開いたナビゲーションサイドバー" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="../images/screenshots/2026-10-26-duo/portrait-home.webp"><img src="../images/screenshots/2026-10-26-duo/portrait-home.webp" alt="展開時の縦向きホーム" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>開いたナビゲーションサイドバー</strong></td>
    <td align="center"><strong>展開時の縦向きホーム</strong></td>
  </tr>
</table>

### チーム・添付ファイル・ローカルストレージ

<table>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-members.webp"><img src="../images/screenshots/2026-10-23/team-members.webp" alt="Agent チームのメンバー" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/team-tasks.webp"><img src="../images/screenshots/2026-10-23/team-tasks.webp" alt="チームの共有タスク" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/attachment-picker.webp"><img src="../images/screenshots/2026-10-23/attachment-picker.webp" alt="ファイルと画像の選択" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Agent チームのメンバー</strong></td>
    <td align="center"><strong>チームの共有タスク</strong></td>
    <td align="center"><strong>ファイルと画像の選択</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/text-selection.webp"><img src="../images/screenshots/2026-10-23/text-selection.webp" alt="独立した文字選択画面" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/conversation-files.webp"><img src="../images/screenshots/2026-10-23/conversation-files.webp" alt="会話内のファイル" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/release-notes.webp"><img src="../images/screenshots/2026-10-23/release-notes.webp" alt="同梱のリリースノート" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>独立した文字選択画面</strong></td>
    <td align="center"><strong>会話内のファイル</strong></td>
    <td align="center"><strong>同梱のリリースノート</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/storage.webp"><img src="../images/screenshots/2026-10-23/storage.webp" alt="ローカルストレージとキャッシュ分類" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/voice-input.webp"><img src="../images/screenshots/2026-10-23/voice-input.webp" alt="長押しで音声入力" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="../images/screenshots/2026-10-23/file-upload.webp"><img src="../images/screenshots/2026-10-23/file-upload.webp" alt="入力欄の添付ファイル" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>ローカルストレージとキャッシュ分類</strong></td>
    <td align="center"><strong>長押しで音声入力</strong></td>
    <td align="center"><strong>入力欄の添付ファイル</strong></td>
  </tr>
</table>

<details>
<summary>以前の画像：iPad、プレビュー、設定、ペアリング</summary>

> 画像は中国語 UI の iOS アプリです。Android は Jetpack Compose で同様の情報構造とフローを実装しています。本グループの音声、ファイル、文字サイズ、iPad の画像は 2026 年 10 月 16 日にバージョン 1.7.6 で撮影しました。[画像の出典](../images/screenshots/2026-10/SOURCES.md)をご覧ください。

### 音声入力・ファイルプレビュー・文字サイズ

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/voice-input.png" alt="iPhone で長押し録音中の音声入力オーバーレイ" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/pdf-preview.png" alt="会話のファイルカードから開いた PDF プレビュー" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/font-size.png" alt="アプリの文字サイズプレビューとスライダー" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>長押しで話す</strong><br>離すと文字起こし、上にスライドでキャンセル</td>
    <td align="center"><strong>PDF プレビュー</strong><br>会話から直接ファイルを開く</td>
    <td align="center"><strong>文字サイズ</strong><br>システムに合わせるか手動で調整</td>
  </tr>
</table>

[上にスライドしてキャンセルする状態を見る](../images/screenshots/2026-10/voice-cancel.png)

### iPad と大画面

<table>
  <tr>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-home.png" alt="ワークスペースと最近のセッションを 2 列で表示する iPad ホーム" width="100%"></td>
    <td width="50%" align="center"><img src="../images/screenshots/2026-10/ipad-conversation.png" alt="ファイルカードを含む横向き iPad の会話" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>ワイドなホーム画面</strong><br>ワークスペースとセッションを並べて表示</td>
    <td align="center"><strong>タブレットでの会話</strong><br>読みやすい本文幅と入力欄</td>
  </tr>
  <tr>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-pdf-preview.png" alt="iPad の会話に重ねて表示する PDF プレビュー" width="100%"></td>
    <td align="center"><img src="../images/screenshots/2026-10/ipad-voice-input.png" alt="横向き iPad に適応する録音オーバーレイ" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>ファイルプレビュー</strong><br>会話の流れを保ったまま閲覧</td>
    <td align="center"><strong>iPad の音声入力</strong><br>録音表示がウィンドウに適応</td>
  </tr>
</table>

### 基本フロー

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/home.png" alt="iOS のワークスペースホーム画面" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/2026-10/file-card.png" alt="ファイルカードと実行統計の操作を含む iOS の会話" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-light.png" alt="iOS のライトモードの Agent 実行履歴" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>ホーム</strong><br>ワークスペースと最近のセッション</td>
    <td align="center"><strong>会話</strong><br>Markdown、コード、ツールの結果</td>
    <td align="center"><strong>Agent の実行履歴</strong><br>所要時間の概要とイベントのタイムライン</td>
  </tr>
</table>

### 既定の設定

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/settings.png" alt="iOS アプリの設定" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/agent-presets.png" alt="iOS の Agent プリセット" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/default-model.png" alt="iOS の既定モデル設定" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>設定</strong><br>デプロイの既定値と Gateway の状態</td>
    <td align="center"><strong>Agent プリセット</strong><br>ツール、プロンプト、機能</td>
    <td align="center"><strong>既定のモデル</strong><br>プロバイダー、モデル、推論レベル</td>
  </tr>
</table>

### プラグインと定期実行タスク

<table>
  <tr>
    <td width="50%" align="center"><img src="../../website/assets/plugins.webp" alt="iOS の公式・インストール済みプラグイン一覧" width="100%"></td>
    <td width="50%" align="center"><img src="../../website/assets/tasks.webp" alt="iOS の定期実行タスクのルールと詳細" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>プラグイン管理</strong><br>詳細と有効化状態を確認</td>
    <td align="center"><strong>定期実行タスク</strong><br>スケジュールと実行状態を確認</td>
  </tr>
</table>

<details>
<summary>その他の画面：ワークスペース、実行履歴の詳細、端末ペアリング</summary>

### ワークスペースの操作と実行履歴の詳細

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-switcher.png" alt="iOS のワークスペース切り替え" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/workspace-directory-picker.png" alt="iOS のワークスペースディレクトリ選択" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/trajectory-detail-light.png" alt="iOS の実行履歴詳細パネル" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>ワークスペースを切り替え</strong><br>別のプロジェクトにアクセス</td>
    <td align="center"><strong>ディレクトリを選択</strong><br>ワークスペースを閲覧・作成</td>
    <td align="center"><strong>実行履歴の詳細</strong><br>概要、トークン、内容のプレビュー</td>
  </tr>
</table>

### ライトモード・ダークモード・ペアリング

<table>
  <tr>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-light.png" alt="iOS のライトモードの端末ペアリング" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/pairing-dark.png" alt="iOS のダークモードの端末ペアリング" width="100%"></td>
    <td width="33.33%" align="center"><img src="../images/screenshots/conversation-dark.png" alt="iOS のダークモードの会話" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>ライトモードのペアリング</strong><br>QR コードの読み取りまたは情報の手入力</td>
    <td align="center"><strong>ダークモードのペアリング</strong><br>ネイティブのダーク表示</td>
    <td align="center"><strong>ダークモードの会話</strong><br>閲覧、コード、入力欄</td>
  </tr>
</table>

</details>

</details>

## クロスプラットフォーム構成

```text
                         DeepSeek Harness
                                │
                  dsh-plugin-mobile-gateway
                                │
                             WebSocket
                ┌───────────────┴───────────────┐
                │                               │
     Android OkHttp Transport          iOS URLSession GatewayClient
                │                               │
       KMP Gateway Runtime             Swift Router / KMP Adapter
                │                               │
                └───────────────┬───────────────┘
                                │
                    KMP shared / commonMain
            Protocol · Store · Reducer · Projection
       History Sync · Question/Approval · Workspace Files
                ┌───────────────┴───────────────┐
                │                               │
       Jetpack Compose                     SwiftUI / UIKit
          Android UI                          iOS UI
```

### 共有モジュールの役割

- `protocol/`：Gateway DTO、JSON 表現、通信データのデコード。
- `gateway/`：プラットフォームのトランスポートインターフェース、ペアリング、認証、要求の対応付け、再接続、イベントのバックプレッシャー。Android は共有ランタイム全体を使用し、OkHttp が下位トランスポートを提供します。
- `domain/`：セッション、質問、承認、セッション制御の Reducer。
- `projection/`：会話と実行履歴の増分投影。
- `sync/`：履歴のページング、リアルタイム末尾の統合、重複排除。
- `facade/`：Swift/Kotlin アダプター向けの粗粒度 Store・Intent・Event、およびワークスペースのパス、チャンク順序、進捗、SHA-256 検証。

共有モジュールは Compose、SwiftUI、Android Framework、Apple の UI フレームワークに依存しません。iOS は共有 Store、Reducer、投影、ファイル状態機械を再利用します。Swift の `GatewayClient` が引き続き WebSocket 接続と再接続を担当し、アダプターを通じて共有モジュールにイベントを渡します。iOS はまだ KMP Gateway Runtime 全体を使用していません。

各プラットフォームが安全なストレージ、画像処理、一時ファイル、システム共有・表示、ライフサイクルを担当します。共有モジュールはプラットフォームのファイルシステムに直接アクセスせず、リモートファイルのプロトコル、状態遷移、チャンクの整合性、完全性検証を担当します。

## リポジトリ構成

```text
.
├── shared/                         # Kotlin Multiplatform business logic
│   └── src/
│       ├── commonMain/             # Protocol, state machines, projections, sync, facades
│       └── commonTest/             # Cross-platform unit tests
├── androidApp/                     # Native Android app (Jetpack Compose)
│   └── src/
│       ├── main/                   # App code and platform integrations
│       ├── test/                   # JVM tests
│       └── androidTest/            # Device tests
├── DeepSeekHarnessMobile/          # Native iOS app (SwiftUI/UIKit)
├── DeepSeekHarnessMobileTests/     # iOS XCTest tests
├── DeepSeekHarnessMobile.xcodeproj
├── Docs/                           # Research, KMP migration, and validation
└── Design/                         # Design specifications and artwork
```

## 開発環境

### 共通

- Java 17.
- `dsh-plugin-mobile-gateway` 0.11.1 以降を有効にした DeepSeek Harness。
- Mobile Gateway に接続できるシミュレーター、エミュレーター、または実機。

### Android

- Android Studio または Android SDK コマンドラインツール。
- Android SDK Platform 36（`compileSdk` / `targetSdk`）。
- Android 7.0（API 24）以降の端末またはエミュレーター。

### iOS

- macOS と Xcode 26 以降、および iOS 26 SDK。Liquid Glass などの API のコンパイルに必要です。アプリの最小対応バージョンは iOS 16.1 です。
- iOS 16.1 以降のシミュレーターまたは実機。
- Android SDK：Xcode のビルドフェーズが Gradle を呼び出して KMP フレームワークをコンパイルします。
- 利用可能な Metal ツールチェーン。ビルドフェーズが自動検出します。

## Android の実行

1. Java 17 を設定し、`ANDROID_HOME` または `ANDROID_SDK_ROOT` に Android SDK のパスを指定します。
2. リポジトリのルートから Debug APK をビルドします。

   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

3. 接続した端末またはエミュレーターにインストールします。

   ```bash
   ./gradlew :androidApp:installDebug
   ```

4. `DshMobile` を起動し、WebUI のペアリング QR コードを読み取るか、ペアリング情報を手入力します。

Debug APK の出力先：

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Android エミュレーターからホストの `127.0.0.1:3080` にアクセスするには、先に次を実行します。

```bash
adb reverse tcp:3080 tcp:3080
```

## iOS の実行

1. Java 17 と Android SDK を設定します。
2. Xcode で `DeepSeekHarnessMobile.xcodeproj` を開きます。
3. `DeepSeekHarnessMobile` スキームと対象端末を選択します。
4. ビルドして実行します。`Build KMP Framework` フェーズが適切な Kotlin/Native フレームワークを自動で選択・コンパイルします。
5. アプリで WebUI のペアリング QR コードを読み取るか、情報を手入力します。

フレームワークの事前ビルドは不要です。Apple Silicon の iOS シミュレーター用フレームワークのみをビルドする場合：

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## 接続とペアリング

1. DeepSeek Harness で `dsh-plugin-mobile-gateway`（最低 0.11.1）をインストールまたは更新し、`dsh web` を完全終了して再起動します。

   ```bash
   dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
   ```

2. WebUI のモバイル端末パネルを開き、モバイル接続と端末認証を有効にします。
3. モバイル端末やシミュレーターから WebSocket エンドポイントに接続できることを確認します。
4. 使い捨てのペアリング QR コード / トークンを生成し、Android または iOS アプリで認証します。
5. ホームに戻り、Gateway の接続を確認して、ワークスペースとセッションを選択します。

一般的なエンドポイント：

| 環境 | エンドポイント |
| --- | --- |
| iOS シミュレーター | `ws://127.0.0.1:3080/ws/mobile` |
| `adb reverse` を使用する Android エミュレーター | `ws://127.0.0.1:3080/ws/mobile` |
| 同じ LAN 上の実機 | `ws://<HOST-LAN-IP>:3081/ws/mobile` |
| 公開デプロイ | `wss://<your-domain>/ws/mobile` |

> [!IMPORTANT]
> 実機のスマートフォンから `127.0.0.1` 経由でコンピューターには接続できません。`ws://` の例は信頼できるローカル開発環境専用です。公開ネットワークや信頼できないネットワークでは、TLS と端末認証を正しく設定した `wss://` を使用してください。使い捨てのペアリングトークンをログ、Issue、チャットに含めないでください。

> [!NOTE]
> LAN 上の実機は `dsh-plugin-mobile-gateway` が提供する別のポート `3081` のリスナーを使用します。認証済みの `/ws/mobile` リクエストのみを処理し、ほかのパスは 404 を返します。ポート `3080` は DSH WebUI 用でループバックのみにバインドされるため、実機からは接続できません。

## ビルドとテスト

Android の単体テスト、lint、APK ビルドを実行します。

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
```

Apple Silicon の macOS で共有 Kotlin/Native テストを実行します。

```bash
./gradlew :shared:iosSimulatorArm64Test
```

Android 端末またはエミュレーターが利用できる場合、端末テストを実行します。

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

iOS のテストでは Xcode で `DeepSeekHarnessMobile` スキームを選択し、**Product → Test** を実行します。ビルドフェーズが対象アーキテクチャの `DeepSeekHarnessShared.framework` を自動リンクします。

## 関連ドキュメント

リンク先の技術ドキュメントの一部は現在中国語のみです。

- [Harness / WebUI の機能とプロトコル調査](../exploration.md)
- [モバイルデザイン仕様](../../Design/design-spec.md)
- [サードパーティーに関する表記](../third-party-notices.md)

以下は KMP 移行と Android 実装の記録です。現在の状態はこの README とソースコードを参照してください。

- [KMP 開発ガイド](../kmp-development.md)
- [Android Gateway フェーズ 12 検証](../kmp-stage12-android-gateway-verification.md)
- [Android UI フェーズ 13 検証](../kmp-stage13-android-ui-verification.md)

## プロジェクトの状況

Whaleness は継続して開発中です。KMP 共有モジュールと Android・iOS のネイティブアプリは実装済みで、プロトコル対応、プラットフォーム間の一貫性、モバイル体験は DeepSeek Harness と Mobile Gateway の進化に合わせて改善しています。

このプロジェクトは独立して開発された DeepSeek Harness のサードパーティー製クライアントです。

[![dsh-plugin.org 掲載](https://dsh-plugin.org/badges/listed.svg)](https://dsh-plugin.org/plugins/clarklevis1995/dsh-plugin-mobile-gateway)

### アプリ同梱のリリースノート

両アプリの「このアプリについて」は `AppRelease/release-info.json` と `AppRelease/release-notes.md` を使用します。JSON の `version` はアプリのバージョンと一致する必要があり、`minimumGatewayVersion` は Mobile Gateway プラグインの最低バージョンを指定します。Markdown は該当バージョンのリリースノートです。これらは同梱リソースから読み込み、本文をネットワークから取得しません。

KMM 両プラットフォームリリーススキルは、コンパイル前に確認済みリリースノートを書き込み、リソースのバージョンを同期します。APK、AAB、iOS バンドル内のリソースとソースの一致を検証し、パッケージ化に失敗した場合はリソースとバージョンファイルを復元します。リリースノートの変更にはアプリの再ビルドが必要で、出力ディレクトリのみの編集では同梱内容は更新されません。

### Android Studio からの iOS Metal コンパイル

`Build Metal Library` フェーズは `scripts/build_metal_library.sh` を実行し、インストール済みの Metal ツールチェーンを検出して、アプリバンドルに `default.metallib` を生成します。Android Studio が注入した `TOOLCHAINS` と `SDKROOT` は子プロセス内のみで解除し、現在の iOS SDK とデプロイ対象を明示的に維持します。これにより `XcodeDefault` の強制による誤った Metal ツールチェーン未検出エラーを防ぎます。

### Android の更新元

Android は最初に `https://download.whaleness.cc/latest.json` を確認し、要求の失敗や設定の不備がある場合は GitHub Releases に切り替えます。`schemaVersion: 1` の設定には `version`、`versionCode`、`packageName`、`downloadUrl`、`size`、`sha256` が含まれ、`githubDownloadUrl` と `releaseNotes` は任意です。ダウンロード先は、設定済みの信頼できる高速ダウンロードドメインと本プロジェクトの GitHub URL のみに制限されます。高速配信元で失敗した場合は代替元へ切り替え、同じ設定に基づいてサイズ、SHA-256、バージョン、パッケージ名、署名を検証します。

公開時は不変のバージョン付き APK を先にアップロードし、その後ルートの `latest.json` を更新します。更新マニフェストは CDN キャッシュを回避し、APK はキャッシュ可能です。新しいビルドがない場合、GitHub への要求は行いません。起動時の確認は通知を出さず、設定からの手動確認では結果を表示します。

### R2 への自動公開

`python3 scripts/configure_r2_release.py` を実行すると、対象バケットの S3 オブジェクト読み書き認証情報を `~/.config/whaleness/r2-release.json` に権限 `600` で保存します。`sh scripts/r2_release.sh --check` は接続確認のみ、`sh scripts/r2_release.sh --prepare <release-directory>` はマニフェスト生成、`--publish <release-directory>` はリモート公開を行います。S3 SDK は分離された `build/r2-tools` 環境にインストールされます。

ローカル設定がある場合、KMM 両プラットフォームリリーススキルは自動公開します。ローカルパッケージを検証し、ビルド番号をファイル名に含む不変の APK をアップロードして、リモートオブジェクトと公開ダウンロードを確認し、条件付きで `latest.json` を更新します。アップロード失敗時は再ビルドせず同じリリースディレクトリで再試行できます。ローカルパッケージ化のみにする場合は `R2_UPLOAD=0` を設定します。アップロード全体の検証では R2 読み取り要求が発生する場合がありますが、初回の接続確認はリモートオブジェクトを変更しません。
