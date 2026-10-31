package net.pono.baseware.sakura

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * \_b[ファイル,x,y,オプション...] / \_b[ファイル,inline,オプション...] の見せ方。
 * --option=use_self_alpha（PNG のアルファで抜く）・fixed（スクロールしても動かさない。x,y のときだけ）・
 * foreground（文字の上に重ねる。x,y のとき）・background（既定。文字の下）、--clipping=左 上 右 下、--scaling=…、
 * --source=（マルチページの TIFF のページ・PSD のレイヤー・DLL / EXE のアイコンの番号。shell.SourceImages）
 */
data class BalloonImageOptions(
    val selfAlpha: Boolean = false,
    val clip: Clip? = null,
    val scaling: Scaling? = null,
    val fixed: Boolean = false,
    val foreground: Boolean = false,
    val source: String? = null,
) {
    /** 切り抜く範囲（画像の左上からのピクセル。右と下は含まない） */
    data class Clip(val left: Int, val top: Int, val right: Int, val bottom: Int)

    sealed interface Scaling {
        /** 横・縦の大きさ。[wPercent] / [hPercent] なら切り抜いた後の大きさに対する %。0 は縦横比を保ってもう一方に合わせる。負なら反転 */
        data class Size(val w: Float, val wPercent: Boolean, val h: Float, val hPercent: Boolean) : Scaling

        /** 縦横比を保ち、長辺（[long]）か短辺を [size] ピクセルにする。[shrinkOnly] は …max、[growOnly] は …min。負なら上下左右とも反転 */
        data class Edge(val size: Int, val long: Boolean, val shrinkOnly: Boolean, val growOnly: Boolean) : Scaling
    }

    /** 描く大きさ（ピクセル）と反転 */
    data class DrawSize(val width: Int, val height: Int, val flipX: Boolean, val flipY: Boolean)

    /** 切り抜いた後の大きさが [w] × [h] の画像を描く大きさ。大きさは 1～4096 に収める */
    fun drawSize(w: Int, h: Int): DrawSize {
        fun clamp(v: Float) = abs(v).roundToInt().coerceIn(MIN_SIZE, MAX_SIZE)
        return when (val s = scaling) {
            null -> DrawSize(w, h, false, false)
            is Scaling.Size -> {
                val tw = if (s.wPercent) w * s.w / 100f else s.w
                val th = if (s.hPercent) h * s.h / 100f else s.h
                when {
                    tw == 0f && th == 0f -> DrawSize(w, h, false, false)
                    tw == 0f -> DrawSize(clamp(abs(th) * w / h.coerceAtLeast(1)), clamp(th), false, th < 0)
                    th == 0f -> DrawSize(clamp(tw), clamp(abs(tw) * h / w.coerceAtLeast(1)), tw < 0, false)
                    else -> DrawSize(clamp(tw), clamp(th), tw < 0, th < 0)
                }
            }
            is Scaling.Edge -> {
                val base = (if (s.long) maxOf(w, h) else minOf(w, h)).coerceAtLeast(1)
                var k = abs(s.size).toFloat() / base
                if (s.shrinkOnly && k > 1f) k = 1f
                if (s.growOnly && k < 1f) k = 1f
                val flip = s.size < 0
                DrawSize(clamp(w * k), clamp(h * k), flip, flip)
            }
        }
    }

    companion object {
        const val MIN_SIZE = 1
        const val MAX_SIZE = 4096

        /**
         * \_b の位置引数の後ろのオプションを読む。古い書き方の opaque（--option= なし）も読む。
         * 戻り値の 2 つ目は opaque（左上の色を抜かない）
         */
        fun parse(args: List<String>): Pair<BalloonImageOptions, Boolean> {
            val flags = mutableSetOf<String>()
            var clip: Clip? = null
            var scaling: Scaling? = null
            var source: String? = null
            for (raw in args.map { it.trim() }) {
                val lower = raw.lowercase()
                when {
                    lower.startsWith("--option=") -> raw.substringAfter('=').split(',').forEach { flags += it.trim().lowercase() }
                    lower.startsWith("--clipping=") -> clip = parseClip(raw.substringAfter('=')) ?: clip
                    lower.startsWith("--scaling=") -> scaling = parseScaling(raw.substringAfter('=')) ?: scaling
                    lower.startsWith("--source=") -> source = raw.substringAfter('=').trim().takeIf { it.isNotEmpty() } ?: source
                    lower.startsWith("--") -> {}
                    else -> flags += lower
                }
            }
            return BalloonImageOptions(
                selfAlpha = "use_self_alpha" in flags,
                clip = clip,
                scaling = scaling,
                fixed = "fixed" in flags,
                foreground = "foreground" in flags && "background" !in flags,
                source = source,
            ) to ("opaque" in flags)
        }

        private fun parseClip(s: String): Clip? {
            val n = s.trim().split(Regex("""[\s,]+""")).mapNotNull { it.toIntOrNull() }
            if (n.size != 4) return null
            val c = Clip(minOf(n[0], n[2]).coerceAtLeast(0), minOf(n[1], n[3]).coerceAtLeast(0), maxOf(n[0], n[2]), maxOf(n[1], n[3]))
            return c.takeIf { it.right > it.left && it.bottom > it.top }
        }

        private val EDGE = Regex("""(long|short)?(max|min)?""")

        private fun parseScaling(s: String): Scaling? {
            val parts = s.trim().lowercase().split(Regex("""\s+"""))
            if (parts.size != 2) return null
            val edge = EDGE.matchEntire(parts[1])?.takeIf { parts[1].isNotEmpty() }
            if (edge != null) {
                // サイズはピクセルだけ（% は使えない）。long / short を省けば long
                val size = parts[0].removeSuffix("px").toIntOrNull()?.takeIf { it != 0 } ?: return null
                return Scaling.Edge(size, edge.groupValues[1] != "short", edge.groupValues[2] == "max", edge.groupValues[2] == "min")
            }
            fun dim(t: String): Pair<Float, Boolean>? = when {
                t.endsWith("%") -> t.dropLast(1).toFloatOrNull()?.let { it to true }
                else -> t.removeSuffix("px").toFloatOrNull()?.let { it to false }
            }
            val (w, wp) = dim(parts[0]) ?: return null
            val (h, hp) = dim(parts[1]) ?: return null
            return Scaling.Size(w, wp, h, hp)
        }
    }
}
