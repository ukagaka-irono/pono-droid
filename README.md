# pono-droid

伺か（ukagaka）のゴーストを Android で動かすベースウェアです。
ゴーストを他のアプリの上に常駐させ、SHIORI（ゴーストの頭脳）には [YAYA](https://github.com/YAYA-shiori/yaya-shiori) を Android 向けにビルドして使います。

> 開発中のソフトウェアです。動作確認は moto g05（Android 15）と YAYA 製のゴースト1体で行っています。

## できること

- `.nar` ファイルからゴーストをインストール
- 他のアプリやホーム画面の上にキャラクター（`\0` / `\1`）と吹き出しを表示
  - シェルの透過（PNG のアルファ / `.pna` / 左上の色）、`surfaces.txt` の element と SERIKO アニメーション（新旧両形式）
  - ドラッグで移動、大きさはアプリの画面で変更
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
| キャラクターをドラッグ | 移動（位置は覚えておきます） |
| 吹き出しをタップ | `\x` の待ちを進める / 話し終わっていれば閉じる |
| 通知の「ネットワーク更新」、アプリの「更新」 | 起動中のゴーストをネットワーク更新 |
| 通知の「終了」、アプリの「終了」 | `OnClose` を送ってゴーストを終了 |

メニューの開き方はゴーストによって違います（ダブルタップ、3回タップなど）。右クリック・中クリック・キー入力で開くゴーストは、今のところスマートフォンでは操作できません。

## ビルド

### 必要なもの

- JDK 17（Gradle 自体は新しい JDK でも動きます）
- Android SDK（compileSdk 37）
- Android NDK 29.0.14206865 と CMake（SDK のライセンスに同意していれば、ビルド時に自動でインストールされます）
- Git

### 手順

YAYA とその依存ライブラリは git サブモジュールです。必ずサブモジュールごと取得してください。

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

端末をつないで、YAYA を端末上で動かすテスト（UTF-8 / Shift_JIS の辞書、2体同時）:

```bash
./gradlew :shiori-yaya:connectedDebugAndroidTest
```

> このタスクは、テストがすべて通っても終了コード 1 で失敗扱いになることがあります（原因調査中）。結果は `shiori-yaya/build/reports/androidTests/` で確認してください。

## 構成

```
app/          Android アプリ（ゴースト一覧、オーバーレイの Service とウィンドウ、Compose の UI）
baseware/     Android に依存しない Kotlin。descript / surfaces.txt の読み込み、SHIORI/3.0、
              さくらスクリプト、ゴーストの制御（GhostController）、ネットワーク更新
shiori-yaya/  YAYA を NDK でビルドする Android ライブラリと JNI
  src/main/cpp/yaya   YAYA 本体（サブモジュール。手を加えていません）
  src/main/cpp/shim   Android 向けの差し替え（CP932 対応の iconv、statx / shm_open）
```

YAYA を Android で動かすための工夫:

- YAYA のソースは Shift_JIS なので、ビルド時に Gradle で UTF-8 に変換してからコンパイルします（`#line` で元のファイルを指すので、エラー位置はサブモジュール側を指します）。
- Android の libc の iconv は CP932 を持たないため、Java の `windows-31j` から変換表を作り、自前の iconv に差し替えています。
- サブモジュールは書き換えず、足りない関数は強制インクルードするヘッダで補っています。

## 既知の制限

- SHIORI は YAYA のみ（`yaya.dll` / `aya.dll` / `aya5.dll` と書かれたゴースト）。里々などは動きません。SAORI（DLL のプラグイン）も使えません。
- 吹き出しは簡易的なもので、ゴーストに同梱のバルーン画像は使いません。選択肢は1行に1つずつ並べます（`\_l` による横並びは無視）。
- SSP 固有の `\![execute,http-get,...]` などには未対応です。
- SERIKO の `start` / `stop` などアニメーションどうしの操作、着せ替え（`bind`）、サーフェスの別名（alias）には未対応です。楕円・多角形の当たり判定は外接矩形で近似します。
- オーバーレイの透明な部分もタッチを受け取ります。Android の制約で画素単位では透過できないため、キャラクターと吹き出しを別々のウィンドウにして、塞ぐ範囲をそれぞれの矩形に絞っています。
- ネットワーク更新: `delete.txt` には未対応。シェル画像の更新は次の起動から反映されます。
- インストールできるのはゴーストの `.nar` のみ（シェル・バルーン単体は不可）。アンインストール、シェルの切り替え、複数ゴーストの同時起動は未対応です。
- SSTP には未対応です。
- YAYA の内部でクラッシュすると、アプリごと終了します。

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

同梱・利用しているソフトウェアは次のとおりです（[NOTICE](NOTICE) も参照）。ライセンスの全文はアプリの「ライセンス」画面で確認できます。YAYA とその依存ライブラリはサブモジュールのまま配布し、手を加えていません。

| 名前 | ライセンス |
|------|------------|
| [YAYA](https://github.com/YAYA-shiori/yaya-shiori) | BSD 3-Clause |
| Mersenne Twister (MT19937) | BSD 3-Clause |
| [parson](https://github.com/kgabis/parson) | MIT |
| [TinyXML-2](https://github.com/leethomason/tinyxml2) | zlib |
| [Gumbo](https://github.com/google/gumbo-parser) | Apache-2.0（UTF-8 デコーダは MIT） |
| [uka_regexp](https://github.com/ukatech/uka_regexp) | Unlicense |
| [SQLite](https://www.sqlite.org/) | Public Domain |
| Android Jetpack / Kotlin / kotlinx.coroutines | Apache-2.0 |
