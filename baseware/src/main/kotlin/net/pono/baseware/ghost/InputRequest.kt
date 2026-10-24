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
         * sliderinput は ID, タイムアウト, 今の値, 最小, 最大。ipinput は ID, タイムアウト, 4つの数（か --text="192,168,0,1"）。
         * "--" で始まる引数（オプション）は、ipinput の --text と --timeout のほかは読み飛ばす。
         */
        fun fromOpenTag(kind: String, args: List<String>, serial: Int): InputRequest? {
            when (kind.lowercase()) {
                "communicatebox" -> return InputRequest(Kind.COMMUNICATE, "", args.getOrNull(0).orEmpty(), 0, serial)
                "teachbox" -> return InputRequest(Kind.TEACH, "", "", 0, serial)
            }
            val positional = args.filterNot { it.trim().startsWith("--") }
            val id = positional.firstOrNull() ?: return null
            if (kind.lowercase() == "ipinput") {
                fun opt(name: String) = args.map { it.trim() }.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
                val timeout = positional.getOrNull(1)?.trim()?.toLongOrNull() ?: opt("timeout")?.toLongOrNull() ?: 0L
                val parts = opt("text")?.split(',', '.')?.map { it.trim() } ?: positional.drop(2)
                val ip = (0 until 4).map { i -> parts.getOrNull(i)?.trim()?.toIntOrNull()?.coerceIn(0, 255) ?: 0 }
                return InputRequest(Kind.IP, id, "", timeout, serial, ip)
            }
            fun timeout() = positional.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            fun number(i: Int, default: Int) = positional.getOrNull(i)?.trim()?.toIntOrNull() ?: default
            val now = java.util.Calendar.getInstance()
            return when (kind.lowercase()) {
                "inputbox" -> InputRequest(Kind.INPUT, id, positional.getOrNull(2).orEmpty(), timeout(), serial)
                "passwordinput" -> InputRequest(Kind.PASSWORD, id, positional.getOrNull(2).orEmpty(), timeout(), serial)
                "textareabox" -> InputRequest(Kind.TEXTAREA, id, positional.getOrNull(2).orEmpty().replace('\u0001', '\n'), timeout(), serial)
                "dateinput" -> InputRequest(
                    Kind.DATE, id, "", timeout(), serial,
                    listOf(number(2, now.get(java.util.Calendar.YEAR)), number(3, now.get(java.util.Calendar.MONTH) + 1), number(4, now.get(java.util.Calendar.DAY_OF_MONTH))),
                )
                "timeinput" -> InputRequest(
                    Kind.TIME, id, "", timeout(), serial,
                    listOf(number(2, now.get(java.util.Calendar.HOUR_OF_DAY)), number(3, now.get(java.util.Calendar.MINUTE)), number(4, now.get(java.util.Calendar.SECOND))),
                )
                "sliderinput" -> {
                    val min = number(3, 0)
                    val max = maxOf(number(4, 100), min)
                    InputRequest(Kind.SLIDER, id, "", timeout(), serial, listOf(number(2, min).coerceIn(min, max), min, max))
                }
                else -> null
            }
        }
    }
}
