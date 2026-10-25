# ukadoc-spec との差

[ukadoc-spec](https://github.com/ukatech/ukadoc-spec)（UKADOC の Markdown 版、b1f8ab1 時点）の項目を1つずつ pono-droid と比べた結果。
項目ごとの状態は [ukadoc-spec-status.tsv](ukadoc-spec-status.tsv)（DONE / PARTIAL / BUILD / IMPOSSIBLE / NA）。作ったら DONE に書き換える。
前に HTML 版の ukadoc から作った一覧は [ssp-gap.md](ssp-gap.md)。

全 2050 項目: 済み 1021・一部だけ 119・作る 291・作れない 281・ベースウェアのことではない 338

## バルーンの descript.txt など

済み 105 項目。

### 🔨 作る（53）
- arrow.filename,ファイル名 — 矢印は arrow0/1.png 決め打ち。arrow.filename と arrows/arrowk/arrowpN を Balloon.arrowImage で読む
- clickwaitmarker.filename,ファイル名 — クリック待ちの印が無い。state.waitingClick はあるので SkinFrame に clickwait*.png を出す
- marker.filename,ファイル名 — \![*] は「▸」の文字で出している。marker.png（markers/markerk/markerpN）を選択肢の前に画像で置く
- sstpmarker.filename,ファイル名 — SSTP で届いたスクリプトの印が無い。sstp.png / sstp_new.png（s/k/pN）と送り主の名前を出す
- anchor.blendmethod,コマンド — 読み飛ばしている。notmaskpen / mergepennot などは BlendMode や色の反転で近い見た目にできる
- anchor.font.shadowcolor.b,数値 — LinkLook に影が無い。影の色を足して SpanStyle の shadow にする
- anchor.font.shadowcolor.g,数値 — LinkLook に影が無い。影の色を足して SpanStyle の shadow にする
- anchor.font.shadowcolor.r,数値 — LinkLook に影が無い。影の色を足して SpanStyle の shadow にする
- anchor.font.shadowstyle,形態指定 — LinkLook に影が無い。offset / outline を足す
- anchor.notselect.font.shadowcolor.b,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- anchor.notselect.font.shadowcolor.g,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- anchor.notselect.font.shadowcolor.r,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- anchor.notselect.font.shadowstyle,形態指定 — LinkLook に影が無い。anchor.font.shadowstyle と一緒に作る
- anchor.visited.font.shadowcolor.b,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- anchor.visited.font.shadowcolor.g,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- anchor.visited.font.shadowcolor.r,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- anchor.visited.font.shadowstyle,形態指定 — LinkLook に影が無い。anchor.font.shadowstyle と一緒に作る
- clickwaitmarker.x,座標 — クリック待ちの印と一緒に作る。既定は arrow1 の位置
- clickwaitmarker.y,座標 — クリック待ちの印と一緒に作る。既定は arrow1 の位置
- communicatebox.font.name,フォント名 — 入力欄の書体を変えていない。BalloonFonts.family で SkinInputBox の TextStyle に渡す
- communicatebox.placeholder.color.b,数値 — --placeholder の案内文がまだ無い。BasicTextField の decorationBox に薄い色で出す
- communicatebox.placeholder.color.g,数値 — --placeholder の案内文がまだ無い。BasicTextField の decorationBox に薄い色で出す
- communicatebox.placeholder.color.r,数値 — --placeholder の案内文がまだ無い。BasicTextField の decorationBox に薄い色で出す
- craftmanurl,URL — バルーンでは使っていない。アプリのバルーンの一覧に作者の URL を出す
- cursor.blendmethod,コマンド — 読み飛ばしている。anchor.blendmethod と一緒に近い見た目で作る
- cursor.font.shadowcolor.b,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- cursor.font.shadowcolor.g,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- cursor.font.shadowcolor.r,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- cursor.font.shadowstyle,形態指定 — LinkLook に影が無い。anchor.font.shadowstyle と一緒に作る
- cursor.notselect.font.shadowcolor.b,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- cursor.notselect.font.shadowcolor.g,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- cursor.notselect.font.shadowcolor.r,数値 — LinkLook に影が無い。anchor.font.shadowcolor と一緒に作る
- cursor.notselect.font.shadowstyle,形態指定 — LinkLook に影が無い。anchor.font.shadowstyle と一緒に作る
- dpi,推奨DPI — 読んでいない。バルーンの倍率に 96/dpi を掛ける（SkinBalloon と SkinInputBox の scale）
- number.font.name,フォント名 — 数の書体を変えていない。BalloonLabel に BalloonFonts.family を渡す
- number.yb,座標 — number.y の別名として読むだけ。縦書きの意味は vertical と一緒に
- overlay_outside_balloon,数値 — 印はバルーンのウィンドウの中だけ。1 ならウィンドウを印の分だけ広げる
- paint_transparent_region_black,数値 — .pna のときキー色の所を黒で塗っていない（0 相当）。ImageTransparency.applyPna で既定 1 にする
- readme.charset,文字コード — バルーンの readme と一緒に作る。TextCharsets で読む
- readme,ファイル名 — バルーンの readme を開く所が無い（ゴーストだけ）。インストール後とバルーンの一覧から TextViewerActivity で出す
- sstpmarker.x,座標 — SSTP の印と一緒に作る
- sstpmarker.y,座標 — SSTP の印と一緒に作る
- sstpmessage.font.name,フォント名 — 印の文字の書体を変えていない。BalloonLabel に BalloonFonts.family を渡す
- sstpmessage.xr,座標 — 読んでいない。印の文字をこの位置で切る
- sstpmessage.yb,座標 — 縦書きのときだけ使う。vertical と一緒に
- use_input_alpha,数値 — バルーンを半透明にする設定がまだ無い。作るときに入力の箱にも効かせるかをこれで決める
- vertical,0/1 — 縦書きが無い（balloon.scope.vertical もいつも 0）。Compose に縦書きが無いので1文字ずつ並べる組版が要る
- windowposition.x,座標 — 読んでいない。CharacterWindow.placeBalloon の balloonAlign（center / bottom）と balloonOffset と同じ仕組みで作れる
- windowposition.y,座標 — 読んでいない。windowposition.x と一緒に placeBalloon でずらす
- wordwrappoint.y,座標 — 縦書きのときだけ使う。vertical と一緒に
- 推奨の意味 — 推奨の意味の説明。ゴーストを消すとき推奨バルーンも消すか聞く動きは recommended.ghost と一緒に作る
- recommended.ghost,ゴースト名 — 読んでいない。ほかのゴーストで選ぶとき警告し、そのゴーストを消すとき一緒に消すか聞く
- recommended.ghost.path,パス — recommended.ghost と一緒に。ゴーストのフォルダ名で比べる

### 🔧 一部だけ（9）
- balloons\*s.txt/balloonk\*s.txt — balloonc*s.txt の communicatebox だけ読む。balloons*s.txt/balloonk*s.txt の origin・validrect・font・arrow などの上書きと windowposition は無い。balloonc5s.txt が descript.txt を引き継いでしまう
- disable.font.(フォント定義),(指定) — disable.font.color だけ。disable.font.name / height / bold などの他の font.* は読まない。色が無いときも画像の色と混ぜない
- origin.x,座標 — 読むが、負の値（右端から）を 0 にしてしまう。縦書きの意味も無い
- origin.y,座標 — 読むが、負の値（下端から）を 0 にしてしまう
- use_self_alpha,値 — 画像のアルファと .pna はいつも使う（1 相当）。full（アルファの無い画像もキー色で抜かない）と 0 を読まない
- validrect.left,座標 — 読むが、負の値（右端から）を 0 にしてしまう
- validrect.top,座標 — 読むが、負の値（下端から）を 0 にしてしまう
- windowposition.limit,0/1 — オーバーレイは Android が画面の中に収めるので 1 相当。0 は FLAG_LAYOUT_NO_LIMITS で作れる
- Balloon設定 — *1 の負の座標は arrow・online・sstpmessage・number と validrect.right/bottom だけ。origin と validrect.left/top の負の値、--数値 の書き方は読まない

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

済み 30 項目。

### 🔨 作る（30）
- balloon.defaultsurface,数値 — \b の既定を 0 でなく descript.txt の値にする（balloonId が null のときの番号）
- balloon.syncscale,true — バルーンの拡大率はアプリの設定でキャラクターと別。true ならキャラクターの拡大率に合わせる
- char\*.balloon.defaultsurface,数値 — \p[N] の \b の既定（balloon.defaultsurface と同じところで）
- char\*.seriko.alignmenttodesktop,位置情報 — \![set,alignmenttodesktop] はある。起動時の既定を descript.txt から決める（ゴースト全体＜ゴーストのスコープ＜シェル全体＜シェルのスコープ）
- don't need bind,数値 — 1 なら着せ替えのメニューと \![bind] を止める
- don't need seriko talk,数値 — 1 なら interval が talk の口パクを動かさない
- don't need onmousemove,数値 — 1 なら onMouseMove（なでる）を送らない
- kero.balloon.defaultsurface,数値 — \1 の \b の既定（balloon.defaultsurface と同じところで）
- kero.seriko.alignmenttodesktop,位置情報 — \1 の alignmenttodesktop の既定（seriko.alignmenttodesktop と一緒に）
- menu.font.height,フォントサイズ — メニューはバルーンに出すので、\f[height] をメニューのスクリプトの頭に付ける（シェルの指定が先）
- menu.font.name,フォント名 — 同じく \f[name] を付ける
- name.allowoverride,数値 — シェルの sakura.name などを使う仕組みを作るとき、0 なら使わない
- readme.charset,文字コード — readme は UTF-8 か CP932 の自動判定だけ。readme.charset があればそれで読む
- sakura.balloon.defaultsurface,数値 — \0 の \b の既定（balloon.defaultsurface と同じところで）
- sakura.seriko.alignmenttodesktop,位置情報 — \0 の alignmenttodesktop の既定（seriko.alignmenttodesktop と一緒に）
- seriko.alignmenttodesktop,位置情報 — 既定は bottom（下の端に付けて縦に動かさない）。今は自由に動かせる。descript.txt を読んで既定にする
- seriko.defaultsurfacedirectoryname,ディレクトリ名 — Ghost.defaultShellDir が master 決め打ち。この値のディレクトリを先に探す
- shiori.encoding,文字コード — ShioriSession の最初の文字コードを charset でなくこれにする（Charset ヘッダが来たらそちら）
- shiori.escape_unknown,0/1 — ShioriRequest.encode で変換できない文字を ?escape!unicode[0xABCD] にし、答えで戻す
- shiori.forceencoding,文字コード — Charset ヘッダを無視してこの文字コードに固定する（ShioriSession）
- shiori.logo.align,位置情報 — AI グラフを絵で描くとき（今はラベルと値を文字で並べる）の背景画像の基準
- shiori.logo.file,ファイル名 — 同上。背景画像（複数・_dark）
- shiori.logo.x,X座標 — 同上。背景画像の X
- shiori.logo.y,Y座標 — 同上。背景画像の Y
- sstp.allowcommunicate,数値 — 0 なら SSTP の COMMUNICATE（と GIVE の Document）を受けない（handleSstp）
- sstp.allowunspecifiedsend,数値 — 0 なら ReceiverGhostName も IfGhost も無い SSTP を受けない（handleSstp / GhostHub）
- sstp.alwaystranslate,数値 — 1 なら notranslate を無視して、SSTP のスクリプトも OnTranslate に通す
- default.balloon.path,パス — balloon より先に、このパスのバルーンを探す（Ghost.balloon）
- recommended.balloon,バルーン名 — 既定のバルーンにし、ほかに切り替えるときに確かめる
- recommended.balloon.path,パス — パスで探す。ほかに切り替えるときに確かめる

### 🔧 一部だけ（2）
- descript.txt（Ghostの） — 説明のページ。\ や % で始まる値を捨てる決まりはしていない（名前などをメニューのスクリプトにそのまま入れている）
- readme,ファイル名 — メニューと \![open,readme] からは開ける。インストールしたときには開かない

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

済み 24 項目。

### 🔨 作る（58）
- char\*.balloon.syncscale,true — シェルの balloon.syncscale（ゴーストの指定より先）
- char\*.bindgroup\*.addid,ID — 着せ替えを付けたら addid のものも付け、元が全部外れたら外す（applyBind）
- char\*.bindoption\*.group,カテゴリ名,オプション — 今は同じカテゴリから 1 つ・外せる、の決め打ち。mustselect と multiple を applyBind に入れる
- char\*.defaultleft,X座標 — シェルの descript.txt の既定位置を読んでいない（SHIORI リソース＞シェル＞ゴーストの順にする）
- char\*.defaulttop,Y座標 — 同上
- char\*.defaultx,X座標 — 同上
- char\*.defaulty,Y座標 — 同上
- char\*.menuitem\*,ID — 着せ替えのメニューを menuitem の順に並べ、- で区切りを入れる（dressupMenu）
- char\*.menuitemex\*,メニュー名,ID — 着せ替えのメニューにメニュー名の階層を作る（dressupMenu）
- char\*.name,名前 — シェルの名前でゴーストの名前を上書きしていない（name.allowoverride と一緒に）
- char\*.seriko.alignmenttodesktop,位置情報 — シェルのスコープの alignmenttodesktop（いちばん先）
- craftman,作者名 — シェルの作者を出すところが無い。シェルの一覧に出す
- craftmanurl,URL — 同上
- craftmanw,作者名 — 同上
- kero.balloon.alignment,位置情報 — \![set,balloonalign] はある。既定をこれにする（none は画面の真ん中より左なら右、右なら左）
- kero.balloon.offsetx,座標 — \![set,balloonoffset] はある。既定をこれにする（l / r / c / b 付きも）。ssp-gap.md の ⛔ は古い
- kero.balloon.offsety,座標 — 同上
- kero.balloon.syncscale,true — シェルの \1 の syncscale
- kero.bindgroup\*.addid,ID — addid（char*.bindgroup*.addid と同じ）
- kero.bindoption\*.group,カテゴリ名,オプション — mustselect / multiple（char*.bindoption*.group と同じ）
- kero.defaultleft,X座標 — シェルの descript.txt の既定位置を読んでいない
- kero.defaulttop,Y座標 — 同上
- kero.defaultx,X座標 — 同上
- kero.defaulty,Y座標 — 同上
- kero.menuitem\*,ID — menuitem の順と区切り（char*.menuitem* と同じ）
- kero.menuitemex\*,メニュー名,ID — menuitemex の階層（char*.menuitemex* と同じ）
- kero.name,名前 — シェルの kero.name でゴーストの名前を上書きしていない
- kero.seriko.alignmenttodesktop,位置情報 — シェルの \1 の alignmenttodesktop
- menu.background.font.color.b,数値 — SHIORI リソースのものは使う。シェルの descript.txt のものも読む（menuColors、リソースが先）
- menu.background.font.color.g,数値 — 同上
- menu.background.font.color.r,数値 — 同上
- menu.font.height,フォントサイズ — \f[height] をメニューの頭に付ける（ゴーストの指定より先）
- menu.font.name,フォント名 — \f[name] をメニューの頭に付ける
- menu.foreground.font.color.b,数値 — SHIORI リソースのものは使う。シェルの descript.txt のものも読む（menuColors）
- menu.foreground.font.color.g,数値 — 同上
- menu.foreground.font.color.r,数値 — 同上
- readme.charset,文字コード — シェルの readme を作るとき、この文字コードで読む
- readme,ファイル名 — シェルの readme を開くところが無い。シェルのメニューか一覧から開けるようにする
- sakura.balloon.alignment,位置情報 — \0 の balloonalign の既定（kero.balloon.alignment と同じ）
- sakura.balloon.offsetx,座標 — \0 の balloonoffset の既定（l / r / c / b 付きも）。ssp-gap.md の ⛔ は古い
- sakura.balloon.offsety,座標 — 同上
- sakura.balloon.syncscale,true — シェルの \0 の syncscale
- sakura.bindgroup\*.addid,ID — addid
- sakura.bindoption\*.group,カテゴリ名,オプション — mustselect / multiple
- sakura.defaultleft,X座標 — シェルの descript.txt の既定位置を読んでいない
- sakura.defaulttop,Y座標 — 同上
- sakura.defaultx,X座標 — 同上
- sakura.defaulty,Y座標 — 同上
- sakura.menuitem\*,ID — menuitem の順と区切り
- sakura.menuitemex\*,メニュー名,ID — menuitemex の階層
- sakura.name,名前 — シェルの sakura.name でゴーストの名前を上書きしていない
- sakura.name2,名前 — シェルの sakura.name2 で %selfname2 を上書きしていない
- sakura.seriko.alignmenttodesktop,位置情報 — シェルの \0 の alignmenttodesktop
- seriko.alignmenttodesktop,位置情報 — シェル全体の alignmenttodesktop
- seriko.dpi,推奨DPI — シェル画像の倍率に 96 / dpi を掛ける（SurfaceRenderer と当たり判定）
- seriko.paint_transparent_region_black,数値 — .pna のとき、左上の色の画素を黒にしてから透明度を掛ける（既定は 1 相当）。今は色のまま
- seriko.sticky-window,スコープID,スコープID,... — \![set,sticky-window] はある。シェルの descript.txt から起動時の組を作っていない
- seriko.zorder,スコープID,スコープID,... — \![set,zorder] はある。シェルの descript.txt から起動時の順を作っていない

### 🔧 一部だけ（4）
- char\*.bindgroup\*.name,カテゴリ名,パーツ名,サムネイル名 — カテゴリとパーツは読む。サムネイル名は読まない（メニューは文字だけ、OnNotifyDressupInfo も空）
- kero.bindgroup\*.name,カテゴリ名,パーツ名,サムネイル名 — サムネイル名は読まない
- sakura.bindgroup\*.name,カテゴリ名,パーツ名,サムネイル名 — サムネイル名は読まない
- seriko.use_self_alpha,値 — 1 だけ読む。true と full を読まない。1 でもアルファの無い画像を左上の色で抜かない（full の動きになる）

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

済み 114 項目。

### 🔨 作る（1）
- overlayscreen — blend-screen-fast と同じにする。今は else に落ちて普通の overlay になる（SurfaceRenderer の when に1行）

### 🔧 一部だけ（17）
- animation-sort,ソート順序 — 当たり判定の順にだけ効く。ascend でも絵の重ね順は変わらない（SurfaceRenderer はいつも番号の大きい方が手前）
- descriptブレス記述例 — surfaces*.txt をつないで読むので、descript ブレスをファイルごとに違う設定にできない（最後のものが全体に効く）
- animation\*.collision\*,当たり判定定義 animation\*.collisionex\*,当たり判定定義(ex) — animation*.collision / collisionex は読むが、collisionex の region が無い
- animation\*.name,定義名 — \i と \![anim] では名前を使えるが、start / stop / alternativestart などと exclusive,(…) の名前は読まない（数字だけ拾う）
- animation\*.option,shared-index — \i や start で動かしたものだけ続きから動く。interval（always など）で動いているものは running に無いので最初からになる
- animation\*.pattern\*,描画メソッド,サーフェス番号,ウェイト,X座標,Y座標(,オプション...) — --alpha、サーフェス番号 -2（ほかのアニメーションを全部止める）、サーフェス番号に別名を書く（SSP 2.8.25）が無い
- SERIKOの旧定義と新定義について — 旧定義の start / alternativestart / insert などで番号を読まない（parseOldPattern が targets を作らない）
- 各要素の上下構造 — 着せ替えはいつも普通のアニメーションの下（番号順にならない）。background のアニメーションの当たり判定を土台より先に見る
- collisionex\*,ID,タイプ,座標1,座標2... — rect / ellipse / circle / polygon はある。region（画像の指定色の領域）が無い
- element\*,描画メソッド,ファイル名,X座標,Y座標(,オプション...) — --clipping / --alpha / --source / --scaling と、surface*.png や element の GIF / APNG / WebP を自動で動かすのが無い
- auto — overlay として重ねる。--source の PSD などのレイヤーから描画方法を読む部分が無い
- blend-add-glow-fast — -glow を取って読むだけで、発光（半透明の所の足し方）の計算が無い。ふつうの合成と同じになる
- blend-add-glow — -glow を取って読むだけで、発光（半透明の所の足し方）の計算が無い。ふつうの合成と同じになる
- blend-color-dodge-glow-fast — -glow を取って読むだけで、発光（半透明の所の足し方）の計算が無い。ふつうの合成と同じになる
- blend-color-dodge-glow — -glow を取って読むだけで、発光（半透明の所の足し方）の計算が無い。ふつうの合成と同じになる
- 描画メソッド — ベースレイヤの外にはみ出したコマで絵を広げない（土台の大きさで切れる。element も負の座標は切れる）
- 着せ替え（MAYUNA） — 多重着せ替えが無い（コマに使ったサーフェスの bind アニメーションを重ねない。baseImage は element だけ）

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

済み 279 項目。

### 🔨 作る（9）
- \q[ID][タイトル]または\q*[ID][タイトル] — 古い \q[ID][タイトル] / \q0[ID][タイトル] を読まない（tokenize で2つ目の [] を読む）
- \* — \* を読み飛ばしていて、選択肢がタイムアウトする。Host の choiceTimeoutMs を 0 にすればよい
- \__c — \__c が未対応のタグになる。\![open,communicatebox] と同じにする
- \__t — \__t を見た目だけのタグとして読み飛ばしている（コメントも「時刻」と誤り）。\![open,teachbox] と同じにする
- \![open,addressbar] — URL を打つ箱を出して、共有で URL を渡したのと同じ処理（onTextShared）に回せる。docs では ⛔ だが PC 専用ではない
- \![open,archiveviewer,(ファイル名)] — ファイルを指定されたら、ほかのアプリで開く（\![open,file] と同じ）。ビューア自体は持たない
- \![open,pictureviewer,(ファイル名)] — 画像をほかのアプリで開く（\![open,file] と同じ道筋。OnPictureViewerOpen はもうある）
- sound の --spectrum= で通知される帯域とレベル \[2.9.18\] — MediaCodec で波形を取り出して FFT し OnSoundSpectrum を送る（Visualizer はマイクの許可が要るので使わない）
- \![set,alignmentondesktop,bottomまたはtop] — 黙って飛ばしている。alignmenttodesktop の top / bottom と同じにすればよい（SSP も同じ扱い）

### 🔧 一部だけ（55）
- \_b\,オプション...\] — --option=… と --clipping / --scaling を読まない（opaque は位置引数のときだけ）
- \_b\,オプション...\] — --option=foreground・fixed、--clipping / --scaling を読まない。画像はいつも文字の下
- \_l[x,y] — x が数のときに次の列にするだけ。y・centerx / centery・em / lh / %・@ の相対を扱わない
- \b[ID番号] — \b[ID,--fallback=ID2] の代わりの番号を読まない
- \![set,balloontimeout,時間] — 時間を省くと既定に戻す、が無い（未対応のタグとして飛ばす）
- 補足 — \_b の --option=opaque / use_self_alpha / fixed / foreground / background、--clipping、--scaling、--source と base64・URL の画像を読まない
- 補足 — 色の default.anchor / default.anchornotselect / default.anchorvisited / default.cursor / default.plain が無く、CSS の色名は 25 個だけ
- \_a[ID,r2,r3...] — ジャンパだけのスクリプトも選択肢として待ち、OnChoiceTimeout になる（SSP は選択肢タイムアウトせず、バルーンのタイムアウト）
- \_a[ID] — ジャンパだけのスクリプトも選択肢として待ち、OnChoiceTimeout になる（SSP は選択肢タイムアウトせず、バルーンのタイムアウト）
- \_a[OnID,r0,r1...] — ジャンパだけのスクリプトも選択肢として待ち、OnChoiceTimeout になる（SSP は選択肢タイムアウトせず、バルーンのタイムアウト）
- \![set,choicetimeout,時間] — 時間を省くと既定に戻す、が無い
- \![change,balloon,バルーン名] — lastinstalled を読まない（random はある）
- \![change,shell,シェル名(,--option=raise-event)] — --option=raise-event（OnShellChanging）と lastinstalled を読まない
- \![set,wallpaper,ファイル名,オプション] — 配置の stretch-x / stretch-y / span を読まない（center・tile・stretch・fit・fill と --color はある）
- \f[bold,パラメータ] — default が off になる（バルーンが太字などのとき消せない）、disable が無効表示の飾りにならない
- \f[color,色指定] — default.anchor などの default.* と、CSS の色名の多くを読まない
- \f[height,数値] — 数・%・+/- はある。スタイルシートの大きさ（xx-small〜xx-large など）を読まない
- \f[italic,パラメータ] — default が off になる（バルーンが太字などのとき消せない）、disable が無効表示の飾りにならない
- \f[strike,パラメータ] — default が off になる（バルーンが太字などのとき消せない）、disable が無効表示の飾りにならない
- \f[sub,パラメータ] — default が off になる（バルーンが太字などのとき消せない）、disable が無効表示の飾りにならない
- \f[sup,パラメータ] — default が off になる（バルーンが太字などのとき消せない）、disable が無効表示の飾りにならない
- \f[underline,パラメータ] — default が off になる（バルーンが太字などのとき消せない）、disable が無効表示の飾りにならない
- \![enter,inductionmode] — 上の InductionMode と同じ
- \![enter,passivemode] — 上の PassiveMode の制限の多くが無い
- \![execute,compressarchive,ファイル名,ディレクトリ名,オプション...] — --password（AES の ZIP）が無い
- \![execute,dumpballoon,ディレクトリ,スコープID,prefix,イベントID] — --hide=balloonmarker,balloonnum を読まない
- \![execute,extractarchive,ファイル名,ディレクトリ名,オプション...] — --password（パスワード付き ZIP）が無い。java.util.zip では読めないので zip4j などが要る
- \![execute,install,url,URL,種別] — 種別は nar だけ。homeurl（そこから更新）・ical（予定表として購読）・ssf を作れる。feed はヘッドラインなので作らない
- \![execute,schedule-add,オプション,オプション,オプション...] — --url・--categories・--status・--type・--exdate・--script を読まない
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
- \j[ID] — http / https だけ。file:///（ghost/master からのファイルを開く）と mailto: が無い
- InputBoxのID — On で始まる ID はある。--reference= の追加情報を Reference に足さない
- InputBoxオプション — --text / --timeout は ipinput だけ。noclose / noclear・--placeholder・--balloon・--limit・--reference が無い
- \!\...\] — --sound-only（動画の音だけ）と --spectrum 系が無い
- \!\...\] — --spectrum 系の開始・止める・間隔の変更が無い
- \!\...\] — --volume / --balance / --rate / --seektime はある。--window と --spectrum 系が無い
- sound play/load/option のオプション — --window・--sound-only・--spectrum / --spectrum-bands / --spectrum-hold が無い
- \![anim,add,overlay,ID] — 最後のアニメーションタイミング runonce / always を読まない
- \![bind-noevent,カテゴリ名,パーツ名,数値] — \![bind] と同じに扱い、OnDressupChanged と OnNotifyDressupInfo を送ってしまう
- \![bind,カテゴリ名,パーツ名,数値] — パーツ名を空にしたカテゴリごとの着脱が無い（category と part が両方合うものだけ）
- \![moveasync] — \![moveasync,cancel] で動いている途中を止める、が無い
- \![set,alpha,数値,オプション] — --time=（だんだん変える）と --wait を読まず、すぐ変える
- \![set,scaling,横倍率,縦倍率,オプション] — --time= と --wait を読まず、すぐ変える
- \__w[時間] — 再生の始め（か \x）から数えず、そこから待つだけ。\__w[clear] も無い
- \x — クリックのあと \0 に戻さない。消すのは \0 と \1 のバルーンだけ

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

済み 147 項目。

### 🔨 作る（27）
- activeghostlist(ゴースト名/本体側名/パス).ext.拡張プロパティ名 — ext.名前 を相手の SHIORI に property.get / property.set で聞く・書く（shiori.変数名 と同じ道筋で作れる）
- activeghostlist.index(ID).ext.拡張プロパティ名 — 上の ext と同じ。index(ID) で選んだゴーストの SHIORI に property.get / set
- thumbnail — そのフォルダの thumbnail.png（ゴースト・シェル・バルーン）のパスを返す
- update_result — ネットワーク更新の最後の結果をゴーストごとに profile に残して返す
- update_time — ネットワーク更新の最後の時刻を profile に残して返す
- ghostlist(ゴースト名/本体側名/パス).icon — ghostProperty に icon が無い。descript.txt の icon をゴーストのフォルダからのパスにして返す
- rateofuselist(名前).bootminute — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).bootminutemonthly — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).bootminuteweekly — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).boottime — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).boottimemonthly — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).boottimeweekly — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).keroname — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).name — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).percent — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).percentmonthly — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).percentweekly — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- rateofuselist(名前).sakuraname — rateofuselist(名前). の形を読まない（index(順位) だけ）。rateOfUseProperty で名前から選べばよい
- サウンドプロパティのメタデータ（meta.～）について \[2.8.73\] — meta.* を作るときの決まり（タグが無ければプロパティごと無いことにする）
- meta.album — meta.* は無い。MediaMetadataRetriever でタグを読み、無ければ取得失敗にする
- meta.albumartist — meta.* は無い。MediaMetadataRetriever でタグを読み、無ければ取得失敗にする
- meta.artist — meta.* は無い。MediaMetadataRetriever でタグを読み、無ければ取得失敗にする
- meta.artwork — meta.* は無い。MediaMetadataRetriever でタグを読み、無ければ取得失敗にする
- meta.genre — meta.* は無い。MediaMetadataRetriever でタグを読み、無ければ取得失敗にする
- meta.title — meta.* は無い。MediaMetadataRetriever でタグを読み、無ければ取得失敗にする
- meta.track — meta.* は無い。MediaMetadataRetriever でタグを読み、無ければ取得失敗にする
- meta.year — meta.* は無い。MediaMetadataRetriever でタグを読み、無ければ取得失敗にする

### 🔧 一部だけ（8）
- currentghost.scope(ID).animation.num — 動いているアニメーションの ID の一覧でなく、そのサーフェスの定義の数を返している。SET（\i と同じに動かす）も無い
- currentghost.status — opening(teach / textarea / dialog) とスラッシュ区切り、表示中のバルーンの balloon(キャラID=バルーンID/…) を足す
- craftmanurl — ゴーストだけ。バルーンとシェルの descript.txt の craftmanurl を返さない
- craftmanw — ゴーストとバルーンだけ。シェルの craftmanw を返さない
- homeurl — ゴーストだけ。バルーンとシェルの homeurl を返さない
- username — %username はあるが、currentghost.username と activeghostlist(…).username を返さない（一緒に動いているゴーストの userName を聞く）
- 補足 — Status の opening(…) は communicate / input だけ（teach・textarea・dialog と / 区切りが無い）、balloon(0=2/1=0) が無い
- system.cpu.(キー) — load と clock がいつも 0、vendor と features が空。load は OnCPULoadHigh で測る /proc/stat の値を使える

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

済み 219 項目。

### 🔨 作る（34）
- OnBasewareUpdated — 前に起動したときのアプリの版を覚えておき、変わっていたら OnBoot の代わりに送る（ssp-gap では ⛔）
- OnDestroy — SHIORI を外す直前に NOTIFY（reloadShiori のときは reload）。ssp-gap では ⛔ だが足すだけ
- OnInitialize — SHIORI を読み込んだ直後に NOTIFY（読み込み直しは reload）。ssp-gap では ⛔ だが SSP は送っているので足すだけ
- OnNotifyPluginFailure — \![notifyplugin] を受けたら notfound で送る（いまは黙って捨てている。ssp-gap では ⛔）
- OnRaisePluginFailure — プラグインが無いので、\![raiseplugin] / \![timerraiseplugin] を受けたら notfound で送ればよい（いまは黙って捨てている。ssp-gap では ⛔）
- OnArchiveViewerOpen — 答えが無い zip などを OnPictureViewerOpen と同じく、ほかのアプリで開いて送る（dropDefault に足す。ssp-gap では ⛔）
- OnFileDrop — OnFileDrop2 と OnFileDropEx に答えが無いとき、古いゴースト向けに1ファイルずつ送る（いまは送っていない）
- OnFileDropped — OnFileDrop の後に送る古い通知。OnFileDrop と一緒に足す
- inputbox.autocomplete — 入力の箱を開くときに聞き、返った候補（バイト値1区切り）を InputBalloon の入力欄の下に出す
- OnInstallReroute — accept で名指しされたゴーストが一緒に起動していたら、そちらへ入れて以降のイベントもそちらに送る（いまは OnInstallRefuse）
- OnMouseDoubleClickEx — 2本指で2回続けて叩いたとき（とマウスの中・戻る・進むボタン）に middle などで送る
- OnMouseHover — マウスやペンのホバー（ACTION_HOVER_MOVE）が止まったときに送る。指では起きない
- OnMouseMultipleClickEx — 2本指で3回以上続けて叩いたとき。答えが無ければ ClickEx / DoubleClickEx へ
- OnUpdateProcessExec — メニュー・アプリの「更新」から始めるとき最初に送り、答えがあればそれを流して標準の更新はしない
- OnUpdateResultExplorer — アプリのゴースト一覧の「更新」から始めたときは OnUpdateResult の代わりにこれを送る
- balloonpathlist — 起動時にバルーンの置き場のパスを NOTIFY
- capability — 起動時に pono-droid が扱う SHIORI のヘッダ（request.* / response.*）を NOTIFY
- configuredbiffname — 設定したメールのアカウント名を起動時に NOTIFY（いまは1つだけ）
- enable_log — 開発用の画面に「SHIORI のログ」の入り切りを足し、変えたら NOTIFY
- ghostpathlist — 起動時にゴーストの置き場のパスを NOTIFY
- installedballoonname — 起動時に入っているバルーンの名前を NOTIFY
- installedghostname — 起動時に repository の一覧から NOTIFY
- installedkeroname — installedghostname の前に、同じ並びで kero.name を NOTIFY
- installedsakuraname — installedghostname の前に、同じ並びで sakura.name を NOTIFY
- installedshellname — 起動時にこのゴーストのシェルの名前を NOTIFY
- otherghostname — 一緒に起動しているゴーストの名前とサーフェスを起動時と増減したときに NOTIFY
- rateofusegraph — 名前だけ見つかったが NOTIFY は送っていない。使用率を出すとき（\![open,rateofusegraph]・メニュー）に history.usage から送る
- OnDesktopWallpaperChange — WallpaperManager の色が変わった知らせで送る（Reference2 のパスは空、きっかけは unknown）。ssp-gap では ⛔
- OnDisplayChangeEx — 画面1枚分だけ送る（例 0,0,幅,高さ,32,1,unknown,0）。起動時 NOTIFY と回転のとき。ssp-gap では ⛔
- OnMusicPlayEx — 🔒 通知へのアクセスを許したときだけ MediaSessionManager で曲名・アーティスト・album・duration などを送る。ssp-gap では ⛔
- OnSoundSpectrum — \_v の --spectrum。自分で鳴らす音を MediaExtractor / MediaCodec で読んで帯域ごとの音量を出す（Visualizer はマイクの許可が要る）。ssp-gap の ⛔ の理由は違う
- OnVideoPlayEx — 🔒 OnMusicPlayEx と同じ仕組みで、動画のセッションのときに送る。ssp-gap では ⛔
- property.get — activeghostlist(...).ext.名前 を読んだとき相手のゴーストに送る（.ext はまだ無い）
- property.set — activeghostlist(...).ext.名前 に書いたとき相手のゴーストに送り、204 なら成功

### 🔧 一部だけ（8）
- OnSchedule5MinutesToGo — Reference0 に種類（event など。\![execute,schedule-add] の --type）でなく ghost / user を入れ、Reference3 のスクリプトがいつも空
- OnScheduleTodayNotify — 各予定の1つ目に種類でなく ghost / user を入れ、スクリプトがいつも空（--type・--script を覚えていない）
- OnInstallFailure — install.txt の不備を invalid で送る（SSP は invalid type）。unknown は決まった値に無い
- ネットワーク更新理由 — メニューやアプリの「更新」からは user を送っている。SSP の値は manual。自動更新（auto）は無い
- ネットワーク失敗理由 — timeout・md5 miss・404 など・fileio は送る。更新中にもう一度始めたときの executing を送らず黙って止める
- basewareversion — Reference0 に pono-droid、Reference1 に版を送っていて逆。仕様は Reference0 が版、Reference1 が本体の名前
- OnMusicPlay — SSTP の GIVE Song だけ。端末で鳴っている曲（MediaSession）からは送らない
- アーカイブ操作エラーコード — Failure の理由が例外の文（notauthorized・too large など）のまま。file not found・corrupted・password required などの決まった言葉にする

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

済み 70 項目。

### 🔨 作る（18）
- オーナードローメニュー項目表示 — *button.visible（0 なら出さない）を聞いていない。メニューに出す項目ごとに caption と一緒に聞く
- ショートカットキー — 「(&P)」などがそのまま出てしまう。キーは無いので & の印を表示から消す
- char\*.recommendsites.caption — \p[2] 以降のおすすめの名前として、char*.recommendbuttoncaption の次に聞く
- configurationbutton.caption — メニューに「設定」を足す（\![open,configurationdialog] と同じくアプリの設定を開く）
- configurationrootbutton.caption — configurationbutton と同じ項目の名前として聞く
- dictationbutton.caption — メニューに音声認識の入り切りを足す（マイクの許可が無ければ設定を開く）
- ghostexplorerbutton.caption — メニューにアプリのゴースト一覧を開く項目を足す（\![open,ghostexplorer] と同じ）
- ghostinstallbutton.caption — メニューに .nar を選んで入れる項目を足す（アプリの画面にある機能）
- helpbutton.caption — メニューに「説明書」を足す（\![open,help] と同じ pono-droid の説明）
- leavepassivebutton.caption — パッシブモードの間、メニューに強制解除を出してこの名前を使う
- shellscaleotherbutton.caption — 大きさの下位メニューの「その他」。入力の箱で倍率を聞く
- shellscalerootbutton.caption — メニューにキャラクターの大きさを足す（アプリの画面にあり、OnShellScaling も送れる）
- sntpbutton.caption — メニューに時計合わせ（\7 と同じ、ずれを測るだけ）を足す
- switchblacklistingbutton.caption — メニューの「SSTP を受けない」の名前に使う（いまは決まった文）
- switchmovetodefaultpositionbutton.caption — メニューの「位置を戻す」の名前に使う（いまは決まった文）
- switchquietbutton.caption — メニューに「サウンドを鳴らさない」の入り切りを足す（いまは無い）
- switchreloadbutton.caption — メニューに SHIORI の読み込み直し（reloadShiori）を足す
- texttospeechbutton.caption — メニューに読み上げの入り切りを足す（アプリの設定にある）

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

済み 12 項目。

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

### 🔧 一部だけ（12）
- request — SenderType がいつも internal（SSTP・raise・embed・communicate を区別しない）。Status・BaseID・SecurityOrigin・X-SSTP-PassThru-* を送らない
- response — Value しか見ていない。ValueNotify・SecurityLevel・Marker・ErrorLevel / ErrorDescription・BalloonOffset・Reference0 のコミュニケート（Age・MarkerSend）・X-SSTP-PassThru-* が無い
- メソッド：COMMUNICATE — Surface ヘッダを OnCommunicate に渡していない
- メソッド：EXECUTE — GetFMO が無い（501）。動いているゴーストの一覧を FMO の形で返せば作れる
- メソッド：SEND — Sender: First が無い。SHIORI の答えのヘッダを SSTP の答えに透過しない。Entry は SHIORI/2 用で要らない
- request/response — X-SSTP-PassThru-* を SHIORI に渡さず答えにも返さない。SecurityOrigin を渡さない。Option strict が無い
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
- バルーン — balloons*s.txt / balloonk*s.txt、balloonp*、arrows* / arrowk* / arrowp*、sstp*.png・marker*.png、clickwait*.png、thumbnail.png を使っていない（バルーンの組と重なる）
- ゴースト — thumbnail.png（一覧のサムネイル）・*.ico・ai0.png / ai0_dark.png（AI グラフの絵）を使っていない
- シェル — surface*.png や element に APNG / GIF / WebP を直に書いたときのアニメーション（SSP 2.7.38）が無い。今は止まった絵で読む
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

