# ukadoc-spec との差

[ukadoc-spec](https://github.com/ukatech/ukadoc-spec)（UKADOC の Markdown 版、b1f8ab1 時点）の項目を1つずつ pono-droid と比べた結果。
項目ごとの状態は [ukadoc-spec-status.tsv](ukadoc-spec-status.tsv)（DONE / PARTIAL / BUILD / IMPOSSIBLE / NA）。作ったら DONE に書き換える。
前に HTML 版の ukadoc から作った一覧は [ssp-gap.md](ssp-gap.md)。

全 2050 項目: 済み 1452・一部だけ 22・作る 0・作れない 239・ベースウェアのことではない 337

## バルーンの descript.txt など

済み 166 項目。

### 🔧 一部だけ（5）
- balloons\*s.txt/balloonk\*s.txt — 画像ごとの balloons*s.txt で、文字の範囲・矢印や印の位置・windowposition を上書きする。文字の色・大きさ・書体は descript.txt のまま
- overlay_outside_balloon,数値 — 0 ならオンライン・SSTP の印をバルーンの画像の中に収め、1 なら収めない。ウィンドウを印の分だけ広げてはいない
- sstpmessage.yb,座標 — 縦書きのときの印の文字の終わりとして読むが、印の文字は縦書きにしていない
- use_input_alpha,数値 — 1 なら入力の箱の画像もバルーンと同じ透明度の扱いにする。バルーン全体を半透明にする設定は pono-droid に無い
- vertical,0/1 — 1 なら上から下へ字を送り、右から左へ列を送る（縦書き用の句読点・かっこ、長音と英数字は回す、小さい仮名は右上）。\f[align] は列の中の寄せ。選択肢の長押しのカーソル、\f[valign] の厚み方向の寄せ、印の文字と数の縦書きは無い

### ― ベースウェアのことではない（4）
- balloons\*s.txt/balloonk\*s.txt/balloonc\*s.txt(個別指定) — 一覧のページ
- descript.txt — 一覧のページ
- descript.txt（Balloonの） — 概要と記述例。中身は各項目で見る
- descript.txt - 推奨ゴースト — 一覧のページ

## ゴーストの descript.txt

済み 69 項目。

### 🔧 一部だけ（1）
- seriko.alignmenttodesktop,位置情報 — 書いてあればその吸着にする。どこにも書いていないときは、アプリの設定のキャラクター自由移動が常に制限なら仕様どおり bottom、ゴースト設定なら自由（決めたこと）。最初の設定の常に自由移動では吸着しない

### ⛔ 作れない（1）
- makoto,ファイル名 — MAKOTO は Windows の DLL（⛔）

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

済み 86 項目。

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

済み 147 項目。

### 🔧 一部だけ（2）
- element\*,描画メソッド,ファイル名,X座標,Y座標(,オプション...) — --clipping / --alpha / --scaling と、動く surface*.png・element はある。--source（PSD のレイヤー・TIFF のページ・アイコン）は読まない。動く画像は SERIKO のコマより上に出る
- auto — overlay として重ねる。--source の PSD などのレイヤーから描画方法を読む部分が無い

### ⛔ 作れない（6）
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

済み 7 項目。

### ― ベースウェアのことではない（2）
- Shell設定 - surfacetable.txt — 目次のページ
- surfacetable.txt — 目次のページ

## install.txt・delete.txt・updates

済み 21 項目。

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

済み 342 項目。

### 🔧 一部だけ（6）
- \_l[x,y] — 横書きのバルーンは座標どおり（px・em・lh・%・@ の相対・centerx / centery、\_l の後は左寄せ）。縦書きのバルーンは次の列にするだけ
- 補足 — オプションと data:…;base64 は済み。残りは Windows の exe / dll のリソースと --source=（TIFF のページ・PSD のレイヤー・アイコン）で、Android では読めない
- \![execute,install,url,URL,種別] — 種別は nar だけ。homeurl（そこから更新）・ical（予定表として購読）・ssf を作れる。feed はヘッドラインなので作らない
- http/rss/ical/websocket オプション — --log=（送ったものと返ってきたものを ghost/master/var/ のファイルに足す）は済み。--insecure だけは安全のため使わない（証明書をいつも確かめる）
- \!\...\] — --spectrum / --spectrum-bands / --spectrum-hold と --sound-only は済み。--window（シークバーのついた再生のウインドウ）は pono-droid に無く、読むだけ
- sound play/load/option のオプション — --spectrum / --spectrum-bands / --spectrum-hold と --sound-only は済み。--window（シークバーのついた再生のウインドウ）は pono-droid に無く、読むだけ

### ⛔ 作れない（26）
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

済み 263 項目。

### ⛔ 作れない（53）
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
- OnKeyPress — オーバーレイがキーを受けると、ほかのアプリへの入力を横取りする（決めたこと）
- OnNekodorifExist — Windows の猫どりふ
- OnSNTPCorrect — アプリは端末の時計を直せない。ずれを測るだけ
- OnSNTPCorrectEx — アプリは端末の時計を直せない。ずれを測るだけ
- OnURLDragDropping — 落とす前のドラッグが無い。共有で渡すだけ（決めたこと）

### ― ベースウェアのことではない（42）
- 42 項目（一覧は ukadoc-spec-status.tsv）

## SHIORI リソース

済み 94 項目。

### ⛔ 作れない（65）
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
- alignrootbutton.caption — キャラクター自由移動の切り替えはアプリの設定の画面（ゴースト設定・常に自由移動・常に制限。決めたこと）なので、メニューに項目が無い
- alwaysstayontopbutton.caption — オーバーレイはいつも手前（決めたこと・メニューに無い）
- alwaystrayiconvisiblebutton.caption — タスクトレイが無い（決めたこと・メニューに無い）
- biffallbutton.caption — アカウントごとに分けず、「メールを確認」が全部を確かめる（決めたこと・メニューに無い）
- calendarbutton.caption — カレンダーの画面が無い（決めたこと・メニューに無い）
- callsstpsendboxbutton.caption — SSTP 送信ボックスが無い（決めたこと・メニューに無い）
- charsetbutton.caption — 文字コードの選択が無い（決めたこと・メニューに無い）
- collisionvisiblebutton.caption — Materia の当たり判定表示。メニューに無い（決めたこと・メニューに無い）
- debugballoonbutton.caption — Materia のバルーンマーカー表示。メニューに無い（決めたこと・メニューに無い）
- definedsurfaceonlybutton.caption — SSP の互換の切り替え。メニューに無い（決めたこと・メニューに無い）
- duibutton.caption — DUI が無い（決めたこと・メニューに無い）
- firststaffbutton.caption — バージョン情報の画面が無い（決めたこと・メニューに無い）
- headlinesensehistorybutton.caption — ヘッドラインセンサーは作らない（決めたこと・メニューに無い）
- headlinesenserootbutton.caption — ヘッドラインセンサーは作らない（決めたこと・メニューに無い）
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
- switchdontforcealignbutton.caption — キャラクター自由移動の切り替えはアプリの設定の画面（ゴースト設定・常に自由移動・常に制限。決めたこと）なので、メニューに項目が無い
- switchduivisiblebutton.caption — DUI が無い（決めたこと・メニューに無い）
- switchforcealignfreebutton.caption — キャラクター自由移動の切り替えはアプリの設定の画面（ゴースト設定・常に自由移動・常に制限。決めたこと）なので、メニューに項目が無い
- switchforcealignlimitbutton.caption — キャラクター自由移動の切り替えはアプリの設定の画面（ゴースト設定・常に自由移動・常に制限。決めたこと）なので、メニューに項目が無い
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

済み 1 項目。

### ― ベースウェアのことではない（212）
- 212 項目（一覧は ukadoc-spec-status.tsv）

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

済み 73 項目。

### 🔧 一部だけ（3）
- Decompress — gzip（複数のメンバーも）・zlib・deflate は済み。brotli と zstd の展開は部品（ライブラリ）が要るので、まだ 400 unsupported algorithm
- CompressArchive — overwrite・append・--password は済み。lzma とヘッダの暗号化（headerencrypt）は zip4j に無いので 500 unknown method
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

済み 3 項目。

### 🔧 一部だけ（3）
- バルーン — balloons*s.txt・arrows* など・marker・clickwait・sstp の画像は使う。balloonp*（3人目以降のバルーン画像）と thumbnail.png はまだ
- ゴースト — thumbnail.png（一覧のサムネイル）・*.ico・ai0.png / ai0_dark.png（AI グラフの絵）を使っていない
- シェル — surface*.png や element の APNG / GIF / WebP は動く（Android 9 から）。SERIKO のコマより上に出る

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

