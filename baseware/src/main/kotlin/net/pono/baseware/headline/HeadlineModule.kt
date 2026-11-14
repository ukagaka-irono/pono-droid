package net.pono.baseware.headline

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.pono.baseware.shiori.ShioriFactory
import net.pono.baseware.shiori.ShioriSession
import net.pono.baseware.text.TextCharsets
import java.io.File
import java.nio.charset.Charset

/** HEADLINE/2.0 を共通 DLL ホストで動かす。Windows パスへの変換はランタイム側から渡す。 */
class HeadlineModule(private val engines: ShioriFactory, private val guestPath: (File) -> String = { it.absolutePath }) : HeadlineAnalyzer {
    override suspend fun analyze(headline: Headline, previous: File?, current: File): Pair<List<HeadlineItem>, List<HeadlineItem>>? {
        val dll = TextCharsets.fileIn(headline.dir, headline.dllName) ?: return null
        val engine = engines.create(dll.name, dll.parentFile) ?: return null
        var charset = TextCharsets.resolve(headline.descript["charset"]) ?: TextCharsets.CP932
        val session = ShioriSession(engine, charset)
        try {
            if (!session.load(dll.parentFile)) return null
            suspend fun request(kind: String, file: File? = null): Response? {
                val path = file?.let(guestPath)
                require(path?.none { it == '\r' || it == '\n' } != false) { "Invalid headline path" }
                val bytes = buildString {
                    append("GET $kind HEADLINE/2.0\r\nCharset: ${charset.name()}\r\nSender: ${ShioriSession.SENDER}\r\n")
                    if (path != null) append("Option: url\r\nPath: $path\r\n")
                    append("\r\n")
                }.toByteArray(charset)
                val response = session.rawRequest(bytes)?.let { parse(it, charset) } ?: return null
                response.requestCharset?.let { charset = it }
                return response.takeIf { it.code in 200..299 }
            }
            request("Version") ?: return null
            val old = if (previous == null) emptyList() else request("Headline", previous)?.items ?: return null
            val fresh = request("Headline", current)?.items ?: return null
            return old to fresh
        } finally {
            withContext(NonCancellable) { session.unload() }
        }
    }

    data class Response(val code: Int, val items: List<HeadlineItem>, val requestCharset: Charset?)

    companion object {
        /** Headline ヘッダの重複と順序を保持する。本文をヘッダとして解釈しない。 */
        fun parse(bytes: ByteArray, fallback: Charset): Response? {
            val ascii = bytes.toString(Charsets.ISO_8859_1).substringBefore("\r\n\r\n").lineSequence().toList()
            val declared = ascii.firstOrNull { it.startsWith("Charset:", true) }?.substringAfter(':')?.trim()?.let(TextCharsets::resolve)
            val lines = bytes.toString(declared ?: fallback).split("\r\n").takeWhile { it.isNotEmpty() }
            val code = Regex("""^HEADLINE/2\.0 (\d{3})(?:\s.*)?$""").matchEntire(lines.firstOrNull() ?: return null)?.groupValues?.get(1)?.toInt() ?: return null
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                line.take(i).lowercase() to line.substring(i + 1).removePrefix(" ")
            }
            val next = headers.lastOrNull { it.first == "requestcharset" }?.second?.trim()?.let(TextCharsets::resolve) ?: declared
            val items = headers.filter { it.first == "headline" }.map { (_, value) ->
                HeadlineItem(value.substringBefore('\u0001'), value.substringAfter('\u0001', ""))
            }
            return Response(code, items, next)
        }
    }
}
