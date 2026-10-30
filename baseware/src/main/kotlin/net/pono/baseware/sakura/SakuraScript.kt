package net.pono.baseware.sakura

/** さくらスクリプトの字句。 */
sealed interface Token {
    /**
     * [literal] は \_? ... \_? の中。タグも環境変数もそのまま文字として出す。
     * [escapedPercents] は \% と書いた % の位置（環境変数の展開の始まりにしない）
     */
    data class Text(val text: String, val literal: Boolean = false, val escapedPercents: List<Int> = emptyList()) : Token

    /** `\s[10]` なら name="s", args=["10"]。`\_w[100]` なら name="_w"。 */
    data class Tag(val name: String, val args: List<String> = emptyList()) : Token
}

object SakuraScript {
    /** 引数を [] で取りうるタグ。`\s0` のような1桁の省略形を持つもの以外は、[] が無ければ引数なし。 */
    private val shortDigitTags = setOf("s", "w", "p", "b", "i")

    fun tokenize(script: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val text = StringBuilder()
        val escaped = mutableListOf<Int>()
        fun flush() {
            if (text.isNotEmpty()) {
                tokens += Token.Text(text.toString(), escapedPercents = escaped.toList())
                text.clear()
                escaped.clear()
            }
        }

        var i = 0
        while (i < script.length) {
            val c = script[i]
            if (c != '\\') {
                text.append(c)
                i++
                continue
            }
            if (i + 1 >= script.length) break
            val next = script[i + 1]
            // エスケープ。\% は環境変数を展開しない % なので、その位置を覚えておく（目印の文字に置き換えると、
            // 地の文に同じ文字があったときに取り違える）
            if (next == '\\' || next == ']' || next == '%') {
                if (next == '%') escaped += text.length
                text.append(next)
                i += 2
                continue
            }

            var p = i + 1
            // 名前の 1 文字（絵文字などのサロゲートペアは分けない）
            fun charEnd(at: Int) = if (at < script.length) minOf(script.length, at + Character.charCount(script.codePointAt(at))) else at
            val name: String = when {
                script.startsWith("__", p) -> {
                    // \__q, \__w, \__c, \__t, \__v。どれも英字1文字なので、後ろに続く英字は地の文（\__qand → \__q と and）
                    val start = p
                    p += 2
                    if (p < script.length && (script[p] in 'a'..'z' || script[p] in 'A'..'Z')) p++
                    script.substring(start, p)
                }
                next == '_' -> {
                    // \_w, \_q, \_a など
                    p = charEnd(p + 1)
                    script.substring(i + 1, p)
                }
                else -> {
                    // \0, \h, \s, \!, \- など1文字
                    p = charEnd(p)
                    script.substring(i + 1, p)
                }
            }

            // \_? から次の \_? まで（古い書き方は \_!）は、タグを実行せずにそのまま出す
            if (name == "_?" || name == "_!") {
                flush()
                val close = script.indexOf("\\$name", p)
                val end = if (close < 0) script.length else close
                if (end > p) tokens += Token.Text(script.substring(p, end), literal = true)
                i = if (close < 0) script.length else close + 3
                continue
            }

            // 古い選択肢 \q[ID][タイトル] / \q0[ID][タイトル]（番号は通し番号で使わない）。今の \q[タイトル,ID] にして、後ろで改行する
            if (name == "q") {
                var o = p
                while (o < script.length && script[o].isDigit()) o++
                if (o < script.length && script[o] == '[') {
                    val idEnd = findBracketEnd(script, o)
                    if (idEnd + 1 < script.length && script[idEnd + 1] == '[' && (o > p || ',' !in script.substring(o + 1, idEnd))) {
                        val titleEnd = findBracketEnd(script, idEnd + 1)
                        flush()
                        tokens += Token.Tag("q", listOf(script.substring(idEnd + 2, titleEnd), script.substring(o + 1, idEnd)))
                        tokens += Token.Tag("n", emptyList())
                        i = minOf(titleEnd + 1, script.length)
                        continue
                    }
                }
            }

            val args: List<String>
            if (p < script.length && script[p] == '[') {
                val end = findBracketEnd(script, p)
                args = splitArgs(script.substring(p + 1, end))
                p = minOf(end + 1, script.length)
            } else if (name in shortDigitTags && p < script.length && script[p].isDigit()) {
                args = listOf(script[p].toString())
                p += 1
            } else {
                args = emptyList()
            }

            flush()
            tokens += Token.Tag(name, args)
            i = p
        }
        flush()
        return tokens
    }

    /**
     * `[` の位置から対応する `]` の位置を返す。`\]`（`]` の文字）と `\\`（`\` の文字。後ろの `]` は閉じかっこ）、
     * "..." で囲んだ引数の中は飛ばす。" が引用になるのは引数の頭（`[` かカンマの後。空白は飛ばす）にあるときだけで、
     * 引数の途中の " はただの文字（閉じていない " で後ろが全部引数になってしまわないように）
     */
    private fun findBracketEnd(s: String, open: Int): Int {
        var i = open + 1
        var quoted = false
        var atStart = true
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> i++
                quoted && c == '"' -> quoted = false
                quoted -> {}
                c == '\\' && i + 1 < s.length && (s[i + 1] == ']' || s[i + 1] == '\\') -> {
                    i++
                    atStart = false
                }
                c == '"' && atStart -> quoted = true
                c == ']' -> return i
                c == ',' -> atStart = true
                c != ' ' && c != '\t' -> atStart = false
            }
            i++
        }
        return s.length
    }

    /** カンマ区切り。引数の頭から "..." で囲めばカンマや ] を含められ、"" は " になる（[findBracketEnd] と同じ決まり）。 */
    fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var atStart = true
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                quoted && c == '"' -> quoted = false
                quoted -> cur.append(c)
                c == '\\' && i + 1 < s.length && s[i + 1] == ']' -> {
                    cur.append(']')
                    i++
                    atStart = false
                }
                c == '\\' && i + 1 < s.length && s[i + 1] == '\\' -> {
                    // \\ は 2 文字のまま残す（パスなど。閉じかっこを作らないだけ）
                    cur.append("\\\\")
                    i++
                    atStart = false
                }
                c == '"' && atStart && cur.isBlank() -> {
                    quoted = true
                    cur.clear()
                }
                c == ',' -> {
                    out += cur.toString()
                    cur.clear()
                    atStart = true
                }
                else -> {
                    cur.append(c)
                    if (c != ' ' && c != '\t') atStart = false
                }
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
