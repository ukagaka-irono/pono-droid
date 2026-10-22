package net.pono.baseware.sakura

import kotlinx.coroutines.delay

data class Choice(val title: String, val id: String, val references: List<String> = emptyList())

/** さくらスクリプトの実行結果を受け取る側（見た目を持つ側）。 */
interface ScriptHost {
    fun setSurface(scope: Int, surface: Int)
    fun appendText(scope: Int, text: String)
    fun newLine(scope: Int)
    fun clearBalloon(scope: Int)
    fun addChoice(scope: Int, choice: Choice)
    fun startAnimation(scope: Int, id: Int) {}

    /** `\![*]`。選択肢の前の印 */
    fun addMarker(scope: Int) {}

    /** `\_l[x,y]` で横の位置を指定された（同じ行で次の列に移る） */
    fun moveCursor(scope: Int, x: Int) {}

    /** `\b[n]`。バルーンの番号を選ぶ（-1 ならバルーンを隠す） */
    fun setBalloonId(scope: Int, id: Int) {}

    /** `\![open,種類,引数...]` のうち、ブラウザ以外（入力の箱など）。扱えたら true */
    fun open(kind: String, args: List<String>): Boolean = false

    /** `\![close,種類,引数...]`。扱えたら true */
    fun close(kind: String, args: List<String>): Boolean = false

    /** `\![updatebymyself]`。ゴースト自身のネットワーク更新を頼まれた */
    fun requestUpdate() {}

    /** `\![set,name,args...]`。扱えたら true。 */
    fun setOption(name: String, args: List<String>): Boolean = false

    /** `\![execute,name,args...]`（SSP の http-get など）。扱えたら true。 */
    fun execute(name: String, args: List<String>): Boolean = false

    /** `\![bind,カテゴリ,パーツ,1/0]`（着せ替え）。 */
    fun bind(scope: Int, category: String, part: String, on: Boolean?) {}

    /** `\x`。クリックされるまで戻らない。 */
    suspend fun waitClick()

    /** `\![raise,...]`。スクリプトが終わってから送ればよい。 */
    fun raise(event: String, references: List<String>)
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
        var scope = 0
        var quick = false
        var quit = false
        var hasChoices = false
        // \__q[ID]...\__q と \_a[ID]...\_a の間のテキスト
        var anchor: Choice? = null
        val anchorTitle = StringBuilder()

        suspend fun text(s: String) {
            val expanded = expandEnv(s, host)
            if (anchor != null) {
                anchorTitle.append(expanded)
                return
            }
            if (quick || charWaitMs <= 0) {
                host.appendText(scope, expanded)
            } else {
                // サロゲートペアを分けないよう、コードポイント単位で出す
                var i = 0
                while (i < expanded.length) {
                    val n = Character.charCount(expanded.codePointAt(i))
                    host.appendText(scope, expanded.substring(i, i + n))
                    i += n
                    delay(charWaitMs)
                }
            }
        }

        loop@ for (token in SakuraScript.tokenize(script)) {
            when (token) {
                is Token.Text -> text(token.text)
                is Token.Tag -> {
                    val a = token.args
                    when (token.name) {
                        "0", "h" -> scope = 0
                        "1", "u" -> scope = 1
                        "p" -> scope = a.firstOrNull()?.toIntOrNull() ?: scope
                        "s" -> a.firstOrNull()?.toIntOrNull()?.let { host.setSurface(scope, it) }
                        "i" -> a.firstOrNull()?.toIntOrNull()?.let { host.startAnimation(scope, it) }
                        "n" -> host.newLine(scope)
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
                        "e" -> break@loop
                        "-" -> quit = true
                        "q" -> if (a.size >= 2) {
                            hasChoices = true
                            host.addChoice(scope, Choice(expandEnv(a[0], host), a[1], a.drop(2)))
                        }
                        "__q", "_a" -> {
                            val open = anchor
                            if (open == null && a.isNotEmpty()) {
                                anchor = Choice("", a[0], a.drop(1))
                            } else if (open != null) {
                                hasChoices = true
                                host.addChoice(scope, open.copy(title = anchorTitle.toString()))
                                anchor = null
                                anchorTitle.clear()
                            }
                        }
                        "!" -> when (a.firstOrNull()?.lowercase()) {
                            "raise" -> if (a.size >= 2) host.raise(a[1], a.drop(2))
                            "open" -> when {
                                a.getOrNull(1)?.lowercase() == "browser" && a.size >= 3 -> host.openUrl(a[2])
                                a.size >= 2 && host.open(a[1].lowercase(), a.drop(2)) -> {}
                                else -> host.unknownTag(token)
                            }
                            "close" -> if (a.size < 2 || !host.close(a[1].lowercase(), a.drop(2))) host.unknownTag(token)
                            "quicksection" -> quick = a.getOrNull(1)?.lowercase() in setOf("1", "true")
                            "*" -> host.addMarker(scope)
                            "updatebymyself" -> host.requestUpdate()
                            "execute" -> if (a.size < 2 || !host.execute(a[1].lowercase(), a.drop(2))) host.unknownTag(token)
                            // \![bind,カテゴリ,パーツ,1|0]（3つ目が無ければ切り替え）
                            "bind" -> if (a.size >= 3) host.bind(scope, a[1], a[2], a.getOrNull(3)?.let { it == "1" || it.lowercase() == "true" })
                            "set" -> if (a.size >= 2 && !host.setOption(a[1].lowercase(), a.drop(2))) host.unknownTag(token)
                            else -> host.unknownTag(token)
                        }
                        "j" -> a.firstOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let(host::openUrl)
                        // 横の位置が数で書かれているときだけ（@ 付きの相対指定や縦だけの指定は無視する）
                        "_l" -> a.firstOrNull()?.trim()?.toIntOrNull()?.let { host.moveCursor(scope, it) }
                        "b" -> a.firstOrNull()?.trim()?.toIntOrNull()?.let { host.setBalloonId(scope, it) }
                        in layoutOnlyTags -> {}
                        else -> host.unknownTag(token)
                    }
                }
            }
        }
        return PlayResult(quit, hasChoices)
    }

    /**
     * 見た目の細かい調整だけのタグ。バルーンを自前で描いているので、無視しても会話の流れは変わらない。
     * \_n: 自動改行の切り替え、\f: 書式、\t: 割り込み禁止、\* : 選択肢タイムアウト禁止、
     * \C: 前のバルーンを消さない（先頭にあるかどうかは GhostController が見る）
     */
    private val layoutOnlyTags = setOf("_n", "f", "t", "*", "C")

    private val envPattern = Regex("""%([a-zA-Z][a-zA-Z0-9]*)""")

    private fun expandEnv(s: String, host: ScriptHost): String {
        val replaced = if ('%' in s) {
            // %selfname2 のような名前もあるので、長い名前から順に試す
            envPattern.replace(s) { m ->
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
}
