package net.pono.baseware.sakura

import kotlinx.coroutines.delay

/** [anchor] は \_a（ジャンパ）。選択肢（\q, \__q）とは送るイベントが違う */
data class Choice(val title: String, val id: String, val references: List<String> = emptyList(), val anchor: Boolean = false)

enum class TextAlign { LEFT, CENTER, RIGHT }

/** \f[valign,...]。1行の中で大きさの違う文字をどこにそろえるか */
enum class VerticalAlign { TOP, CENTER, BOTTOM }

/**
 * 選択肢やリンクの、ある状態（押している・押していない・訪問済み）の見た目。null はバルーンの既定。
 * [shape] は square / underline / square+underline / none、[brush] は矩形の中、[pen] は枠と下線、[font] は文字の色（0xRRGGBB）
 */
data class LinkLook(val shape: String? = null, val brush: Int? = null, val pen: Int? = null, val font: Int? = null) {
    /** [other] で決めていないところを、こちらで埋める */
    fun or(other: LinkLook) = LinkLook(shape ?: other.shape, brush ?: other.brush, pen ?: other.pen, font ?: other.font)
}

/** \f[cursor*] / \f[cursornotselect*] / \f[anchor*] / \f[anchornotselect*] / \f[anchorvisited*] と \f[anchor.font.color] */
data class ChoiceLooks(
    val cursor: LinkLook = LinkLook(),
    val cursorNotSelect: LinkLook = LinkLook(),
    val anchor: LinkLook = LinkLook(),
    val anchorNotSelect: LinkLook = LinkLook(),
    val anchorVisited: LinkLook = LinkLook(),
) {
    companion object {
        val Default = ChoiceLooks()
    }
}

/** \f で決める文字の飾り。 */
data class TextStyle(
    /** \f[bold] など。true で付ける、false で外す、null ならバルーンの既定のまま */
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    /** 0xRRGGBB。null なら既定の色 */
    val color: Int? = null,
    /**
     * \f[color,default.anchor] など。バルーンの決めた色を名前で指す（anchor / anchornotselect / anchorvisited / cursor /
     * cursornotselect / plain）。null なら [color]
     */
    val colorRef: String? = null,
    /** \f[bold,disable] などで、無効表示の飾り（バルーンの disable.font.*）にしたもの（bold / italic / underline / strike） */
    val disabledFlags: Set<String> = emptySet(),
    /** 文字の高さ。null なら既定。[heightMode] で意味が変わる */
    val height: Float? = null,
    val heightMode: HeightMode = HeightMode.ABSOLUTE,
    val sub: Boolean? = null,
    val sup: Boolean? = null,
    /** \f[name,...]。フォントの候補（ゴーストやバルーンのフォルダのフォントファイルか、フォントの名前）。null ならバルーンの既定 */
    val font: List<String>? = null,
    /** \f[shadowcolor,...]。0xRRGGBB。null ならバルーンの既定 */
    val shadowColor: Int? = null,
    /** \f[shadowcolor,none]。影を付けない */
    val noShadow: Boolean = false,
    /** \f[shadowstyle,...]。null ならバルーンの既定 */
    val shadowStyle: ShadowStyle? = null,
    /** \f[outline,...]。白抜き（縁だけ描く）。null ならバルーンの既定 */
    val outline: Boolean? = null,
    /** \f[disable]。操作できないことを表す薄い色の文字 */
    val disabled: Boolean = false,
    /** \_n から次の \_n まで。自動で折り返さない */
    val noWrap: Boolean = false,
) {
    /** ABSOLUTE: ピクセル、RELATIVE: 既定からの差（ピクセル）、PERCENT: 既定に対する割合 */
    enum class HeightMode { ABSOLUTE, RELATIVE, PERCENT }

    /** 影の付け方。OFFSET は右下にずらし、OUTLINE は縁取りにする */
    enum class ShadowStyle { OFFSET, OUTLINE }

    companion object {
        val Default = TextStyle()
    }
}

/** さくらスクリプトの実行結果を受け取る側（見た目を持つ側）。 */
interface ScriptHost {
    fun setSurface(scope: Int, surface: Int)

    /** `\s[...]` の指定そのまま（番号か、シェルの surface.alias の別名）。既定では番号だけ扱う */
    fun setSurface(scope: Int, surface: String) {
        surface.trim().toIntOrNull()?.let { setSurface(scope, it) }
    }
    fun appendText(scope: Int, text: String)
    fun newLine(scope: Int)

    /** `\n[half]` / `\n[150]` など。[percent] はふつうの改行に対する割合（負なら上へ戻る） */
    fun newLine(scope: Int, percent: Int) = newLine(scope)
    fun clearBalloon(scope: Int)

    /**
     * `\c[char,数,開始位置]` / `\c[line,数,開始位置]`。[lines] なら行、そうでなければ文字を [count] だけ消す。
     * [start] が null なら最後から（カーソルの手前を）、あれば頭から数えて [start] 番目（0 から）から消す
     */
    fun clearPart(scope: Int, lines: Boolean, count: Int, start: Int?) {}

    /** `\f[valign,...]`。今の行から後の、行の中の縦のそろえ方 */
    fun setVerticalAlign(scope: Int, align: VerticalAlign) {}

    /** `\t`。スクリプトが終わるか止められるまで、マウス（タッチ）のイベントを送らない */
    fun setTimeCritical() {}

    /** `\![lock,balloonrepaint|repaint|balloonmove(,manual)]` / `\![unlock,...]`。扱えたら true */
    fun lock(what: String, locked: Boolean, manual: Boolean): Boolean = false

    /** `\![enter,onlinemode|nouserbreakmode|inductionmode]` / `\![leave,...]`。扱えたら true */
    fun setMode(name: String, on: Boolean): Boolean = false

    /** `\![enter,selectmode,rect,...]` など、引数と今のキャラクターも要るモード。既定では [setMode] に回す */
    fun setMode(scope: Int, name: String, on: Boolean, args: List<String>): Boolean = setMode(name, on)
    fun addChoice(scope: Int, choice: Choice)

    /** [looks] は \f[cursor*] などで決めた見た目（既定なら [ChoiceLooks.Default]） */
    fun addChoice(scope: Int, choice: Choice, looks: ChoiceLooks) = addChoice(scope, choice)
    fun startAnimation(scope: Int, id: Int) {}

    /** `\i[ID]` の ID（番号か、surfaces.txt の animation*.name の名前）。既定では番号だけ扱う */
    fun startAnimation(scope: Int, key: String) {
        key.trim().toIntOrNull()?.let { startAnimation(scope, it) }
    }

    /** `\i[ID,wait]` / `\__w[animation,ID]`。そのアニメーションが終わるまで戻らない（動いていなければすぐ戻る） */
    suspend fun waitAnimation(scope: Int, key: String) {}

    /** `\![set,syncobject,名前]` / `\![reset,syncobject,名前]`。名前つきの合図を立てる・下ろす */
    fun setSyncObject(name: String, signaled: Boolean) {}

    /** `\![wait,syncobject,名前,時間]`。合図が立つまで戻らない。[timeoutMs] が 0 なら止められるまで待つ */
    suspend fun waitSyncObject(name: String, timeoutMs: Long) {}

    /** `\![anim,offset|clear|stop,...]`。扱えたら true */
    fun animation(scope: Int, args: List<String>): Boolean = false

    /** `\![*]`。選択肢の前の印 */
    fun addMarker(scope: Int) {}

    /**
     * `\_b[ファイル,x,y]` / `\_b[ファイル,inline]`。バルーンに画像を置く（[x] / [y] が null なら文字の中に1文字として）。
     * [opaque] なら左上の色を抜かない
     */
    fun addImage(scope: Int, file: String, x: Int?, y: Int?, opaque: Boolean) {}

    /** `\_l[x,y]` で横の位置を指定された（同じ行で次の列に移る） */
    fun moveCursor(scope: Int, x: Int) {}

    /** `\b[n]`。バルーンの番号を選ぶ（-1 ならバルーンを隠す） */
    fun setBalloonId(scope: Int, id: Int) {}

    /** \b[ID,--fallback=ID2,...]。バルーンにある最初の番号にする（どれも無ければ最初の番号） */
    fun setBalloonIds(scope: Int, candidates: List<Int>) = setBalloonId(scope, candidates.first())

    /** \*。このスクリプトの選択肢はタイムアウトしない */
    fun disableChoiceTimeout() {}

    /** \x のクリックのあと、出ているバルーンをすべて消す */
    fun clearAllBalloons() {
        clearBalloon(0)
        clearBalloon(1)
    }

    /** `\f[...]`。これ以降の文字の飾り */
    fun setFont(scope: Int, style: TextStyle) {}

    /** `\f[align,...]`。今の行の寄せ方 */
    fun setAlign(scope: Int, align: TextAlign) {}

    /** `\![open,種類,引数...]` のうち、ブラウザ以外（入力の箱など）。扱えたら true */
    fun open(kind: String, args: List<String>): Boolean = false

    /** `\![close,種類,引数...]`。扱えたら true */
    fun close(kind: String, args: List<String>): Boolean = false

    /** `\![updatebymyself]`。ゴースト自身のネットワーク更新を頼まれた */
    fun requestUpdate() {}

    /**
     * `\![updatebymyself,オプション...]` / `\![update,ghost+shell+balloon,オプション...]`。[types] は更新するもの、
     * [options] は checkonly / testonly / recovery
     */
    fun requestUpdate(types: List<String>, options: Set<String>) = requestUpdate()

    /** `\![updateother,--shell=名前,--balloon=名前,...]` */
    fun updateOther(args: List<String>) {}

    /** `\![set,name,args...]`。扱えたら true。 */
    fun setOption(name: String, args: List<String>): Boolean = false

    /** `\![set,name,args...]` のうち、今のキャラクターにかかるもの（scaling / alpha など）。扱えなければ [setOption] に回す */
    fun setOption(scope: Int, name: String, args: List<String>): Boolean = setOption(name, args)

    /** `\![execute,name,args...]`（SSP の http-get など）。扱えたら true。 */
    fun execute(name: String, args: List<String>): Boolean = false

    /** スクリプトから呼ぶ `\![execute,...]`。待つもの（--async でない http など）は終わるまで戻らない。既定では [execute] */
    suspend fun runExecute(name: String, args: List<String>): Boolean = execute(name, args)

    /** `\![cancel,http|websocket|filewatch,...]`。扱えたら true */
    fun cancel(kind: String, args: List<String>): Boolean = false

    /** `\![send,websocket(-binary),URL,...]`。扱えたら true */
    fun send(kind: String, args: List<String>): Boolean = false

    /** `\7` / `\![executesntp]`。時刻サーバーとのずれを測る（OnSNTPBegin / OnSNTPCompare） */
    fun adjustClock() {}

    /** `\6`。時計をサーバーに合わせる（Android では直せない） */
    fun correctClock() {}

    /** `\![bind,カテゴリ,パーツ,1/0]`（着せ替え）。 */
    fun bind(scope: Int, category: String, part: String, on: Boolean?) {}

    /** \![bind-noevent,...]（[noEvent]）。OnDressupChanged などを送らない。[part] が空ならカテゴリごと */
    fun bind(scope: Int, category: String, part: String, on: Boolean?, noEvent: Boolean) = bind(scope, category, part, on)

    /** `\x`。クリックされるまで戻らない。 */
    suspend fun waitClick()

    /** `\![raise,...]`。スクリプトが終わってから送ればよい。 */
    fun raise(event: String, references: List<String>)

    /** `\![notify,...]`。送るだけで、応答は使わない */
    fun notify(event: String, references: List<String>) {}

    /** `\![embed,...]`。イベントを送り、返ってきたスクリプトをその場に埋め込む。null なら何も入れない */
    suspend fun embed(event: String, references: List<String>): String? = null

    /** `\![timerraise,時間,一回だけか,イベント,...]`。[delayMs] が 0 なら取り消し */
    fun timerRaise(delayMs: Long, once: Boolean, event: String, references: List<String>) {}

    /** `\![timernotify,時間,一回だけか,イベント,...]`。timerraise と同じで、答えは使わない */
    fun timerNotify(delayMs: Long, once: Boolean, event: String, references: List<String>) {}

    /** `\![raiseother,ゴースト名,イベント,...]` / `\![notifyother]`（[notify]）。[ghosts] は 0x01 区切りか __SYSTEM_ALL_GHOST__ */
    fun raiseOther(ghosts: String, event: String, references: List<String>, notify: Boolean) {}

    /** `\![timerraiseother,時間,一回だけか,ゴースト名,イベント,...]` / `\![timernotifyother]` */
    fun timerRaiseOther(delayMs: Long, once: Boolean, ghosts: String, event: String, references: List<String>, notify: Boolean) {}

    /** `\![call,ghost,名前(,--option=raise-event)]`。同時に動かすゴーストを呼ぶ。呼べなければ false */
    fun callGhost(name: String, raiseEvent: Boolean): Boolean = false

    /** `\![get,property,イベント,名前...]`。名前ごとの値を Reference に入れてイベントを送る */
    fun getProperty(event: String, names: List<String>) {}

    /** `%property[名前]`。知らない名前なら null */
    fun property(name: String): String? = null

    /** `\![reload,shiori]` など。[what] は shiori / shell / balloon / ghost / descript ... */
    fun reload(what: String) {}

    /** `\![unload,shiori]`（false）/ `\![load,shiori]`（true） */
    fun setShioriLoaded(loaded: Boolean) {}

    /** `\![vanishbymyself(,ゴースト名)(,--option=query)]` */
    fun vanish(args: List<String>) {}

    /** `\![biff(,アカウント名)]`。メールを確かめる */
    fun checkMail(account: String?) {}

    /** `\![set,property,名前,値]`。扱えたら true */
    fun setProperty(name: String, value: String): Boolean = false

    /** `\![create,shortcut(,startup)]`。扱えたら true */
    fun createShortcut(args: List<String>): Boolean = false

    /** `\![enter,passivemode]` / `\![leave,passivemode]` */
    fun setPassive(on: Boolean) {}

    /** `\8[file]` `\_v[file]` `\![sound,play|loop,file,--volume=n]`。[file] は ghost/master からの相対パス */
    fun playSound(file: String, loop: Boolean, volume: Int?) {}

    /** `\![sound,play|loop,file,オプション...]`。既定では [volume] だけ見る */
    fun playSound(file: String, loop: Boolean, options: SoundOptions) = playSound(file, loop, options.volume)

    /** `\![sound,load|pause|resume|option,file,オプション...]`。[file] が null なら鳴っている音すべて（pause / resume） */
    fun soundControl(command: String, file: String?, options: SoundOptions) {}

    /** 話した文字（読み上げと発言履歴）。`\__v[disable]` の間は来ず、`\__v[alternate,文字]` ならその文字が来る */
    fun voice(scope: Int, text: String) {}

    /** `\![sound,stop(,file)]`。file が null なら全部 */
    fun stopSound(file: String?) {}

    /** `\_V` `\![sound,wait]`。再生が終わるまで戻らない（鳴っていなければすぐ戻る） */
    suspend fun waitSound() {}

    /** `\![change,shell|balloon,名前]`。扱えたら true */
    fun change(kind: String, name: String): Boolean = false

    /** \![change,shell,名前,--option=raise-event]（[raiseEvent] なら OnShellChanging を先に送る） */
    fun change(kind: String, name: String, raiseEvent: Boolean): Boolean = change(kind, name)

    /** `\![change,ghost,名前]`。[raiseEvent] なら先に OnGhostChanging を送る。扱えたら true */
    fun changeGhost(name: String, raiseEvent: Boolean): Boolean = false

    /** `\![move]` / `\![moveasync]`。キャラクターのウィンドウを動かす（待つのは ScriptPlayer 側） */
    fun move(scope: Int, command: MoveCommand) {}

    /** `\![set,position,x,y,スコープ]`。[x] / [y] に固定してドラッグさせない。null なら固定をやめる（`\![reset,position]`） */
    fun setPosition(scope: Int, x: Int?, y: Int?) {}

    /** `\4`（[away]）/ `\5`。相手のキャラクターから離れる・そばへ寄る */
    fun nudge(scope: Int, away: Boolean) {}

    /** `\v`。キャラクターとバルーンを、ほかのウィンドウより手前に出す */
    fun bringToFront() {}

    fun openUrl(url: String)

    /** `%username` などの環境変数。知らない名前なら null。 */
    fun envVariable(name: String): String?

    fun unknownTag(tag: Token.Tag) {}

    /**
     * ほかのアプリから SSTP で届いたスクリプト。そのときは、端末やゴーストを変えるタグ（\![execute] \![open,browser] \![change]
     * \![vanishbymyself] \- \j など）を使わせない（[refused] に回す）
     */
    val untrusted: Boolean get() = false

    /** [untrusted] のスクリプトで使わせなかったタグ */
    fun refused(tag: Token.Tag) = unknownTag(tag)
}

data class PlayResult(val quit: Boolean, val hasChoices: Boolean)

/** さくらスクリプトを時間に沿って再生する。 */
class ScriptPlayer(
    private val charWaitMs: Long = 50,
    private val unitWaitMs: Long = 50,
) {
    suspend fun play(script: String, host: ScriptHost): PlayResult {
        val playback = Playback(host)
        playback.run(script, 0)
        return PlayResult(playback.quit, playback.hasChoices)
    }

    /** 1本のスクリプトの再生中の状態。\![embed] で埋め込まれたスクリプトも同じ状態で続ける */
    private inner class Playback(private val host: ScriptHost) {
        var scope = 0
        var quick = false
        var quit = false
        var hasChoices = false
        var ended = false

        // \__q[ID]...\__q と \_a[ID]...\_a の間のテキスト
        private var anchor: Choice? = null
        private val anchorTitle = StringBuilder()

        // \f の飾り（キャラクターごと）
        private val styles = HashMap<Int, TextStyle>()

        // \f[cursor*] / \f[anchor*] の選択肢とリンクの見た目（キャラクターごと）
        private val looks = HashMap<Int, ChoiceLooks>()

        // \__v[disable] / \__v[alternate,文字] の間は、話した文字を読み上げと発言履歴に渡さない
        private var voiceOff = false

        // \_s のシンクロナイズセクション。null でなければ、文字と改行をこのキャラクター全員に出す
        private var sync: List<Int>? = null

        /** 文字や改行を出す先 */
        private fun targets(): List<Int> = sync ?: listOf(scope)

        /** \__w[時間] を数え始めた時刻（再生の始めか、\x のクリックか \__w[clear]） */
        private var waitMark = System.currentTimeMillis()

        /** \![set,balloonwait,...] で変えた、1文字ごとの待ち時間。null ならふつう（このスクリプトの間だけ） */
        private var charWaitOverride: Long? = null

        private fun charWait(): Long = charWaitOverride ?: charWaitMs

        suspend fun run(script: String, depth: Int) {
            for (token in SakuraScript.tokenize(script)) {
                if (ended) return
                when (token) {
                    is Token.Text -> text(token.text, literal = token.literal)
                    is Token.Tag -> tag(token, depth)
                }
            }
        }

        /** [literal] なら \_? の中の文字で、環境変数も展開しない。%* は \![*] と同じ印 */
        private suspend fun text(s: String, literal: Boolean = false) {
            if (!literal && "%*" in s) {
                s.split("%*").forEachIndexed { i, piece ->
                    if (i > 0) host.addMarker(scope)
                    if (piece.isNotEmpty()) text(piece)
                }
                return
            }
            val expanded = if (literal) s else expandEnv(s, host)
            if (anchor != null) {
                anchorTitle.append(expanded)
                return
            }
            if (!voiceOff) host.voice(scope, expanded)
            val wait = charWait()
            if (quick || wait <= 0) {
                for (t in targets()) host.appendText(t, expanded)
            } else {
                // サロゲートペアを分けないよう、コードポイント単位で出す
                var i = 0
                while (i < expanded.length) {
                    val n = Character.charCount(expanded.codePointAt(i))
                    for (t in targets()) host.appendText(t, expanded.substring(i, i + n))
                    i += n
                    delay(wait)
                }
            }
        }

        private suspend fun tag(token: Token.Tag, depth: Int) {
            val a = token.args
            if (host.untrusted && token.name in UNTRUSTED_TAGS) return host.refused(token)
            when (token.name) {
                "0", "h" -> scope = 0
                "1", "u" -> scope = 1
                // 番号には上限を置く（細工したスクリプトにキャラクターのウィンドウを無数に作らせない）
                "p" -> scope = a.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 0..MAX_SCOPE } ?: scope
                "s" -> a.firstOrNull()?.takeIf { it.isNotBlank() }?.let { host.setSurface(scope, it) }
                // \i[ID,wait] はアニメーションが終わるまで待つ
                "i" -> a.firstOrNull()?.takeIf { it.isNotBlank() }?.let { id ->
                    host.startAnimation(scope, id)
                    if (a.getOrNull(1)?.trim()?.lowercase() == "wait" && !quick) host.waitAnimation(scope, id)
                }
                // \n[half] は半分、\n[150] は1行半の改行。負の値なら上へ戻る
                "n" -> {
                    val percent = when (val p = a.firstOrNull()?.trim()?.lowercase()) {
                        null, "" -> 100
                        "half" -> 50
                        else -> p.toIntOrNull()?.coerceIn(-MAX_LINE_PERCENT, MAX_LINE_PERCENT) ?: 100
                    }
                    for (t in targets()) if (percent == 100) host.newLine(t) else host.newLine(t, percent)
                }
                // 次の \_n まで自動で折り返さない
                "_n" -> {
                    val current = styles[scope] ?: TextStyle.Default
                    setStyle(current.copy(noWrap = !current.noWrap))
                }
                // \_s で始めて \_s で終わる。引数が無ければ \0 と \1、あれば並べたキャラクター
                "_s" -> sync = if (sync != null) {
                    null
                } else {
                    a.mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n in 0..MAX_SCOPE } }.ifEmpty { listOf(0, 1) }
                }
                "_b" -> a.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { file ->
                    val opts = a.drop(1).map { it.trim().lowercase() }
                    val inline = opts.firstOrNull() == "inline"
                    host.addImage(
                        scope, file,
                        if (inline) null else opts.getOrNull(0)?.toIntOrNull() ?: 0,
                        if (inline) null else opts.getOrNull(1)?.toIntOrNull() ?: 0,
                        "opaque" in opts,
                    )
                }
                // \c[char,数,開始位置] / \c[line,数,開始位置] は一部だけ、\c は全部消す
                "c" -> when (val unit = a.firstOrNull()?.trim()?.lowercase()) {
                    "char", "line" -> {
                        val count = a.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return
                        host.clearPart(scope, lines = unit == "line", count, a.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it >= 0 })
                    }
                    else -> host.clearBalloon(scope)
                }
                "w" -> if (!quick) delay((a.firstOrNull()?.toLongOrNull() ?: 1) * unitWaitMs)
                // \__w[animation,ID] はそのアニメーションが終わるまで待つ
                // \__w[時間] は再生の始め（か \x のクリック）から数えてその時間まで待つ。\__w[clear] で数え直す
                "__w" -> when (a.firstOrNull()?.trim()?.lowercase()) {
                    "animation" -> if (!quick) a.getOrNull(1)?.let { host.waitAnimation(scope, it) }
                    "clear" -> waitMark = System.currentTimeMillis()
                    else -> if (!quick) {
                        val until = a.firstOrNull()?.trim()?.toLongOrNull() ?: 0
                        delay((until - (System.currentTimeMillis() - waitMark)).coerceAtLeast(0))
                    }
                }
                "_w" -> if (!quick) delay(a.firstOrNull()?.toLongOrNull() ?: 0)
                "_q" -> quick = !quick
                "x" -> {
                    host.waitClick()
                    // クリックのあとは \0 から。\x[noclear] なら文字も \f の飾りも残す
                    scope = 0
                    waitMark = System.currentTimeMillis()
                    if (a.firstOrNull() != "noclear") {
                        host.clearAllBalloons()
                        for (s in styles.keys.toList()) host.setFont(s, TextStyle.Default)
                        styles.clear()
                        looks.clear()
                    }
                }
                // \z は古い書き方の、選択肢を含むスクリプトの \e
                "e", "z" -> ended = true
                "t" -> host.setTimeCritical()
                "-" -> quit = true
                "q" -> if (a.size >= 2) {
                    hasChoices = true
                    host.addChoice(scope, Choice(expandEnv(a[0], host), a[1], a.drop(2)), looks[scope] ?: ChoiceLooks.Default)
                }
                "__q", "_a" -> {
                    val open = anchor
                    if (open == null && a.isNotEmpty()) {
                        anchor = Choice("", a[0], a.drop(1), anchor = token.name == "_a")
                    } else if (open != null) {
                        // \_a のリンクだけなら選択肢を待たない（バルーンのタイムアウトで消える。SSP と同じ）
                        if (!open.anchor) hasChoices = true
                        host.addChoice(scope, open.copy(title = anchorTitle.toString()), looks[scope] ?: ChoiceLooks.Default)
                        anchor = null
                        anchorTitle.clear()
                    }
                }
                // 文字コードで文字を書く。\_u[0x22EE] / \_u[8942]（UCS-2）、\_m[0x41]（ASCII）
                "_u", "_m" -> a.firstOrNull()?.let(::parseCode)?.let { text(String(Character.toChars(it))) }
                // \&[amp] のような実体参照
                "&" -> a.firstOrNull()?.let(::entity)?.let { text(it) }
                "8", "_v" -> a.firstOrNull()?.takeIf { it.isNotBlank() }?.let { host.playSound(it, loop = false, volume = null) }
                "_V" -> host.waitSound()
                "f" -> font(a)
                "!" -> bang(token, depth)
                // \j[http(s)://...] / \j[mailto:...] は開き、\j[file:///...] は ghost/master からのファイルをほかのアプリで開く
                "j" -> a.firstOrNull()?.trim()?.let { target ->
                    val lower = target.lowercase()
                    when {
                        lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") -> host.openUrl(target)
                        lower.startsWith("file:///") -> host.open("file", listOf(target.substring(8)))
                        else -> {}
                    }
                }
                // 横の位置が数で書かれているときだけ（@ 付きの相対指定や縦だけの指定は無視する）
                "_l" -> a.firstOrNull()?.trim()?.toIntOrNull()?.let { host.moveCursor(scope, it) }
                // \b[ID,--fallback=ID2,--fallback=ID3]
                "b" -> a.firstOrNull()?.trim()?.toIntOrNull()?.let { id ->
                    val fallbacks = a.drop(1).map { it.trim() }.filter { it.lowercase().startsWith("--fallback=") }.mapNotNull { it.substringAfter('=').trim().toIntOrNull() }
                    host.setBalloonIds(scope, listOf(id) + fallbacks)
                }
                "4", "5" -> host.nudge(scope, away = token.name == "4")
                "6" -> host.correctClock()
                "7" -> host.adjustClock()
                "v" -> host.bringToFront()
                // \__v[disable] から \__v までは読み上げない。\__v[alternate,文字] はその文字を代わりに読む
                "__v" -> when (a.firstOrNull()?.trim()?.lowercase()) {
                    null, "" -> voiceOff = false
                    "disable" -> voiceOff = true
                    "alternate" -> {
                        a.getOrNull(1)?.takeIf { it.isNotEmpty() && !voiceOff }?.let { host.voice(scope, expandEnv(it, host)) }
                        voiceOff = true
                    }
                    else -> host.unknownTag(token)
                }
                // \a はランダムトーク（OnAITalk）。\+ はほかのゴーストのどれか、\_+ は並びで次のゴーストに切り替える（OnGhostChanging は送らない）
                "a" -> host.raise("OnAITalk", emptyList())
                // \* はこのスクリプトの選択肢をタイムアウトさせない。\__t は教える箱、\__c はコミュニケートの箱を開く
                "*" -> host.disableChoiceTimeout()
                "__t" -> host.open("teachbox", emptyList())
                "__c" -> host.open("communicatebox", emptyList())
                "+" -> host.changeGhost("random", false)
                "_+" -> host.changeGhost("sequential", false)
                in layoutOnlyTags -> {}
                else -> host.unknownTag(token)
            }
        }

        private suspend fun bang(token: Token.Tag, depth: Int) {
            val a = token.args
            if (host.untrusted && !untrustedAllowed(a)) return host.refused(token)
            when (val command = a.firstOrNull()?.lowercase()) {
                "raise" -> if (a.size >= 2) host.raise(a[1], a.drop(2))
                "notify" -> if (a.size >= 2) host.notify(a[1], a.drop(2))
                "embed" -> if (a.size >= 2) {
                    // 埋め込んだ先でまた埋め込むのは、無限に続かないよう回数を絞る
                    val embedded = host.embed(a[1], a.drop(2))
                    if (embedded != null && depth < MAX_EMBED_DEPTH) run(embedded, depth + 1)
                }
                "timerraise", "timernotify" -> if (a.size >= 4) {
                    val ms = a[1].trim().toLongOrNull() ?: return
                    // 「繰り返すか否か」は 0 で繰り返し、1 以上で一回だけ
                    val once = (a[2].trim().toIntOrNull() ?: 1) >= 1
                    if (command == "timerraise") host.timerRaise(ms, once, a[3], a.drop(4)) else host.timerNotify(ms, once, a[3], a.drop(4))
                }
                "raiseother", "notifyother" -> if (a.size >= 3) host.raiseOther(a[1], a[2], a.drop(3), notify = command == "notifyother")
                "timerraiseother", "timernotifyother" -> if (a.size >= 5) {
                    val ms = a[1].trim().toLongOrNull() ?: return
                    val once = (a[2].trim().toIntOrNull() ?: 1) >= 1
                    host.timerRaiseOther(ms, once, a[3], a[4], a.drop(5), notify = command == "timernotifyother")
                }
                // \![call,ghost,名前(,--option=raise-event)]
                "call" -> if (a.size < 3 || a[1].lowercase() != "ghost" ||
                    !host.callGhost(a[2].trim(), a.drop(3).any { it.trim().lowercase() == "--option=raise-event" })
                ) {
                    host.unknownTag(token)
                }
                "get" -> if (a.getOrNull(1)?.lowercase() == "property" && a.size >= 3) host.getProperty(a[2], a.drop(3)) else host.unknownTag(token)
                "open" -> when {
                    a.getOrNull(1)?.lowercase() == "browser" && a.size >= 3 -> host.openUrl(a[2])
                    a.size >= 2 && host.open(a[1].lowercase(), a.drop(2)) -> {}
                    else -> host.unknownTag(token)
                }
                "close" -> if (a.size < 2 || !host.close(a[1].lowercase(), a.drop(2))) host.unknownTag(token)
                "quicksection", "quicksession" -> quick = a.getOrNull(1)?.lowercase() in setOf("1", "true")
                "*" -> host.addMarker(scope)
                "updatebymyself" -> host.requestUpdate(listOf("ghost"), updateOptions(a.drop(1)))
                // \![update,ghost+shell+balloon,...]。all は \![updatebymyself] と同じ。本体（platform）の更新は Play ストアに任せる
                "update" -> when (val what = a.getOrNull(1)?.trim()?.lowercase()) {
                    null, "" -> host.unknownTag(token)
                    "platform" -> {}
                    "all" -> host.requestUpdate(listOf("ghost"), updateOptions(a.drop(2)))
                    else -> host.requestUpdate(what.split('+').map { it.trim() }.filter { it in setOf("ghost", "shell", "balloon") }, updateOptions(a.drop(2)))
                }
                "updateother" -> host.updateOther(a.drop(1))
                // \![anim,offset,ID,x,y] など。pause / resume / add などは見た目の細かい制御なので扱えなくても飛ばす
                "anim" -> host.animation(scope, a.drop(1))
                "reload" -> host.reload(a.getOrNull(1)?.lowercase().orEmpty())
                "reloadsurface" -> host.reload("shell")
                "load", "unload" -> when (a.getOrNull(1)?.lowercase()) {
                    "shiori" -> host.setShioriLoaded(command == "load")
                    "makoto" -> {}
                    else -> host.unknownTag(token)
                }
                "vanishbymyself" -> host.vanish(a.drop(1))
                "biff" -> host.checkMail(a.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() })
                "create" -> if (a.getOrNull(1)?.trim()?.lowercase() != "shortcut" || !host.createShortcut(a.drop(2))) host.unknownTag(token)
                "sound" -> when (a.getOrNull(1)?.lowercase()) {
                    "play", "loop" -> a.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { file ->
                        host.playSound(file, loop = a[1].lowercase() == "loop", options = SoundOptions.parse(a.drop(3)))
                    }
                    "wait" -> host.waitSound()
                    "stop" -> host.stopSound(a.getOrNull(2)?.takeIf { it.isNotBlank() })
                    "load", "option" -> a.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { file ->
                        host.soundControl(a[1].lowercase(), file, SoundOptions.parse(a.drop(3)))
                    }
                    "pause", "resume" -> host.soundControl(a[1].lowercase(), a.getOrNull(2)?.takeIf { it.isNotBlank() }, SoundOptions())
                    // 音楽 CD は Android に無い
                    "cdplay" -> {}
                    else -> host.unknownTag(token)
                }
                "change" -> when {
                    a.size >= 3 && a[1].lowercase() in setOf("shell", "balloon") &&
                        host.change(a[1].lowercase(), a[2], a.drop(3).any { it.trim().lowercase() == "--option=raise-event" }) -> {}
                    // \![change,ghost,名前(,--option=raise-event)]
                    a.size >= 3 && a[1].lowercase() == "ghost" &&
                        host.changeGhost(a[2].trim(), a.drop(3).any { it.trim().lowercase() == "--option=raise-event" }) -> {}
                    // カレンダーは pono-droid に無い
                    a.getOrNull(1)?.lowercase() == "calendarskin" -> {}
                    else -> host.unknownTag(token)
                }
                // \![move] は動き終わるまで待ち、\![moveasync] は待たずに続ける
                "move", "moveasync" -> {
                    val move = MoveCommand.parse(a.drop(1)) ?: return host.unknownTag(token)
                    host.move(scope, move)
                    if (command == "move" && !quick && move.timeMs > 0) delay(move.timeMs)
                }
                // \![wait,syncobject,名前,時間|--timeout=時間]
                "wait" -> if (a.getOrNull(1)?.lowercase() == "syncobject" && a.size >= 3) {
                    val timeout = a.drop(3).map { it.trim().removePrefix("--timeout=") }.firstNotNullOfOrNull { it.toLongOrNull() } ?: 0
                    host.waitSyncObject(a[2].trim(), timeout)
                } else {
                    host.unknownTag(token)
                }
                "reset" -> when (a.getOrNull(1)?.lowercase()) {
                    "syncobject" -> a.getOrNull(2)?.let { host.setSyncObject(it.trim(), false) }
                    // \![reset,position] は、\![set,position] で固定したキャラクター全員を放す
                    "position" -> for (s in 0..MAX_SCOPE) host.setPosition(s, null, null)
                    "zorder", "sticky-window" -> host.setOption(scope, a[1].lowercase(), emptyList())
                    else -> {}
                }
                "enter", "leave" -> when (val mode = a.getOrNull(1)?.lowercase()) {
                    "passivemode" -> host.setPassive(command == "enter")
                    // selectmode / collisionmode などで、まだ扱えないものは飛ばす
                    else -> host.setMode(scope, mode.orEmpty(), command == "enter", a.drop(2))
                }
                // \![lock,balloonrepaint] など。manual が無ければスクリプトの終わりで外れる
                "lock", "unlock" -> host.lock(
                    a.getOrNull(1)?.trim()?.lowercase().orEmpty(),
                    locked = command == "lock",
                    manual = a.drop(2).any { it.trim().lowercase() == "manual" },
                )
                "execute" -> when {
                    a.size < 2 -> host.unknownTag(token)
                    a[1].lowercase() in silentExecutes -> {}
                    !host.runExecute(a[1].lowercase(), a.drop(2)) -> host.unknownTag(token)
                }
                "cancel" -> if (a.size < 2 || !host.cancel(a[1].trim().lowercase(), a.drop(2))) host.unknownTag(token)
                "send" -> if (a.size < 2 || !host.send(a[1].trim().lowercase(), a.drop(2))) host.unknownTag(token)
                "executesntp" -> host.adjustClock()
                // \![bind,カテゴリ,パーツ,1|0]（3つ目が無ければ切り替え）
                // パーツ名が空ならカテゴリごと。bind-noevent はイベントを送らない
                "bind", "bind-noevent" -> if (a.size >= 3) {
                    val on = a.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.let { it == "1" || it.lowercase() == "true" }
                    host.bind(scope, a[1].trim(), a[2].trim(), on, noEvent = command == "bind-noevent")
                }
                "set" -> when {
                    a.size < 2 -> host.unknownTag(token)
                    // \![set,position,x,y,スコープ]（スコープを省くと今のキャラクター）
                    a[1].lowercase() == "position" -> {
                        val x = a.getOrNull(2)?.trim()?.toIntOrNull()
                        val y = a.getOrNull(3)?.trim()?.toIntOrNull()
                        val target = a.getOrNull(4)?.trim()?.toIntOrNull() ?: scope
                        if (x != null && y != null && target in 0..MAX_SCOPE) host.setPosition(target, x, y) else host.unknownTag(token)
                    }
                    // 文字の流れる速さ（このスクリプトの間だけ）
                    a[1].lowercase() == "balloonwait" -> charWaitOverride = balloonWait(a.getOrNull(2)?.trim()?.lowercase().orEmpty())
                    a[1].lowercase() == "syncobject" -> a.getOrNull(2)?.let { host.setSyncObject(it.trim(), true) }
                    a[1].lowercase() == "property" -> if (a.size < 3 || !host.setProperty(a[2], a.getOrNull(3).orEmpty())) host.unknownTag(token)
                    !host.setOption(scope, a[1].lowercase(), a.drop(2)) -> host.unknownTag(token)
                }
                // ウィンドウの重なりや他のゴースト・プラグインなど、pono-droid に無いものは黙って飛ばす
                in silentCommands -> {}
                else -> host.unknownTag(token)
            }
        }

        /** \f[...]。飾りを変えて host に伝える */
        private fun font(a: List<String>) {
            val name = a.firstOrNull()?.trim()?.lowercase() ?: return
            val arg = a.getOrNull(1)?.trim()?.lowercase()
            if (linkLook(name, a.drop(1))) return
            // \f[default] は選択肢とリンクの見た目も戻す
            if (name == "default") looks.remove(scope)
            val current = styles[scope] ?: TextStyle.Default
            // true / 1 で付け、false / 0 で外す。default はバルーンの既定、disable は無効表示の飾り（バルーンの disable.font.*）
            val flag: Boolean? = when (arg) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
            fun disabledFlags(key: String) = if (arg == "disable") current.disabledFlags + key else current.disabledFlags - key
            val next = when (name) {
                // \_n の折り返しは \f ではないので残す
                "default" -> TextStyle.Default.copy(noWrap = current.noWrap)
                "disable" -> current.copy(disabled = true)
                "bold" -> current.copy(bold = flag, disabledFlags = disabledFlags("bold"))
                "italic" -> current.copy(italic = flag, disabledFlags = disabledFlags("italic"))
                "underline" -> current.copy(underline = flag, disabledFlags = disabledFlags("underline"))
                "strike" -> current.copy(strike = flag, disabledFlags = disabledFlags("strike"))
                "sub" -> current.copy(sub = flag, sup = if (flag == true) false else current.sup)
                "sup" -> current.copy(sup = flag, sub = if (flag == true) false else current.sub)
                "color" -> when {
                    arg == "disable" -> current.copy(color = null, colorRef = null, disabled = true)
                    arg == "default" -> current.copy(color = null, colorRef = null, disabled = false)
                    // default.anchor などは、バルーンの決めたアンカーなどの色
                    arg != null && arg.startsWith("default.") ->
                        current.copy(color = null, colorRef = arg.removePrefix("default.").takeIf { it in COLOR_REFS }, disabled = false)
                    // 読めない色は無視する（2.8.93 から）
                    else -> parseColor(a.drop(1))?.let { current.copy(color = it, colorRef = null, disabled = false) } ?: current
                }
                // \f[name,メイリオ,meiryo.ttf]。書いた順に、使えるものを選ぶ
                "name" -> {
                    val names = a.drop(1).map { it.trim() }.filter { it.isNotEmpty() }
                    current.copy(font = names.takeUnless { it.isEmpty() || it[0].lowercase() in setOf("default", "disable") })
                }
                "shadowcolor" -> when (arg) {
                    "none" -> current.copy(shadowColor = null, noShadow = true)
                    null, "", "default", "disable" -> current.copy(shadowColor = null, noShadow = false)
                    else -> current.copy(shadowColor = parseColor(a.drop(1)), noShadow = false)
                }
                "shadowstyle" -> current.copy(
                    shadowStyle = when (arg) {
                        "offset" -> TextStyle.ShadowStyle.OFFSET
                        "outline" -> TextStyle.ShadowStyle.OUTLINE
                        else -> null
                    },
                )
                "outline" -> current.copy(
                    outline = when (arg) {
                        null, "", "default" -> null
                        "disable" -> false
                        else -> flag
                    },
                )
                "valign" -> {
                    when (arg) {
                        "top" -> VerticalAlign.TOP
                        "center" -> VerticalAlign.CENTER
                        "bottom" -> VerticalAlign.BOTTOM
                        else -> null
                    }?.let { host.setVerticalAlign(scope, it) }
                    return
                }
                "height" -> when {
                    arg == null || arg == "default" -> current.copy(height = null)
                    // スタイルシートの大きさ（medium がバルーンの既定）
                    arg in CSS_SIZES -> current.copy(height = CSS_SIZES.getValue(arg), heightMode = TextStyle.HeightMode.PERCENT)
                    arg.endsWith("%") -> arg.dropLast(1).toFloatOrNull()?.let { current.copy(height = it, heightMode = TextStyle.HeightMode.PERCENT) }
                    arg.startsWith("+") || arg.startsWith("-") -> arg.toFloatOrNull()?.let { current.copy(height = it, heightMode = TextStyle.HeightMode.RELATIVE) }
                    else -> arg.toFloatOrNull()?.let { current.copy(height = it, heightMode = TextStyle.HeightMode.ABSOLUTE) }
                } ?: current
                "align" -> {
                    when (arg) {
                        "center" -> TextAlign.CENTER
                        "right" -> TextAlign.RIGHT
                        "left" -> TextAlign.LEFT
                        else -> null
                    }?.let { host.setAlign(scope, it) }
                    return
                }
                // cursor* / anchor* などは見た目の細かい指定なので無視する
                else -> return
            }
            setStyle(next)
        }

        /**
         * \f[cursorstyle,...] / \f[anchornotselectfontcolor,...] / \f[anchor.font.color,...] など、選択肢とリンクの見た目。
         * 扱ったら true。method（描き方）は Android では選べないので読み飛ばす
         */
        private fun linkLook(name: String, args: List<String>): Boolean {
            val current = looks[scope] ?: ChoiceLooks.Default
            if (name == "anchor.font.color") {
                looks[scope] = current.copy(anchorNotSelect = current.anchorNotSelect.copy(font = parseColor(args)))
                return true
            }
            val prefix = listOf("cursornotselect", "cursor", "anchornotselect", "anchorvisited", "anchor").firstOrNull { name.startsWith(it) }
                ?: return false
            val look = when (prefix) {
                "cursornotselect" -> current.cursorNotSelect
                "cursor" -> current.cursor
                "anchornotselect" -> current.anchorNotSelect
                "anchorvisited" -> current.anchorVisited
                else -> current.anchor
            }
            val value = args.firstOrNull()?.trim()?.lowercase()
            val next = when (name.removePrefix(prefix)) {
                "style" -> look.copy(shape = value?.takeIf { it in setOf("square", "underline", "square+underline", "none") })
                "color", "brushcolor" -> look.copy(brush = parseColor(args))
                "pencolor" -> look.copy(pen = parseColor(args))
                "fontcolor" -> look.copy(font = parseColor(args))
                "method" -> look
                else -> return false
            }
            looks[scope] = when (prefix) {
                "cursornotselect" -> current.copy(cursorNotSelect = next)
                "cursor" -> current.copy(cursor = next)
                "anchornotselect" -> current.copy(anchorNotSelect = next)
                "anchorvisited" -> current.copy(anchorVisited = next)
                else -> current.copy(anchor = next)
            }
            return true
        }

        /** 更新オプション（checkonly など。--option=checkonly の形でもよい） */
        private fun updateOptions(args: List<String>): Set<String> =
            args.flatMap { it.trim().removePrefix("--option=").split('+') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

        private fun setStyle(next: TextStyle) {
            if (next == (styles[scope] ?: TextStyle.Default)) return
            styles[scope] = next
            host.setFont(scope, next)
        }

        /**
         * \![set,balloonwait,...] の1文字ごとの待ち時間。数だけなら倍率（0〜100）、% なら百分率（0〜10000）、
         * ms ならそのまま（0〜10000）。空か 1 ならふつうに戻す（null）
         */
        private fun balloonWait(arg: String): Long? = when {
            arg.isEmpty() -> null
            arg.endsWith("ms") -> arg.dropLast(2).trim().toLongOrNull()?.coerceIn(0, MAX_CHAR_WAIT_MS)
            arg.endsWith("%") -> arg.dropLast(1).trim().toLongOrNull()?.coerceIn(0, 10_000)?.let { charWaitMs * it / 100 }
            else -> arg.toDoubleOrNull()?.takeIf { it != 1.0 }?.coerceIn(0.0, 100.0)?.let { (charWaitMs * it).toLong() }
        }
    }

    /**
     * 見た目の細かい調整だけのタグ。バルーンを自前で描いているので、無視しても会話の流れは変わらない。
     * \C: 前のバルーンを消さない（先頭にあるかどうかは GhostController が見る）、\_e: 終わり
     */
    private val layoutOnlyTags = setOf("C", "_e")

    /** `\![コマンド,...]` のうち、pono-droid に無い仕組み（ウィンドウの重なり・効果・他のゴースト・プラグイン）向けのもの */
    private val silentCommands = setOf(
        "effect", "effect2", "filter",
        "raiseplugin", "timerraiseplugin", "notifyplugin", "timernotifyplugin",
    )


    private val silentExecutes = setOf("resetballoonpos")

    private val envPattern = Regex("""%(m\?|[a-zA-Z][a-zA-Z0-9]*)""")
    private val propertyPattern = Regex("""%property\[([^\]]*)\]""")

    private fun expandEnv(s: String, host: ScriptHost): String {
        val replaced = if ('%' in s) {
            val withProperties = propertyPattern.replace(s) { m -> host.property(m.groupValues[1].trim()).orEmpty() }
            // %selfname2 のような名前もあるので、長い名前から順に試す
            envPattern.replace(withProperties) { m ->
                val name = m.groupValues[1]
                (name.length downTo 1).firstNotNullOfOrNull { len ->
                    host.envVariable(name.substring(0, len))?.let { it + name.substring(len) }
                } ?: m.value
            }
        } else {
            s
        }
        return replaced.replace(SakuraScript.ESCAPED_PERCENT, '%')
    }

    /** SSTP で届いたスクリプトで使ってよい \![...]。入力の箱は開けるが、ブラウザやファイルは開かせない */
    private fun untrustedAllowed(a: List<String>): Boolean {
        val command = a.firstOrNull()?.trim()?.lowercase() ?: return true
        val sub = a.getOrNull(1)?.trim()?.lowercase()
        return when (command) {
            "raise", "notify", "embed", "timerraise", "get", "lock", "unlock", "anim", "bind", "bind-noevent", "move", "moveasync",
            "enter", "leave", "wait", "reset", "*", "quicksection", "quicksession", "sound", "close" -> true
            "open" -> sub in UNTRUSTED_OPEN
            "set" -> sub != null && sub !in UNTRUSTED_SET_REFUSED
            else -> false
        }
    }

    companion object {
        const val MAX_EMBED_DEPTH = 8

        /** SSTP で届いたスクリプトで使わせないタグ（終了・ゴーストの切り替え・ブラウザ・時計） */
        private val UNTRUSTED_TAGS = setOf("-", "+", "_+", "j", "6", "7")
        private val UNTRUSTED_OPEN = setOf("inputbox", "passwordinput", "communicatebox", "teachbox", "dateinput", "timeinput", "sliderinput", "textareabox", "ipinput")
        private val UNTRUSTED_SET_REFUSED = setOf("property", "wallpaper")

        /** \p[N] で使えるキャラクターの番号の上限 */
        const val MAX_SCOPE = 15

        /** \n[パーセント] の上限（何十行分も飛ばさない） */
        const val MAX_LINE_PERCENT = 1000

        /** \![set,balloonwait,Nms] の上限 */
        const val MAX_CHAR_WAIT_MS = 10_000L

        /** "0x22EE" か "8942" を文字コードにする。文字として書けない値は null */
        fun parseCode(s: String): Int? {
            val t = s.trim()
            val code = if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toIntOrNull(16) else t.toIntOrNull()
            return code?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }
        }

        /** \f[color,...] の色。"255,0,0" / "50%,90%,20%" / "#FF0000" / "red" / "default"。わからなければ null（既定の色） */
        fun parseColor(args: List<String>): Int? {
            val parts = args.map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                fun channel(s: String) = if (s.endsWith("%")) {
                    s.dropLast(1).trim().toFloatOrNull()?.let { (it * 255 / 100).toInt() }
                } else {
                    s.toIntOrNull()
                }
                val rgb = parts.take(3).map { channel(it)?.coerceIn(0, 255) ?: return null }
                return (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
            }
            val v = parts.firstOrNull()?.lowercase() ?: return null
            if (v.startsWith("#") && v.length == 7) return v.substring(1).toIntOrNull(16)
            // #07f は #0077ff
            if (v.startsWith("#") && v.length == 4) return v.substring(1).map { "$it$it" }.joinToString("").toIntOrNull(16)
            if (v.length == 6 && v.all { it.isDigit() || it in 'a'..'f' }) return v.toIntOrNull(16)
            return namedColors[v]
        }

        /** CSS Color Module Level 3 の色の名前（W3C の定義） */
        private val namedColors: Map<String, Int> = CssColors.NAMES

        /** \f[color,default.○○] で指せるバルーンの色 */
        val COLOR_REFS = setOf("anchor", "anchornotselect", "anchorvisited", "cursor", "cursornotselect", "plain")

        /** \f[height,xx-small〜xx-large]。medium（バルーンの既定）に対する割合 */
        private val CSS_SIZES = mapOf(
            "xx-small" to 60f, "x-small" to 75f, "small" to 89f, "medium" to 100f, "large" to 120f, "x-large" to 150f, "xx-large" to 200f,
            "smaller" to 83f, "larger" to 120f,
        )

        /** \&[amp] の実体参照。&#123; と &#x7B; の数値も読む */
        fun entity(id: String): String? {
            val name = id.trim().removePrefix("&").removeSuffix(";")
            if (name.startsWith("#")) {
                val code = if (name.startsWith("#x", ignoreCase = true)) name.substring(2).toIntOrNull(16) else name.substring(1).toIntOrNull()
                return code?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) }
            }
            return entities[name]?.toString()
        }

        private val entities = mapOf(
            "amp" to '&', "lt" to '<', "gt" to '>', "quot" to '"', "apos" to '\'', "nbsp" to ' ',
            "copy" to '©', "reg" to '®', "trade" to '™', "sect" to '§', "para" to '¶', "deg" to '°', "plusmn" to '±',
            "times" to '×', "divide" to '÷', "middot" to '·', "bull" to '•', "hellip" to '…', "ndash" to '–', "mdash" to '—',
            "laquo" to '«', "raquo" to '»', "lsquo" to '‘', "rsquo" to '’', "ldquo" to '“', "rdquo" to '”',
            "yen" to '¥', "euro" to '€', "pound" to '£', "cent" to '¢',
            "hearts" to '♥', "spades" to '♠', "clubs" to '♣', "diams" to '♦',
            "larr" to '←', "uarr" to '↑', "rarr" to '→', "darr" to '↓', "harr" to '↔', "lArr" to '⇐', "rArr" to '⇒',
            "infin" to '∞', "ne" to '≠', "le" to '≤', "ge" to '≥', "sum" to '∑', "prod" to '∏', "radic" to '√', "asymp" to '≈',
            "alpha" to 'α', "beta" to 'β', "gamma" to 'γ', "delta" to 'δ', "lambda" to 'λ', "mu" to 'μ', "pi" to 'π', "sigma" to 'σ', "omega" to 'ω',
        )
    }
}
