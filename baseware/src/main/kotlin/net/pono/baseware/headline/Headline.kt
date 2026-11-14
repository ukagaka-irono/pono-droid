package net.pono.baseware.headline

import net.pono.baseware.ghost.Ghost
import net.pono.baseware.net.FeedDocument
import net.pono.baseware.text.AtomicFiles
import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** HEADLINE の配布物。type,rss は pono-droid が登録した RSS/Atom の保存形式。 */
class Headline(val dir: File) {
    val descript = Descript.read(File(dir, "descript.txt"))
    val name: String get() = descript["name"]?.let(Ghost::plainText)?.trim().orEmpty().ifEmpty { dir.name }
    val url: String get() = descript["url"]?.trim().orEmpty()
    val openUrl: String get() = descript["openurl"]?.trim().orEmpty()
    val dllName: String get() = descript["dllname"]?.trim().orEmpty()
    val isFeed: Boolean get() = descript["type"]?.trim()?.equals("rss", true) == true
    val alwaysDisplay: Boolean get() = descript.int("alwaysdisplay") == 1
    val isValid: Boolean get() = descript["name"]?.isNotBlank() == true && webUrl(url) != null &&
        (isFeed || (dllName.isNotEmpty() && webUrl(openUrl) != null))

    fun matches(key: String): Boolean = key == name || key == dir.name ||
        key.trimEnd('/', '\\') == dir.absolutePath.trimEnd('/', '\\')

    fun readmeText(): String? = TextCharsets.fileIn(dir, descript["readme"]?.takeIf { it.isNotBlank() } ?: "readme.txt")
        ?.let { runCatching { TextCharsets.decode(it.readBytes(), descript["readme.charset"]) }.getOrNull() }

    companion object {
        fun webUrl(value: String): String? = runCatching {
            val uri = URI(value.trim())
            value.trim().takeIf { uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
                uri.port <= 65535 && value.none { it.isISOControl() } }
        }.getOrNull()

        internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

class HeadlineRepository(val root: File) {
    fun list(): List<Headline> = root.listFiles { file -> file.isDirectory }.orEmpty()
        .mapNotNull { runCatching { Headline(it) }.getOrNull() }.filter { it.isValid }.sortedBy { it.name }

    fun find(key: String): Headline? = list().firstOrNull { it.matches(key) }

    /** 検証済みのフィードを登録。配布物のファイルを上書きしない。 */
    fun addFeed(url: String, feed: FeedDocument): Headline {
        val source = requireNotNull(Headline.webUrl(url)) { "Invalid feed URL" }
        list().firstOrNull { it.isFeed && it.url == source }?.let { return it }
        root.mkdirs()
        val name = "rss-${Headline.digest(source).take(24)}"
        var dir = File(root, name)
        var suffix = 0
        while (dir.exists()) dir = File(root, "$name-${++suffix}")
        check(dir.mkdirs()) { "Cannot create feed directory" }
        fun plain(value: String) = value.replace("\\", "\\\\").replace("%", "\\%")
            .replace('\r', ' ').replace('\n', ' ').replace('\u0001', ' ')
        val title = plain(feed.title).ifBlank { URI(source).host }
        val site = Headline.webUrl(feed.link) ?: source
        AtomicFiles.write(File(dir, "descript.txt")) { out ->
            out.write("charset,UTF-8\ntype,rss\nname,$title\nurl,$source\nopenurl,$site\n".toByteArray(Charsets.UTF_8))
        }
        return Headline(dir)
    }

    fun uninstall(headline: Headline): Boolean {
        val dir = headline.dir.canonicalFile
        if (dir.parentFile != root.canonicalFile) return false
        return dir.deleteRecursively()
    }
}
