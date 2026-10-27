package net.pono.droid.overlay

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pono.baseware.ghost.SoundStatus
import net.pono.baseware.sakura.SoundOptions
import net.pono.baseware.sakura.SoundSpectrum
import java.io.File

/**
 * ゴーストの音（\8 \_v \![sound,...]）。SSP と同じく、ファイルごとに1つずつ何個でも鳴らせる（[MAX_SOUNDS] まで）。
 * 同じファイルを鳴らせば頭から鳴らし直す。ループはファイルの終わりで頭に戻すたびに [onLoop] を呼ぶ。
 * メインスレッドから使う
 */
class SoundPlayer(
    private val onStop: (File) -> Unit = {},
    private val onLoop: (File) -> Unit = {},
    private val onError: (File, Int, String) -> Unit = { _, _, _ -> },
    /** --spectrum= の音の、帯域の数・レベル・再生位置（ミリ秒）。メインスレッドで呼ぶ */
    private val onSpectrum: (File, Int, SoundSpectrum.Frame, Long) -> Unit = { _, _, _, _ -> },
) {
    /** --spectrum= の通知。[analyzer] は帯域の数とホールドの時間で作り直す */
    private class Spectrum(var intervalMs: Int, var analyzer: SoundSpectrum) {
        var job: Job? = null
        var tap: SpectrumTap? = null
        var opened = false
        var lastPositionMs = 0L
    }

    private class Sound(val file: File, val player: MediaPlayer, val id: Int) {
        var spectrum: Spectrum? = null
        var loop = false

        /** play されたことがあるか（無ければ load で先に読んだだけ） */
        var started = false
        var prepared = false

        /** prepare が終わったら鳴らす（load だけなら鳴らさない） */
        var startWhenReady = false
        var paused = false
        var options = SoundOptions()

        /** 読み終わる前に頼まれた --seektime */
        var pendingSeek: SoundOptions? = null
    }

    private val sounds = LinkedHashMap<String, Sound>()

    /** スペクトラムの間隔を刻む（メインスレッド）と、波形を読んで解析する（1本ずつ） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val analysis = Dispatchers.Default.limitedParallelism(1)
    private var nextId = 1
    private val main = Handler(Looper.getMainLooper())

    /** ループでない音がすべて鳴り終わったら完了する（\_V の待ち） */
    private var finished: CompletableDeferred<Unit>? = null

    fun play(file: File, loop: Boolean, options: SoundOptions) {
        val sound = sounds[file.path] ?: open(file) ?: return
        sound.loop = loop
        sound.started = true
        sound.options = merge(sound.options, options)
        sound.paused = false
        if (sound.prepared) {
            // 鳴らし直すときは頭から（--seektime があればそこから）
            if (options.seekMs == null) sound.player.seekTo(0)
            apply(sound, options)
            sound.player.start()
        } else {
            sound.startWhenReady = true
            if (options.seekMs != null) sound.pendingSeek = options
        }
        if (!loop) waitFor()
    }

    /** load: 先に読んでおき、次の play ですぐ鳴らす */
    fun load(file: File, options: SoundOptions) {
        val sound = sounds[file.path] ?: open(file) ?: return
        sound.options = merge(sound.options, options)
        if (options.seekMs != null) {
            if (sound.prepared) apply(sound, options) else sound.pendingSeek = options
        }
    }

    fun pause(file: File?) = forEach(file) { s ->
        if (s.prepared && s.player.isPlaying) s.player.pause()
        s.paused = true
        s.startWhenReady = false
    }

    fun resume(file: File?) = forEach(file) { s ->
        if (!s.paused) return@forEach
        s.paused = false
        if (s.prepared) s.player.start() else s.startWhenReady = true
    }

    /** option: 鳴っている音の音量・左右・速さ・位置を変える */
    fun option(file: File, options: SoundOptions) {
        val sound = sounds[file.path] ?: return
        sound.options = merge(sound.options, options)
        if (sound.prepared) apply(sound, options)
    }

    fun stop(file: File? = null) {
        forEach(file) { release(it, notify = false) }
    }

    /** 鳴らしている音と、先に読んだ音（プロパティシステムの currentghost.sound.*） */
    fun statuses(): List<SoundStatus> = sounds.values.map { s ->
        val ready = s.prepared
        SoundStatus(
            file = s.file,
            id = s.id,
            positionMs = if (ready) runCatching { s.player.currentPosition.toLong() }.getOrDefault(-1) else -1,
            durationMs = if (ready) runCatching { s.player.duration.toLong() }.getOrDefault(-1) else -1,
            playing = s.started && !s.paused,
            paused = s.paused,
            loop = s.loop,
            preload = !s.started,
        )
    }

    suspend fun await() {
        finished?.await()
    }

    private fun open(file: File): Sound? {
        if (sounds.size >= MAX_SOUNDS) sounds.values.firstOrNull()?.let { release(it, notify = false) }
        val player = MediaPlayer()
        val sound = Sound(file, player, nextId++)
        try {
            player.setDataSource(file.path)
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "sound error $what/$extra: ${file.name}")
                release(sound, notify = false)
                onError(file, what, "error $what/$extra")
                true
            }
            player.setOnPreparedListener {
                sound.prepared = true
                apply(sound, sound.options)
                sound.pendingSeek?.let { seek -> apply(sound, SoundOptions(seekMs = seek.seekMs, seekRelative = seek.seekRelative)) }
                sound.pendingSeek = null
                if (sound.startWhenReady && !sound.paused) it.start()
            }
            player.setOnCompletionListener {
                if (sound.loop) {
                    // MediaPlayer の isLooping では頭に戻ったことが分からないので、自分で頭に戻す
                    onLoop(file)
                    it.seekTo(0)
                    it.start()
                } else {
                    release(sound, notify = true)
                }
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "cannot play ${file.name}: $e")
            player.release()
            onError(file, 0, e.message ?: e.javaClass.simpleName)
            return null
        }
        sounds[file.path]?.let { release(it, notify = false) }
        sounds[file.path] = sound
        return sound
    }

    private fun apply(sound: Sound, changed: SoundOptions) {
        updateSpectrum(sound, changed)
        val o = sound.options
        val volume = (o.volume ?: 100) / 100f
        val balance = (o.balance ?: 0) / 100f
        sound.player.setVolume(volume * minOf(1f, 1f - balance), volume * minOf(1f, 1f + balance))
        if (changed.rate != null) {
            runCatching { sound.player.playbackParams = sound.player.playbackParams.setSpeed(changed.rate!! / 100f) }
                .onFailure { Log.w(TAG, "cannot change rate: ${sound.file.name}") }
        }
        changed.seekMs?.let { ms ->
            val to = if (changed.seekRelative) sound.player.currentPosition + ms else ms
            sound.player.seekTo(to.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        }
    }

    /** 新しく指定されたものだけ上書きする（位置は覚えない） */
    private fun merge(old: SoundOptions, new: SoundOptions) = SoundOptions(
        volume = new.volume ?: old.volume,
        balance = new.balance ?: old.balance,
        rate = new.rate ?: old.rate,
        spectrumMs = new.spectrumMs ?: old.spectrumMs,
        spectrumBands = new.spectrumBands ?: old.spectrumBands,
        spectrumHoldMs = new.spectrumHoldMs ?: old.spectrumHoldMs,
    )

    /**
     * --spectrum= / --spectrum-bands= / --spectrum-hold= を反映する。間隔ごとに再生位置の波形を解析して [onSpectrum] に渡す。
     * 一時停止中は送らない。0 で止めるときは、全部 0 の通知を 1 回送る
     */
    private fun updateSpectrum(sound: Sound, changed: SoundOptions) {
        if (changed.spectrumMs == null && changed.spectrumBands == null && changed.spectrumHoldMs == null) return
        val o = sound.options
        val interval = o.spectrumMs ?: 0
        val current = sound.spectrum
        if (interval <= 0) {
            if (current != null) stopSpectrum(sound, current)
            return
        }
        val bands = o.spectrumBands ?: DEFAULT_BANDS
        val hold = (o.spectrumHoldMs ?: DEFAULT_HOLD_MS).toLong()
        val analyzer = current?.analyzer?.takeIf { it.bands == bands && changed.spectrumHoldMs == null } ?: SoundSpectrum(bands, hold)
        val s = current ?: Spectrum(interval, analyzer).also { sound.spectrum = it }
        s.intervalMs = interval
        s.analyzer = analyzer
        if (s.job?.isActive == true) return
        s.job = scope.launch {
            while (isActive) {
                delay(s.intervalMs.toLong())
                if (!sound.prepared || !sound.started || sound.paused || !runCatching { sound.player.isPlaying }.getOrDefault(false)) continue
                val position = runCatching { sound.player.currentPosition.toLong() }.getOrDefault(0)
                val a = s.analyzer
                val frame = withContext(analysis) {
                    if (!s.opened) {
                        s.opened = true
                        s.tap = SpectrumTap.open(sound.file)
                    }
                    val tap = s.tap
                    val window = tap?.let { runCatching { it.window(position, SoundSpectrum.windowSize(it.sampleRate)) }.getOrNull() }
                    // 波形を取り出せない音は送らない（帯域なしなら 0 で送る）
                    if (window == null && a.bands > 0) null else a.analyze(window.orEmpty(), tap?.sampleRate ?: 44100, System.currentTimeMillis())
                } ?: continue
                s.lastPositionMs = position
                onSpectrum(sound.file, a.bands, frame, position)
            }
        }
    }

    private fun stopSpectrum(sound: Sound, s: Spectrum) {
        s.job?.cancel()
        sound.spectrum = null
        onSpectrum(sound.file, s.analyzer.bands, s.analyzer.zero(), s.lastPositionMs)
        val tap = s.tap
        if (tap != null) scope.launch(analysis) { tap.close() }
    }

    private fun forEach(file: File?, block: (Sound) -> Unit) {
        val targets = if (file == null) sounds.values.toList() else listOfNotNull(sounds[file.path])
        targets.forEach(block)
    }

    private fun waitFor() {
        if (finished?.isActive != true) finished = CompletableDeferred()
    }

    private fun release(sound: Sound, notify: Boolean) {
        if (sounds[sound.file.path] === sound) sounds.remove(sound.file.path)
        // スペクトラムは、止めたことを全部 0 の通知で知らせてから（そのあとに OnSoundStop）
        sound.spectrum?.let { stopSpectrum(sound, it) }
        runCatching { sound.player.release() }
        // 鳴り終わりのイベントは、ほかの音の後始末が済んでから
        if (notify) main.post { onStop(sound.file) }
        if (sounds.values.none { !it.loop }) {
            finished?.complete(Unit)
            finished = null
        }
    }

    private companion object {
        const val TAG = "pono"

        /** 一度に鳴らしておける音の数。超えたら古いものから止める */
        const val MAX_SOUNDS = 16

        /** --spectrum-bands= と --spectrum-hold= の既定 */
        const val DEFAULT_BANDS = 10
        const val DEFAULT_HOLD_MS = 500
    }
}
