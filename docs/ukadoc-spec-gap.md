# ukadoc-spec との差

[ukadoc-spec](https://github.com/ukatech/ukadoc-spec)（UKADOC の Markdown 版、b1f8ab1 時点）の項目を1つずつ pono-droid と比べた結果。
項目ごとの状態は [ukadoc-spec-status.tsv](ukadoc-spec-status.tsv)（DONE / PARTIAL / BUILD / IMPOSSIBLE / NA）。作ったら DONE に書き換える。
前に HTML 版の ukadoc から作った一覧は [ssp-gap.md](ssp-gap.md)。

全 2050 項目: 済み 1283・一部だけ 52・作る 96・作れない 281・ベースウェアのことではない 338

## バルーンの descript.txt など

済み 154 項目。

### 🔨 作る（10）
- craftmanurl,URL — バルーンでは使っていない。アプリのバルーンの一覧に作者の URL を出す
- number.yb,座標 — number.y の別名として読むだけ。縦書きの意味は vertical と一緒に
- readme.charset,文字コード — バルーンの readme と一緒に作る。TextCharsets で読む
- readme,ファイル名 — バルーンの readme を開く所が無い（ゴーストだけ）。インストール後とバルーンの一覧から TextViewerActivity で出す
- sstpmessage.yb,座標 — 縦書きのときだけ使う。vertical と一緒に
- vertical,0/1 — 縦書きが無い（balloon.scope.vertical もいつも 0）。Compose に縦書きが無いので1文字ずつ並べる組版が要る
- wordwrappoint.y,座標 — 縦書きのときだけ使う。vertical と一緒に
- 推奨の意味 — 推奨の意味の説明。ゴーストを消すとき推奨バルーンも消すか聞く動きは recommended.ghost と一緒に作る
- recommended.ghost,ゴースト名 — 読んでいない。ほかのゴーストで選ぶとき警告し、そのゴーストを消すとき一緒に消すか聞く
- recommended.ghost.path,パス — recommended.ghost と一緒に。ゴーストのフォルダ名で比べる

### 🔧 一部だけ（3）
- balloons\*s.txt/balloonk\*s.txt — 画像ごとの balloons*s.txt で、文字の範囲・矢印や印の位置・windowposition を上書きする。文字の色・大きさ・書体は descript.txt のまま
- overlay_outside_balloon,数値 — 0 ならオンライン・SSTP の印をバルーンの画像の中に収め、1 なら収めない。ウィンドウを印の分だけ広げてはいない
- use_input_alpha,数値 — 1 なら入力の箱の画像もバルーンと同じ透明度の扱いにする。バルーン全体を半透明にする設定は pono-droid に無い

### ⛔ 作れない（4）
- cursor,ファイル名 / mousecursor,ファイル名 — マウスカーソルが無い（タッチで操作する）
- mousecursor.arrow,ファイル名 — マウスカーソルが無い
- mousecursor.text,ファイル名 — マウスカーソルが無い
- mousecursor.wait,ファイル名 — マウスカーソルが無い

### ― ベースウェアのことではない（4）
- balloons\*s.txt/balloonk\*s.txt/balloonc\*s.txt(個別指定) — 一覧のページ
- descript.txt — 一覧のページ
- descript.txt（Balloonの） — 概要と記述例。中身は各項目で見る
- descript.txt - 推奨ゴースト — 一覧のページ

## ゴーストの descript.txt

済み 54 項目。

### 🔨 作る（5）
- readme.charset,文字コード — readme は UTF-8 か CP932 の自動判定だけ。readme.charset があればそれで読む
- shiori.logo.align,位置情報 — AI グラフを絵で描くとき（今はラベルと値を文字で並べる）の背景画像の基準
- shiori.logo.file,ファイル名 — 同上。背景画像（複数・_dark）
- shiori.logo.x,X座標 — 同上。背景画像の X
- shiori.logo.y,Y座標 — 同上。背景画像の Y

### 🔧 一部だけ（3）
- descript.txt（Ghostの） — 説明のページ。\ や % で始まる値を捨てる決まりはしていない（名前などをメニューのスクリプトにそのまま入れている）
- readme,ファイル名 — メニューと \![open,readme] からは開ける。インストールしたときには開かない
- seriko.alignmenttodesktop,位置情報 — 書いてあればその吸着にする。どこにも書いていないときは bottom でなく自由に動かせる（スマートフォンの画面なので、下の端に並べない）

### ⛔ 作れない（9）
- cursor,ファイル名 / mousecursor,ファイル名 — マウスカーソルが無い（⛔）
- icon,ファイル名 — タスクトレイが無い。常駐の通知の小さいアイコンは替えられない（tasktrayicon と同じ扱い）
- icon.minimize,ファイル名 — 最小化のアイコンを出す場所が無い（最小化は常駐の通知の「表示」で戻す）
- makoto,ファイル名 — MAKOTO は Windows の DLL（⛔）
- mousecursor.arrow,ファイル名 — マウスカーソルが無い（⛔）
- mousecursor.grip,ファイル名 — マウスカーソルが無い（⛔）
- mousecursor.hand,ファイル名 — マウスカーソルが無い（⛔）
- mousecursor.text,ファイル名 — マウスカーソルが無い（⛔）
- mousecursor.wait,ファイル名 — マウスカーソルが無い（⛔）

### ― ベースウェアのことではない（9）
- id,ID名 — 起動ログに出すだけ。pono-droid には起動ログが無い
- descript.txt — 一覧のページ
- 補足 — 位置情報の説明のページ（seriko.alignmenttodesktop の値）
- shiori.cache,数値 — ゴーストキャッシュが無い。切り替えるといつも unload する（0 と同じ）
- shiori.version,バージョン — pono-droid で動く SHIORI（YAYA・里々・華和梨・かがり）はどれも 3.0。2.x は送らない
- title,表示名 — 起動ログに出すだけ。pono-droid には起動ログが無い
- Ghost設定 — 一覧のページ
- 標準と推奨の使い分け — 使い分けの説明。中身は recommended.balloon などの行で扱う
- descript.txt - 推奨バルーン — 一覧のページ

## シェルの descript.txt

済み 81 項目。

### 🔨 作る（5）
- craftman,作者名 — シェルの作者を出すところが無い。シェルの一覧に出す
- craftmanurl,URL — 同上
- craftmanw,作者名 — 同上
- readme.charset,文字コード — シェルの readme を作るとき、この文字コードで読む
- readme,ファイル名 — シェルの readme を開くところが無い。シェルのメニューか一覧から開けるようにする

### ⛔ 作れない（15）
- menu.background.alignment,位置 — メニューはバルーンに出すので、メニューの絵が無い（⛔）
- menu.background.bitmap.filename,ファイル名 — メニューの絵が無い（⛔）
- menu.disable.font.color.b — 押せない項目を出さない（⛔）
- menu.disable.font.color.g — 押せない項目を出さない（⛔）
- menu.disable.font.color.r — 押せない項目を出さない（⛔）
- menu.foreground.alignment,位置 — メニューの絵が無い（⛔）
- menu.foreground.bitmap.filename,ファイル名 — メニューの絵が無い（⛔）
- menu.frame.color.b,数値 — メニューの枠が無い（⛔）
- menu.frame.color.g,数値 — メニューの枠が無い（⛔）
- menu.frame.color.r,数値 — メニューの枠が無い（⛔）
- menu.separator.color.b,数値 — メニューの区切り線が無い（⛔）
- menu.separator.color.g,数値 — メニューの区切り線が無い（⛔）
- menu.separator.color.r,数値 — メニューの区切り線が無い（⛔）
- menu.sidebar.alignment,位置 — メニューの絵が無い（⛔）
- menu.sidebar.bitmap.filename,ファイル名 — メニューの絵が無い（⛔）

### ― ベースウェアのことではない（5）
- id,ID名 — 起動ログに出すだけ。pono-droid には起動ログが無い
- descript.txt — 一覧のページ
- descript.txt（Shellの） — 説明のページ。書いてある項目は各行で扱う
- 補足 — 位置情報・メニュー画像の位置・アイコン枠の説明のページ
- Shell設定 — 一覧のページ

## surfaces.txt

済み 130 項目。

### 🔧 一部だけ（2）
- element\*,描画メソッド,ファイル名,X座標,Y座標(,オプション...) — --clipping / --alpha / --scaling と、動く surface*.png・element はある。--source（PSD のレイヤー・TIFF のページ・アイコン）は読まない。動く画像は SERIKO のコマより上に出る
- auto — overlay として重ねる。--source の PSD などのレイヤーから描画方法を読む部分が無い

### ⛔ 作れない（23）
- cursorブレス記述例 — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- mousedown\*,当たり判定ID,ファイル名 — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- mousehover\*,当たり判定ID,ファイル名 — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- mouserightdown\*,当たり判定ID,ファイル名 — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- mouseup\*,当たり判定ID,ファイル名 — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- mousewheel\*,当たり判定ID,ファイル名 — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- システムカーソル — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:arrow — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:cross — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:finger — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:grip — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:hand — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:help — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:move — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:no — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:text — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- system:wait — cursor ブレスは使わない（マウスカーソルが無い。ssp-gap.md の決め事）
- balloon.offsetx,座標 — サーフェスごとのバルーン位置は使わない（バルーンはいつもキャラクターの上。ssp-gap.md の決め事）
- balloon.offsety,座標 — サーフェスごとのバルーン位置は使わない（バルーンはいつもキャラクターの上。ssp-gap.md の決め事）
- kero.balloon.offsetx,座標 — サーフェスごとのバルーン位置は使わない（バルーンはいつもキャラクターの上。ssp-gap.md の決め事）
- kero.balloon.offsety,座標 — サーフェスごとのバルーン位置は使わない（バルーンはいつもキャラクターの上。ssp-gap.md の決め事）
- sakura.balloon.offsetx,座標 — サーフェスごとのバルーン位置は使わない（バルーンはいつもキャラクターの上。ssp-gap.md の決め事）
- sakura.balloon.offsety,座標 — サーフェスごとのバルーン位置は使わない（バルーンはいつもキャラクターの上。ssp-gap.md の決め事）

### ― ベースウェアのことではない（9）
- cursorブレス — 目次のページ（cursor ブレスはマウスカーソルが無いので使わない）
- descriptブレス — 目次のページ
- maxwidth,ピクセル — SSP も使わない（実際の大きさから決める）
- Shell設定 - surfaces.txt — 目次のページ
- surface.aliasブレス — 目次のページ
- アニメーションインターバル — 目次のページ
- 描画メソッド — 目次のページ
- surfaceブレス — 目次のページ
- tooltipブレス — 目次のページ

## surfacetable.txt

済み 0 項目。

### 🔨 作る（7）
- surfacetable.txt — surfacetable.txt を読んでいない。サーフェステストの一覧の名前・グループ分けに使う（DeveloperActivity の SurfaceTestPage）
- サーフェスID,名前 — サーフェステストの一覧に名前を出す。__parts は名前なし扱い
- charset,文字コード — surfacetable.txt を読むときに charset 行を見る（TextCharsets.decode でよい）
- group,グループ名 — サーフェステストでグループごとに分け、__disabled は出さない
- option,オプション1,オプション2,... — DisableNoDefineSurfaces なら、定義も名前も無いサーフェスをサーフェステストに出さない
- scope,数値 — サーフェステストで、選んだキャラクターのグループを先に出す
- version,数値 — 読むだけ（いつも 1）。surfacetable.txt を読むときに一緒に

### ― ベースウェアのことではない（2）
- Shell設定 - surfacetable.txt — 目次のページ
- surfacetable.txt — 目次のページ

## install.txt・delete.txt・updates

済み 19 項目。

### 🔨 作る（2）
- bootghost,ディレクトリ名 — ゴーストが動いていないときに package を入れたら、bootghost のゴーストを起動する（アプリの画面・共有から）
- 相対パス,ignore — 旧仕様だが SSP は読む。createupdatedata で ignore のファイルを外す（GhostPackager の UPDATE）

### ― ベースウェアのことではない（6）
- delete.txt — 一覧のページ
- developer_options.txt — 一覧のページ
- Install設定 — 一覧のページ
- install.txt — 一覧のページ
- updates_def.txt — 一覧のページ
- 補足 — 旧仕様の説明

## ヘッドライン

済み 0 項目。

### ⛔ 作れない（12）
- alwaysdisplay,数値 — ヘッドラインは作らない（⛔）
- charset,文字コード — ヘッドラインは作らない（⛔）
- dllname,ファイル名 — ヘッドラインは作らない（⛔。Windows の DLL）
- homeurl,URL — ヘッドラインは作らない（⛔）
- descript.txt — ヘッドラインは作らない（⛔）
- descript.txt（Headlineの） — ヘッドラインは作らない（⛔）
- name,ヘッドライン名 — ヘッドラインは作らない（⛔）
- openurl,URL — ヘッドラインは作らない（⛔）
- readme.charset,文字コード — ヘッドラインは作らない（⛔）
- readme,ファイル名 — ヘッドラインは作らない（⛔）
- url,URL — ヘッドラインは作らない（⛔）
- Headline設定 — ヘッドラインは作らない（⛔）

## プラグイン

済み 0 項目。

### ⛔ 作れない（16）
- charset,文字コード — プラグインは作らない（⛔）
- craftman,作者名 — プラグインは作らない（⛔）
- craftmanurl,URL — プラグインは作らない（⛔）
- craftmanw,作者名 — プラグインは作らない（⛔）
- filename,dll — プラグインは作らない（⛔。Windows の DLL）
- homeurl,URL — プラグインは作らない（⛔）
- id,ID — プラグインは作らない（⛔）
- descript.txt — プラグインは作らない（⛔）
- descript.txt（Pluginの） — プラグインは作らない（⛔）
- name,プラグイン名 — プラグインは作らない（⛔）
- otherghosttalk,オプション — プラグインは作らない（⛔）
- readme.charset,文字コード — プラグインは作らない（⛔）
- readme,ファイル名 — プラグインは作らない（⛔）
- secondchangeinterval,秒数 — プラグインは作らない（⛔）
- type,種別 — プラグインは作らない（⛔）
- Plugin設定 — プラグインは作らない（⛔）

## さくらスクリプト

済み 309 項目。

### 🔨 作る（4）
- \![open,addressbar] — URL を打つ箱を出して、共有で URL を渡したのと同じ処理（onTextShared）に回せる。docs では ⛔ だが PC 専用ではない
- \![open,archiveviewer,(ファイル名)] — ファイルを指定されたら、ほかのアプリで開く（\![open,file] と同じ）。ビューア自体は持たない
- \![open,pictureviewer,(ファイル名)] — 画像をほかのアプリで開く（\![open,file] と同じ道筋。OnPictureViewerOpen はもうある）
- sound の --spectrum= で通知される帯域とレベル \[2.9.18\] — MediaCodec で波形を取り出して FFT し OnSoundSpectrum を送る（Visualizer はマイクの許可が要るので使わない）

### 🔧 一部だけ（30）
- \_b\,オプション...\] — --option=… と --clipping / --scaling を読まない（opaque は位置引数のときだけ）
- \_b\,オプション...\] — --option=foreground・fixed、--clipping / --scaling を読まない。画像はいつも文字の下
- \_l[x,y] — x が数のときに次の列にするだけ。y・centerx / centery・em / lh / %・@ の相対を扱わない
- 補足 — \_b の --option=opaque / use_self_alpha / fixed / foreground / background、--clipping、--scaling、--source と base64・URL の画像を読まない
- \![set,wallpaper,ファイル名,オプション] — 配置の stretch-x / stretch-y / span を読まない（center・tile・stretch・fit・fill と --color はある）
- \![enter,inductionmode] — 上の InductionMode と同じ
- \![enter,passivemode] — 上の PassiveMode の制限の多くが無い
- \![execute,dumpballoon,ディレクトリ,スコープID,prefix,イベントID] — --hide=balloonmarker,balloonnum を読まない
- \![execute,install,url,URL,種別] — 種別は nar だけ。homeurl（そこから更新）・ical（予定表として購読）・ssf を作れる。feed はヘッドラインなので作らない
- http/rss/ical/websocket オプション — --log（通信の中身をファイルに残す）が無い。--insecure は安全のため使わない
- InductionMode — induction はパッシブの残りの制限（メールを見ない・切り替えや更新や消去をさせないなど）を持たない
- PassiveMode — タイムアウトしない・メールを見ない・cantalk 0・SSTP 409 だけ。メニュー・スクリプトブレーク・ファイルを渡す・切り替え・最小化・終了・更新・消去を止めない
- \![open,communicatebox] — 位置引数の文字だけ。--text= などのオプションを読まない（--text=… がそのまま入る）
- \![open,dateinput,ID,表示時間,年,月,日,オプション] — 位置引数だけ。--timeout= と --text="…" の書き方と、ほかのオプションを読まない
- \![open,inputbox,ID,表示時間,テキスト,オプション,...] — --timeout= / --text= の新しい書き方と、ほかの InputBox オプションを読まない。キャンセルに答えが無いときの timeout の OnUserInput も無い
- \![open,ipinput,ID,表示時間,IP1桁目,IP2桁目,IP3桁目,IP4桁目,オプション] — --text / --timeout はある。noclose・--balloon・--reference が無い
- \![open,passwordinput,ID,表示時間,テキスト,オプション] — --timeout= / --text= の新しい書き方と、ほかの InputBox オプションを読まない。キャンセルに答えが無いときの timeout の OnUserInput も無い
- \![open,sliderinput,ID,表示時間,現在値,最小,最大,オプション] — 位置引数だけ。--timeout= と --text="…" の書き方と、ほかのオプションを読まない
- \![open,textareabox,ID,表示時間,テキスト,オプション,...] — --timeout= / --text= / --limit= / --placeholder= などを読まない
- \![open,timeinput,ID,表示時間,時,分,秒,オプション] — 位置引数だけ。--timeout= と --text="…" の書き方と、ほかのオプションを読まない
- InputBoxのID — On で始まる ID はある。--reference= の追加情報を Reference に足さない
- InputBoxオプション — --text / --timeout は ipinput だけ。noclose / noclear・--placeholder・--balloon・--limit・--reference が無い
- \!\...\] — --sound-only（動画の音だけ）と --spectrum 系が無い
- \!\...\] — --spectrum 系の開始・止める・間隔の変更が無い
- \!\...\] — --volume / --balance / --rate / --seektime はある。--window と --spectrum 系が無い
- sound play/load/option のオプション — --window・--sound-only・--spectrum / --spectrum-bands / --spectrum-hold が無い
- \![anim,add,overlay,ID] — 最後のアニメーションタイミング runonce / always を読まない
- \![moveasync] — \![moveasync,cancel] で動いている途中を止める、が無い
- \![set,alpha,数値,オプション] — --time=（だんだん変える）と --wait を読まず、すぐ変える
- \![set,scaling,横倍率,縦倍率,オプション] — --time= と --wait を読まず、すぐ変える

### ⛔ 作れない（31）
- \f[anchormethod,描画方法] — Windows GDI のラスタ演算の指定で、Android の描画に同じものが無い（読み飛ばす）
- \f[anchornotselectmethod,描画方法] — Windows GDI のラスタ演算の指定で、Android の描画に同じものが無い（読み飛ばす）
- \f[anchorvisitedmethod,描画方法] — Windows GDI のラスタ演算の指定で、Android の描画に同じものが無い（読み飛ばす）
- \f[cursormethod,描画方法] — Windows GDI のラスタ演算の指定で、Android の描画に同じものが無い（読み飛ばす）
- \f[cursornotselectmethod,描画方法] — Windows GDI のラスタ演算の指定で、Android の描画に同じものが無い（読み飛ばす）
- \6 — アプリは端末の時計を合わせられない（ずれを測るだけ）
- \![change,calendarskin,カレンダースキン名] — カレンダーの画面が無い（決めたこと）
- \![execute,calendarplugin,カレンダープラグイン名] — カレンダープラグイン（スケジュールセンサ）は DLL のプラグイン
- \![execute,headline,ヘッドライン名] — ヘッドラインセンサーは作らない（決めたこと）
- \![notifyplugin,プラグインのIDまたは名前,イベント名,r0,r1,r2...] — プラグインは作らない（決めたこと）
- \![raiseplugin,プラグインのIDまたは名前,イベント名,r0,r1,r2...] — プラグインは作らない（決めたこと）
- \![restore,wallpaper] — Android 13 からアプリが今の壁紙を読めないので戻せない
- \![save,wallpaper] — 同上
- \![timernotifyplugin,時間,繰り返すか否か,プラグインのIDまたは名前,イベント名,r0,r1,r2...] — プラグインは作らない（決めたこと）
- \![timerraiseplugin,時間,繰り返すか否か,プラグインのIDまたは名前,イベント名,r0,r1,r2...] — プラグインは作らない（決めたこと）
- \![execute,emptyrecyclebin] — Android にごみ箱が無い（決めたこと）
- \![load,makoto] — MAKOTO は Windows の DLL
- \![reload,makoto] — 同上
- \![unload,makoto] — 同上
- \m[umsg,wparam,lparam] — Windows のメッセージ
- \![open,calendar] — カレンダーの画面は作らない（決めたこと）
- \![open,calendarpluginexplorer] — カレンダープラグインは DLL
- \![open,calendarskinexplorer] — カレンダーの画面が無い（決めたこと）
- \![open,headlinesensorexplorer] — ヘッドラインセンサーは作らない（決めたこと）
- \![open,messenger] — SSP のメッセンジャーが無い（決めたこと）
- \![open,pluginexplorer] — プラグインは作らない（決めたこと）
- \![sound,cdplay,トラックNo.] — 音楽 CD は Android に無い
- \![effect,プラグイン名,速度倍率,パラメータ] — DLL のプラグイン
- \![effect2,追加サーフェスID,プラグイン名,速度倍率,パラメータ] — DLL のプラグイン
- \![filter] — DLL のプラグイン（解除するものも無い）
- \![filter,プラグイン名,起動時間,パラメータ] — DLL のプラグイン

### ― ベースウェアのことではない（15）
- バルーン、テキストコマンド — 目次のページ
- 選択肢マーカー変更コマンド — 目次のページ
- 選択肢コマンド — 目次のページ
- 環境変数の記述例 — 書き方の例だけ
- 環境変数 — 目次のページ
- イベントコマンド — 目次のページ
- 文字変更コマンド — 目次のページ
- さくらスクリプトリスト — 目次のページ
- 未分類コマンド — 目次のページ
- オープンコマンド — 目次のページ
- プロパティシステム操作コマンド — 目次のページ
- スコープコマンド — 目次のページ
- サウンドコマンド — 目次のページ
- サーフェスコマンド — 目次のページ
- ウエイトコマンド — 目次のページ

## プロパティシステム

済み 180 項目。

### 🔧 一部だけ（2）
- activeghostlist(ゴースト名/本体側名/パス).ext.拡張プロパティ名 — 読むと相手に property.get、書くと property.set を送る（\![get,property] など待てるところだけ。%property[…] はその場で答えが要るので空）
- activeghostlist.index(ID).ext.拡張プロパティ名 — activeghostlist(…).ext と同じ（index(ID) で選んだゴーストに property.get / set。%property[…] では空）

### ⛔ 作れない（14）
- headlinelist.count — ヘッドラインセンサーは作らない（決めたこと）
- headlinelist.index(ID).汎用プロパティ名 — ヘッドラインセンサーは作らない（決めたこと）
- headlinelist(ヘッドライン名/パス).汎用プロパティ名 — ヘッドラインセンサーは作らない（決めたこと）
- history.headline.count — ヘッドラインセンサーは作らない（決めたこと）
- history.headline.index(ID).汎用プロパティ名 — ヘッドラインセンサーは作らない（決めたこと）
- history.headline(ヘッドライン名/パス).汎用プロパティ名 — ヘッドラインセンサーは作らない（決めたこと）
- history.plugin(プラグイン名/パス/ID).汎用プロパティ名 — プラグインは作らない（決めたこと）
- history.plugin.count — プラグインは作らない（決めたこと）
- history.plugin.index(ID).汎用プロパティ名 — プラグインは作らない（決めたこと）
- pluginlist(プラグイン名/パス/ID).ext.拡張プロパティ名 — プラグインは作らない（決めたこと）
- pluginlist(プラグイン名/パス/ID).汎用プロパティ名 — プラグインは作らない（決めたこと）
- pluginlist.count — プラグインは作らない（決めたこと）
- pluginlist.index(ID).ext.拡張プロパティ名 — プラグインは作らない（決めたこと）
- pluginlist.index(ID).汎用プロパティ名 — プラグインは作らない（決めたこと）

### ― ベースウェアのことではない（5）
- プロパティシステム — 目次のページ
- 汎用プロパティ名 — 目次のページ
- プロパティシステム — 目次のページ
- プロパティシステム — 説明のページ（%property と get/set,property は別の行で扱う）
- サウンドプロパティ名 — 目次のページ

## SHIORI イベント

済み 249 項目。

### 🔨 作る（11）
- inputbox.autocomplete — 入力の箱を開くときに聞き、返った候補（バイト値1区切り）を InputBalloon の入力欄の下に出す
- OnInstallReroute — accept で名指しされたゴーストが一緒に起動していたら、そちらへ入れて以降のイベントもそちらに送る（いまは OnInstallRefuse）
- OnMouseDoubleClickEx — 2本指で2回続けて叩いたとき（とマウスの中・戻る・進むボタン）に middle などで送る
- OnMouseHover — マウスやペンのホバー（ACTION_HOVER_MOVE）が止まったときに送る。指では起きない
- OnMouseMultipleClickEx — 2本指で3回以上続けて叩いたとき。答えが無ければ ClickEx / DoubleClickEx へ
- enable_log — 開発用の画面に「SHIORI のログ」の入り切りを足し、変えたら NOTIFY
- rateofusegraph — 名前だけ見つかったが NOTIFY は送っていない。使用率を出すとき（\![open,rateofusegraph]・メニュー）に history.usage から送る
- OnDesktopWallpaperChange — WallpaperManager の色が変わった知らせで送る（Reference2 のパスは空、きっかけは unknown）。ssp-gap では ⛔
- OnMusicPlayEx — 🔒 通知へのアクセスを許したときだけ MediaSessionManager で曲名・アーティスト・album・duration などを送る。ssp-gap では ⛔
- OnSoundSpectrum — \_v の --spectrum。自分で鳴らす音を MediaExtractor / MediaCodec で読んで帯域ごとの音量を出す（Visualizer はマイクの許可が要る）。ssp-gap の ⛔ の理由は違う
- OnVideoPlayEx — 🔒 OnMusicPlayEx と同じ仕組みで、動画のセッションのときに送る。ssp-gap では ⛔

### 🔧 一部だけ（1）
- OnMusicPlay — SSTP の GIVE Song だけ。端末で鳴っている曲（MediaSession）からは送らない

### ⛔ 作れない（55）
- OnBasewareUpdating — アプリの更新は Play ストアが行い、置き換える前にアプリへ知らせが来ない
- OnCacheRestore — ゴーストキャッシュが無い（決めたこと）
- OnCacheSuspend — ゴーストキャッシュが無い（決めたこと）
- OnFullScreenAppMinimize — ほかのアプリが全画面になったことをオーバーレイから確かに知る方法が無い（決めたこと）
- OnFullScreenAppRestore — ほかのアプリが全画面になったことをオーバーレイから確かに知る方法が無い（決めたこと）
- OnVirtualDesktopChanged — Windows の仮想デスクトップ
- OnScheduleRead — カレンダーのアイコンもカーソルも無い（決めたこと）
- OnSchedulepostBegin — スケジュールセンサー・ポストはプラグイン（決めたこと）
- OnSchedulepostComplete — スケジュールセンサー・ポストはプラグイン（決めたこと）
- OnSchedulesenseBegin — スケジュールセンサー・ポストはプラグイン（決めたこと）
- OnSchedulesenseComplete — スケジュールセンサー・ポストはプラグイン（決めたこと）
- OnSchedulesenseFailure — スケジュールセンサー・ポストはプラグイン（決めたこと）
- OnConfigurationDialogHelp — SSP の設定画面の項目ごとのヘルプ。pono-droid の設定画面には同じ仕組みが無い（決めたこと）
- OnDirectoryDrop — Android の共有ではフォルダーを渡せない
- OnFileDropping — 落とす前のドラッグが無い。共有で渡すだけ（決めたこと）
- OnOtherObjectDropped — Windows の「PC」などファイルでないものは Android に無い
- OnOtherObjectDropping — Windows の「PC」などファイルでないものは Android に無い
- OnWallpaperChange — 共有した画像は見るアプリで開き、壁紙にはしない（SSP も 2.8.51 から画像は開く）
- OnGamepadAxisMove — オーバーレイはフォーカスを取らないので、ゲームパッドのボタンや軸を受けられない（決めたこと）
- OnGamepadButtonDown — オーバーレイはフォーカスを取らないので、ゲームパッドのボタンや軸を受けられない（決めたこと）
- OnGamepadButtonUp — オーバーレイはフォーカスを取らないので、ゲームパッドのボタンや軸を受けられない（決めたこと）
- OnGamepadConnected — つないだ・外したは InputManager で分かるが、ボタンを受けられないので作らない（決めたこと）
- OnGamepadDisconnected — つないだ・外したは InputManager で分かるが、ボタンを受けられないので作らない（決めたこと）
- OnHeadlinesense.OnFind — ヘッドラインセンサーは作らない（決めたこと）。RSS は \![execute,rss-get] の OnExecuteRSS* で使える
- OnHeadlinesenseBegin — ヘッドラインセンサーは作らない（決めたこと）。RSS は \![execute,rss-get] の OnExecuteRSS* で使える
- OnHeadlinesenseComplete — ヘッドラインセンサーは作らない（決めたこと）。RSS は \![execute,rss-get] の OnExecuteRSS* で使える
- OnHeadlinesenseFailure — ヘッドラインセンサーは作らない（決めたこと）。RSS は \![execute,rss-get] の OnExecuteRSS* で使える
- OnRSSBegin — ヘッドラインセンサーは作らない（決めたこと）。RSS は \![execute,rss-get] の OnExecuteRSS* で使える
- OnRSSComplete — ヘッドラインセンサーは作らない（決めたこと）。RSS は \![execute,rss-get] の OnExecuteRSS* で使える
- OnRSSFailure — ヘッドラインセンサーは作らない（決めたこと）。RSS は \![execute,rss-get] の OnExecuteRSS* で使える
- calendarpluginpathlist — カレンダーのプラグインは作らない（決めたこと）
- calendarskinpathlist — カレンダーの画面が無い（決めたこと）
- headlinepathlist — ヘッドラインセンサーは作らない（決めたこと）
- hwnd — Windows のウィンドウハンドルが無い
- installedcalendarpluginname — カレンダーのプラグインは作らない（決めたこと）
- installedcalendarskinname — カレンダーの画面が無い（決めたこと）
- installedheadlinename — ヘッドラインセンサーは作らない（決めたこと）
- installedplugin — プラグインは作らない（決めたこと）
- pluginpathlist — プラグインは作らない（決めたこと）
- OnDisplayHandover — オーバーレイは1つの画面にしか出ない（決めたこと）
- OnRecycleBinEmpty — Android にごみ箱が無い（決めたこと）
- OnRecycleBinEmptyFromOther — Android にごみ箱が無い（決めたこと）
- OnRecycleBinStatusUpdate — Android にごみ箱が無い（決めたこと）
- OnSessionDisconnect — Windows のユーザー切り替え（決めたこと）
- OnSessionReconnect — Windows のユーザー切り替え（決めたこと）
- OnTabletMode — Windows のタブレットモード（決めたこと）
- OnWindowModeChange — SSP のコンテナに出すモードが無い。いつも normal（決めたこと）
- OnEmbryoExist — Windows の materia.exe
- OnInternalSaoriComplete — SAORI は止めている（決めたこと）
- OnInternalSaoriFailure — SAORI は止めている（決めたこと）
- OnKeyPress — オーバーレイがキーを受けると、ほかのアプリへの入力を横取りする（決めたこと）
- OnNekodorifExist — Windows の猫どりふ
- OnSNTPCorrect — アプリは端末の時計を直せない。ずれを測るだけ
- OnSNTPCorrectEx — アプリは端末の時計を直せない。ずれを測るだけ
- OnURLDragDropping — 落とす前のドラッグが無い。共有で渡すだけ（決めたこと）

### ― ベースウェアのことではない（42）
- 42 項目（一覧は ukadoc-spec-status.tsv）

## SHIORI リソース

済み 88 項目。

### ⛔ 作れない（71）
- (入力ボックス種類).defaultleft　(入力ボックス種類).defaulttop — 入力の箱は \0 のバルーンの位置に出す（決めたこと）
- menu.disable.font.color.b — バルーンのメニューに押せない項目が無い（決めたこと）
- menu.disable.font.color.g — バルーンのメニューに押せない項目が無い（決めたこと）
- menu.disable.font.color.r — バルーンのメニューに押せない項目が無い（決めたこと）
- menu.frame.color.b — バルーンのメニューに枠が無い（決めたこと）
- menu.frame.color.g — バルーンのメニューに枠が無い（決めたこと）
- menu.frame.color.r — バルーンのメニューに枠が無い（決めたこと）
- menu.separator.color.b — バルーンのメニューに区切り線が無い（決めたこと）
- menu.separator.color.g — バルーンのメニューに区切り線が無い（決めたこと）
- menu.separator.color.r — バルーンのメニューに区切り線が無い（決めたこと）
- menu.background.bitmap.filename — メニューはバルーンに出すので絵を敷かない（決めたこと）
- menu.foreground.bitmap.filename — メニューはバルーンに出すので絵を敷かない（決めたこと）
- menu.sidebar.bitmap.filename — メニューはバルーンに出すので絵を敷かない（決めたこと）
- activaterootbutton.caption — Windows の手前に出す設定。オーバーレイはいつも手前（決めたこと・メニューに無い）
- addressbarbutton.caption — SSP のアドレスバー。メニューに無い（決めたこと・メニューに無い）
- alignrootbutton.caption — キャラクターの自由移動の設定。pono-droid はいつも自由に動かせる（決めたこと・メニューに無い）
- alwaysstayontopbutton.caption — オーバーレイはいつも手前（決めたこと・メニューに無い）
- alwaystrayiconvisiblebutton.caption — タスクトレイが無い（決めたこと・メニューに無い）
- balloonhistorybutton.caption — 最近使ったものの一覧が無い（決めたこと・メニューに無い）
- biffallbutton.caption — アカウントごとに分けず、「メールを確認」が全部を確かめる（決めたこと・メニューに無い）
- calendarbutton.caption — カレンダーの画面が無い（決めたこと・メニューに無い）
- callghosthistorybutton.caption — 最近使ったものの一覧が無い（決めたこと・メニューに無い）
- callsstpsendboxbutton.caption — SSTP 送信ボックスが無い（決めたこと・メニューに無い）
- charsetbutton.caption — 文字コードの選択が無い（決めたこと・メニューに無い）
- collisionvisiblebutton.caption — Materia の当たり判定表示。メニューに無い（決めたこと・メニューに無い）
- debugballoonbutton.caption — Materia のバルーンマーカー表示。メニューに無い（決めたこと・メニューに無い）
- definedsurfaceonlybutton.caption — SSP の互換の切り替え。メニューに無い（決めたこと・メニューに無い）
- duibutton.caption — DUI が無い（決めたこと・メニューに無い）
- enableballoonmovebutton.caption — バルーンはいつもキャラクターの上で動かせない（決めたこと・メニューに無い）
- firststaffbutton.caption — バージョン情報の画面が無い（決めたこと・メニューに無い）
- ghosthistorybutton.caption — 最近使ったものの一覧が無い（決めたこと・メニューに無い）
- headlinesensehistorybutton.caption — ヘッドラインセンサーは作らない（決めたこと・メニューに無い）
- headlinesenserootbutton.caption — ヘッドラインセンサーは作らない（決めたこと・メニューに無い）
- historyrootbutton.caption — 最近使ったものの一覧が無い（決めたこと・メニューに無い）
- inforootbutton.caption — SSP の情報の下位メニュー。メニューに無い（決めたこと・メニューに無い）
- messengerbutton.caption — SSP メッセンジャが無い（決めたこと・メニューに無い）
- pluginhistorybutton.caption — プラグインは作らない（決めたこと・メニューに無い）
- pluginrootbutton.caption — プラグインは作らない（決めたこと・メニューに無い）
- purgeghostcachebutton.caption — ゴーストキャッシュが無い（決めたこと・メニューに無い）
- rateofuseballoonbutton.caption — Materia の使用率の下位メニュー。rateofusebutton で足りる（決めたこと・メニューに無い）
- rateofuserootbutton.caption — Materia の使用率の下位メニュー。rateofusebutton で足りる（決めたこと・メニューに無い）
- rateofusetotalbutton.caption — Materia の使用率の下位メニュー。rateofusebutton で足りる（決めたこと・メニューに無い）
- regionenabledbutton.caption — Windows のリージョン（決めたこと・メニューに無い）
- reloadinfobutton.caption — SSP の動作情報の読み込み直し。メニューに無い（決めたこと・メニューに無い）
- resetballoonpositionbutton.caption — バルーンはいつもキャラクターの上で動かせない（決めたこと・メニューに無い）
- resettodefaultbutton.caption — SSP の設定を標準に戻す。メニューに無い（決めたこと・メニューに無い）
- scriptlogbutton.caption — SSP の開発用パレットのスクリプトログ。メニューに無く、開発用の画面で見る
- switchactivatewhentalkbutton.caption — Windows の手前に出す設定。オーバーレイはいつも手前（決めたこと・メニューに無い）
- switchactivatewhentalkexceptupdatebutton.caption — Windows の手前に出す設定。オーバーレイはいつも手前（決めたこと・メニューに無い）
- switchautobiffbutton.caption — Materia のスイッチ。間隔はアプリの設定にある（決めたこと・メニューに無い）
- switchautoheadlinesensebutton.caption — ヘッドラインセンサーは作らない（決めたこと・メニューに無い）
- switchcompatiblemodebutton.caption — SSP の旧仕様サポートの切り替え。メニューに無い（決めたこと・メニューに無い）
- switchconsolealwaysvisiblebutton.caption — Materia のコンソール（決めたこと・メニューに無い）
- switchconsolevisiblebutton.caption — Materia のコンソール（決めたこと・メニューに無い）
- switchdeactivatebutton.caption — Windows の裏へ沈む設定（決めたこと・メニューに無い）
- switchdontactivatebutton.caption — Windows の手前に出す設定（決めたこと・メニューに無い）
- switchdontforcealignbutton.caption — キャラクターの自由移動の設定。いつも自由（決めたこと・メニューに無い）
- switchduivisiblebutton.caption — DUI が無い（決めたこと・メニューに無い）
- switchforcealignfreebutton.caption — キャラクターの自由移動の設定。いつも自由（決めたこと・メニューに無い）
- switchforcealignlimitbutton.caption — キャラクターの自由移動の設定。いつも自由（決めたこと・メニューに無い）
- switchignoreserikomovebutton.caption — SERIKO の move を無視する切り替えが無い（決めたこと・メニューに無い）
- switchlocalsstpbutton.caption — Materia のスイッチ。SSTP はアプリの設定で選ぶ（決めたこと・メニューに無い）
- switchproxybutton.caption — プロキシの設定が無い（端末の設定に従う）（決めたこと・メニューに無い）
- switchreloadtempghostbutton.caption — 一時起動ゴーストが無い（決めたこと・メニューに無い）
- switchremotesstpbutton.caption — LAN からの SSTP は受けない（決めたこと）（決めたこと・メニューに無い）
- switchrootbutton.caption — Materia のスイッチの下位メニュー（決めたこと・メニューに無い）
- switchtalkghostbutton.caption — 話す間隔の設定がメニューに無い（決めたこと・メニューに無い）
- systeminfobutton.caption — SSP のシステム情報の提出（決めたこと・メニューに無い）
- updatefmobutton.caption — Windows の FMO（決めたこと・メニューに無い）
- updateplatformbutton.caption — アプリの更新は Play ストア（決めたこと・メニューに無い）
- utilityrootbutton.caption — SSP の機能の下位メニュー。メニューに無い（決めたこと・メニューに無い）

### ― ベースウェアのことではない（11）
- SHIORI Resource — SHIORI Resource の説明
- ゴースト情報 — 目次のページ
- legacyinterface — Materia 582 だけの古い項目
- SHIORI Resourceリスト — 目次のページ
- オーナードローメニュー文字色 — 目次のページ
- オーナードローメニュー画像 — 目次のページ
- - — 変更できない項目（言語）。pono-droid のメニューに言語は無い
- オーナードローメニュー項目名 — 目次のページ
- SHIORI情報 — 目次のページ
- ツールチップ系 — 目次のページ
- 更新情報 — 目次のページ

## 拡張イベント（ゴーストやアプリが決めたもの）

済み 0 項目。

### ― ベースウェアのことではない（213）
- 213 項目（一覧は ukadoc-spec-status.tsv）

## プラグインのイベント

済み 0 項目。

### ⛔ 作れない（21）
- PLUGIN Eventリスト — プラグインに送るイベントの一覧。プラグインは作らない
- OnChoiceSelect(Ex)/OnAnchorSelect(Ex)/\q等に指定された任意名イベント — プラグインに送るもの。プラグインは作らない
- OnGhostBoot — プラグインに送るもの。プラグインは作らない
- OnGhostExit — プラグインに送るもの。プラグインは作らない
- OnGhostInfoUpdate — プラグインに送るもの。プラグインは作らない
- OnInstallComplete — プラグインに送るもの。プラグインは作らない
- OnMenuExec — プラグインに送るもの。プラグインは作らない
- OnOtherGhostTalk — プラグインに送るもの。プラグインは作らない
- OnSecondChange — プラグインに送るもの。プラグインは作らない
- balloonpathlist — プラグインに送るもの。プラグインは作らない
- \![raiseplugin]および\![notifyplugin]に指定された任意名イベント — プラグインに送るもの。プラグインは作らない
- ghostpathlist — プラグインに送るもの。プラグインは作らない
- headlinepathlist — プラグインに送るもの。プラグインは作らない
- PLUGIN Event — プラグインに送るイベントの一覧。プラグインは作らない
- installedballoonname — プラグインに送るもの。プラグインは作らない
- installedghostname — プラグインに送るもの。プラグインは作らない
- installedplugin — プラグインに送るもの。プラグインは作らない
- pluginpathlist — プラグインに送るもの。プラグインは作らない
- property.get — プラグインに送るもの。プラグインは作らない
- property.set — プラグインに送るもの。プラグインは作らない
- version — プラグインに送るもの。プラグインは作らない

## 仕様（SSTP・SHIORI/3.0・SAORI・更新ファイルなど）

済み 17 項目。

### 🔨 作る（52）
- 非同期の呼び出し — --async で 204 を返し、あとで OnInternalSaoriComplete / Failure（または識別子）を送る。ssp-gap.md の ⛔ を直す
- SAORI over DirectSSTP — 内蔵モジュールの入口。:shiori プロセスの YAYA の LOADLIB / REQUESTLIB と里々の SendSaoriDirectSSTP で internal_* を本体に回す
- 互換モジュール — string・textcopy・clipcopy・httpc・mciaudio などを DLL 名で内蔵に差し替える（読み込みは止めたまま）
- SAORI over DirectSSTP — WM_COPYDATA の代わりに IShioriService にコールバックを足し、SAORI/1.0 の要求を本体で答える。ソケットの SAORI は 400 direct sstp only
- SAORI over DirectSSTP — 内蔵モジュールの一覧。chapter0 と同じ
- 内蔵モジュールの共通規約 — 位置引数と --オプション、--名前-in / --out の形式、ErrorDescription の共通部分を作る
- internal_crypto — アルゴリズム名の読み方（ハイフン・大文字小文字を見ない）を共通で作る
- Compare — MessageDigest.isEqual で比べる
- Decrypt — javax.crypto（AES-GCM / CBC / CTR、ChaCha20）で作れる
- DeriveKey — PBKDF2・HKDF は JCA、scrypt・argon2id は BouncyCastle
- Digest — MessageDigest。sha3・blake2 は BouncyCastle。--data-in=file はゴーストのフォルダの中だけ
- Encrypt — javax.crypto で作れる。nonce は SecureRandom
- GenerateKeyPair — KeyPairGenerator（Ed25519・P-256・RSA）。secp256k1 は BouncyCastle
- HMAC — javax.crypto.Mac で作れる
- HashPassword — argon2id・scrypt・bcrypt・crypt 系は BouncyCastle か自前。PHC 文字列で返す
- KeyAgreement — secp256k1 の ECDH を BouncyCastle で
- Protect — DPAPI の代わりに Android Keystore の鍵で包む。--scope=machine も同じ扱い
- Sign — Signature（Ed25519・ES256・RS256・PS256）。bip340 は BouncyCastle か自前
- TOTP — HMAC と時刻で作れる
- Unprotect — Android Keystore の鍵でほどく
- Verify — Signature で作れる
- VerifyPassword — HashPassword と同じ部品で照合
- 共通鍵暗号（Encrypt / Decrypt） — 暗号文の形（nonce＋暗号文＋タグ）、--iv / --pack=none
- 公開鍵暗号（GenerateKeyPair / Sign / Verify / KeyAgreement） — DER / 1行の PEM の鍵の読み書き、bech32 の鍵
- internal_data — internal_data の入口
- Compress — java.util.zip（deflate / zlib / gzip）で作れる
- Convert — hex・base64・base32・url・bech32 の変換
- Decompress — java.util.zip で作れる。展開後の大きさに上限を置く
- internal_file — internal_file の入口
- CompressArchive — 中身は \![execute,compressarchive] のものを使える。入口が無い
- ExtractArchive — 中身は \![execute,extractarchive] のものを使える。入口が無い
- MediaInfo — MediaMetadataRetriever と ExifInterface・BitmapFactory の大きさで読む（app 側）
- MediaInfoを実装するベースウェア向けの注記 — ほかの OS 向けの注記。取れない値は空にする決まりを守る
- internal_jwt — internal_jwt の入口
- Sign — HS* は Mac、RS256 / PS256 / ES256 / EdDSA は internal_crypto の署名を使う
- Verify — 署名と exp / nbf を確かめる
- internal_random — internal_random の入口
- Bytes — SecureRandom で作れる
- Number — SecureRandom で作れる
- UUID — v4 は UUID.randomUUID、v7 は時刻と SecureRandom で組む
- internal_system — internal_system の入口
- ListFunctions — 作ったモジュールの関数の一覧を返す
- ListModules — 作ったモジュールの一覧。里々はこれで内蔵かどうかを決める
- internal_text — internal_text の入口
- Normalize — java.text.Normalizer で作れる
- 日時の引数 — エポック秒と ISO 8601 の読み方
- internal_time — internal_time の入口
- Add — java.time で作れる
- Diff — java.time で作れる
- Format — java.time で作れる
- ゾーン（--tz=） — ZoneId で作れる（端末のタイムゾーンの一覧）
- 送信側の手順 — YAYA の FUNCTIONEX と里々の（internal_…）がそのまま本体に届くようにする

### 🔧 一部だけ（7）
- response — ValueNotify・SecurityLevel・ErrorLevel / ErrorDescription・BalloonOffset・Reference0 のコミュニケート（Age）・X-SSTP-PassThru-* は扱う。Marker と MarkerSend はバルーンの印がまだ無いので出さない
- 概要 — 9801 だけで待つ。SSP の 9821 では待たない
- 生成処理 — 作る一覧が UTF-8 なのに charset を書かない（dau の先頭の charset=、txt の charset, 行）。SSP は Shift_JIS と読む。date= も無い
- パスの扱い — 全部の行がパーセントエンコード済みのときに戻していない（ファイル名に %XX が残り、URL は二重にエンコードされる）。MD5 なし・/ で終わる・.. は済み
- updates2.dau (Version 2) — 先頭の charset= の欄を読まず、UTF-8 か CP932 かを中身で決めている（EUC-JP などは読めない）
- application/x-nar MIMEタイプ — 共有するときは application/x-nar にしている。ブラウザで落とした .nar を開く VIEW（application/x-nar・zip）を受けていない
- x-ukagaka-link スキーム — type=event だけ。type=install（URL の .nar を落として入れる）と type=homeurl（更新と同じ方法で取ってきて入れる）が無い

### ⛔ 作れない（9）
- DLL共通仕様 — Windows の DLL の決まり。pono-droid は SHIORI を組み込みで動かす
- FMO/Mutex — Windows だけの仕組み
- FMO用Mutex — Windows の名前つき Mutex
- FMO (File Mapping Object) — Windows の共有メモリ。代わりの EXECUTE GetFMO は method_execute に書いた
- FMO/Mutex — Windows だけの仕組み
- Mutex — Windows の名前つき Mutex（ssp / sakura）。ほかのアプリから見る手段が無い
- HEADLINE/2.0 — ヘッドラインは Windows の DLL。決めたとおり作らない
- PLUGIN/2.0 — プラグインは Windows の DLL。決めたとおり作らない
- Direct SSTP — Windows の WM_COPYDATA。Android には同じものが無い

### ― ベースウェアのことではない（4）
- SAORI/1.0の概要 — SAORI/1.0 の説明。DLL の SAORI は作らないが、内蔵モジュールの要求の形として使う
- 実装対応状況の検知 — NOTIFY capability のページへの案内。中身はイベントの組で見る
- Web関連 — 執筆中の空のページ
- Web関連 — 目次

## そのほか（説明のページ）

済み 2 項目。

### 🔧 一部だけ（4）
- バルーン — balloons*s.txt・arrows* など・marker・clickwait・sstp の画像は使う。balloonp*（3人目以降のバルーン画像）と thumbnail.png はまだ
- ゴースト — thumbnail.png（一覧のサムネイル）・*.ico・ai0.png / ai0_dark.png（AI グラフの絵）を使っていない
- シェル — surface*.png や element の APNG / GIF / WebP は動く（Android 9 から）。SERIKO のコマより上に出る
- ネットワーク更新 — delete1.txt など delete[数字].txt を読まない（delete.txt だけ）

### ⛔ 作れない（1）
- トランスレータ — MAKOTO（makoto.dll）は Windows の DLL

### ― ベースウェアのことではない（13）
- CLAUDE.md — 仕様書のリポジトリの決まりで、ベースウェアの要件ではない
- UKADOC トップページ — 目次のページ
- ukadoc-spec — リポジトリの説明
- 着せ替えの設定 — 着せ替えを作る人向けの説明。中身は surfaces.txt の組で見る
- 配布用ファイルの作成 — 配る人向けの説明。refresh・package などは済み。initial_install.nar はアプリでは置き場所が無いので対象外
- オーナードローメニューの設定 — 作る人向けの説明。中身は menu.* の組で見る
- エラーメッセージの解消 — シェルを作る人向けの説明
- シェルの作成 — シェルを作る人向けの説明。中身は surfaces.txt の組で見る
- ネットワーク更新への対応 — 更新に対応する人向けの説明。updates.txt での代わりは済み
- 全体の構成 — フォルダの構成の説明
- メモ — ベースウェアの比較などのメモ
- 補足: SHIORI Eventとは — SHIORI Event の説明
- HTML 版からの移行メモ — 仕様書の移行メモ

