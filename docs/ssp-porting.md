# SSP 全機能移植の進捗

更新: 2026-11-14

目標は Windows 専用ゴーストと DLL を含む完全互換。以前の「作らない」という方針は移植対象から除外する根拠にしない。
現時点では完全互換ではなく、Android で任意の Windows DLL を実行できる状態でもない。
以下は作業ツリーの実装状況であり、公開済みリリースの機能一覧ではない。

栞の追加移植については [SHIORI 対応状況](shiori-support.md) を参照。なし栞 2 を追加し、ネイティブに組み込むエンジンは 8 種になった。下記の APK のハッシュ・7 種という記録は、それ以前の SSP 機能移植時点の検証結果。

## 今回追加した実装

| 対象 | 実装と確認範囲 | 残る確認・制限 |
|---|---|---|
| サーフェスごとのバルーン位置 | 汎用・sakura/kero/charN・方向別の offset、surface 切り替え、descript へのフォールバック。JVM テスト | 端末での表示確認 |
| OnKeyPress | 専用入力画面、画面キーと物理キー、Win32 VK、repeat、scope、修飾キー。Kotlin テスト | 実機、各国キーボード配列。入力画面を開いた間だけ受信 |
| ゲームパッド | 接続・切断、ボタンの押下と解放、6 軸、デッドゾーン、全起動ゴーストへの配信。キーと hat の重複処理。JVM テスト | 実機。ボタン・軸は入力画面を開いた間だけ受信 |
| RSS / Atom | 登録・一覧・実行・削除、取得結果の差分保存、RSS のイベント、従来ヘッドラインイベントへのフォールバック。JVM テスト | 自動巡回、HTML の RSS Auto Discovery、JSON Feed、その他の SSP 管理 UI |
| Headline 配布物 | type,headline の NAR、descript・readme、履歴、headlinelist / history.headline、パス・名前通知、SSTP GetHeadlineNameList、updateother --headline | DLL 型センサーの Android 実行 |
| HEADLINE/2.0 | 文字コード交渉、複数 Headline ヘッダ、旧新ファイルの解析、共通 DLL セッションとの接続。モックエンジンでテスト | Android の DLL ランタイム、実配布センサー、旧 HEADLINE/1.1 |
| カレンダー | 日付選択、ゴースト・購読・許可済み端末の予定、繰り返し予定、タップ・長押し時の OnScheduleRead、open,calendar とメニュー接続 | 実機、予定編集、SSP のカレンダースキン・DLL センサー |
| Windows DLL 共通 ABI | x86 / x64 ホスト、loadu / load / request / unload、生バイト転送、ログ分離、異常終了とタイムアウト。Windows 上で実 DLL フィクスチャを実行 | Android に組み込む Wine / CPU 互換層、実配布ゴースト、SAORI コールバック |

RSS の登録は、ゴーストのメニューの「RSS・ヘッドライン」→「RSS・Atom を追加」で行う。
登録したフィードは `headline/rss-…/descript.txt` に `type,rss` として保存する。これは pono-droid の保存形式で、SSP の RSS 設定ファイルの互換形式とは主張しない。
取得結果は配布物の外の `pono/headline-cache/` に保存し、通信・解析に失敗したときは直前の成功結果を維持する。
カレンダーは起動中のゴースト一覧の「カレンダー」、または本体メニューから開く。予定を選ぶと `OnScheduleRead` を送り、Reference3 のスクリプトはゴースト側に渡すデータとして扱う。
DLL 型のセンサーは NAR をインストールできても、Android では解析失敗として通知する。成功したような応答は返さない。

## 完全互換に必要な残作業

1. Android の現在の target SDK を維持した実行環境を選定・構築する。Wine、x86 / x64 命令変換、ロード可能な実行ファイルの配置、ファイルパス・文字コード・プロセス終了を検証する。
2. DLL モジュールを Android の起動経路へ接続する。SHIORI、SAORI、PLUGIN、HEADLINE、MAKOTO、カレンダー、エフェクト DLL は ABI と責務を分け、順に実ゴーストで確認する。
3. Windows のウィンドウメッセージ、DirectSSTP、FMO / Mutex、外部プロセス、トレイ・デスクトップ依存の処理を仮想 Windows 環境内で接続する。Android の機能に対応づけるものは OS の制約を記録する。
4. カレンダーのスキン・予定編集・センサー、メニュー画像・装飾、ゴーストキャッシュ、RSS 自動巡回・自動検出、言語配布物など、旧一覧で除外した機能を実装する。
5. 既存の DONE / NA も再監査する。旧 DONE は全ゴーストや全条件の互換性を証明しておらず、NA にも旧方針に由来する除外がある。
6. Android 実機またはエミュレータで表示・入力・プロセス隔離と復旧を検証し、公開仕様の試験用ゴーストと実配布ゴーストで SSP と比較する。

特に Winlator の既存構成をそのまま持ち込む判断はしていない。調査した実装は target SDK 28 と独自 rootfs を使い、
本アプリの target SDK 37 にそのまま適用できることを確認できていない。
参考: [Winlator](https://github.com/brunodev85/winlator)、[Winlator アプリのビルド設定](https://github.com/brunodev85/winlator-app/blob/main/app/build.gradle)。

## 互換性表の扱い

[ukadoc-spec-status.tsv](ukadoc-spec-status.tsv) は 2050 項目の旧台帳を更新したもの。
`BUILD` は移植対象で残作業があるもの、`PARTIAL` は一部実装または実行環境が未接続のもの。
旧 `IMPOSSIBLE` は一律の除外を取り消し、実装した項目を除いて `BUILD` に戻した。元の理由は注記に残している。
この件数から互換率を算出しない。SSP の公開仕様の追補と、実際のゴーストの依存関係も確認が必要。

## 再現手順と今回の制約

```powershell
.\gradlew.bat :baseware:test :app:testDebugUnitTest --offline
.\windows-module-host\Test.ps1 -Offline
.\gradlew.bat :app:assembleDebug --offline
```

2026-11-14 のローカル検証結果:

- `baseware`: 518 テスト、`app`: 6 テスト。失敗・エラー・スキップはすべて 0。Windows ホスト必須の環境変数を設定し、x86 / x64 の JVM 統合テストも実行した。
- Windows ホスト: x86 / x64 各 4 テストが成功。DLL の生バイト往復、旧 load、異常応答、クラッシュ、ログの分離を確認した。
- `:app:assembleDebug`: 成功。今回の APK は arm64-v8a、Android 8.0 以上向け。7 種の SHIORI ライブラリを収録し、`apksigner verify` で署名を検証した。
- 出力: `app/build/outputs/apk/debug/app-debug.apk`（66,498,271 bytes）。SHA-256: `e903041ca7873190621e93c11fba56b6121a09cfca9d342099ef6f418f7e7dbc`。
- Gradle の非推奨警告と既存 Kotlin 警告は残っている。上記の成功は実機の互換性試験の代わりにはならない。

Windows 統合テストの前提と環境変数は [ホストの README](../windows-module-host/README.md) に記載。
Windows CI を追加した。実行結果は GitHub Actions の `Windows module host` ワークフローで確認できる。
今回の環境には接続済み Android デバイスと設定済み AVD がなく、実機確認は未実施。
APK ビルドに不足していた `gumbo-parser` は、ユーザー承認後に固定コミット `33103d884bbc77807d188e1a04cf9a83f19f4c5f` を取得した。
ベンダーソースの書き換えや別ソースへの差し替えは行っていない。

参照仕様: [HEADLINE/2.0](https://github.com/ukatech/ukadoc-spec/blob/main/spec/headline.md)、
[DLL 共通仕様](https://github.com/ukatech/ukadoc-spec/blob/main/spec/dll.md)、
[RSS・ヘッドラインの操作](https://ssp.shillest.net/ukadoc/ssphelp/howto-headline.html)。
