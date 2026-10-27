package net.pono.baseware.sakura

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * サウンドの --spectrum= の解析（OnSoundSpectrum）。帯域は ISO の中心周波数（5・10・15・31 帯域）で、
 * レベルは振幅 1.0 の正弦波を 100、-60dB を 0 とした値。上がるときはすぐ、下がるときは 500 ミリ秒で 100 から 0 まで落ちる
 */
class SoundSpectrum(bands: Int, private val holdMs: Long) {
    val bands: Int = nearestBands(bands)

    private val centers = CENTERS.getValue(this.bands)

    /** 帯域の境目（隣り合う中心の幾何平均。両端は外へ同じ倍率で延ばす） */
    private val edges: DoubleArray = DoubleArray(centers.size + 1).also { e ->
        for (i in 1 until centers.size) e[i] = sqrt(centers[i - 1] * centers[i])
        if (centers.size >= 2) {
            e[0] = centers[0] * centers[0] / e[1]
            e[centers.size] = centers.last() * centers.last() / e[centers.size - 1]
        }
    }

    private var bandLevels = DoubleArray(this.bands)
    private var channelLevels = DoubleArray(0)
    private val bandPeaks = Peaks(this.bands)
    private var channelPeaks = Peaks(0)
    private var lastMs = Long.MIN_VALUE

    /** 1回分の通知の中身。並びは OnSoundSpectrum の Reference2～5 */
    class Frame(val channels: List<Int>, val channelPeaks: List<Int>, val bands: List<Int>, val bandPeaks: List<Int>)

    /**
     * [samples]（チャンネルごとの、今の位置を含む窓の波形。-1～1）と [sampleRate] から、今の値を求める。[nowMs] は落ちる速さとホールドに使う。
     * [samples] が空（波形を取り出せない）なら全部 0 から落ちる
     */
    fun analyze(samples: List<FloatArray>, sampleRate: Int, nowMs: Long): Frame {
        val channels = samples.take(MAX_CHANNELS)
        val newChannel = DoubleArray(maxOf(channels.size, 1)) { i -> channels.getOrNull(i)?.let { level(sineAmplitudeOfRms(it)) } ?: 0.0 }
        val newBands = if (bands == 0 || channels.isEmpty()) DoubleArray(bands) else bandLevels(mix(channels), sampleRate)
        val dt = if (lastMs == Long.MIN_VALUE) 0L else (nowMs - lastMs).coerceAtLeast(0)
        lastMs = nowMs
        if (channelLevels.size != newChannel.size) {
            channelLevels = DoubleArray(newChannel.size)
            channelPeaks = Peaks(newChannel.size)
        }
        channelLevels = fall(channelLevels, newChannel, dt)
        bandLevels = fall(bandLevels, newBands, dt)
        return Frame(
            channelLevels.map(::round), channelPeaks.update(channelLevels, nowMs, holdMs).map(::round),
            bandLevels.map(::round), bandPeaks.update(bandLevels, nowMs, holdMs).map(::round),
        )
    }

    /** 止めるとき・鳴り終わったときの、全部 0 の通知 */
    fun zero(): Frame {
        val n = maxOf(channelLevels.size, 1)
        return Frame(List(n) { 0 }, List(n) { 0 }, List(bands) { 0 }, List(bands) { 0 })
    }

    private fun round(v: Double) = (v + 0.5).toInt().coerceIn(0, 100)

    /** 上がるときはすぐ、下がるときは 100 から 0 まで [FALL_MS] の速さ */
    private fun fall(old: DoubleArray, new: DoubleArray, dt: Long): DoubleArray =
        DoubleArray(new.size) { i -> maxOf(new[i], (old.getOrElse(i) { 0.0 }) - 100.0 * dt / FALL_MS) }

    private class Peaks(n: Int) {
        private val values = DoubleArray(n)
        private val times = LongArray(n)

        fun update(levels: DoubleArray, now: Long, hold: Long): DoubleArray {
            for (i in values.indices) {
                // 新しい山か、ホールドの時間が過ぎたら今の値にする
                if (levels[i] >= values[i] || now - times[i] >= hold) {
                    values[i] = levels[i]
                    times[i] = now
                }
            }
            return values.copyOf()
        }
    }

    private fun mix(channels: List<FloatArray>): FloatArray {
        val n = channels.minOf { it.size }
        return FloatArray(n) { i -> (channels.sumOf { it[i].toDouble() } / channels.size).toFloat() }
    }

    /** 帯域ごとのレベル。Hann 窓の FFT のエネルギーを帯域ごとに足し、同じエネルギーの正弦波の振幅にする */
    private fun bandLevels(mono: FloatArray, sampleRate: Int): DoubleArray {
        val n = windowSize(sampleRate)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        // 窓は波形の最後の n 点（足りなければ前を 0 で埋める）
        val offset = mono.size - n
        var windowEnergy = 0.0
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            windowEnergy += w * w
            re[i] = (mono.getOrElse(offset + i) { 0f }) * w
        }
        fft(re, im)
        val binHz = sampleRate.toDouble() / n
        val nyquist = sampleRate / 2.0
        val out = DoubleArray(bands)
        for (b in 0 until bands) {
            val lo = edges[b]
            val hi = edges[b + 1]
            // 範囲の半分以上がナイキスト周波数より上の帯域は 0
            if (hi - nyquist > (hi - lo) / 2) continue
            var energy = 0.0
            var k = maxOf(1, Math.ceil(lo / binHz).toInt())
            while (k * binHz < hi && k <= n / 2) {
                energy += re[k] * re[k] + im[k] * im[k]
                k++
            }
            out[b] = level(sqrt(4 * energy / (n * windowEnergy)))
        }
        return out
    }

    companion object {
        /** チャンネルは 8 個まで */
        const val MAX_CHANNELS = 8

        /** 100 から 0 まで落ちる時間 */
        const val FALL_MS = 500.0

        private val CENTERS: Map<Int, DoubleArray> = mapOf(
            0 to DoubleArray(0),
            5 to doubleArrayOf(63.0, 250.0, 1000.0, 4000.0, 16000.0),
            10 to doubleArrayOf(32.0, 64.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0),
            15 to doubleArrayOf(25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0),
            31 to doubleArrayOf(
                20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
                800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0,
            ),
        )

        /** 0・5・10・15・31 のうち、いちばん近い数（同じだけ離れていれば小さい方） */
        fun nearestBands(n: Int): Int = CENTERS.keys.sorted().minBy { Math.abs(it - n) }

        /** 帯域の中心と範囲（テストと説明のため） */
        fun ranges(bands: Int): List<Pair<Double, Double>> {
            val s = SoundSpectrum(bands, 0)
            return (0 until s.bands).map { s.edges[it] to s.edges[it + 1] }
        }

        /** 解析の窓の点の数。約 85 ミリ秒ぶんの 2 の累乗（44.1kHz・48kHz では 4096） */
        fun windowSize(sampleRate: Int): Int {
            val want = (sampleRate * 0.085).toInt().coerceAtLeast(64)
            return Integer.highestOneBit(want).let { if (it < want) it * 2 else it }
        }

        /** 振幅（1.0 が満点の正弦波）を 0～100 のレベルに（-60dB が 0） */
        fun level(amplitude: Double): Double {
            if (amplitude <= 0.0) return 0.0
            return ((20 * log10(amplitude) + 60) / 60 * 100).coerceIn(0.0, 100.0)
        }

        /** 波形の実効値から、同じ実効値の正弦波の振幅 */
        private fun sineAmplitudeOfRms(x: FloatArray): Double {
            if (x.isEmpty()) return 0.0
            val rms = sqrt(x.sumOf { it.toDouble() * it } / x.size)
            return rms * sqrt(2.0)
        }

        /** 基数 2 の FFT（その場で） */
        private fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    re[i] = re[j].also { re[j] = re[i] }
                    im[i] = im[j].also { im[j] = im[i] }
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang)
                val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]
                        val ui = im[i + k]
                        val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                        val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                        re[i + k] = ur + vr
                        im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr
                        im[i + k + len / 2] = ui - vi
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
