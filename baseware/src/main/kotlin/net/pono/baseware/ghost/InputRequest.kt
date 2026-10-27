package net.pono.baseware.ghost

/**
 * ゴーストがユーザーに何かを入れてもらうための箱（SSP の \![open,inputbox,...] など）。
 * 表示はプラットフォーム側で、答えは [GhostController.onInputSubmit] / [GhostController.onInputCancel] で返す。
 */
data class InputRequest(
    val kind: Kind,
    /** inputbox などの ID。communicatebox と teachbox では空 */
    val id: String,
    /** 最初から入っている文字（textareabox は改行入り） */
    val text: String,
    /** これを過ぎたら timeout で取り消す。0 以下なら待ち続ける */
    val timeoutMs: Long,
    /** 同じ内容で開き直したときも区別するための通し番号 */
    val serial: Int,
    /** DATE は 年,月,日、TIME は 時,分,秒、SLIDER は 今の値,最小,最大 */
    val values: List<Int> = emptyList(),
    /** --placeholder=案内文。入力欄が空のときだけ薄く出す（答えには入らない。textareabox は改行入り） */
    val placeholder: String? = null,
    /** --option=noclose。決めた後も閉じずに出し続ける */
    val noClose: Boolean = false,
    /** --option=noclear。閉じずに出し続けるとき、打った文字を消さずに残す */
    val noClear: Boolean = false,
    /** --limit=。打てる文字の数（textareabox は改行も 1 文字）。null なら制限なし */
    val limit: Int? = null,
    /** --balloon=。入力の箱の画像 balloonc?.png の番号。null なら箱ごとの既定（1行は 0、textareabox は 5） */
    val balloonId: Int? = null,
    /** --reference=。答えのイベントの後ろに足す追加情報（書いた順） */
    val references: List<String> = emptyList(),
) {
    enum class Kind {
        /** 1行入力。答えは ID のイベント（On で始まるとき）か OnUserInput */
        INPUT,

        /** 伏せ字の1行入力。イベントは INPUT と同じ */
        PASSWORD,

        /** ゴーストに話しかける。答えは OnCommunicate */
        COMMUNICATE,

        /** ゴーストに教える。答えは OnTeach */
        TEACH,

        /** 年月日。答えは "年,月,日" */
        DATE,

        /** 時分秒。答えは "時,分,秒" */
        TIME,

        /** スライダー。答えは数、Reference2 に "最小,最大" */
        SLIDER,

        /** 何行かの文。答えは改行を 0x01 にした文で、イベントは OnTextAreaInput */
        TEXTAREA,

        /** IP アドレス（4つの数）。答えは "192.168.0.1" で、イベントは INPUT と同じ */
        IP,
    }

    /** 答えを送るときの値 */
    val isNumeric: Boolean get() = kind == Kind.DATE || kind == Kind.TIME || kind == Kind.SLIDER || kind == Kind.IP

    companion object {
        /**
         * `\![open,種類,引数...]` の種類と引数から作る。扱えない種類なら null。
         * inputbox / passwordinput / textareabox は 引数 = ID, タイムアウト（ミリ秒）, 最初の文字。
         * dateinput は ID, タイムアウト, 年, 月, 日。timeinput は ID, タイムアウト, 時, 分, 秒。
         * sliderinput は ID, タイムアウト, 今の値, 最小, 最大。ipinput は ID, タイムアウト, 4つの数。communicatebox は 最初の文字。
         * 後ろには InputBox オプション（--timeout= / --text= が位置引数の代わりになり、ほかに --option=noclose,noclear・
         * --placeholder=・--limit=・--balloon=・--reference=）を書ける。数を入れる箱の --text= は "50,0,100" のようにカンマで区切る
         */
        fun fromOpenTag(kind: String, args: List<String>, serial: Int): InputRequest? {
            val o = Options(args)
            val positional = args.filterNot { it.trim().startsWith("--") }
            when (kind.lowercase()) {
                "communicatebox" -> return o.apply(InputRequest(Kind.COMMUNICATE, "", o.text ?: positional.getOrNull(0).orEmpty(), o.timeout ?: 0, serial))
                "teachbox" -> return o.apply(InputRequest(Kind.TEACH, "", o.text.orEmpty(), o.timeout ?: 0, serial))
            }
            val id = positional.firstOrNull() ?: return null
            val timeout = o.timeout ?: positional.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            val text = o.text ?: positional.getOrNull(2).orEmpty()
            // 数を入れる箱は、--text= のカンマ区切りか、3つ目からの位置引数
            val parts = o.text?.split(',')?.map { it.trim() } ?: positional.drop(2)
            fun number(i: Int, default: Int) = parts.getOrNull(i)?.trim()?.toIntOrNull() ?: default
            val now = java.util.Calendar.getInstance()
            val request = when (kind.lowercase()) {
                "inputbox" -> InputRequest(Kind.INPUT, id, text, timeout, serial)
                "passwordinput" -> InputRequest(Kind.PASSWORD, id, text, timeout, serial)
                "textareabox" -> InputRequest(Kind.TEXTAREA, id, text.replace('\u0001', '\n'), timeout, serial)
                "dateinput" -> InputRequest(
                    Kind.DATE, id, "", timeout, serial,
                    listOf(number(0, now.get(java.util.Calendar.YEAR)), number(1, now.get(java.util.Calendar.MONTH) + 1), number(2, now.get(java.util.Calendar.DAY_OF_MONTH))),
                )
                "timeinput" -> InputRequest(
                    Kind.TIME, id, "", timeout, serial,
                    listOf(number(0, now.get(java.util.Calendar.HOUR_OF_DAY)), number(1, now.get(java.util.Calendar.MINUTE)), number(2, now.get(java.util.Calendar.SECOND))),
                )
                "sliderinput" -> {
                    val min = number(1, 0)
                    val max = maxOf(number(2, 100), min)
                    InputRequest(Kind.SLIDER, id, "", timeout, serial, listOf(number(0, min).coerceIn(min, max), min, max))
                }
                "ipinput" -> {
                    // --text= は "192.168.0.1" とも書ける
                    val ip = o.text?.split(',', '.')?.map { it.trim() } ?: positional.drop(2)
                    InputRequest(Kind.IP, id, "", timeout, serial, (0 until 4).map { i -> ip.getOrNull(i)?.toIntOrNull()?.coerceIn(0, 255) ?: 0 })
                }
                else -> null
            } ?: return null
            // --placeholder= は文字を打つ箱だけ。textareabox は \1 で改行
            val placeholder = if (request.kind == Kind.INPUT || request.kind == Kind.PASSWORD || request.kind == Kind.TEXTAREA) o.placeholder else null
            return o.apply(request.copy(placeholder = placeholder?.let { if (request.kind == Kind.TEXTAREA) it.replace('\u0001', '\n') else it }))
        }

        /** InputBox オプション（同じ名前は後のものが有効。--option= と --reference= は書いた分だけ足す） */
        private class Options(args: List<String>) {
            private val pairs = args.map { it.trim() }.filter { it.startsWith("--") }.map { it.substring(2).substringBefore('=').lowercase() to it.substringAfter('=', "") }
            private fun last(name: String) = pairs.lastOrNull { it.first == name }?.second
            val text = last("text")
            val timeout = last("timeout")?.toLongOrNull()
            val placeholder = last("placeholder")?.take(MAX_PLACEHOLDER)?.takeIf { it.isNotEmpty() }
            private val flags = pairs.filter { it.first == "option" }.flatMap { it.second.split(',') }.map { it.trim().lowercase() }.toSet()
            private val limit = last("limit")?.toIntOrNull()?.takeIf { it > 0 }
            private val balloon = last("balloon")?.toIntOrNull()?.takeIf { it >= 0 }
            private val references = pairs.filter { it.first == "reference" }.map { it.second }.take(MAX_REFERENCES)

            fun apply(r: InputRequest) = r.copy(
                noClose = "noclose" in flags, noClear = "noclear" in flags, limit = limit, balloonId = balloon, references = references,
                text = limit?.let { r.text.take(it) } ?: r.text,
            )
        }

        /** 案内文の長さの上限 */
        private const val MAX_PLACEHOLDER = 200

        /** --reference= の数の上限 */
        private const val MAX_REFERENCES = 16
    }
}
