# SHIORI 対応状況

更新: 2026-11-20

目標は全ての栞への対応。Windows 専用 DLL、古い栞、ゴースト独自の栞も対象とする。
この表は現時点で確認した実装と候補の台帳であり、世の中の全ての栞を列挙し終えたものではない。
組み込み済みという表示は、全ゴースト・全 SAORI の完全互換を意味しない。

## Android に組み込むエンジン

| エンジン | 選択される DLL 名 | 名前を変更した DLL の判定材料 |
|---|---|---|
| YAYA | yaya.dll / aya.dll / aya5.dll | yaya.txt / aya.txt |
| 里々 | satori.dll | satori_conf.txt、または保存ファイルと辞書 |
| 華和梨 8 | kawari.dll / kawari8.dll | kawarirc.kis |
| 華和梨 7.5.0 | kawari7.dll / kawari.dll（下記参照） | kawari.ini |
| Kagari | kagari.dll | index.lua |
| Minato | minato.dll | config.toml と talks/main.mnt |
| Pasta | pasta.dll | pasta.toml |
| QuickSHIORI | quickshiori.dll | index.js |
| なし栞 2 | nashi.dll | ghost.json / nashi.json の format が nashi/1、scripts が配列 |

実装の正本は `ShioriEngine.forGhost` と CMake のビルド対象。
`aya.dll` / `aya5.dll` は現在 YAYA で実行する。AYA の全世代との同一動作を保証するものではない。
既知の DLL 名が優先され、その次に辞書を判定する。
ただし `kawari.dll` は 7 / 8 共通の名前なので、`kawari.ini` だけがあれば 7、`kawarirc.kis` があれば 8 を選ぶ。

## 華和梨 7 の移植

- ソース: [kawari7](https://github.com/kawari/kawari7)、固定コミット `6b2586c4579e6071b5bedc58fc091d1b46a3c8af`。BSD 系の `license.txt` 全文をアプリに同梱する。
- 華和梨 8 と独立した `libkawari7.so` として、7.5.0 の辞書・インラインスクリプト・暗号化辞書の処理をビルドする。
- ソースを直接変更せず、生成コピーで Shift_JIS のバイトをエスケープし、64 bit の文字列位置、Sender の末尾除去、noresident SAORI の読み込みを修正する。
- SAORI の空の Argument ヘッダを保持し、空・不正な応答を読んでも文字列の範囲外を参照しないようにする。華和梨 7 本来の構文では空の引用符だけの引数は省略されるため、空文字を渡す試験には未定義エントリの展開を使う。
- JNI は辞書とロケールをインスタンスごとに保持する。SAORI は既存の内蔵・移植済みモジュールに接続し、ファイル操作には既存のサンドボックスを適用する。
- 共通の終了処理でも呼び出し元の SAORI ハンドラと JNI スレッドを設定する。複数のゴーストを動かした後の OnUnload も、そのゴーストのハンドラへ渡す。
- 共通の `ShioriSession` に SHIORI/2.6 経路を追加。Version / String / Sentence、OnCommunicate、OnTeach、OnTranslate、ownerghostname / otherghostname を変換する。3.0 の一般 NOTIFY に相当する 2.x イベントは送らない。
- 読み込み直後に GET Version で文字コードを確認する。forceencoding と escape_unknown は旧栞にも適用する。応答の Sentence / String / Version を Value に、To と追加 Reference を 3.0 の会話形式に合わせる。
- `Kawari7NativeTest` は日本語辞書・要求、旧プロトコル、SAORI の 3 読込モード、2 インスタンスの文字コード、別プロセス接続、保存と再読み込み、書込範囲を検証する。CI では既存のなし栞 5 件と合わせて 11 件を API 26 / 35 で実行する。
- 日本語ファイル名や Windows のパス表記を含む全辞書、および実配布ゴースト全体の動作比較は未完了。任意の Windows DLL を動かす機能ではない。

## なし栞 2 の移植

- ソース: [nashi-shiori-studio-2](https://github.com/ukagaka-irono/nashi-shiori-studio-2)、固定コミット `347c61f244532027399d7ea94f63b11f6b7e251f`。
- 原作: [なし栞 + なしスタジオ](https://github.com/miyagawa-yukinterface/nashi-shiori-studio)、作者 月見里 彩葉 (miyagawa-yukinterface)。
- ライセンス: サブモジュールの `LICENSE`（なしライセンス 1.1）。全文と変更内容・原作者をアプリのライセンス画面にも収録する。
- `json.cpp` / `program.cpp` / `interp.cpp` / `shiori.cpp` / `util.cpp` を使用する。Windows の DLL 入口・CRT 代替・SAORI ローダーとエディタはビルドしない。
- `libnashi.so`、JNI、UTF-8 / CP932 変換、POSIX のファイル操作・時刻、同期・非同期 SAORI を追加。辞書とセーブの書き込みは既存サンドボックスを通る。
- Win32 の時刻フィールドで使う `unsigned long` に取得値の幅を合わせ、64 bit Android でも経過時間の計算を保つ。
- 非同期 SAORI は呼び出し元の Java ハンドラを保持し、SHIORI の request が返った後や別の呼び出し中でも同じ相手へ渡す。エンジンをまたぐ SAORI でも同じ Java スレッドのコンテキストを使う。
- SAORI は pono-droid の内蔵・移植済みモジュールを使用する。未知の Windows DLL はエラーとなる。なし栞を追加しても、任意の Windows SAORI を実行できるようにはならない。
- バージョン応答には `-pono` を付け、Windows の配布版と識別できるようにする。

### 検証

```powershell
.\gradlew.bat :shiori-yaya:testDebugUnitTest :app:testDebugUnitTest :app:assemblePreview :shiori-yaya:assembleDebugAndroidTest
```

JVM テストは、DLL 名の指定、JSONC / CP932 / BOM の辞書判定、他形式や不正な JSON の拒否、非同期呼び出し先の保持を確認する。
Android の `NashiNativeTest` は、JNI 経由の辞書・要求の UTF-8 / CP932、保存と再読み込み、未知の SAORI、SAORI 応答の CP932、非同期の完了を検証する。
Build ワークフローは 4 ABI の APK とテスト APK を作り、後続ジョブで API 26 / 35 の x86_64 エミュレータにテスト APK を入れて実行する。
ローカル PC に接続済み端末・AVD はないため、テスト APK のコンパイル成功と端末での実行成功を区別する。CI の結果は該当コミットの実行結果を参照する。

2026-11-15（日本時間）の確認結果:

| 検証 | 結果 |
|---|---|
| 追加した JVM 回帰テスト | 8 件成功（辞書判定 6 件、SAORI コンテキスト 2 件） |
| API 26 / x86_64 の Android エミュレータ | `NashiNativeTest`: 5 件成功 |
| API 35 / x86_64 の Android エミュレータ | `NashiNativeTest`: 5 件成功 |
| APK とテスト APK | arm64-v8a / armeabi-v7a / x86 / x86_64 の 4 ABI でビルド成功 |
| 既存の baseware / app 単体テスト | CI 成功 |
| Windows DLL ホスト | x86 / x64 の CI 成功 |

対象コードのコミットは `1f4f8732e615e518ad6ec9e02b0cb79d414ab23e`。
[APK・Android 試験の実行結果](https://github.com/ukagaka-irono/pono-droid/actions/runs/43607029503)、
[Windows ホストの実行結果](https://github.com/ukagaka-irono/pono-droid/actions/runs/43607029485)。
実配布ゴーストの全機能や、物理 Android 端末での動作まで確認できたことを意味しない。

## 追加対応が必要な系統

| 対象・一次資料 | 現状 | 必要な作業 |
|---|---|---|
| [蒼空](https://github.com/kanadelab/aosora-shiori) | 未組み込み | `license.txt` は配布 DLL の SHIORI / SAORI 利用と配布を認める一方、ソースの利用は個人利用・プロジェクト参加などに制限している。Android 移植版の APK 同梱は現条件から許可を確認できないため保留。配布 DLL を動かす経路も対象とする |
| [AYA 5](https://github.com/ponapalt/aya5-shiori) | YAYA の互換経路のみ | readme.txt は改変・再配布を許可。旧ソース専用ビルド、Boost.Regex 依存、CP932・64 bit・Android の API 差分と旧ゴーストでの比較が必要 |
| [MiyoJS](https://github.com/Narazaka/miyojs) | 未組み込み | Node.js とモジュール依存を調査し、実行環境を移植する。QuickSHIORI の JavaScript エンジンとは別物として扱う |
| [phiori](https://github.com/phiori/phiori) | 未組み込み | Python を使う DLL の実行環境、依存ライブラリ、Android 向けアダプタと配布条件の確認 |
| [ShioriSharp](https://github.com/Narazaka/ShioriSharp) / [dnproxy](https://github.com/nikolat/dnproxy) | 未組み込み | .NET 系の栞・プロキシと、それぞれのゴースト固有コードを扱う経路の調査 |
| [PowerSHIORI](https://github.com/ukatech/powershiori) | 未組み込み | PowerShell / .NET Framework を使う Windows DLL。Windows 実行環境と PowerShell のホストが必要 |
| [shioruby](https://github.com/Narazaka/shioruby) / [shiolink-ruby](https://github.com/Narazaka/shiolink-ruby) | 未組み込み | Ruby の実行環境、SHIOLINK プロセス、追加 gem / native extension の扱い |
| [aktk](https://github.com/kinokon/aktk) / [dktrtk](https://github.com/kinokon/dktrtk) / [krht](https://github.com/kinokon/krht) | 未組み込み | それぞれ mruby / AngelScript / Tcl を使う栞。各言語のランタイム、DLL アダプタ、ゴーストの依存機能が必要。確認したルートと ReadMe.txt では本体の再配布条件を特定できていないため、ソースの APK 同梱前に確認を続ける |
| [cshiori](https://github.com/Narazaka/cshiori)、Go / Rust / Nim / Zig 等で書かれた独自栞 | 個別ゴーストの生成 DLL は未対応 | 開発用ライブラリを追加するだけでは、既に配布された各ゴーストの DLL は動かない。ソース別の Android ビルドか Windows DLL 実行環境が必要 |
| 美坂、偽栞、文の旧世代、結奈、翡翠、里珠、忍、Lisp 系など | 未対応・調査継続 | 原配布物・版・ソース・配布条件の特定、または Windows DLL 経路。名称だけで既存エンジンへ割り当てない |
| 未知・非公開・ゴースト専用の SHIORI DLL | 未対応 | 下記の共通 Windows DLL 実行経路 |

`tama` は YAYA / AYA の動作確認・ログ閲覧ツールであり、追加の栞として数えない。
参照: [tama の README](https://github.com/YAYA-shiori/tama)。

## 共通 Windows DLL 経路

`windows-module-host` は Windows 上の x86 / x64 DLL を実行するための ABI ホストであり、Android 上の Windows 実行環境ではない。
Wine・命令変換・ファイルとプロセスの管理を Android の起動経路へ接続し、SHIORI / SAORI と Windows 依存機能を実際に試験する必要がある。
未対応の DLL 名を登録しただけで対応済みとはしない。詳細は [SSP 移植の進捗](ssp-porting.md) を参照。
