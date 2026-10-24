package net.pono.baseware.net

import java.io.ByteArrayOutputStream

/**
 * \![execute,http-*,--streaming] の受け取ったものを区切って渡す。[mode] は sse（Server-Sent Events の data をまとめて1つ）・
 * text（1行ずつ）・auto（Content-Type が text/event-stream なら sse、それ以外は text）。
 * 渡す文字の改行はバイト値1にする（Reference に改行は入れられない）
 */
class HttpStreamer(private val mode: String, private val emit: (String) -> Unit) {
    private var sse = mode == "sse"
    private val line = ByteArrayOutputStream()
    private val event = StringBuilder()

    /** 応答の頭が来た（auto のときに決める） */
    fun contentType(type: String) {
        if (mode == "auto") sse = type.lowercase().startsWith("text/event-stream")
    }

    fun feed(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val c = b[i]
            if (c == '\n'.code.toByte()) {
                line(String(line.toByteArray(), Charsets.UTF_8).removeSuffix("\r"))
                line.reset()
            } else {
                // 1行が大きすぎるものは切って渡す（メモリを使い切らせない）
                if (line.size() >= MAX_LINE) {
                    line(String(line.toByteArray(), Charsets.UTF_8))
                    line.reset()
                }
                line.write(c.toInt())
            }
        }
    }

    /** 最後の改行の無い行と、まだ渡していない SSE のイベント */
    fun finish() {
        if (line.size() > 0) {
            line(String(line.toByteArray(), Charsets.UTF_8))
            line.reset()
        }
        if (sse) flushEvent()
    }

    private fun line(text: String) {
        if (!sse) {
            if (text.isNotEmpty()) emit(text)
            return
        }
        when {
            text.isEmpty() -> flushEvent()
            text.startsWith("data:") -> {
                if (event.isNotEmpty()) event.append('\u0001')
                event.append(text.removePrefix("data:").removePrefix(" "))
            }
            // コメント（:）や event: / id: / retry: は渡さない
            else -> {}
        }
    }

    private fun flushEvent() {
        if (event.isEmpty()) return
        emit(event.toString())
        event.clear()
    }

    private companion object {
        const val MAX_LINE = 128 * 1024
    }
}
