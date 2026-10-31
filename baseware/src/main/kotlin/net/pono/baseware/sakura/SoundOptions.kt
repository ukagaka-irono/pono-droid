package net.pono.baseware.sakura

/**
 * `\![sound,play / loop / load / option,ファイル,オプション...]` のオプション（SSP と同じ）。
 * [volume] は 0〜100、[balance] は -100（左）〜100（右）、[rate] は 100 が普通の速さ、[seekMs] は再生を始める位置（ミリ秒）で、
 * [seekRelative] なら今の位置からの相対（負でもよい）。
 * [spectrumMs] は OnSoundSpectrum を送る間隔（20〜1000。0 で止める）、[spectrumBands] は帯域の数（0・5・10・15・31）、
 * [spectrumHoldMs] はピークホールドの時間（0〜10000）。[window] は --window（シークバーのついた再生のウインドウを出すか。
 * 書いていなければ動画だけ出す）、[soundOnly] は --sound-only（動画の音だけ鳴らし、絵は出さない）
 */
data class SoundOptions(
    val volume: Int? = null,
    val balance: Int? = null,
    val rate: Int? = null,
    val seekMs: Long? = null,
    val seekRelative: Boolean = false,
    val spectrumMs: Int? = null,
    val spectrumBands: Int? = null,
    val spectrumHoldMs: Int? = null,
    val window: Boolean? = null,
    val soundOnly: Boolean? = null,
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
                    // 範囲の外は端に丸める（0 は止める）
                    "spectrum" -> value.toIntOrNull()?.let { o.copy(spectrumMs = if (it <= 0) 0 else it.coerceIn(20, 1000)) } ?: o
                    "spectrum-bands" -> value.toIntOrNull()?.let { o.copy(spectrumBands = SoundSpectrum.nearestBands(it)) } ?: o
                    "spectrum-hold" -> value.toIntOrNull()?.let { o.copy(spectrumHoldMs = it.coerceIn(0, 10_000)) } ?: o
                    "window" -> flag(value)?.let { o.copy(window = it) } ?: o
                    "sound-only" -> flag(value)?.let { o.copy(soundOnly = it) } ?: o
                    else -> o
                }
            }
            return o
        }

        private fun flag(value: String): Boolean? = when (value.lowercase()) {
            "true", "on", "1" -> true
            "false", "off", "0" -> false
            else -> null
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
