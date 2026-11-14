package net.pono.baseware.headline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pono.baseware.net.FeedDocument
import net.pono.baseware.net.FeedParser
import net.pono.baseware.net.HttpClient
import net.pono.baseware.net.HttpOutcome
import net.pono.baseware.net.HttpRequest
import net.pono.baseware.text.AtomicFiles
import java.io.ByteArrayOutputStream
import java.io.File

data class HeadlineItem(val title: String, val url: String = "")

/** DLL の解析と RSS の取得を分離。実行可能なモジュールだけを呼び出し側が提供する。 */
fun interface HeadlineAnalyzer {
    suspend fun analyze(headline: Headline, previous: File?, current: File): Pair<List<HeadlineItem>, List<HeadlineItem>>?
}

sealed interface HeadlineResult {
    data class Updated(val items: List<HeadlineItem>, val feed: FeedDocument? = null) : HeadlineResult
    data object Unchanged : HeadlineResult
    data class Failure(val reason: String) : HeadlineResult
}

class HeadlineReader(private val client: HttpClient, private val cacheRoot: File, private val analyzer: HeadlineAnalyzer? = null) {
    suspend fun fetchFeed(url: String): FeedDocument? = withContext(Dispatchers.IO) {
        fetch(url)?.let { FeedParser.parseDocument(it, url) }
    }

    suspend fun read(headline: Headline): HeadlineResult = withContext(Dispatchers.IO) {
        val bytes = fetch(headline.url) ?: return@withContext HeadlineResult.Failure("can't download")
        cacheRoot.mkdirs()
        val cache = File(cacheRoot, Headline.digest(headline.dir.canonicalPath + "\n" + headline.url) + ".cache")
        val previous = cache.takeIf { it.isFile && it.length() <= MAX_BYTES }
        val result = if (headline.isFeed) {
            val feed = FeedParser.parseDocument(bytes, headline.url) ?: return@withContext HeadlineResult.Failure("can't analyze")
            val old = previous?.let { runCatching { FeedParser.parseDocument(it.readBytes(), headline.url) }.getOrNull() }
            val known = old?.items.orEmpty().toHashSet()
            val changed = if (headline.alwaysDisplay) feed.items else feed.items.filterNot(known::contains)
            if (changed.isEmpty()) HeadlineResult.Unchanged
            else HeadlineResult.Updated(changed.map { HeadlineItem(it.title, it.link) }, feed.copy(items = changed))
        } else {
            val engine = analyzer ?: return@withContext HeadlineResult.Failure("can't analyze")
            val current = File.createTempFile("headline-", ".tmp", cacheRoot)
            val parsed = try {
                current.writeBytes(bytes)
                engine.analyze(headline, previous, current)
            } finally { current.delete() }
            if (parsed == null) return@withContext HeadlineResult.Failure("can't analyze")
            val (old, fresh) = parsed
            val known = old.toHashSet()
            val changed = if (headline.alwaysDisplay) fresh else fresh.filterNot(known::contains)
            if (changed.isEmpty()) HeadlineResult.Unchanged else HeadlineResult.Updated(changed)
        }
        AtomicFiles.write(cache) { it.write(bytes) }
        result
    }

    private fun fetch(url: String): ByteArray? {
        if (Headline.webUrl(url) == null) return null
        val sink = ByteArrayOutputStream()
        val result = client.execute(HttpRequest("GET", url, timeoutMs = 15_000), sink, MAX_BYTES)
        return sink.toByteArray().takeIf { result is HttpOutcome.Response && result.code in 200..299 && it.size <= MAX_BYTES }
    }

    companion object { const val MAX_BYTES = 2L * 1024 * 1024 }
}
