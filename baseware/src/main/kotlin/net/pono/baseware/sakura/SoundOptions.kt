package net.pono.baseware.sakura

/**
 * `\![sound,play / loop / load / option,ファイル,オプション...]` のオプション（SSP と同じ）。
 * [volume] は 0〜100、[balance] は -100（左）〜100（右）、[rate] は 100 が普通の速さ、[seekMs] は再生を始める位置（ミリ秒）で、
 * [seekRelative] なら今の位置からの相対（負でもよい）
 */
data class SoundOptions(
    val volume: Int? = null,
    val balance: Int? = null,
    val rate: Int? = null,
    val seekMs: Long? = null,
    val seekRelative: Boolean = false,
) {
    val isEmpty: Boolean get() = this == SoundOptions()

    companion object {
        /** "--volume=50" などを読む。読めないものは飛ばす */
        fun parse(args: List<String>): SoundOptions {
            var o = SoundOptions()
            for (raw in args) {
                val arg = raw.trim()
                if (!arg.startsWith("--")) continue
                val name = arg.substring(2).substringBefore('=').lowercase()
                val value = arg.substringAfter('=', "").trim()
                o = when (name) {
                    "volume" -> value.toIntOrNull()?.let { o.copy(volume = it.coerceIn(0, 100)) } ?: o
                    "balance" -> value.toIntOrNull()?.let { o.copy(balance = it.coerceIn(-100, 100)) } ?: o
                    "rate" -> value.toIntOrNull()?.let { o.copy(rate = it.coerceIn(1, 10_000)) } ?: o
                    "seektime" -> {
                        val relative = value.startsWith("@")
                        o.copy(seekMs = seekTime(value.removePrefix("@"), relative), seekRelative = relative)
                    }
                    else -> o
                }
            }
            return o
        }

        /**
         * 「:」が無ければミリ秒、あれば [[時:]分:]秒[.ミリ秒]。"90500"・"1:30.5"・"0:01:30.5" はどれも 1分30.5秒。
         * 読めないものと負の値は 0（先頭）。[relative] のときだけ負を許す
         */
        fun seekTime(text: String, relative: Boolean = false): Long {
            if (!relative && text.startsWith("-")) return 0
            val negative = text.startsWith("-")
            val t = text.removePrefix("-").removePrefix("+")
            val ms = if (':' !in t) {
                t.toLongOrNull()
            } else {
                val parts = t.split(':')
                val seconds = parts.last().split('.')
                val whole = seconds[0].toLongOrNull()
                val fraction = seconds.getOrNull(1)?.take(3)?.padEnd(3, '0')?.toLongOrNull() ?: 0
                val upper = parts.dropLast(1).map { it.toLongOrNull() }
                if (whole == null || upper.any { it == null } || upper.size > 2) {
                    null
                } else {
                    upper.fold(0L) { acc, v -> acc * 60 + v!! } * 60_000 + whole * 1000 + fraction
                }
            } ?: return 0
            return if (negative) -ms else ms.coerceAtLeast(0)
        }
    }
}
