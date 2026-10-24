package net.pono.baseware.net

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** RSS / Atom の1項目（OnExecuteRSSComplete の1つの Reference） */
data class FeedItem(val title: String, val link: String, val updated: LocalDateTime?, val author: String, val content: String) {
    /** タイトル[1]URL[1]更新日時（年,月,日,時,分,秒）[1]作者[1]内容。改行はバイト値1にしない（Reference の中の区切りと混ざるので空白に） */
    fun reference(): String = listOf(
        title, link, updated?.let { "%d,%d,%d,%d,%d,%d".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute, it.second) }.orEmpty(),
        author, content,
    ).joinToString("\u0001") { it.replace('\r', ' ').replace('\n', ' ').replace('\u0001', ' ') }
}

/** RSS 1.0 / 2.0 と Atom を読む。外の実体（DTD など）は読まない */
object FeedParser {
    fun parse(bytes: ByteArray): List<FeedItem>? {
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                // XXE を防ぐ（使えない環境もあるので、使えるものだけ）
                for (feature in listOf(
                    "http://apache.org/xml/features/disallow-doctype-decl" to true,
                    "http://xml.org/sax/features/external-general-entities" to false,
                    "http://xml.org/sax/features/external-parameter-entities" to false,
                )) runCatching { setFeature(feature.first, feature.second) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrNull() ?: return null
        val root = doc.documentElement ?: return null
        val items = elements(root).filter { it.localName() == "item" || it.localName() == "entry" }
        if (items.isEmpty() && root.localName() !in setOf("rss", "rdf", "feed", "RDF")) return null
        return items.map { item ->
            val children = elements(item)
            fun text(vararg names: String) = names.firstNotNullOfOrNull { n -> children.firstOrNull { it.localName() == n }?.textContent?.trim() }.orEmpty()
            val link = children.firstOrNull { it.localName() == "link" }?.let { l -> l.getAttribute("href").ifEmpty { l.textContent.trim() } }.orEmpty()
            val author = children.firstOrNull { it.localName() == "author" }?.let { a ->
                elements(a).firstOrNull { it.localName() == "name" }?.textContent?.trim() ?: a.textContent.trim()
            } ?: text("creator")
            FeedItem(
                title = text("title"),
                link = link,
                updated = parseDate(text("pubDate", "updated", "published", "date")),
                author = author,
                content = text("description", "summary", "content", "encoded"),
            )
        }
    }

    /** --from / --to / --limit で絞る（SSP 2.8.92）。期間を決めたら、更新日時の無い項目は返さない。--limit なら新しい順に打ち切る */
    fun filter(items: List<FeedItem>, from: LocalDate?, to: LocalDate?, limit: Int?): List<FeedItem> {
        var out = items
        if (from != null || to != null) {
            out = out.filter { i ->
                val d = i.updated ?: return@filter false
                (from == null || !d.toLocalDate().isBefore(from)) && (to == null || !d.toLocalDate().isAfter(to))
            }
        }
        // どれも日時を持たなければ、書かれた順のまま
        if (out.any { it.updated != null }) out = out.sortedByDescending { it.updated ?: LocalDateTime.MIN }
        if (limit != null && limit > 0) out = out.take(limit)
        return out
    }

    private fun Element.localName() = localName ?: nodeName.substringAfter(':')

    /** 子孫の要素（RSS 1.0 では item が channel の外にあるので、深さに関係なく探す） */
    private fun elements(e: Element): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = e.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType != Node.ELEMENT_NODE) continue
            val child = n as Element
            out += child
            if (child.localName() in setOf("channel", "RDF", "rdf")) out += elements(child)
        }
        return out
    }

    /** RFC 822（RSS 2.0 の pubDate）と ISO 8601（Atom・dc:date）。端末の時刻にする */
    fun parseDate(s: String): LocalDateTime? {
        if (s.isBlank()) return null
        val zone = ZoneId.systemDefault()
        runCatching { return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(zone).toLocalDateTime() }
        runCatching {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH))
                .withZoneSameInstant(zone).toLocalDateTime()
        }
        runCatching { return OffsetDateTime.parse(s.trim()).atZoneSameInstant(zone).toLocalDateTime() }
        runCatching { return LocalDateTime.parse(s.trim()) }
        runCatching { return LocalDate.parse(s.trim().take(10)).atStartOfDay() }
        return null
    }
}
