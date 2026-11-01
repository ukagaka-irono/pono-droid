package net.pono.droid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 文字列リソースが 5 つの言語で揃っていること（抜け・余り・%1$s などの食い違いが無い） */
class StringResourcesTest {
    private val res = File("src/main/res")
    private val folders = listOf("values", "values-ja", "values-b+zh+Hans", "values-b+zh+Hant", "values-ko")

    @Test
    fun everyLanguageHasTheSameStrings() {
        val base = strings("values")
        assertTrue(base.isNotEmpty())
        for (folder in folders.drop(1)) {
            val other = strings(folder)
            assertEquals("$folder に無いもの", emptySet<String>(), base.keys - other.keys)
            assertEquals("$folder にだけあるもの", emptySet<String>(), other.keys - base.keys)
            for ((name, text) in other) {
                assertEquals("$folder の $name", arguments(base.getValue(name)), arguments(text))
            }
        }
    }

    @Test
    fun everyLanguageHasTheSameFiles() {
        val files = files("values")
        for (folder in folders.drop(1)) assertEquals(folder, files, files(folder))
    }

    private fun files(folder: String) = File(res, folder).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
        .orEmpty().map { it.name }.toSortedSet()

    /** フォルダの中の strings*.xml の string と plurals。plurals は「名前/数の種類」の名前にする（言語で種類が違うので other だけ比べる） */
    private fun strings(folder: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in files(folder)) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(File(res, folder), name))
            val root = doc.documentElement
            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val key = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> if (e.getAttribute("translatable") != "false") out[key] = e.textContent
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        for (j in 0 until items.length) {
                            val item = items.item(j) as Element
                            if (item.getAttribute("quantity") == "other") out["$key/other"] = item.textContent
                        }
                    }
                }
            }
        }
        return out
    }

    private fun arguments(text: String) = Regex("%(\\d+\\$)?[sdf]").findAll(text).map { it.value }.toSortedSet()
}
