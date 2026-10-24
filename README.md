# pono-droid

伺か（ukagaka）のゴーストを Android で動かすベースウェアです。
ゴーストを他のアプリの上に常駐させ、SHIORI（ゴーストの頭脳）には [YAYA](https://github.com/YAYA-shiori/yaya-shiori)・[里々](https://github.com/ukatech/satoriya-shiori)・[華和梨](https://github.com/kawari/kawari)・[Kagari](https://github.com/Tatakinov/kagari_shiori) を Android 向けにビルドして使います。

> 開発中のソフトウェアです。動作確認は moto g05（Android 15）と YAYA 製のゴースト1体で行っています。

## できること

- `.nar` ファイルからゴーストをインストール
- 他のアプリやホーム画面の上にキャラクター（`\0` / `\1`）と吹き出しを表示
  - シェルの透過（PNG のアルファ / `.pna` / 左上の色）、`surfaces.txt` の element と SERIKO アニメーション（新旧両形式）
  - ドラッグで移動（当たり判定の上からならなでる）、大きさはアプリの画面で変更
- さくらスクリプトの再生（文字送り、ウェイト、`\s` `\n` `\c` `\x` `\e` `\-`、選択肢 `\q` `\__q`、`\![raise,...]`、`\![set,balloontimeout,...]` など）
- SHIORI/3.0 のイベント
  - 起動・終了: `OnFirstBoot` / `OnBoot` / `OnClose`
  - 時間: `OnSecondChange` / `OnMinuteChange`（画面が消えている間は止まる）
  - タップ: `OnMouseClick`（1回）/ `OnMouseDoubleClick`（2回）/ `OnMouseMultipleClick`（3回以上）。当たり判定（collision）の部位名つき
  - 選択肢: `OnChoiceSelectEx` → `OnChoiceSelect`。ID が `On` で始まればそのイベントを、`http(s)://` ならブラウザを開く
- SSP 互換のネットワーク更新（`updates2.dau` / `updates.txt`、MD5 で差分だけ取得し、すべて揃ってから入れ替え）
- 辞書の文字コードは UTF-8 と Shift_JIS（CP932）

## 使い方

1. APK をインストールして起動します（Android 8.0 以上）。
2. 「他のアプリの上に重ねて表示」を許可します。通知の許可も求められます（常駐中の通知から終了・更新するため）。
3. 「.nar をインストール」からゴーストの `.nar` を選びます。
4. 一覧の「起動」を押すと、ゴーストが画面に出てきます。

| 操作 | 動き |
|------|------|
| キャラクターをタップ | `OnMouseClick`。続けて叩くと `OnMouseDoubleClick`、3回目以降は `OnMouseMultipleClick` |
| キャラクターをドラッグ | 移動（位置は覚えておきます。`OnMouseDragStart` / `OnMouseDragEnd`）。当たり判定（頭など）の上から指を動かしたときは移動せず、なでる（`OnMouseMove`） |
| 当たり判定の上をさっと払う | `OnMouseGesture`（8方向）。上下に払ったのにゴーストが答えなければ `OnMouseWheel` |
| 2本指で上下になぞる・2本指で叩く | `OnMouseWheel`（ホイールの代わり）・中クリック（`OnMouseDownEx` / `OnMouseUpEx` / `OnMouseClickEx`） |
| 長押しメニューの「隠す」・通知の「表示」 | キャラクターを隠す・出し直す（`OnWindowStateMinimize` / `OnWindowStateRestore`） |
| 長押しメニューの「位置を戻す」 | `OnResetWindowPos`。ゴーストが答えなければ最初の位置に戻す |
| 吹き出しをタップ | `\x` の待ちを進める / 話し終わっていれば閉じる |
| 他のアプリでファイルを「共有」→「ゴーストに渡す」 | 起動中のゴーストにファイルを渡す（`OnFileDrop2`）。`.nar` ならインストール |
| 通知の「ネットワーク更新」、アプリの「更新」 | 起動中のゴーストをネットワーク更新 |
| 通知の「終了」、アプリの「終了」 | `OnClose` を送ってゴーストを終了 |

メニューの開き方はゴーストによって違います（ダブルタップ、3回タップなど）。右クリックは長押し、中クリックは2本指で叩くか長押しメニューの「ゴーストのメニュー（中クリック）」で代わりにします。キー入力で開くゴーストは、スマートフォンでは操作できません。

## ビルド

### 必要なもの

- JDK 17（Gradle 自体は新しい JDK でも動きます）
- Android SDK（compileSdk 37）
- Android NDK 29.0.14206865 と CMake（SDK のライセンスに同意していれば、ビルド時に自動でインストールされます）
- Git

### 手順

SHIORI（YAYA・里々・華和梨・Kagari）とその依存ライブラリ（Lua・sol2 など）は git サブモジュールです。必ずサブモジュールごと（`--recursive`）取得してください。

```bash
git clone --recurse-submodules https://github.com/ukagaka-irono/pono-droid.git
```

すでにクローンしてある場合:

```bash
git submodule update --init --recursive
```

`local.properties` に SDK の場所を書くか（`sdk.dir=...`）、環境変数 `ANDROID_HOME` を設定してからビルドします。

```bash
./gradlew :app:assembleDebug
```

APK は `app/build/outputs/apk/debug/app-debug.apk` にできます。

### テスト

```bash
./gradlew :baseware:test
```

端末をつないで、SHIORI を端末上で動かすテスト（YAYA の UTF-8 / Shift_JIS の辞書と2体同時、里々・華和梨・Kagari の辞書、サンドボックス）:

```bash
./gradlew :shiori-yaya:connectedDebugAndroidTest
```

> このタスクは、テストがすべて通っても終了コード 1 で失敗扱いになることがあります（原因調査中）。結果は `shiori-yaya/build/reports/androidTests/` で確認してください。

## リリース

`v0.2.0` のようなタグをプッシュすると、GitHub Actions（`.github/workflows/release.yml`）が署名した APK を作り、GitHub Releases に載せます。
`versionName` はタグから（`v0.2.0` → `0.2.0`）、`versionCode` はワークフローの通し番号から決まります。`-` を含むタグ（`v0.2.0-beta.1` など）はプレリリースになります。

### 最初に一度だけ: 署名鍵を作ってシークレットに登録する

署名鍵はリポジトリに入れないでください。なくすと同じアプリとして更新できなくなるので、手元で安全に保管します。

1. 鍵を作ります（合言葉を聞かれます）。

   ```bash
   keytool -genkeypair -keystore pono-droid-release.jks -storetype PKCS12 -alias pono-droid -keyalg RSA -keysize 4096 -validity 10000
   ```

2. GitHub に登録するため、base64 にします。

   ```bash
   base64 -w0 pono-droid-release.jks > pono-droid-release.jks.base64
   ```

3. リポジトリの Settings → Secrets and variables → Actions に、次の4つを登録します。

   | 名前 | 中身 |
   |------|------|
   | `PONO_KEYSTORE_BASE64` | 2. で作ったファイルの中身 |
   | `PONO_KEYSTORE_PASSWORD` | キーストアの合言葉 |
   | `PONO_KEY_ALIAS` | 鍵の別名（上の例では `pono-droid`） |
   | `PONO_KEY_PASSWORD` | 鍵の合言葉（PKCS12 ではキーストアと同じ） |

### リリースする

`develop` を `main` に取り込んでから、`main` にタグを付けます。

```bash
git checkout main
git merge --ff-only develop
git push origin main
git tag v0.2.0
git push origin v0.2.0
```

手元で署名付きのビルドをするときは、同じ名前の環境変数（`PONO_KEYSTORE_FILE` に .jks のパス、ほかの3つ）を設定して `./gradlew :app:assembleRelease` を実行します。設定が無ければ署名なしの APK になります。

## ブランチ

| ブランチ | 役目 |
|----------|------|
| `main` | リリースしたもの。リリースのときだけ `develop` から取り込み、タグ（`v0.2.0` など）を付ける |
| `develop` | 開発中のもの。ふだんの作業はここに取り込む |
| `feature/名前` | 新しい機能。`develop` から切り、終わったら `develop` に取り込んで消す |
| `fix/名前` | 不具合の修正。`develop` から切り、`develop` に取り込んで消す。リリース済みの版を急いで直すときは `main` から切って `main` に取り込み、`develop` にも取り込む |

GitHub Actions のビルドとテストは、どのブランチへのプッシュとプルリクエストでも走ります。

## 構成

```
app/          Android アプリ（ゴースト一覧、オーバーレイの Service とウィンドウ、Compose の UI）
baseware/     Android に依存しない Kotlin。descript / surfaces.txt の読み込み、SHIORI/3.0、
              さくらスクリプト、ゴーストの制御（GhostController）、ネットワーク更新
shiori-yaya/  SHIORI を NDK でビルドする Android ライブラリ（JNI、別プロセスで動かすサービス）
  src/main/cpp/yaya   YAYA 本体（サブモジュール。手を加えていません。以下のサブモジュールも同じ）
  src/main/cpp/satori 里々
  src/main/cpp/kawari 華和梨
  src/main/cpp/kagari Kagari（辞書を Lua で書く SHIORI）。lua（5.4）と sol2 を一緒にビルドする
  src/main/cpp/shim   Android 向けの差し替え（CP932 対応の iconv、statx / shm_open、ゴーストにできることを絞る pono_sandbox）
```

SHIORI を Android で動かすための工夫:

- SHIORI はエンジンごとに別の .so（libyaya・libsatori・libkawari・libkagari）にし、アプリとは別のプロセス（`:shiori`）で動かして Binder（AIDL）でやりとりします。SHIORI の中で落ちてもアプリは落ちず、ゴーストを閉じて知らせます。辞書が無限ループして応答しなくなったときも、時間切れでそのプロセスを止めてゴーストを閉じます。
- SHIORI が呼ぶ libc の関数（`dlopen` / `fork` / `system` / `popen` / `open` など）をリンク時の `--wrap` で `shim/pono_sandbox.cpp` に差し替え、辞書が触れるファイルをゴーストのディレクトリに限っています（下の「ゴーストにできること」）。

- YAYA と里々のソースは Shift_JIS なので、ビルド時に Gradle で UTF-8 に変換してからコンパイルします（`#line` で元のファイルを指すので、エラー位置はサブモジュール側を指します）。華和梨は文字列が ASCII だけなのでそのまま、C++98 でコンパイルします。
- どの SHIORI を使うかは descript.txt の `shiori`（`yaya.dll`・`satori.dll`・`kawari.dll`・`kagari.dll` など）で決め、`shiori.dll` としか書かれていなければ ghost/master の辞書の設定ファイル（`yaya.txt`・`kawarirc.kis`・`index.lua` など）で見分けます。
- Android の libc の iconv は CP932 を持たないため、Java の `windows-31j` から変換表を作り、自前の iconv に差し替えています。
- サブモジュールは書き換えず、足りない関数は強制インクルードするヘッダで補っています。

## SSP との互換性

SSP（Windows の伺かベースウェア）と同じイベント・タグをできるだけ送り、同じように振る舞うようにしています。

- イベント: OnFirstBoot / OnBoot / OnClose、OnSecondChange / OnMinuteChange / OnHourTimeSignal、OnMouseDown / OnMouseUp / OnMouseClick / OnMouseDoubleClick / OnMouseMultipleClick（長押しは右クリック扱い）、OnMouseMove（当たり判定の上で指を動かす＝なでる）、OnMouseEnter / OnMouseLeave / OnMouseEnterAll / OnMouseLeaveAll（指が触れた・離れた・なでる指が別の当たり判定に移った）、OnMouseDragStart / End、OnMouseGesture、OnMouseWheel、OnMouseDownEx / UpEx / ClickEx、OnOverlap / OnOffscreen、OnResetWindowPos、OnShellScaling、OnWindowStateMinimize / Restore、OnSurfaceRestore（バルーンが閉じて 15 秒後に1回）、OnSysSuspend / OnSysResume（画面が消えたら休止、ロックを解いたら復帰）、OnNetworkStatusChange（起動時は NOTIFY、以後はつながる・切れる・種類やアドレスが変わったとき）、OnFileDrop2（Android の「共有」で渡したファイル。ゴーストのフォルダの `pono/drop/` に写して渡す。答えが無ければ OnFileDropEx）、OnChoiceSelectEx / OnChoiceSelect / OnChoiceTimeout、OnAnchorSelectEx / OnAnchorSelect、OnBalloonClose / OnBalloonTimeout / OnBalloonBreak、OnSurfaceChange、OnTranslate、OnUserInput / OnCommunicate / OnTeach とその取り消し、OnUpdate*、OnExecuteHTTPComplete / Failure、OnBatteryNotify（と Low / Critical / ChargingStart / ChargingStop）、OnDisplayChange、起動時の OnNotifySelfInfo / BalloonInfo / ShellInfo / DressupInfo / UserInfo / OSInfo / InternationalInfo
- SHIORI リソース: `homeurl`（descript.txt より優先）、`username`
- タグ: 文字と待ち（`\0 \1 \p \s \i \n \c \w \_w \_q \x \e \-`）、選択肢とリンク（`\q \__q \_a`、`script:` の ID）、`\f` の文字装飾（bold / italic / underline / strike / color / height / align / sub / sup / default）、`\_u \_m \&`、`\b[n]`、`\_l`、`\![raise] \![notify] \![embed] \![timerraise] \![get,property]` と `%property[...]`（system.* / baseware.* / ghostlist / balloonlist / currentghost の一部）、`\![open,inputbox / passwordinput / communicatebox / teachbox / readme / configurationdialog]`、`\![set,balloontimeout / choicetimeout]`、`\![bind]`、`\![execute,http-get]`、`\![updatebymyself]`、`\![reload,shiori]`、`\![enter,passivemode]`
- サウンド: `\8` `\_v` `\_V` `\![sound,play / loop / stop / wait]`（`--volume`）。ゴーストのディレクトリの中のファイルだけ鳴らします。
- 切り替え: `\![change,shell]` / `\![change,balloon]`（random も可）と OnShellChanging / OnShellChanged / OnBalloonChange、着せ替えの OnDressupChanged。
- ゴーストの切り替え: `\![change,ghost,名前]`（random / sequential / lastinstalled、`--option=raise-event`）と長押しメニューの「ゴースト切り替え」。メニューからなら OnGhostChanging（答えが無ければ OnClose）で別れを話してから切り替え、切り替わった先には OnGhostChanged（答えが無ければ OnBoot）を送ります。
- 見た目: `\_s`（`\_s[ID,...]`）の同期、バルーンの中の画像 `\_b[ファイル,x,y]` / `\_b[ファイル,inline]`（`opaque`）、`\![set,scaling]`（反転も可）、`\![set,alpha]`、`\![anim,offset / clear / stop]`、SERIKO の `insert`（着せ替えの重なり順）と `parallelstart` / `parallelstop`、`talk,N`（話している間 N 文字ごとに動く口パクなど）と `yen-e`（話し終わりに動く）、サーフェスの別名（`sakura.surface.alias` / `kero.surface.alias` / `charN.surface.alias`、`alias.txt`、surfaces.txt の `name`。候補が複数ならランダム）。
- ウィンドウ: `\![move]` / `\![moveasync]`（新旧どちらの書式も。基準は screen・me・スコープ番号、`--base-offset` / `--move-offset`）、`\![set,position]` / `\![reset,position]`。座標は画面のピクセルで、画面の外には出しません。`\![set,zorder]`（重なり順）、`\![set,sticky-window]`（一緒に動く組。`\![move]` の `--option=ignore-sticky-window` で外せます）、`\![set,alignmenttodesktop]`（画面の端に付ける）、`\![set,windowstate,minimize]`、`\![execute,resetwindowpos]`、`\4` / `\5`（相方から離れる・寄る）、`\v`（手前に出す）、`sakura.defaultx` などの最初の位置。動かした後の重なりと画面からのはみ出しは OnOverlap / OnOffscreen、アプリでキャラクターの大きさを変えたら OnShellScaling で知らせます。
- 入力: `\![open,dateinput / timeinput / sliderinput / textareabox]`（OnUserInput と OnTextAreaInput）。入力の箱は SSP と同じく \0 のバルーンの位置に出します。バルーンに `balloonc0.png`（複数行は `balloonc5.png`）があればそれを敷いて `communicatebox.x / y / width / height` の範囲に入力欄を置き、無ければ簡易バルーンと同じ見た目の箱にします。戻るボタンで取り消せます。
- 長押しのメニューは SSP のオーナードローメニューに合わせて、SHIORI の `sakura.recommendsites` / `portalsites`（と `*buttoncaption`）、きせかえ、シェル、バルーン、Read Me、ネットワーク更新を出します。おすすめを選ぶと OnRecommendsiteChoice を送ります。
- Windows のウィンドウやトレイ向けのタグ（`\![set,trayicon]`、`\![lock,repaint]`、`\6` / `\7` など）は黙って読み飛ばします。

## 既知の制限

- SHIORI は YAYA・里々・華和梨・Kagari に対応しています。美坂・偽林檎など、ほかの SHIORI のゴーストは動きません。
- SAORI（YAYA の `LOADLIB` / `REQUESTLIB`、里々・華和梨の SAORI）、YAYA の `EXECUTE`、Kagari（Lua）の C モジュール・`os.execute`・`io.popen` は、ゴーストにネイティブコードやシェルを動かさせないために止めています。これらに頼るゴーストは一部の機能が動きません。
- `\_l` による選択肢の横並びは、座標どおりではなく列に分けて並べます。
- サウンドは同時に鳴らせるのが1回分とループ1つまでです。
- `\![move]` で他のゴーストを基準にすることはできません（複数ゴーストの同時起動が無いため）。`point.basepos` は見ず、`base` はウィンドウの中央・下端として扱います。
- 楕円・多角形の当たり判定は外接矩形で近似します。
- オーバーレイの透明な部分もタッチを受け取ります。Android の制約で画素単位では透過できないため、キャラクターと吹き出しを別々のウィンドウにして、塞ぐ範囲をそれぞれの矩形に絞っています。
- ネットワーク更新: シェル画像の更新は次の起動から反映されます。
- インストールできるのはゴーストとバルーンの `.nar`（シェル単体は不可）。複数ゴーストの同時起動は未対応です。
- SSTP には未対応です。

## ゴーストにできること（セキュリティ）

ゴーストはインターネットから持ってくる第三者のプログラムなので、pono-droid の中でできることを絞っています。

- 辞書（YAYA・里々・華和梨・Kagari）が読み書きできるのは、そのゴーストのディレクトリの中だけです。アプリの他のデータ（別のゴースト、設定）は読めません。システムのファイル（タイムゾーンなど）は読むだけなら許しています。
- SAORI（`LOADLIB` / `REQUESTLIB` など）、`EXECUTE`、Lua の C モジュール・`os.execute`・`io.popen` は失敗します（`dlopen`・`fork`・`exec`・`system`・`popen` をリンク時に差し替えています）。
- スクリプトから開ける URL は http / https だけです。
- 画像の大きさ（1辺 4096px、800 万画素まで）と surfaces.txt の定義の数には上限があり、超えたものは無視します。
- ネットワーク更新と `.nar` のインストールで書けるのはゴーストのディレクトリの中だけで、ファイルの大きさと数にも上限があります。homeurl が `http://` でも、まず `https://` で試し、通らなければ書かれたとおり `http://` で更新します（この場合は経路上で書き換えられるおそれが残ります）。
- 入力の箱に打った文字はゴーストにそのまま渡ります。他のサービスのパスワードなどは入れないでください。打った文字はログに残しません。
- 辞書が無限ループして応答しなくなったら、60 秒で SHIORI のプロセスを止めてゴーストを閉じます。`\![raise]` の連鎖と `http-get` の同時実行にも上限があります。

## ライセンス

pono-droid は [Apache License 2.0](LICENSE) で公開しています。

```
Copyright 2026 ukagaka-irono

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

同梱・利用しているソフトウェアは次のとおりです（[NOTICE](NOTICE) も参照）。ライセンスの全文はアプリの「ライセンス」画面で確認できます。SHIORI とその依存ライブラリはサブモジュールのまま配布し、手を加えていません。

| 名前 | ライセンス |
|------|------------|
| [YAYA](https://github.com/YAYA-shiori/yaya-shiori) | BSD 3-Clause |
| Mersenne Twister (MT19937) | BSD 3-Clause |
| [parson](https://github.com/kgabis/parson) | MIT |
| [TinyXML-2](https://github.com/leethomason/tinyxml2) | zlib |
| [Gumbo](https://github.com/google/gumbo-parser) | Apache-2.0（UTF-8 デコーダは MIT） |
| [uka_regexp](https://github.com/ukatech/uka_regexp) | Unlicense |
| [里々（SATORI）](https://github.com/ukatech/satoriya-shiori) | BSD 2-Clause |
| [華和梨（KAWARI）](https://github.com/kawari/kawari) | BSD |
| [Kagari](https://github.com/Tatakinov/kagari_shiori) | MIT |
| [Lua](https://www.lua.org/) | MIT |
| [sol2](https://github.com/ThePhD/sol2) | MIT |
| [SQLite](https://www.sqlite.org/) | Public Domain |
| Android Jetpack / Kotlin / kotlinx.coroutines | Apache-2.0 |
