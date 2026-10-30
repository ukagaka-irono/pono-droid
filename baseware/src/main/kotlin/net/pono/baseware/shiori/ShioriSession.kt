package net.pono.baseware.shiori

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import net.pono.baseware.text.TextCharsets
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.Executors

/** SHIORI が時間内に答えなかった（辞書が無限ループしているなど）。[Shiori.abort] は済んでいる */
class ShioriTimeoutException(message: String) : RuntimeException(message)

/**
 * 1つの SHIORI を1本のスレッドで動かし、SHIORI/3.0 の文字列でやりとりする。
 * 文字コードは最初は descript.txt の指定を使い、以後は SHIORI が返した `Charset` に合わせる（[forceCharset] なら合わせない）。
 * [escapeUnknown] なら、その文字コードにできない文字を ?escape!unicode[0xABCD] にして渡し、答えで元に戻す。
 * 呼び出しには時間の上限があり、過ぎたら [Shiori.abort] して [ShioriTimeoutException] を投げる。
 */
class ShioriSession(
    private val shiori: Shiori,
    initialCharset: Charset,
    private val sender: String = SENDER,
    dispatcher: CoroutineDispatcher? = null,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    private val loadTimeoutMs: Long = LOAD_TIMEOUT_MS,
    private val forceCharset: Boolean = false,
    private val escapeUnknown: Boolean = false,
) {
    private val executor = if (dispatcher == null) Executors.newSingleThreadExecutor { r -> Thread(r, "shiori") } else null
    private val dispatcher: CoroutineDispatcher = dispatcher ?: executor!!.asCoroutineDispatcher()

    // 固まった呼び出しは止められないので、呼び出し自体はこのスコープで走らせ、待つ側だけ時間で区切る
    private val calls = CoroutineScope(SupervisorJob() + this.dispatcher)

    @Volatile
    var charset: Charset = initialCharset
        private set

    suspend fun load(dir: File): Boolean = call(loadTimeoutMs, "load") { shiori.load(dir) } ?: false

    suspend fun request(request: ShioriRequest): ShioriResponse? = call(requestTimeoutMs, request.id) {
        val raw = shiori.request(request.encode(charset, sender, escapeUnknown)) ?: return@call null
        val response = ShioriResponse.parse(raw, charset, force = forceCharset, unescape = escapeUnknown) ?: return@call null
        if (!forceCharset) response.charset?.let(TextCharsets::resolve)?.let { charset = it }
        response
    }

    suspend fun get(id: String, vararg references: String): ShioriResponse? =
        request(ShioriRequest(ShioriMethod.GET, id, references.toList()))

    suspend fun notify(id: String, vararg references: String) {
        request(ShioriRequest(ShioriMethod.NOTIFY, id, references.toList()))
    }

    suspend fun unload() {
        runCatching { call(UNLOAD_TIMEOUT_MS, "unload") { shiori.unload() } }
        calls.cancel()
        executor?.shutdown()
    }

    /**
     * [block] を SHIORI のスレッドで動かす。[timeoutMs] 以内に戻らなければ [Shiori.abort] して
     * [ShioriTimeoutException] を投げる（別プロセスならプロセスが止まり、ブロックしていた呼び出しも戻ってくる）。
     */
    private suspend fun <T> call(timeoutMs: Long, what: String, block: () -> T): T? {
        val deferred = calls.async { block() }
        val finished = withTimeoutOrNull(timeoutMs) { deferred.join(); true } ?: false
        if (!finished) {
            shiori.abort()
            throw ShioriTimeoutException("SHIORI did not respond to $what within $timeoutMs ms")
        }
        return deferred.await()
    }

    companion object {
        const val SENDER = "pono-droid"

        /** 重い辞書の起動処理もあるので長め */
        const val LOAD_TIMEOUT_MS = 120_000L
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val UNLOAD_TIMEOUT_MS = 30_000L
    }
}
