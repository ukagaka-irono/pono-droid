package net.pono.droid.overlay

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * ゴーストの音（\8 \_v \![sound,...]）。1回だけ鳴らすものとループするものを1つずつ持つ
 * （SSP は何個でも鳴らせるが、スマートフォンでは十分）。メインスレッドから使う。
 */
class SoundPlayer {
    private var oneShot: MediaPlayer? = null
    private var looping: MediaPlayer? = null

    /** 1回だけ鳴らしている音が終わったら完了する（\_V の待ち） */
    private var finished: CompletableDeferred<Unit>? = null

    fun play(file: File, loop: Boolean, volume: Int?) {
        val player = MediaPlayer()
        try {
            player.setDataSource(file.path)
            player.isLooping = loop
            volume?.let { v -> (v.coerceIn(0, 100) / 100f).let { player.setVolume(it, it) } }
            player.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "sound error $what/$extra: ${file.name}")
                release(p)
                true
            }
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "cannot play ${file.name}: $e")
            player.release()
            return
        }
        if (loop) {
            looping?.release()
            looping = player
        } else {
            oneShot?.release()
            finished?.complete(Unit)
            val done = CompletableDeferred<Unit>()
            finished = done
            oneShot = player
            player.setOnCompletionListener { p ->
                done.complete(Unit)
                release(p)
            }
        }
    }

    fun stop() {
        oneShot?.release()
        looping?.release()
        oneShot = null
        looping = null
        finished?.complete(Unit)
        finished = null
    }

    suspend fun await() {
        finished?.await()
    }

    private fun release(player: MediaPlayer) {
        player.release()
        if (oneShot === player) {
            oneShot = null
            finished?.complete(Unit)
            finished = null
        }
        if (looping === player) looping = null
    }

    private companion object {
        const val TAG = "pono"
    }
}
