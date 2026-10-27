# SSP にあって pono-droid に無いもの（洗い出し）

ukadoc（SSP の仕様書）のイベント・さくらスクリプト・プロパティシステム・SHIORI リソース・surfaces.txt の項目を全部取り出し、
pono-droid のコードと突き合わせた一覧（2026-10-24 時点）。

2026-10-26 からは、Markdown 版の ukadoc-spec の全項目と突き合わせた [ukadoc-spec-gap.md](ukadoc-spec-gap.md) を使う。
この一覧で ⛔ にしたもののうち、あちらで作れると分かったもの（balloon.offsetx / offsety、OnInitialize / OnDestroy、OnBasewareUpdated、
OnInternalSaori*、OnSoundSpectrum、\![open,addressbar] など）は、あちらが正しい。

印の意味:

- ✅ 対応済み
- 🔨 Android で作れる（作る）
- 👥 複数のゴーストを同時に動かす仕組みが要る（大きな作り直し）
- 🔒 作れるが、安全のため決めてから（端末やアカウントに触る・ゴーストに権限を渡す）
- ⛔ 作れない（Windows の DLL やパソコン専用の機能が前提）

数: イベント 270（済 188）、さくらスクリプト 357、プロパティ 180、SHIORI リソース 161。

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
OnSoundStop / OnSoundLoop / OnSoundError、OnSpeechSynthesisStatus（起動時の NOTIFY と、読み上げの設定を変えたとき）、
OnUpdate.OnDownloadBegin / OnMD5CompareBegin / OnMD5CompareComplete / OnMD5CompareFailure（と OnUpdateOther. のもの）、
OnTrayBalloonClick / Timeout（\![set,trayballoon] の Android の通知に触れた・消した。何行かある文は 0x01 でつなぐ）、
OnSSTPBreak（SSTP で届いたスクリプトをバルーンのタップで止めた）・OnSSTPBlacklisting（メニューの「SSTP を受けない」）、
OnScheduleTodayNotify（起動時と、日付が変わったとき・予定を足したり消したりして今日の予定が変わったとき）・OnSchedule5MinutesToGo（--notify の分か 5 分前）、
OnExecuteScheduleComplete / Failure / GetComplete（ゴースト用の予定表。端末のカレンダーの予定は、設定で選んで許可したときだけ「今日の予定」に入る）、
OnVoiceRecognitionStatus / OnVoiceRecognitionWord（設定で選び、マイクを許可したときだけ聞く。名前を呼ぶとそのキャラクターに伝わり、
呼んでから 30 秒のうちに言ったこともそのキャラクターへ。誰も呼んでいない話し声は送らない。画面が消えている間は聞かない）、
OnBIFFBegin / Complete / Failure・OnBIFF2Complete（\![biff] とメニュー・設定した間隔。POP3 と IMAP をいつも TLS で。パスワードは
Android Keystore の鍵で暗号にして保存する。中身は読まず、差出人と件名だけ）、
OnSystemDialog / OnSystemDialogCancel（\![open,dialog,...]。選んだファイルはゴーストのフォルダの pono/dialog/ に写して渡す）、
OnDeviceArrival / OnDeviceRemove（USB 機器。設定で選んだときだけ、画面がついている間に 10 秒ごとに見る。起動したときにつないであったものは知らせない）

### 🔨 Android で作れる
- ⛔ OnConfigurationDialogHelp（SSP の設定画面の項目ごとのヘルプ。pono-droid の設定画面にはヘルプのボタンが無い）

### 🔒 決めてから
- ⛔ OnScheduleRead（カレンダーのアイコンの上にカーソルを置いたとき。pono-droid にはカレンダーの画面もカーソルも無い）
- ⛔ OnRecycleBin*（Android にはごみ箱が無い。写真などのアプリのごみ箱を空にするのは、ユーザーのデータを消すことになるので作らない）

### 👥 複数ゴーストの同時起動が要る
- ✅ 3 体まで同時に動かせる（1体ずつ別の SHIORI のプロセス。2体目からは \![call,ghost]・メニューの「ほかのゴーストを呼ぶ」・アプリの一覧の「一緒に起動」で起動する）:
  OnGhostCalling / Called / CallComplete、OnOtherGhostBooted / Changed / Closed / Vanished / Talk（OnOtherGhostTalk への答えは
  ほかのゴーストに伝えない。2体が互いに答え続けないように）、OnOtherSurfaceChange、OnRaiseOtherFailure / OnNotifyOtherFailure
- ✅ OnOtherOverlap / Offscreen（キャラクターは「本体側の名前/番号」）
- ✅ アプリの一覧の「一緒に起動」（ほかのゴーストが動いているときだけ出る。「起動」はそのゴーストだけにする）。動いているゴーストはどれも「起動中」
- ⛔ OnOtherObjectDropping / Dropped（Windows の「PC」や「ごみ箱」など、ファイルでないものを落としたとき。Android には無い）

### ⛔ 作れない
- プラグイン・ヘッドライン・SAORI・MAKOTO（Windows の DLL）: OnRaisePluginFailure / OnNotifyPluginFailure、OnHeadlinesense*、
  OnRSSBegin / Complete / Failure（ヘッドラインセンサー版）、OnSchedulesense* / OnSchedulepost*、OnInternalSaori*
- パソコン専用: OnWallpaperChange / OnDesktopWallpaperChange、OnVirtualDesktopChanged、
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
%selfname2 %exh %et %wronghour %ms %mz %ml %mc %mh %mt %me %mp %m? %dms（小さな単語表から選ぶ） %lastghostname %lastobjectname（最後にインストールしたもの）
%*（\![*] と同じ印） \![set,trayballoon]（Android の通知。--text は何個でも） \![set,tasktrayicon]（常駐の通知に文を出す。アイコンは替えられない）
\![open,browser / inputbox / passwordinput / communicatebox / teachbox / dateinput / timeinput / sliderinput / textareabox / readme / configurationdialog]
\![close,...] \![set,balloontimeout / choicetimeout / scaling / alpha / position] \![reset,position] \![move] \![moveasync]（--option=ignore-sticky-window も。--base=本体側の名前/ID で一緒に動いているほかのゴーストのキャラクターも基準にできる）
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
\![vanishbymyself]（確かめずに消す。--option=query なら確かめる。ゴースト名を書けば消したあとそのゴーストに替わる。ほかにゴーストがいないときは消さない）、
\+ \_+（ほかのゴーストのどれか・並びで次のゴーストに替える）、\![change,calendarskin]（カレンダーが無いので何もしない）、\a（OnAITalk）、
\![reloadsurface] \![reload,shell / balloon / ghost / descript]（ghost と descript はすべて読み直して起き直す。イベントは送らない）
\![load / unload,shiori] \![open,terms]（ghost/master の terms.txt）
\![open,file]（ゴーストのフォルダの中のファイルを Android のアプリで開く） \![open,mailer] \![open,help]（pono-droid の説明を開く）
\![execute,createnar]（出力先を省けば pono/nar/ に作って「共有」で渡す。developer_options.txt・.narignore・.narinclude と、いつも外すもの）
\![execute,createupdatedata]（updates2.dau / updates.txt、MD5 と大きさ） \![execute,dumpsurface]（サーフェスの一覧・着せ替え・--animation の連番）
\![execute,dumpballoon] \![create,shortcut]（ホーム画面にそのゴーストを起こすショートカット。startup は Android に無いので扱わない）。
書き出しはどれもゴーストのフォルダの中だけ

### 🔨 Android で作れる
- ✅ \![open,dialog,open / save / folder / color] と \![close,dialog]（Android の選ぶ画面。open は選んだファイルを、folder は選んだフォルダの中身を
  ゴーストのフォルダの pono/dialog/ に写して渡す。save はゴーストがそのパスに書き終えたら、選んだ場所に写す。色はアプリの画面で選ぶ）、
  \![open,ipinput]、\![open,ghostexplorer など]（アプリの一覧と設定の画面）、\![open,backlogviewer / errorlog]（発言履歴・ログ）と
  \![open,surfacetest / developer / shiorirequest]（設定の「開発者向けの機能」を選んだときだけ）

### 🔒 決めてから
- ✅ 読み上げ（Android の音声合成。アプリの設定で選ぶ。最初は読み上げない）
- ✅ \![set,wallpaper]（--file / --position=center・tile・stretch・fit・fill / --color。アプリの設定で許したときだけ。最初は変えさせない）
- ⛔ \![save,wallpaper] / \![restore,wallpaper]（Android 13 からは、アプリが今の壁紙を読めないので、元に戻せない）
- ✅ \![open,editor]（ゴーストのファイルを書き換えられるようにほかのアプリで開く。設定の「開発者向けの機能」を選んだときだけ）、
  \![open,explorer]（ファイラーは無いので、そのファイルをほかのアプリで開く）
- ✅ \![execute,schedule-add / delete / get]（ゴースト用の予定表。ゴーストのフォルダの pono/schedule.ics。--uid / --summary / --start / --end / --rrule /
  --notify / --description / --location / --event、get は --from / --to / --expand / --limit）。端末のカレンダーは、設定で選んで読む許可を出したときだけ読む
- ✅ \![biff(,アカウント名)]（アカウントと確かめる間隔はアプリの設定の画面で入れる）
- ⛔ \![open,messenger]（SSP のメッセンジャーは pono-droid に無い）
- ⛔ \![execute,emptyrecyclebin]（上の OnRecycleBin* と同じ理由）
- ✅ \![set,shioridebugmode(,true/false)]（NOTIFY enable_debug）

### 👥 複数ゴーストの同時起動が要る
- ✅ \![call,ghost]（メニューの「ほかのゴーストを呼ぶ」も）、\![raiseother] \![notifyother]
  \![timerraiseother] \![timernotifyother]（__SYSTEM_ALL_GHOST__ も。自分には送らない）、\![set,otherghosttalk] \![set,othersurfacechange]
- ✅ \![timernotify]（1体でも使える）

### ⛔ 作れない
\![effect] \![effect2] \![filter]（DLL のプラグイン）、\![raiseplugin] など（プラグイン）、\![execute,headline]、\![execute,calendarplugin]、
\![reload,makoto] \![load / unload,makoto] \![reload,aigraph]、\![open,headlinesensorexplorer / pluginexplorer / calendar*]、
\m[umsg,wparam,lparam]（Windows のメッセージ）、\![set,windowstate,stayontop]（オーバーレイはいつも上）、\![set,alignmentondesktop]、\![sound,cdplay]、
\![open,addressbar]

---

## 3. プロパティシステム

### ✅ 対応済み
system.year〜millisecond・dayofweek・cursor.pos・os.*・cpu.*・memory.*・monitor.*・power.*、baseware.*、ghostlist.*、balloonlist.*、
currentghost の一部（name・status・scope.count・shelllist.current.name など）、
system.theme.app.mode（pono-droid の画面はいつも light）/ theme.os.mode、system.dnd.mode（おやすみモード）、system.network.*、system.disk.*（アプリの置き場と共有の置き場）、
currentghost.shelllist(…).menu、scope(ID).x / y / rect / surface.x / surface.y / scaling / currentmonitor.* / surface(ID).rect、
balloon.scope(ID).x / y / rect / scaling / vertical / validwidth / validheight / lines（書ける範囲の全体 validrect）/ .initial（書き始めの basepos から）/
basepos.x / y（origin）/ char_width（1行は文字の高さの 1.4 倍）/ background.color（画像でいちばん多い色）、
ghostlist(…).shiori（SHIORI の名前）、ghostlist / activeghostlist / currentghost の shiori.変数名（動いているゴーストの SHIORI に、変数名を ID にして GET で聞く。
SHIORI を待つので \![get,property] でだけ使え、%property[] では空）、seriko.surfacelist.all / defined、seriko.sticky-window / zorder、seriko.tooltip.*、
sound.count / sound.index(ID).* / sound(名前).*、mousecursor・seriko.cursor（カーソルは無いので書いたものを返すだけ）、sakura / kero / char*.bind.menu、
history.ghost / balloon、rateofuselist.index(順位).*（起動回数・分・週・月・割合）、developer.log.script / error / network / update。
\![set,property] は [SET有効] のうち shelllist(…).menu・*.bind.menu・sticky-window・zorder・tooltip・sound の position / playing / pause・カーソル

### 👥 複数ゴーストの同時起動が要る
- ✅ activeghostlist.*（count / index / 名前 / current。1体だけなら自分だけ）

### ⛔ 作れない
headlinelist.* / history.headline.*、pluginlist.* / history.plugin.*

---

## 4. SHIORI リソース

### ✅ 対応済み
version、craftman、craftmanw、name、homeurl、username、sakura / kero / char*.recommendsites・portalsites・*buttoncaption、readmebuttoncaption、updatebuttoncaption、
sakura / kero / char*.defaultx / defaulty / defaultleft / defaulttop（位置を覚えていないときの最初の位置。descript.txt のものも）、
vanishbuttoncaption / vanishbuttonvisible（メニューの「消去」）、sakura / kero / char*.popupmenu.visible / type（1 なら省略メニュー）/ applybindtoself、
menu.background.font.color.* / menu.foreground.font.color.*（メニューの項目の文字と、選んでいる項目の文字の色）、
*button.caption のうち pono-droid のメニューにある項目（ghostroot / shellroot / balloonroot / dressuproot / recommendroot / portalroot / readme / terms /
aistate / rateofuse / update / hide / vanish / quit / closeballoon。新しい名前を古い名前より先に使う）、getaistate / getaistateex（メニューの
「AI グラフ」と \![open,aigraph]。グラフの代わりにラベルと値を並べる）、useorigin1、other_homeurl_override、balloon_tooltip / tooltip（選択肢を長押ししている間、
そのバルーンのキャラクターの上に出す）、log_path（開発用パレットの「SHIORI のログ」の「開く」。ゴーストのフォルダの中のファイルだけ）

### ⛔ 作れない（メニュー）
- menu.*.bitmap.filename、menu.separator.color.*、menu.frame.color.*、menu.disable.font.color.*（pono-droid のメニューはバルーンに出すので、
  メニューの絵や枠・区切り線・押せない項目が無い）

### ⛔ 作れない
SSP 専用のメニュー項目の名前（ヘッドライン・プラグイン・カレンダー・DUI・タスクトレイ・SSTP 送信ボックスなど）、boxname.defaultleft、legacyinterface

---

## 5. そのほか（仕様書の項目以外）

- ✅ SSTP サーバー（設定で選んだときだけ。最初は受けない。この端末の中のアプリからだけ、127.0.0.1:9801）。SEND / NOTIFY（IfGhost の振り分け・
  Option の nobreak / notify / notranslate）、COMMUNICATE、GIVE、EXECUTE（GetName / GetNames / GetGhostName / GetShellName / GetBalloonName / GetVersion /
  GetShortVersion / Get*NameList / GetStatus / Quiet / Restore / GetProperty / SetCookie / GetCookie / SetTrayIcon / SetTrayBalloon。SetProperty・MoveAsync・
  DumpSurface・CompressArchive・ExtractArchive は Owned SSTP だけ）、keep-alive、SSTP over HTTP（localhost のページからだけ）、ReceiverGhostName / Ghost。
  ほかのアプリから届いたスクリプトは \![execute] \![open,browser / file] \![change] \![vanishbymyself] \- \j などを使えない（uniqueid を付けた Owned SSTP は使える）
- ✅ 複数ゴーストの同時起動（3 体まで）。SSTP は ReceiverGhostName / IfGhost / uniqueid の ID で宛先のゴーストに渡し、どれでもなければ最初に起動したゴーストへ
- ⛔ SHIORI の美坂・偽林檎など（Windows の DLL。ソースが公開されていて Android 用にビルドできるものだけ作れる）
- ⛔ ゴースト同梱の SAORI（DLL）・YAYA の EXECUTE・プラグイン（安全のために止めている。ゴーストに端末でプログラムを動かさせることになる）。
  本体の内蔵 SAORI（SAORI over DirectSSTP の internal_*）は ✅（[ukadoc-spec-gap.md](ukadoc-spec-gap.md)）
- ✅ surfaces.txt: collisionex の rect / ellipse / circle / polygon、descript ブレスの collision-sort / animation-sort、animation*.collision(ex)、
  animation*.option（exclusive・background・shared-index）、animation*.name、interval の + の組み合わせと starttalk / endtalk、
  surface.append（あるサーフェスにだけ足す）、element0 による surfaceN.png の置き換え、描画メソッドの overlay-fast / interpolate / replace /
  asis / reduce / move / scaling / add / bind と blend-*（-fast も）、point.basepos / centerx / kinoko、sakura.tooltips など（触れている間に出す）
- ✅ surfaces.txt の import（APNG / GIF / WebP。Android 9 より前は最初のコマだけ）
- ✅ .nar の type,shell / supplement / package、accept（違うゴーストなら OnInstallRefuse）、refresh / refreshundeletemask、
  balloon0.directory などの複数同梱、\![execute,install,path / url]、\![execute,dropdefault,file / url]
- ✅ サウンドを同時にいくつも鳴らす（16 個まで）
- ✅ icon.rect と、当たり判定の face / 顔・head / 頭 からの顔の切り出し（発言履歴の顔）、sakura / kero / char*.icon.frame.color.*（顔の枠の色。
  SSP と違い、明るさは画面の配色に合わせて変えない）
- ⛔ surfaces.txt の cursor ブレス（マウスカーソルが無い）、sakura.balloon.offsetx / offsety（pono-droid のバルーンはいつもキャラクターの上）、
  オーバーレイの透明な部分のタッチを下に通す（Android では画素単位にはできない）
