package net.pono.baseware.sakura

/** さくらスクリプトの字句。 */
sealed interface Token {
    /** [literal] は \_? ... \_? の中。タグも環境変数もそのまま文字として出す */
    data class Text(val text: String, val literal: Boolean = false) : Token

    /** `\s[10]` なら name="s", args=["10"]。`\_w[100]` なら name="_w"。 */
    data class Tag(val name: String, val args: List<String> = emptyList()) : Token
}

object SakuraScript {
    /** `\%` を表す私用領域の文字。[ScriptPlayer] が環境変数を展開したあと % に戻す。 */
    const val ESCAPED_PERCENT = ''

    /** 引数を [] で取りうるタグ。`\s0` のような1桁の省略形を持つもの以外は、[] が無ければ引数なし。 */
    private val shortDigitTags = setOf("s", "w", "p", "b", "i")

    fun tokenize(script: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                tokens += Token.Text(text.toString())
                text.clear()
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
            // エスケープ。\% は環境変数の展開から外すため、展開が済むまで別の文字にしておく
            if (next == '\\' || next == '%' || next == ']') {
                text.append(if (next == '%') ESCAPED_PERCENT else next)
                i += 2
                continue
            }

            var p = i + 1
            val name: String = when {
                script.startsWith("__", p) -> {
                    // \__q, \__w, \__c など。名前は英字だけ（\__v のすぐ後に続く日本語は文字）
                    val start = p
                    p += 2
                    while (p < script.length && (script[p] in 'a'..'z' || script[p] in 'A'..'Z')) p++
                    script.substring(start, p)
                }
                next == '_' -> {
                    // \_w, \_q, \_a など
                    p += 2
                    script.substring(i + 1, minOf(p, script.length))
                }
                else -> {
                    // \0, \h, \s, \!, \- など1文字
                    p += 1
                    next.toString()
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

    /** `[` の位置から対応する `]` の位置を返す。`\]` と "..." の中は飛ばす。 */
    private fun findBracketEnd(s: String, open: Int): Int {
        var i = open + 1
        var quoted = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == ']' -> i++
                c == '"' -> quoted = !quoted
                c == ']' && !quoted -> return i
            }
            i++
        }
        return s.length
    }

    /** カンマ区切り。"..." で囲めばカンマを含められ、"" は " になる。 */
    fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' && quoted && i + 1 < s.length && s[i + 1] == '"' -> {
                    cur.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == '\\' && i + 1 < s.length && s[i + 1] == ']' -> {
                    cur.append(']')
                    i++
                }
                c == ',' && !quoted -> {
                    out += cur.toString()
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
