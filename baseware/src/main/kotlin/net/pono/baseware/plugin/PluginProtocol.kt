package net.pono.baseware.plugin

import net.pono.baseware.shiori.ShioriRequest
import net.pono.baseware.text.TextCharsets
import java.nio.charset.Charset

/**
 * PLUGIN/2.0 の要求と答え（SHIORI/3.0 に準じる）。要求は「GET / NOTIFY PLUGIN/2.0」、ID・Charset・Sender（ゴーストから呼ばれたときだけ）・
 * Reference0… の順で書く。答えは「PLUGIN/2.0 200 OK」と、Value（version の答え）・Charset・Target・Event・EventOption・Reference*・
 * Script・ScriptOption
 */
object PluginProtocol {
    /** [sender] はゴーストから呼ばれたときのゴースト名（本体から送るときは null で、Sender は書かない） */
    fun request(notify: Boolean, id: String, references: List<String>, charset: Charset, sender: String? = null): ByteArray {
        val sb = StringBuilder()
        sb.append(if (notify) "NOTIFY" else "GET").append(" PLUGIN/2.0\r\n")
        sb.append("ID: ").append(sanitize(id)).append("\r\n")
        sb.append("Charset: ").append(ShioriRequest.charsetHeaderName(charset)).append("\r\n")
        if (sender != null) sb.append("Sender: ").append(sanitize(sender)).append("\r\n")
        references.forEachIndexed { i, v -> sb.append("Reference").append(i).append(": ").append(sanitize(v)).append("\r\n") }
        sb.append("\r\n")
        return sb.toString().toByteArray(charset)
    }

    /** 改行と NUL はヘッダを壊すので、SHIORI と同じく 0x01 にする */
    private fun sanitize(v: String) =
        v.replace("\r\n", "\u0001").replace('\r', '\u0001').replace('\n', '\u0001').replace('\u0000', '\u0001')

    /** プラグインの答え */
    data class Response(val statusCode: Int, val headers: Map<String, String>) {
        /** version の答えのバージョン */
        val value: String? get() = headers["Value"]?.takeIf { it.isNotEmpty() }

        /** 以後の要求に使う文字コード（version の答え） */
        val charset: String? get() = headers.entries.firstOrNull { it.key.equals("Charset", ignoreCase = true) }?.value?.trim()

        /** 送り先のゴースト（ゴースト名か本体側の名前、__SYSTEM_ALL_GHOST__ なら起動中の全部） */
        val target: String? get() = headers["Target"]?.trim()?.takeIf { it.isNotEmpty() }

        /** ゴーストに送るイベント */
        val event: String? get() = headers["Event"]?.trim()?.takeIf { it.isNotEmpty() }

        /** イベントを NOTIFY で送るか（EventOption: notify） */
        val eventNotify: Boolean get() = options("EventOption").contains("notify")

        /** ゴーストがイベントに答えなかったときのスクリプト（デフォルトスクリプト） */
        val script: String? get() = headers["Script"]?.takeIf { it.isNotBlank() }

        /** ScriptOption（nobreak・notranslate） */
        val scriptOptions: Set<String> get() = options("ScriptOption")

        val references: List<String>
            get() = generateSequence(0) { it + 1 }.map { headers["Reference$it"] }.takeWhile { it != null }.filterNotNull().toList()

        private fun options(name: String): Set<String> =
            headers[name]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    }

    private val statusLine = Regex("""^PLUGIN/\d\.\d\s+(\d{3})""")

    /** 生の答えを読む。文字コードは Charset ヘッダ（無ければ [fallback]） */
    fun parse(bytes: ByteArray, fallback: Charset): Response? {
        val latin = String(bytes, Charsets.ISO_8859_1)
        val declared = latin.lineSequence().map { it.trimEnd('\r') }
            .firstOrNull { it.startsWith("Charset:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.let(TextCharsets::resolve)
        val lines = String(bytes, declared ?: fallback).split("\r\n", "\n")
        val code = statusLine.find(lines.firstOrNull() ?: return null)?.groupValues?.get(1)?.toInt() ?: return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
            headers[line.substring(0, colon)] = line.substring(colon + 1).removePrefix(" ")
        }
        return Response(code, headers)
    }
}
