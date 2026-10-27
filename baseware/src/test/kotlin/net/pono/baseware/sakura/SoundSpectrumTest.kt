package net.pono.baseware.sakura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** OnSoundSpectrum の帯域・レベル・落ち方・ピークホールド（ukadoc の表と例） */
class SoundSpectrumTest {
    private fun sine(freq: Double, amp: Double, rate: Int, n: Int) = FloatArray(n) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    @Test
    fun bandTables() {
        assertEquals(listOf(0, 5, 10, 15, 31, 15, 15, 31), listOf(0, 4, 10, 14, 31, 16, 20, 64).map(SoundSpectrum::nearestBands))
        val ten = SoundSpectrum.ranges(10).map { (lo, hi) -> lo.roundToInt() to hi.roundToInt() }
        assertEquals(23 to 45, ten.first())
        assertEquals(707 to 1414, ten[5])
        assertEquals(11314 to 22627, ten.last())
        val five = SoundSpectrum.ranges(5).map { (lo, hi) -> lo.roundToInt() to hi.roundToInt() }
        assertEquals(listOf(32 to 125, 125 to 500, 500 to 2000, 2000 to 8000, 8000 to 32000), five.map { (lo, hi) -> (lo / 1) to hi }.let { it })
        assertEquals(4096, SoundSpectrum.windowSize(44100))
        assertEquals(4096, SoundSpectrum.windowSize(48000))
        assertEquals(2048, SoundSpectrum.windowSize(22050))
    }

    @Test
    fun readsSpectrumOptions() {
        val o = SoundOptions.parse(listOf("--spectrum=5", "--spectrum-bands=20", "--spectrum-hold=99999", "--window=true", "--sound-only=off"))
        assertEquals(20, o.spectrumMs)
        assertEquals(15, o.spectrumBands)
        assertEquals(10_000, o.spectrumHoldMs)
        assertEquals(true, o.window)
        assertEquals(false, o.soundOnly)
        assertEquals(0, SoundOptions.parse(listOf("--spectrum=0")).spectrumMs)
        assertEquals(1000, SoundOptions.parse(listOf("--spectrum=5000")).spectrumMs)
    }

    @Test
    fun sineLevels() {
        val s = SoundSpectrum(10, 500)
        // 振幅 0.5（-6dB）の 1kHz の正弦波はおよそ 90。全体も同じ尺度
        val f = s.analyze(listOf(sine(1000.0, 0.5, 44100, 4096)), 44100, 0)
        assertEquals(90.0, f.channels.single().toDouble(), 1.0)
        assertEquals(90.0, f.bands[5].toDouble(), 1.5)
        assertTrue(f.bands.filterIndexed { i, _ -> i != 4 && i != 5 && i != 6 }.all { it < 40 })
        // ステレオはチャンネルごと
        val st = SoundSpectrum(0, 0).analyze(listOf(sine(440.0, 1.0, 48000, 4096), sine(440.0, 0.1, 48000, 4096)), 48000, 0)
        assertEquals(listOf(100, 67), st.channels)
        assertTrue(st.bands.isEmpty())
        // ナイキスト周波数より上の帯域は 0（22.05kHz では 16kHz の帯域）
        val low = SoundSpectrum(10, 0).analyze(listOf(sine(10000.0, 1.0, 22050, 2048)), 22050, 0)
        assertEquals(0, low.bands[9])
    }

    @Test
    fun fallsAndHolds() {
        val s = SoundSpectrum(5, 500)
        val loud = listOf(sine(1000.0, 1.0, 44100, 4096))
        val silent = listOf(FloatArray(4096))
        assertEquals(100, s.analyze(loud, 44100, 0).channels.single())
        // 100ms で 20 落ちる。ピークは 500ms 残る
        val f = s.analyze(silent, 44100, 100)
        assertEquals(80, f.channels.single())
        assertEquals(100, f.channelPeaks.single())
        val g = s.analyze(silent, 44100, 600)
        assertEquals(0, g.channels.single())
        assertEquals(0, g.channelPeaks.single())
        assertEquals(listOf(0, 0, 0, 0, 0), s.zero().bands)
    }
}
