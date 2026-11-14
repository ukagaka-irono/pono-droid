# Windows module host

Windows の伺か DLL 共通 ABI を別プロセスで呼び出すためのホストです。
公開仕様の `loadu` / `load` / `request` / `unload` と GlobalAlloc / GlobalFree の所有権に従います。
第三者の DLL や SSP の実装は同梱していません。

**Android アプリにはまだ接続していません。** この実行ファイルだけでは Windows 用ゴーストは動きません。
Android 側には Wine、CPU 命令の変換、ファイルパスの対応付け、プロセス終了と権限の管理が必要です。
Windows 上のプロセス分離はクラッシュの分離であり、DLL のファイルアクセスを制限するサンドボックスではありません。

## ビルド・テスト

Windows、MSVC Build Tools / Windows SDK、Rust の以下のターゲットが必要です。
JVM 統合テストはプロジェクト共通の JDK / Android SDK 設定も使います。

```powershell
rustup target add i686-pc-windows-msvc x86_64-pc-windows-msvc
.\windows-module-host\Test.ps1
```

依存を取得済みなら `-Offline` を付けられます。スクリプトは x86 / x64 をそれぞれビルドし、
リポジトリ内のテスト用 DLL をコンパイルして、Rust と JVM の統合テストを実行します。
環境変数が欠けた Windows CI ではテストをスキップせず失敗します。

確認するものは、日本語と空白を含むパス、`loadu` の優先と `load` へのフォールバック、
任意のバイト列、null 応答、不正なサイズ、必須エクスポートの欠落、アンロード、
DLL の標準出力、異常終了、応答停止時のタイムアウトです。
一般配布のゴーストを使った互換性の確認はまだ行っていません。

## 通信形式

標準入力・標準出力で `[1 byte opcode/status][4 bytes length, little endian][payload]` を往復します。
長さの上限は 16 MiB。起動時の成功フレームの内容は ASCII `PONO-MODULE/1` です。
DLL の標準出力は標準エラーに向け、プロトコルのハンドルは継承不可の複製を使います。

| コマンド | 内容 |
|---|---|
| 1 | UTF-8 の DLL 絶対パスでロード |
| 2 | DLL の request にバイト列をそのまま渡す |
| 3 | アンロード |
| 4 | アンロードして終了 |

| ステータス | 意味 |
|---|---|
| 0 | 成功。request の場合は DLL の生応答 |
| 1 | load が FALSE を返した |
| 2 | ホストのエラー。本文は UTF-8 |
| 3 | request が null を返した |

テキストプロトコルと文字コードは JVM の呼び出し側が扱います。
`ProcessShiori` は `ShioriSession` から使い、時間切れではホストを強制終了します。
Wine の子プロセス全体の停止や SAORI over DirectSSTP コールバックは今後のランタイム接続で実装する必要があります。

仕様: [DLL 共通仕様](https://github.com/ukatech/ukadoc-spec/blob/main/spec/dll.md)、
[HEADLINE/2.0](https://github.com/ukatech/ukadoc-spec/blob/main/spec/headline.md)。
