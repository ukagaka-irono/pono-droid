package net.pono.baseware.sakura

import kotlinx.coroutines.delay

/** [anchor] は \_a（ジャンパ）。選択肢（\q, \__q）とは送るイベントが違う */
data class Choice(val title: String, val id: String, val references: List<String> = emptyList(), val anchor: Boolean = false)

enum class TextAlign { LEFT, CENTER, RIGHT }

/** \f で決める文字の飾り。 */
data class TextStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** 0xRRGGBB。null なら既定の色 */
    val color: Int? = null,
    /** 文字の高さ。null なら既定。[heightMode] で意味が変わる */
    val height: Float? = null,
    val heightMode: HeightMode = HeightMode.ABSOLUTE,
    val sub: Boolean = false,
    val sup: Boolean = false,
) {
    /** ABSOLUTE: ピクセル、RELATIVE: 既定からの差（ピクセル）、PERCENT: 既定に対する割合 */
    enum class HeightMode { ABSOLUTE, RELATIVE, PERCENT }

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
    fun clearBalloon(scope: Int)
    fun addChoice(scope: Int, choice: Choice)
    fun startAnimation(scope: Int, id: Int) {}

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

    /** `\![set,name,args...]`。扱えたら true。 */
    fun setOption(name: String, args: List<String>): Boolean = false

    /** `\![set,name,args...]` のうち、今のキャラクターにかかるもの（scaling / alpha など）。扱えなければ [setOption] に回す */
    fun setOption(scope: Int, name: String, args: List<String>): Boolean = setOption(name, args)

    /** `\![execute,name,args...]`（SSP の http-get など）。扱えたら true。 */
    fun execute(name: String, args: List<String>): Boolean = false

    /** `\![bind,カテゴリ,パーツ,1/0]`（着せ替え）。 */
    fun bind(scope: Int, category: String, part: String, on: Boolean?) {}

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

    /** `\![get,property,イベント,名前...]`。名前ごとの値を Reference に入れてイベントを送る */
    fun getProperty(event: String, names: List<String>) {}

    /** `%property[名前]`。知らない名前なら null */
    fun property(name: String): String? = null

    /** `\![reload,shiori]` など。[what] は shiori / ghost / descript ... */
    fun reload(what: String) {}

    /** `\![enter,passivemode]` / `\![leave,passivemode]` */
    fun setPassive(on: Boolean) {}

    /** `\8[file]` `\_v[file]` `\![sound,play|loop,file,--volume=n]`。[file] は ghost/master からの相対パス */
    fun playSound(file: String, loop: Boolean, volume: Int?) {}

    /** `\![sound,stop(,file)]`。file が null なら全部 */
    fun stopSound(file: String?) {}

    /** `\_V` `\![sound,wait]`。再生が終わるまで戻らない（鳴っていなければすぐ戻る） */
    suspend fun waitSound() {}

    /** `\![change,shell|balloon,名前]`。扱えたら true */
    fun change(kind: String, name: String): Boolean = false

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

        // \_s のシンクロナイズセクション。null でなければ、文字と改行をこのキャラクター全員に出す
        private var sync: List<Int>? = null

        /** 文字や改行を出す先 */
        private fun targets(): List<Int> = sync ?: listOf(scope)

        suspend fun run(script: String, depth: Int) {
            for (token in SakuraScript.tokenize(script)) {
                if (ended) return
                when (token) {
                    is Token.Text -> text(token.text)
                    is Token.Tag -> tag(token, depth)
                }
            }
        }

        private suspend fun text(s: String) {
            val expanded = expandEnv(s, host)
            if (anchor != null) {
                anchorTitle.append(expanded)
                return
            }
            if (quick || charWaitMs <= 0) {
                for (t in targets()) host.appendText(t, expanded)
            } else {
                // サロゲートペアを分けないよう、コードポイント単位で出す
                var i = 0
                while (i < expanded.length) {
                    val n = Character.charCount(expanded.codePointAt(i))
                    for (t in targets()) host.appendText(t, expanded.substring(i, i + n))
                    i += n
                    delay(charWaitMs)
                }
            }
        }

        private suspend fun tag(token: Token.Tag, depth: Int) {
            val a = token.args
            when (token.name) {
                "0", "h" -> scope = 0
                "1", "u" -> scope = 1
                // 番号には上限を置く（細工したスクリプトにキャラクターのウィンドウを無数に作らせない）
                "p" -> scope = a.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it in 0..MAX_SCOPE } ?: scope
                "s" -> a.firstOrNull()?.takeIf { it.isNotBlank() }?.let { host.setSurface(scope, it) }
                "i" -> a.firstOrNull()?.toIntOrNull()?.let { host.startAnimation(scope, it) }
                "n" -> for (t in targets()) host.newLine(t)
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
                "c" -> host.clearBalloon(scope)
                "w" -> if (!quick) delay((a.firstOrNull()?.toLongOrNull() ?: 1) * unitWaitMs)
                "_w", "__w" -> if (!quick) delay(a.firstOrNull()?.toLongOrNull() ?: 0)
                "_q" -> quick = !quick
                "x" -> {
                    host.waitClick()
                    if (a.firstOrNull() != "noclear") {
                        host.clearBalloon(0)
                        host.clearBalloon(1)
                    }
                }
                "e" -> ended = true
                "-" -> quit = true
                "q" -> if (a.size >= 2) {
                    hasChoices = true
                    host.addChoice(scope, Choice(expandEnv(a[0], host), a[1], a.drop(2)))
                }
                "__q", "_a" -> {
                    val open = anchor
                    if (open == null && a.isNotEmpty()) {
                        anchor = Choice("", a[0], a.drop(1), anchor = token.name == "_a")
                    } else if (open != null) {
                        hasChoices = true
                        host.addChoice(scope, open.copy(title = anchorTitle.toString()))
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
                "j" -> a.firstOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let(host::openUrl)
                // 横の位置が数で書かれているときだけ（@ 付きの相対指定や縦だけの指定は無視する）
                "_l" -> a.firstOrNull()?.trim()?.toIntOrNull()?.let { host.moveCursor(scope, it) }
                "b" -> a.firstOrNull()?.trim()?.toIntOrNull()?.let { host.setBalloonId(scope, it) }
                "4", "5" -> host.nudge(scope, away = token.name == "4")
                "v" -> host.bringToFront()
                in layoutOnlyTags -> {}
                else -> host.unknownTag(token)
            }
        }

        private suspend fun bang(token: Token.Tag, depth: Int) {
            val a = token.args
            when (val command = a.firstOrNull()?.lowercase()) {
                "raise" -> if (a.size >= 2) host.raise(a[1], a.drop(2))
                "notify" -> if (a.size >= 2) host.notify(a[1], a.drop(2))
                "embed" -> if (a.size >= 2) {
                    // 埋め込んだ先でまた埋め込むのは、無限に続かないよう回数を絞る
                    val embedded = host.embed(a[1], a.drop(2))
                    if (embedded != null && depth < MAX_EMBED_DEPTH) run(embedded, depth + 1)
                }
                "timerraise" -> if (a.size >= 4) {
                    val ms = a[1].trim().toLongOrNull() ?: return
                    // 「繰り返すか否か」は 0 で繰り返し、1 以上で一回だけ
                    val once = (a[2].trim().toIntOrNull() ?: 1) >= 1
                    host.timerRaise(ms, once, a[3], a.drop(4))
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
                "updatebymyself" -> host.requestUpdate()
                // \![anim,offset,ID,x,y] など。pause / resume / add などは見た目の細かい制御なので扱えなくても飛ばす
                "anim" -> host.animation(scope, a.drop(1))
                "reload" -> host.reload(a.getOrNull(1)?.lowercase().orEmpty())
                "sound" -> when (a.getOrNull(1)?.lowercase()) {
                    "play", "loop" -> a.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { file ->
                        val volume = a.drop(3).firstOrNull { it.startsWith("--volume=") }?.removePrefix("--volume=")?.trim()?.toIntOrNull()
                        host.playSound(file, loop = a[1].lowercase() == "loop", volume = volume)
                    }
                    "wait" -> host.waitSound()
                    "stop" -> host.stopSound(a.getOrNull(2)?.takeIf { it.isNotBlank() })
                    // load / pause / resume / option / cdplay は、音を先に読む・止めるといった細かい制御なので飛ばす
                    "load", "pause", "resume", "option", "cdplay" -> {}
                    else -> host.unknownTag(token)
                }
                "change" -> when {
                    a.size >= 3 && a[1].lowercase() in setOf("shell", "balloon") && host.change(a[1].lowercase(), a[2]) -> {}
                    // \![change,ghost,名前(,--option=raise-event)]
                    a.size >= 3 && a[1].lowercase() == "ghost" &&
                        host.changeGhost(a[2].trim(), a.drop(3).any { it.trim().lowercase() == "--option=raise-event" }) -> {}
                    else -> host.unknownTag(token)
                }
                // \![move] は動き終わるまで待ち、\![moveasync] は待たずに続ける
                "move", "moveasync" -> {
                    val move = MoveCommand.parse(a.drop(1)) ?: return host.unknownTag(token)
                    host.move(scope, move)
                    if (command == "move" && !quick && move.timeMs > 0) delay(move.timeMs)
                }
                "reset" -> when (a.getOrNull(1)?.lowercase()) {
                    // \![reset,position] は、\![set,position] で固定したキャラクター全員を放す
                    "position" -> for (s in 0..MAX_SCOPE) host.setPosition(s, null, null)
                    "zorder", "sticky-window" -> host.setOption(scope, a[1].lowercase(), emptyList())
                    else -> {}
                }
                "enter", "leave" -> when (a.getOrNull(1)?.lowercase()) {
                    "passivemode" -> host.setPassive(command == "enter")
                    // onlinemode / nouserbreakmode / inducemode / calmmode などは pono-droid では意味が無い
                    else -> {}
                }
                "execute" -> when {
                    a.size < 2 -> host.unknownTag(token)
                    a[1].lowercase() in silentExecutes -> {}
                    !host.execute(a[1].lowercase(), a.drop(2)) -> host.unknownTag(token)
                }
                // \![bind,カテゴリ,パーツ,1|0]（3つ目が無ければ切り替え）
                "bind", "bind-noevent" -> if (a.size >= 3) host.bind(scope, a[1], a[2], a.getOrNull(3)?.let { it == "1" || it.lowercase() == "true" })
                "set" -> when {
                    a.size < 2 -> host.unknownTag(token)
                    // \![set,position,x,y,スコープ]（スコープを省くと今のキャラクター）
                    a[1].lowercase() == "position" -> {
                        val x = a.getOrNull(2)?.trim()?.toIntOrNull()
                        val y = a.getOrNull(3)?.trim()?.toIntOrNull()
                        val target = a.getOrNull(4)?.trim()?.toIntOrNull() ?: scope
                        if (x != null && y != null && target in 0..MAX_SCOPE) host.setPosition(target, x, y) else host.unknownTag(token)
                    }
                    a[1].lowercase() in silentSetOptions -> {}
                    !host.setOption(scope, a[1].lowercase(), a.drop(2)) -> host.unknownTag(token)
                }
                // ウィンドウの重なりや他のゴースト・プラグインなど、pono-droid に無いものは黙って飛ばす
                in silentCommands -> {}
                else -> host.unknownTag(token)
            }
        }

        /** \f[...]。飾りを変えて host に伝える */
        private fun font(a: List<String>) {
            val name = a.firstOrNull()?.lowercase() ?: return
            val arg = a.getOrNull(1)?.trim()?.lowercase()
            val current = styles[scope] ?: TextStyle.Default
            fun flag() = arg == "1" || arg == "true"
            val next = when (name) {
                "default", "disable" -> TextStyle.Default
                "bold" -> current.copy(bold = flag())
                "italic" -> current.copy(italic = flag())
                "underline" -> current.copy(underline = flag())
                "strike" -> current.copy(strike = flag())
                "sub" -> current.copy(sub = flag(), sup = if (flag()) false else current.sup)
                "sup" -> current.copy(sup = flag(), sub = if (flag()) false else current.sub)
                "color" -> current.copy(color = parseColor(a.drop(1)))
                "height" -> when {
                    arg == null || arg == "default" -> current.copy(height = null)
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
                // name / shadowcolor / outline / valign / anchor.font.color などは見た目の細かい指定なので無視する
                else -> return
            }
            if (next != current) {
                styles[scope] = next
                host.setFont(scope, next)
            }
        }
    }

    /**
     * 見た目の細かい調整だけのタグ。バルーンを自前で描いているので、無視しても会話の流れは変わらない。
     * \_n: 自動改行の切り替え、\t: 割り込み禁止、\* : 選択肢タイムアウト禁止、
     * \C: 前のバルーンを消さない（先頭にあるかどうかは GhostController が見る）、
     * \6 / \7 / \z: 時刻合わせや表示、\_s: 両方のバルーンに出す（片方だけに出す）、\__t: 時刻、\_e: 終わり
     */
    private val layoutOnlyTags = setOf("_n", "t", "*", "C", "6", "7", "z", "__t", "_e")

    /** `\![コマンド,...]` のうち、pono-droid に無い仕組み（ウィンドウの重なり・効果・他のゴースト・プラグイン）向けのもの */
    private val silentCommands = setOf(
        "lock", "unlock", "effect", "effect2", "filter", "updateother",
        "timernotify", "raiseother", "timerraiseother", "notifyother", "timernotifyother",
        "raiseplugin", "timerraiseplugin", "notifyplugin", "timernotifyplugin", "executesntp",
    )

    /** `\![set,名前,...]` のうち、Windows のウィンドウやトレイ向けで、pono-droid では意味が無いもの */
    private val silentSetOptions = setOf(
        "trayicon", "tasktrayicon", "trayballoon", "tasktrayballoon", "wallpaper",
        "otherghosttalk", "othersurfacechange", "alignmentondesktop",
        "autoscroll", "balloonwait", "balloonmarker", "balloonalign", "balloonoffset", "balloonnum",
        "serikotalk", "syncobject", "shioridebugmode", "property",
    )

    private val silentExecutes = setOf("resetballoonpos")

    private val envPattern = Regex("""%([a-zA-Z][a-zA-Z0-9]*)""")
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

    companion object {
        const val MAX_EMBED_DEPTH = 8

        /** \p[N] で使えるキャラクターの番号の上限 */
        const val MAX_SCOPE = 15

        /** "0x22EE" か "8942" を文字コードにする。文字として書けない値は null */
        fun parseCode(s: String): Int? {
            val t = s.trim()
            val code = if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toIntOrNull(16) else t.toIntOrNull()
            return code?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }
        }

        /** \f[color,...] の色。"255,0,0" / "#FF0000" / "red" / "default"。わからなければ null（既定の色） */
        fun parseColor(args: List<String>): Int? {
            val parts = args.map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                val rgb = parts.take(3).map { it.toIntOrNull()?.coerceIn(0, 255) ?: return null }
                return (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
            }
            val v = parts.firstOrNull()?.lowercase() ?: return null
            if (v.startsWith("#") && v.length == 7) return v.substring(1).toIntOrNull(16)
            if (v.length == 6 && v.all { it.isDigit() || it in 'a'..'f' }) return v.toIntOrNull(16)
            return namedColors[v]
        }

        private val namedColors = mapOf(
            "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
            "yellow" to 0xFFFF00, "cyan" to 0x00FFFF, "aqua" to 0x00FFFF, "magenta" to 0xFF00FF, "fuchsia" to 0xFF00FF,
            "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xC0C0C0, "maroon" to 0x800000, "olive" to 0x808000,
            "lime" to 0x00FF00, "teal" to 0x008080, "navy" to 0x000080, "purple" to 0x800080, "orange" to 0xFFA500,
            "pink" to 0xFFC0CB, "brown" to 0xA52A2A, "gold" to 0xFFD700, "violet" to 0xEE82EE, "indigo" to 0x4B0082,
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
