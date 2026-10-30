package net.pono.droid.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * マイクで声を聞き、聞き取った文を [onHeard] に渡す（OnVoiceRecognitionWord）。聞き終わったら少し待ってまた聞く。
 * アプリの設定で選び、マイクの許可があるときだけ使う。メインスレッドから使う
 */
class VoiceListener(private val context: Context, private val onHeard: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var failures = 0

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** 聞いている（許可が無いなどで自分で止まったら false） */
    val isRunning: Boolean get() = running

    fun start() {
        if (running || !available) return
        running = true
        failures = 0
        listen()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!running) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { r.startListening(intent) }.onFailure { again(error = true) }
    }

    /** 次に聞くまでの間。続けて失敗するほど長く待つ（電池とネットワークを使い続けないように） */
    private fun again(error: Boolean) {
        if (!running) return
        failures = if (error) (failures + 1).coerceAtMost(MAX_BACKOFF_STEPS) else 0
        main.postDelayed({ listen() }, RESTART_MS shl failures)
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onHeard)
            again(error = false)
        }

        override fun onError(error: Int) {
            when (error) {
                // 許可が無い・使えないなら、もう聞かない
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                    Log.w(TAG, "voice recognition stopped: $error")
                    stop()
                }
                // 何も聞こえなかっただけなら、すぐまた聞く
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> again(error = false)
                else -> again(error = true)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val TAG = "pono"
        const val RESTART_MS = 1000L
        const val MAX_BACKOFF_STEPS = 6
    }
}
