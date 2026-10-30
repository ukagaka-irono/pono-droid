package net.pono.droid.overlay

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * 鳴っている音の波形（--spectrum=）。MediaExtractor と MediaCodec で、再生位置のあたりを PCM に戻して直近の 2 秒ぶんを覚えておき、
 * 再生と同じ時刻で終わる窓を [window] で返す。再生位置が覚えている範囲から外れたら（シーク・ループ）、そこから読み直す。
 * 解析のためのスレッドから使う（[close] まで同じスレッド）
 */
class SpectrumTap private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    val sampleRate: Int,
) {
    private var channels = 0
    private var rings: Array<FloatArray> = emptyArray()
    private val capacity = sampleRate * 2

    /** [rings] の 0 番目のコマの時刻（マイクロ秒）と、これまでに書いたコマの数 */
    private var startUs = 0L
    private var written = 0L
    private var inputDone = false
    private var outputDone = false
    private var floatPcm = false
    private val info = MediaCodec.BufferInfo()

    /** [positionMs] で終わる [size] コマ（チャンネルごと）。取り出せなければ null */
    fun window(positionMs: Long, size: Int): List<FloatArray>? {
        val wantUs = positionMs * 1000
        fun frameOf(us: Long) = (us - startUs) * sampleRate / 1_000_000
        // 覚えている範囲の外なら、少し前から読み直す
        val end = frameOf(wantUs)
        if (written == 0L || end < written - capacity + size || end > written + sampleRate) seek(maxOf(0L, wantUs - WINDOW_LEAD_US))
        var guard = 0
        // 読み直したら、最初の PCM の時刻が分かるまで進める
        while (startUs < 0 && !outputDone && guard++ < MAX_STEPS) step()
        var target = frameOf(wantUs)
        while (written <= target && !outputDone && guard++ < MAX_STEPS) step()
        if (channels == 0 || written == 0L) return null
        target = minOf(target, written - 1)
        val from = target - size + 1
        return List(channels) { c ->
            FloatArray(size) { i ->
                val frame = from + i
                if (frame < 0 || frame < written - capacity) 0f else rings[c][(frame % capacity).toInt()]
            }
        }
    }

    private fun seek(us: Long) {
        extractor.seekTo(us, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        written = 0
        startUs = -1
    }

    /** 入力を 1 つ渡し、出てきた PCM を覚える */
    private fun step() {
        if (!inputDone) {
            val i = codec.dequeueInputBuffer(TIMEOUT_US)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i)!!
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format(codec.outputFormat)
            o >= 0 -> {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                if (channels == 0) format(codec.outputFormat)
                val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                if (startUs < 0) startUs = info.presentationTimeUs
                val frames = info.size / ((if (floatPcm) 4 else 2) * channels.coerceAtLeast(1))
                for (f in 0 until frames) {
                    val at = (written % capacity).toInt()
                    for (c in 0 until channels) {
                        val v = if (floatPcm) buf.float else buf.short / 32768f
                        if (c < rings.size) rings[c][at] = v
                    }
                    written++
                }
                codec.releaseOutputBuffer(o, false)
            }
        }
    }

    private fun format(f: MediaFormat) {
        val n = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, MAX_CHANNELS)
        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        if (n != channels) {
            channels = n
            rings = Array(n) { FloatArray(capacity) }
        }
    }

    fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    companion object {
        private const val TIMEOUT_US = 5_000L

        /** 読み直すとき、窓の分より前から読む */
        private const val WINDOW_LEAD_US = 200_000L

        /** 1 回の [window] で回す上限（読めない形式で止まらないように） */
        private const val MAX_STEPS = 2_000

        const val MAX_CHANNELS = 8

        /** [file] の音の波形を取り出せるようにする。音の無いファイル・読めない形式なら null */
        fun open(file: File): SpectrumTap? {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            return try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: run {
                    extractor.release()
                    return null
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
                decoder.configure(format, null, null, 0)
                decoder.start()
                SpectrumTap(extractor, decoder, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            } catch (e: Exception) {
                // configure や start で失敗しても、作ったデコーダーは放す（数に限りがある）
                codec?.let { c -> runCatching { c.release() } }
                extractor.release()
                null
            }
        }
    }
}
