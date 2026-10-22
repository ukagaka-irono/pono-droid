package net.pono.baseware.ghost

/**
 * ゴーストがユーザーに文字を入れてもらうための箱（SSP の \![open,inputbox,...] など）。
 * 表示はプラットフォーム側で、答えは [GhostController.onInputSubmit] / [GhostController.onInputCancel] で返す。
 */
data class InputRequest(
    val kind: Kind,
    /** inputbox / passwordinput の ID。communicatebox と teachbox では空 */
    val id: String,
    /** 最初から入っている文字 */
    val text: String,
    /** これを過ぎたら timeout で取り消す。0 以下なら待ち続ける */
    val timeoutMs: Long,
    /** 同じ内容で開き直したときも区別するための通し番号 */
    val serial: Int,
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
    }

    companion object {
        /**
         * `\![open,種類,引数...]` の種類と引数から作る。扱えない種類なら null。
         * inputbox / passwordinput は 引数 = ID, タイムアウト（ミリ秒）, 最初の文字。
         */
        fun fromOpenTag(kind: String, args: List<String>, serial: Int): InputRequest? {
            fun timeout(i: Int) = args.getOrNull(i)?.trim()?.toLongOrNull() ?: 0L
            return when (kind.lowercase()) {
                "inputbox" -> args.firstOrNull()?.let { InputRequest(Kind.INPUT, it, args.getOrNull(2).orEmpty(), timeout(1), serial) }
                "passwordinput" -> args.firstOrNull()?.let { InputRequest(Kind.PASSWORD, it, args.getOrNull(2).orEmpty(), timeout(1), serial) }
                "communicatebox" -> InputRequest(Kind.COMMUNICATE, "", args.getOrNull(0).orEmpty(), 0, serial)
                "teachbox" -> InputRequest(Kind.TEACH, "", "", 0, serial)
                else -> null
            }
        }
    }
}
