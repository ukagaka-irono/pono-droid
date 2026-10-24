package net.pono.droid.overlay

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import net.pono.baseware.sakura.SoundOptions
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
) {
    private class Sound(val file: File, val player: MediaPlayer) {
        var loop = false
        var prepared = false

        /** prepare が終わったら鳴らす（load だけなら鳴らさない） */
        var startWhenReady = false
        var paused = false
        var options = SoundOptions()

        /** 読み終わる前に頼まれた --seektime */
        var pendingSeek: SoundOptions? = null
    }

    private val sounds = LinkedHashMap<String, Sound>()
    private val main = Handler(Looper.getMainLooper())

    /** ループでない音がすべて鳴り終わったら完了する（\_V の待ち） */
    private var finished: CompletableDeferred<Unit>? = null

    fun play(file: File, loop: Boolean, options: SoundOptions) {
        val sound = sounds[file.path] ?: open(file) ?: return
        sound.loop = loop
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

    suspend fun await() {
        finished?.await()
    }

    private fun open(file: File): Sound? {
        if (sounds.size >= MAX_SOUNDS) sounds.values.firstOrNull()?.let { release(it, notify = false) }
        val player = MediaPlayer()
        val sound = Sound(file, player)
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
    )

    private fun forEach(file: File?, block: (Sound) -> Unit) {
        val targets = if (file == null) sounds.values.toList() else listOfNotNull(sounds[file.path])
        targets.forEach(block)
    }

    private fun waitFor() {
        if (finished?.isActive != true) finished = CompletableDeferred()
    }

    private fun release(sound: Sound, notify: Boolean) {
        if (sounds[sound.file.path] === sound) sounds.remove(sound.file.path)
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
    }
}
