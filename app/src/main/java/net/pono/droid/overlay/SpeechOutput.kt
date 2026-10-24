package net.pono.droid.overlay

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * ゴーストが話した文字の読み上げ（アプリの設定で選んだときだけ。最初は読み上げない）。Android の音声合成を使い、
 * 本体側はふつうの高さ、相方より後ろのキャラクターは少し低い声にする。メインスレッドから使う
 */
class SpeechOutput(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    /** 音声合成の準備ができる前に話したもの */
    private val pending = ArrayDeque<Pair<Int, String>>()
    private var serial = 0

    fun speak(scope: Int, text: String) {
        val engine = tts ?: TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                while (pending.isNotEmpty()) pending.removeFirst().let { (s, t) -> say(s, t) }
            } else {
                Log.w(TAG, "text to speech is not available: $status")
                pending.clear()
            }
        }.also { tts = it }
        if (!ready) {
            if (pending.size < MAX_PENDING) pending.addLast(scope to text)
            return
        }
        say(scope, text)
    }

    private fun say(scope: Int, text: String) {
        val engine = tts ?: return
        engine.setPitch(if (scope == 0) 1.0f else 0.8f)
        engine.speak(text.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_ADD, null, "pono-${serial++}")
    }

    /** 読み上げの途中と、待っているものをやめる */
    fun stop() {
        pending.clear()
        tts?.stop()
    }

    fun shutdown() {
        pending.clear()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "pono"
        const val MAX_PENDING = 20
    }
}
