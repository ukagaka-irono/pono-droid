# SSP にあって pono-droid に無いもの（洗い出し）

ukadoc（SSP の仕様書）のイベント・さくらスクリプト・プロパティシステム・SHIORI リソース・surfaces.txt の項目を全部取り出し、
pono-droid のコードと突き合わせた一覧（2026-10-24 時点）。

印の意味:

- ✅ 対応済み
- 🔨 Android で作れる（作る）
- 👥 複数のゴーストを同時に動かす仕組みが要る（大きな作り直し）
- 🔒 作れるが、安全のため決めてから（端末やアカウントに触る・ゴーストに権限を渡す）
- ⛔ 作れない（Windows の DLL やパソコン専用の機能が前提）

数: イベント 270（済 165）、さくらスクリプト 357、プロパティ 180、SHIORI リソース 161。

決めたこと（2026-10-24）:

- 🔒 は全部作る。どれもアプリの設定で使うかを選べるようにし、最初は使わない。SSTP はこの端末の中だけで受ける（LAN からは受けない）。
- 👥 の同時起動も作る（いちばん最後に）。
- ⛔ は作らない。

作る順番: 1 操作とウィンドウ → 2 文字とバルーン → 3 アニメーションとシェル → 4 端末の状態 → 5 通信 → 6 共有・インストール・消去・開く →
7 音と読み上げ → 8 プロパティとリソースの残り → 9 🔒 → 10 👥。作ったものは ✅ に書き換える。

---

## 1. イベント

### ✅ 対応済み
OnFirstBoot / OnBoot / OnClose、OnGhostChanging / OnGhostChanged、OnShellChanging / OnShellChanged、OnDressupChanged、
OnBalloonChange、OnSysSuspend / OnSysResume、OnSecondChange / OnMinuteChange / OnHourTimeSignal、
OnMouseDown / Up / Click / DoubleClick / MultipleClick / Move、OnMouseEnter / Leave(All)（指が触れた・離れた）、
OnMouseDragStart / End、OnMouseGesture（当たり判定の上をさっと払う）、OnMouseWheel（2本指で上下・上下に払った代わり）、
OnMouseDownEx / UpEx / ClickEx（2本指で叩く・メニューの「中クリック」）、OnChoiceSelect(Ex) / OnChoiceTimeout、OnAnchorSelect(Ex)、
OnOverlap / OnOffscreen、OnResetWindowPos、OnShellScaling / OnBalloonScaling、OnWindowStateMinimize / Restore、
OnChoiceEnter / OnChoiceHover / OnAnchorEnter / OnAnchorHover（選択肢を長押し）、
OnSelectModeBegin / Cancel / Complete / MouseDown / MouseUp（\![enter,selectmode] の間に指でなぞる）、
OnSurfaceChange / OnSurfaceRestore、OnBalloonBreak / Close / Timeout、OnTranslate、
OnUserInput(Cancel) / OnTextAreaInput(Cancel) / OnCommunicate(InputCancel) / OnTeach、
OnFileDrop2 / OnFileDropEx、OnUpdateBegin / Ready / Complete / Failure、OnExecuteHTTPComplete / Failure、
OnNetworkStatusChange、OnDisplayChange、OnBattery*（Notify / Low / Critical / ChargingStart / Stop）、OnRecommendsiteChoice、
起動時の OnNotifySelfInfo / BalloonInfo / ShellInfo / DressupInfo / UserInfo / OSInfo / InternationalInfo、
OnSessionLock / OnSessionUnlock（画面が消えた・ロックを解いた）、OnDarkTheme、OnLanguageChange、OnDisplayPowerStatus、
OnCPULoadHigh / Low（/proc/stat か /proc/loadavg を読める端末だけ）・OnMemoryLoadHigh / Low、OnNetworkHeavy（10 秒答えない）、
OnOSUpdateInfo（Android の最後の更新だけ）、OnNotifyFontInfo、OnNotifyHolidayInfo（日本の祝日を決まりから求める。日本以外は送らない）、
OnScreenSaverStart / End（Android のスクリーンセーバー）、
OnExecuteHTTPComplete / Failure / Progress / Streaming / SSLInfo、OnExecuteRSSComplete / Failure / _SSLInfo、
OnExecuteICalComplete / Failure / Progress / _SSLInfo、OnExecuteWebSocketOpen / Receive / Reconnect / Close / Failure / _SSLInfo、
OnPingComplete / Progress（Android では ICMP を直に送れないので InetAddress.isReachable で代わりにする）、OnNSLookupComplete / Failure、
OnExecuteFileWatchChange / Failure（ゴーストのフォルダの中だけ）、OnExtractArchiveComplete / Failure・OnCompressArchiveComplete / Failure
（ゴーストのフォルダの中だけ。パスワード付き ZIP は使えない）、OnSNTPBegin / CompareEx / Compare / Failure（ずれを測るだけ）、
OnUpdateCheckComplete / Failure、OnUpdateResult(Ex) / OnUpdateCheckResult(Ex)、OnUpdateOtherBegin / Ready / Complete / Failure（シェルとバルーン）、
OnTextDrop・OnURLQuery・OnURLDropping / OnURLDropped / OnURLDropFailure（Android の「共有」で文字や URL を渡す）、
OnInstallBegin / CompleteEx / Complete / CompleteAll / Failure / Refuse（.nar を入れたとき）、OnPictureViewerOpen / OnMediaPlayerOpen
（渡したファイルにゴーストが答えず、ほかのアプリで開いたとき）、OnXUkagakaLinkOpen（x-ukagaka-link: のリンク）、
OnVanishSelecting / Selected / Cancel / ButtonHold（メニューの「消去」。確かめはバルーンの選択肢、話している途中にバルーンをタップすると取りやめ）・
OnVanished（消去のあとに起きたゴースト。答えが無ければ OnBoot）、OnGhostTermsAccept / Decline（\![open,terms] をバルーンに出す）、
OnTeachStart / OnTeachInputCancel、OnCloseAll（ゴーストを終えるとき。答えが無ければ OnClose。端末の電源を切るときは system）、OnAITalk（\a）、
OnNarCreating / Created・OnUpdatedataCreating / Created（\![execute,createnar / createupdatedata] で作ったとき）、
OnSoundStop / OnSoundLoop / OnSoundError、OnSpeechSynthesisStatus（起動時の NOTIFY と、読み上げの設定を変えたとき）

### 🔨 Android で作れる
- 入力: OnSystemDialog / Cancel（ファイル選択などのダイアログ）、OnConfigurationDialogHelp

### 🔒 決めてから
- OnVoiceRecognitionStatus / Word（マイクで声を聞く。マイクの権限が要る）
- OnScheduleTodayNotify / 5MinutesToGo / Read、OnExecuteSchedule*（予定。端末のカレンダーを読む権限が要る）
- OnBIFFBegin / Complete / Failure、OnBIFF2Complete（メールの確認。メールのパスワードをアプリに預けることになる）
- OnSSTPBreak / OnSSTPBlacklisting（SSTP。外からゴーストに話させる口を開ける）
- OnDeviceArrival / Remove（USB 機器）、OnRecycleBin*（ゴミ箱）

### 👥 複数ゴーストの同時起動が要る
OnGhostCalling / Called / CallComplete、OnOtherGhostBooted / Changed / Closed / Vanished / Talk、OnOtherSurfaceChange、
OnOtherOverlap / Offscreen、OnRaiseOtherFailure / OnNotifyOtherFailure、OnOtherObjectDropping / Dropped

### ⛔ 作れない
- プラグイン・ヘッドライン・SAORI・MAKOTO（Windows の DLL）: OnRaisePluginFailure / OnNotifyPluginFailure、OnHeadlinesense*、
  OnRSSBegin / Complete / Failure（ヘッドラインセンサー版）、OnSchedulesense* / OnSchedulepost*、OnInternalSaori*
- パソコン専用: OnTrayBalloonClick / Timeout（タスクトレイ）、OnWallpaperChange / OnDesktopWallpaperChange、OnVirtualDesktopChanged、
  OnFullScreenAppMinimize / Restore、OnTabletMode、OnWindowModeChange、OnDisplayHandover、OnDisplayChangeEx（複数モニター）、
  OnSessionDisconnect / Reconnect（Windows のユーザー切り替え）、OnGamepad*（作れるが、Android でゲームパッドをつなぐ人はまずいない）、
  OnKeyPress（オーバーレイがキーを受けるには、他のアプリへの文字の入力を横取りすることになる）
- SSP 本体: OnBasewareUpdating / Updated（アプリの更新は Play ストア）、OnCacheSuspend / Restore（ゴーストキャッシュ）、
  OnInitialize / OnDestroy（SHIORI 内部向け）、OnEmbryoExist / OnNekodorifExist、OnFileDropping（落とす前のドラッグ）、
  OnArchiveViewerOpen、OnMusicPlay(Ex) / OnVideoPlayEx / OnSoundSpectrum（パソコンの音楽プレーヤー連携）

---

## 2. さくらスクリプト

### ✅ 対応済み
\0 \1 \h \u \p \s \i \n \n[half] \c \e \- \w \_w \__w \x \_q \q（タイトル,ID / On・r 付き / script:）\__q \_a \* \![*]
\b \_b \_l \_s \f（bold / italic / underline / strike / sub / sup / color / height / align / default / disable）\_u \_m \& \8 \_v \_V \j
\![raise] \![embed] \![notify] \![timerraise] \![get,property] %property[...] %month %day %hour %minute %second %username %selfname %keroname %screenwidth %screenheight
\![open,browser / inputbox / passwordinput / communicatebox / teachbox / dateinput / timeinput / sliderinput / textareabox / readme / configurationdialog]
\![close,...] \![set,balloontimeout / choicetimeout / scaling / alpha / position] \![reset,position] \![move] \![moveasync]（--option=ignore-sticky-window も）
\![set,zorder] \![reset,zorder] \![set,sticky-window] \![reset,sticky-window] \![set,windowstate,minimize] \![set,alignmenttodesktop]
\![execute,resetwindowpos] \4 \5 \v
\c[char,数,開始位置] \c[line,数,開始位置] \n[half] \n[パーセント] \_n \x[noclear]（\x は \f も戻す） \t \z \_? \_!
\f[name]（ゴーストやバルーンのフォルダのフォントファイルと、明朝・ゴシックなどの系統） \f[shadowcolor / shadowstyle / outline / valign / disable]
\![set,autoscroll / balloonwait / serikotalk] \![lock / unlock,balloonrepaint / repaint（manual も）/ balloonmove] \![execute,resetballoonpos]
\![enter / leave,onlinemode / nouserbreakmode / inductionmode]、バルーンの descript.txt の font.name / shadowcolor / shadowstyle / bold / italic /
underline / strike / outline / disable.font.color と、オンラインの印（online*.png、onlinemarker.x / y / interval）
\f[anchor.font.color] \f[cursor* / cursornotselect* / anchor* / anchornotselect* / anchorvisited*]（style / color / brushcolor / pencolor / fontcolor。
method は読み飛ばす）とバルーンの cursor.* / anchor.*、\![set,balloonoffset / balloonalign / balloonmarker / balloonnum]（sstpmessage.* / number.*）
\![anim,pause / resume / add,overlay / add,overlayfast / add,base / add,move / add,text] \__w[animation,ID] \i[ID,wait] \i[名前]（animation*.name）
\![wait,syncobject] \![set,syncobject] \![reset,syncobject]（アプリの中の名前つきの合図） \![enter / leave,collisionmode(,rect)] \![enter / leave,selectmode,rect,...]
\![execute,http-get / post / head / put / delete / patch / options]（--async / --sync / --file / --nofile / --param / --param-charset / --param-input-file /
--content-type / --accept / --accept-language / --authorization / --cookie / --header / --no-cache / --timeout / --progress-notify / --streaming /
--nodescript、古い書き方のパラメータ。--insecure は使わず、証明書はいつも確かめる） \![cancel,http] \![execute,rss-get / rss-post] \![execute,ical-get / ical-post]
（--from / --to / --expand / --limit、RRULE の FREQ・INTERVAL・COUNT・UNTIL・BYDAY・BYMONTHDAY・BYMONTH と EXDATE）
\![execute,websocket]（--async / --header / --authorization / --cookie / --m_websocketProtocol、切れたら5回までつなぎ直す） \![send,websocket(-binary)] \![close / cancel,websocket] \![execute,ping / nslookup] \![execute,filewatch] \![cancel,filewatch] \![execute,extractarchive / compressarchive] \7 \![executesntp]（\6 は時計を直せないので何もしない） \![updatebymyself,checkonly / testonly / recovery] \![update,ghost+shell+balloon] \![update,platform]（Play ストアに任せる） \![updateother,--shell / --balloon]
\![anim,offset / clear / stop] \![bind] \![bind-noevent] \![sound,play / loop / stop / wait / load / pause / resume / option]
（ファイルごとに何個でも。--volume / --balance / --rate / --seektime） \__v[disable] \__v[alternate,文字]（読み上げと発言履歴） \![change,ghost / shell / balloon]
\![execute,http-get] \![updatebymyself] \![reload,shiori] \![enter / leave,passivemode] \![quicksection]
\![vanishbymyself]（確かめずに消す。--option=query なら確かめる。消せるのは自分だけで、ほかにゴーストがいないときは消さない）、
\+ \_+（ほかのゴーストのどれか・並びで次のゴーストに替える）、\![change,calendarskin]（カレンダーが無いので何もしない）、\a（OnAITalk）、
\![reloadsurface] \![reload,shell / balloon / ghost / descript]（ghost と descript はすべて読み直して起き直す。イベントは送らない）
\![load / unload,shiori] \![open,terms]（ghost/master の terms.txt）
\![open,file]（ゴーストのフォルダの中のファイルを Android のアプリで開く） \![open,mailer] \![open,help]（pono-droid の説明を開く）
\![execute,createnar]（出力先を省けば pono/nar/ に作って「共有」で渡す。developer_options.txt・.narignore・.narinclude と、いつも外すもの）
\![execute,createupdatedata]（updates2.dau / updates.txt、MD5 と大きさ） \![execute,dumpsurface]（サーフェスの一覧・着せ替え・--animation の連番）
\![execute,dumpballoon] \![create,shortcut]（ホーム画面にそのゴーストを起こすショートカット。startup は Android に無いので扱わない）。
書き出しはどれもゴーストのフォルダの中だけ

### 🔨 Android で作れる
- 開く: \![open,dialog,open / save / folder / color]（ファイル選択など）、\![open,ipinput]、
  \![open,ghostexplorer など]（アプリの一覧画面）、\![open,surfacetest / developer / shiorirequest / errorlog / backlogviewer]（開発用の画面）
- 実行: \![set,property]（書き換えてよいものだけ）
- 環境変数: %lastghostname %lastobjectname %exh %et %wronghour %selfname2 %ms %mz %ml %mc %mh %mt %me %mp %m? %dms %*2
  （%ms などは SSP 同梱の単語表から選ぶもの。小さな単語表を持つ）
- 通知: \![set,trayballoon] / \![set,tasktrayicon]（Android の通知で代用）

### 🔒 決めてから
- ✅ 読み上げ（Android の音声合成。アプリの設定で選ぶ。最初は読み上げない）。設定の切り替えの画面は 6c-2 の画面と一緒に作る
- \![set,wallpaper] / \![save,wallpaper] / \![restore,wallpaper]（ゴーストが端末の壁紙を変える。壁紙の権限が要る）
- \![open,editor] / \![open,explorer]（他のアプリでゴーストのファイルを開かせる）
- \![execute,schedule-add / delete / get]（端末のカレンダーに予定を書く）
- \![biff]（メールの確認）、\![open,messenger]
- \![execute,emptyrecyclebin]（ゴミ箱を空にする）
- \![set,shioridebugmode]

### 👥 複数ゴーストの同時起動が要る
\![call,ghost]、\![raiseother] \![notifyother] \![timerraiseother] \![timernotifyother]、\![set,otherghosttalk] \![set,othersurfacechange]、\![updateother]

### ⛔ 作れない
\![effect] \![effect2] \![filter]（DLL のプラグイン）、\![raiseplugin] など（プラグイン）、\![execute,headline]、\![execute,calendarplugin]、
\![reload,makoto] \![load / unload,makoto] \![reload,aigraph] \![open,aigraph]、\![open,headlinesensorexplorer / pluginexplorer / calendar*]、
\m[umsg,wparam,lparam]（Windows のメッセージ）、\![set,windowstate,stayontop]（オーバーレイはいつも上）、\![set,alignmentondesktop]、\![sound,cdplay]、
\![open,addressbar] \![open,rateofusegraph*]（下の「使用率」を作れば作れる）

---

## 3. プロパティシステム

### ✅ 対応済み
system.year〜millisecond・dayofweek・cursor.pos・os.*・cpu.*・memory.*・monitor.*・power.*、baseware.*、ghostlist.*、balloonlist.*、
currentghost の一部（name・status・scope.count・shelllist.current.name など）

### 🔨 Android で作れる
- system.theme.app.mode / theme.os.mode（ダークモード）、system.dnd.mode（おやすみモード）、system.network、system.disk.*
- currentghost の残り: shelllist(…).* / index / count、scope(ID).surface.num / animation.num / seriko.defaultsurface / x / y / rect / name / scaling / currentmonitor.* / surface(ID).rect、
  balloon.*（scope(ID).x / y / rect / validwidth / validheight / lines / char_width / scaling など）、seriko.surfacelist.*、seriko.sticky-window / zorder、
  sound.count / sound.index(ID).* / sound(名前).*（鳴っている音の情報）、mousecursor 系（タッチでは空）、seriko.cursor / tooltip（定義を返す）
- sakura.bind.menu / kero.bind.menu / char*.bind.menu、shiori.変数名（SHIORI に聞く）
- history.ghost / balloon（使ったゴースト・バルーンの履歴）、rateofuselist.*（ゴーストごとの起動時間と割合）
- developer.log.*（スクリプトの記録）

### 👥 複数ゴーストの同時起動が要る
activeghostlist.*、ghostlist.current（同時起動の中での「今」）

### ⛔ 作れない
headlinelist.* / history.headline.*、pluginlist.* / history.plugin.*

---

## 4. SHIORI リソース

### ✅ 対応済み
version、craftman、craftmanw、name、homeurl、username、sakura / kero / char*.recommendsites・portalsites・*buttoncaption、readmebuttoncaption、updatebuttoncaption、
sakura / kero / char*.defaultx / defaulty / defaultleft / defaulttop（位置を覚えていないときの最初の位置。descript.txt のものも）、
vanishbuttoncaption / vanishbuttonvisible（メニューの「消去」）

### 🔨 Android で作れる
- sakura / kero / char*.popupmenu.visible / type / applybindtoself（長押しメニューを出すか）
- メニューの見た目: menu.*.bitmap.filename、menu.*.font.color.*、menu.separator.color.*、menu.frame.color.*、menu.disable.font.color.*
- メニューの項目名: *button.caption のうち pono-droid のメニューにある項目（シェル・バルーン・着せ替え・ゴースト切り替え・終了・更新・Read Me・利用規約など）
- getaistate / getaistateex（AI の状態を聞いて、長押しメニューなどで見せる）、log_path、useorigin1、other_homeurl_override、tooltip / balloon_tooltip（長押しで代用）

### ⛔ 作れない
SSP 専用のメニュー項目の名前（ヘッドライン・プラグイン・カレンダー・DUI・タスクトレイ・SSTP 送信ボックスなど）、boxname.defaultleft、legacyinterface

---

## 5. そのほか（仕様書の項目以外）

- 🔒 SSTP サーバー（外のアプリやパソコンからゴーストに話させる。端末の中だけ・LAN も、のどちらにするかを決める）
- 👥 複数ゴーストの同時起動（上の 👥 はすべてこれが前提）
- ⛔ SHIORI の美坂・偽林檎など（Windows の DLL。ソースが公開されていて Android 用にビルドできるものだけ作れる）
- ⛔ SAORI・YAYA の EXECUTE・プラグイン（安全のために止めている。ゴーストに端末でプログラムを動かさせることになる）
- ✅ surfaces.txt: collisionex の rect / ellipse / circle / polygon、descript ブレスの collision-sort / animation-sort、animation*.collision(ex)、
  animation*.option（exclusive・background・shared-index）、animation*.name、interval の + の組み合わせと starttalk / endtalk、
  surface.append（あるサーフェスにだけ足す）、element0 による surfaceN.png の置き換え、描画メソッドの overlay-fast / interpolate / replace /
  asis / reduce / move / scaling / add / bind と blend-*（-fast も）、point.basepos / centerx / kinoko、sakura.tooltips など（触れている間に出す）
- ✅ surfaces.txt の import（APNG / GIF / WebP。Android 9 より前は最初のコマだけ）
- ✅ .nar の type,shell / supplement / package、accept（違うゴーストなら OnInstallRefuse）、refresh / refreshundeletemask、
  balloon0.directory などの複数同梱、\![execute,install,path / url]、\![execute,dropdefault,file / url]
- ✅ サウンドを同時にいくつも鳴らす（16 個まで）
- 🔨 icon.rect（発言履歴の顔）
- ⛔ surfaces.txt の cursor ブレス（マウスカーソルが無い）、sakura.balloon.offsetx / offsety（pono-droid のバルーンはいつもキャラクターの上）、
  オーバーレイの透明な部分のタッチを下に通す（Android では画素単位にはできない）
