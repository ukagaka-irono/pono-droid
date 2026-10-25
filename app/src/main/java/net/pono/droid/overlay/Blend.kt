package net.pono.droid.overlay

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * SERIKO の blend-* メソッド。重ねる絵 [src] を、下の絵 [dst] の (x, y) に、Photoshop などと同じ合成で重ねる
 * （計算は W3C の Compositing and Blending の式）。-fast は下の絵の不透明な所にだけ重ね、下の絵の透明度を変えない。
 * [glow]（-glow。CLIP STUDIO PAINT の「発光」）は、重ねる色に先に不透明度を掛けてから合成し、その結果をそのまま使う
 * （半透明の所も、混ぜて薄めずに光らせる）
 */
object Blend {
    fun apply(dst: Bitmap, src: Bitmap, x: Int, y: Int, name: String, fast: Boolean, glow: Boolean = false) {
        val left = max(0, x)
        val top = max(0, y)
        val right = min(dst.width, x + src.width)
        val bottom = min(dst.height, y + src.height)
        if (left >= right || top >= bottom) return
        val w = right - left
        val h = bottom - top
        val d = IntArray(w * h)
        val s = IntArray(w * h)
        dst.getPixels(d, 0, w, left, top, w, h)
        src.getPixels(s, 0, w, left - x, top - y, w, h)
        val separable = SEPARABLE[name]
        val b = DoubleArray(3)
        for (i in d.indices) {
            val sp = s[i]
            var sa = (sp ushr 24) / 255.0
            if (sa == 0.0) continue
            val dp = d[i]
            val da = (dp ushr 24) / 255.0
            if (fast && da == 0.0) continue
            // dither は重ねる絵の透明度を、まだらな点の有無に置き換える
            if (name == "dither") {
                if (Random.nextDouble() >= sa) continue
                d[i] = sp or 0xFF000000.toInt()
                continue
            }
            val cs = doubleArrayOf(((sp shr 16) and 0xFF) / 255.0, ((sp shr 8) and 0xFF) / 255.0, (sp and 0xFF) / 255.0)
            val straight = cs.copyOf()
            if (glow) for (c in 0..2) cs[c] *= sa
            val cb = doubleArrayOf(((dp shr 16) and 0xFF) / 255.0, ((dp shr 8) and 0xFF) / 255.0, (dp and 0xFF) / 255.0)
            if (separable != null) {
                for (c in 0..2) b[c] = separable(cb[c], cs[c]).coerceIn(0.0, 1.0)
            } else {
                nonSeparable(name, cb, cs, b)
            }
            val ao: Double
            val out = DoubleArray(3)
            if (glow) {
                // 下の絵のある所は合成の結果そのまま、無い所は重ねる絵のまま
                ao = if (fast) da else sa + da * (1 - sa)
                for (c in 0..2) out[c] = if (ao == 0.0) 0.0 else if (fast) b[c] else (da * b[c] + (1 - da) * sa * straight[c]) / ao
            } else if (fast) {
                // 下の絵の透明度のまま、色だけ混ぜる
                ao = da
                for (c in 0..2) out[c] = sa * b[c] + (1 - sa) * cb[c]
            } else {
                ao = sa + da * (1 - sa)
                for (c in 0..2) {
                    val mixed = (1 - da) * cs[c] + da * b[c]
                    out[c] = if (ao == 0.0) 0.0 else (sa * mixed + (1 - sa) * da * cb[c]) / ao
                }
            }
            d[i] = ((ao * 255 + 0.5).toInt().coerceIn(0, 255) shl 24) or
                ((out[0] * 255 + 0.5).toInt().coerceIn(0, 255) shl 16) or
                ((out[1] * 255 + 0.5).toInt().coerceIn(0, 255) shl 8) or
                (out[2] * 255 + 0.5).toInt().coerceIn(0, 255)
        }
        dst.setPixels(d, 0, w, left, top, w, h)
    }

    private fun colorDodge(cb: Double, cs: Double) = when {
        cb == 0.0 -> 0.0
        cs >= 1.0 -> 1.0
        else -> min(1.0, cb / (1 - cs))
    }

    private fun colorBurn(cb: Double, cs: Double) = when {
        cb >= 1.0 -> 1.0
        cs <= 0.0 -> 0.0
        else -> 1 - min(1.0, (1 - cb) / cs)
    }

    private fun hardLight(cb: Double, cs: Double) = if (cs <= 0.5) cb * 2 * cs else {
        val s = 2 * cs - 1
        cb + s - cb * s
    }

    private fun softLight(cb: Double, cs: Double): Double {
        if (cs <= 0.5) return cb - (1 - 2 * cs) * cb * (1 - cb)
        val d = if (cb <= 0.25) ((16 * cb - 12) * cb + 4) * cb else sqrt(cb)
        return cb + (2 * cs - 1) * (d - cb)
    }

    private fun vividLight(cb: Double, cs: Double) = if (cs <= 0.5) colorBurn(cb, 2 * cs) else colorDodge(cb, 2 * (cs - 0.5))

    /** 1色ずつ計算できる合成 */
    private val SEPARABLE: Map<String, (Double, Double) -> Double> = mapOf(
        "multiply" to { cb, cs -> cb * cs },
        "screen" to { cb, cs -> cb + cs - cb * cs },
        "overlay" to { cb, cs -> hardLight(cs, cb) },
        "darken" to { cb, cs -> min(cb, cs) },
        "lighten" to { cb, cs -> max(cb, cs) },
        "color-dodge" to ::colorDodge,
        "color-burn" to ::colorBurn,
        "hard-light" to ::hardLight,
        "soft-light" to ::softLight,
        "difference" to { cb, cs -> abs(cb - cs) },
        "exclusion" to { cb, cs -> cb + cs - 2 * cb * cs },
        "add" to { cb, cs -> min(1.0, cb + cs) },
        "subtract" to { cb, cs -> max(0.0, cb - cs) },
        "divide" to { cb, cs -> if (cs <= 0.0) 1.0 else min(1.0, cb / cs) },
        "linear-burn" to { cb, cs -> max(0.0, cb + cs - 1) },
        "linear-light" to { cb, cs -> cb + 2 * cs - 1 },
        "vivid-light" to ::vividLight,
        "pin-light" to { cb, cs -> if (cs <= 0.5) min(cb, 2 * cs) else max(cb, 2 * cs - 1) },
        "hard-mix" to { cb, cs -> if (vividLight(cb, cs) < 0.5) 0.0 else 1.0 },
    )

    private fun lum(c: DoubleArray) = 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2]

    private fun clipColor(c: DoubleArray) {
        val l = lum(c)
        val n = minOf(c[0], c[1], c[2])
        val x = maxOf(c[0], c[1], c[2])
        for (i in 0..2) {
            if (n < 0) c[i] = l + (c[i] - l) * l / (l - n)
            if (x > 1) c[i] = l + (c[i] - l) * (1 - l) / (x - l)
        }
    }

    private fun setLum(c: DoubleArray, l: Double): DoubleArray {
        val d = l - lum(c)
        val out = doubleArrayOf(c[0] + d, c[1] + d, c[2] + d)
        clipColor(out)
        return out
    }

    private fun sat(c: DoubleArray) = maxOf(c[0], c[1], c[2]) - minOf(c[0], c[1], c[2])

    private fun setSat(c: DoubleArray, s: Double): DoubleArray {
        val out = c.copyOf()
        val max = maxOf(c[0], c[1], c[2])
        val min = minOf(c[0], c[1], c[2])
        for (i in 0..2) out[i] = if (max > min) (c[i] - min) * s / (max - min) else 0.0
        return out
    }

    /** 色相・彩度・カラー・輝度と、比較（暗い色・明るい色）の合成。知らない名前はふつうに重ねる */
    private fun nonSeparable(name: String, cb: DoubleArray, cs: DoubleArray, out: DoubleArray) {
        val r = when (name) {
            "hue" -> setLum(setSat(cs, sat(cb)), lum(cb))
            "saturation" -> setLum(setSat(cb, sat(cs)), lum(cb))
            "color" -> setLum(cs, lum(cb))
            "luminosity" -> setLum(cb, lum(cs))
            "darker-color" -> if (cs.sum() < cb.sum()) cs else cb
            "lighter-color" -> if (cs.sum() > cb.sum()) cs else cb
            else -> cs
        }
        for (i in 0..2) out[i] = r[i].coerceIn(0.0, 1.0)
    }
}
