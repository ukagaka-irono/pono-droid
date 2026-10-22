package net.pono.baseware.shiori

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import net.pono.baseware.text.TextCharsets
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * 1つの SHIORI を1本のスレッドで動かし、SHIORI/3.0 の文字列でやりとりする。
 * 文字コードは最初は descript.txt の指定を使い、以後は SHIORI が返した `Charset` に合わせる。
 */
class ShioriSession(
    private val shiori: Shiori,
    initialCharset: Charset,
    private val sender: String = SENDER,
    dispatcher: CoroutineDispatcher? = null,
) {
    private val executor = if (dispatcher == null) Executors.newSingleThreadExecutor { r -> Thread(r, "shiori") } else null
    private val dispatcher: CoroutineDispatcher = dispatcher ?: executor!!.asCoroutineDispatcher()

    @Volatile
    var charset: Charset = initialCharset
        private set

    suspend fun load(dir: File): Boolean = withContext(dispatcher) { shiori.load(dir) }

    suspend fun request(request: ShioriRequest): ShioriResponse? = withContext(dispatcher) {
        val raw = shiori.request(request.encode(charset, sender)) ?: return@withContext null
        val response = ShioriResponse.parse(raw, charset) ?: return@withContext null
        response.headers["Charset"]?.let(TextCharsets::resolve)?.let { charset = it }
        response
    }

    suspend fun get(id: String, vararg references: String): ShioriResponse? =
        request(ShioriRequest(ShioriMethod.GET, id, references.toList()))

    suspend fun notify(id: String, vararg references: String) {
        request(ShioriRequest(ShioriMethod.NOTIFY, id, references.toList()))
    }

    suspend fun unload() {
        withContext(dispatcher) { shiori.unload() }
        executor?.shutdown()
    }

    companion object {
        const val SENDER = "pono-droid"
    }
}
