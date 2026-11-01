package net.pono.baseware.text

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** baseware の言語表が言語の間で揃っていること、%1 などが入ること */
class MessagesTest {
    @After
    fun reset() {
        Messages.language = "ja"
    }

    @Test
    fun everyLanguageHasTheSameKeys() {
        val english = Messages.table("en")
        assertTrue(english.isNotEmpty())
        for (language in Messages.LANGUAGES) {
            val table = Messages.table(language)
            assertEquals("$language に無いキー", emptySet<String>(), english.keys - table.keys)
            assertEquals("$language にだけあるキー", emptySet<String>(), table.keys - english.keys)
        }
    }

    @Test
    fun everyLanguageUsesTheSameArguments() {
        val english = Messages.table("en")
        for (language in Messages.LANGUAGES) {
            for ((key, value) in Messages.table(language)) {
                assertEquals("$language の $key", arguments(english.getValue(key)), arguments(value))
            }
        }
    }

    @Test
    fun noLabelInsideAChoiceHasAComma() {
        // \q[ラベル,…] のラベルに , があると、選択肢がずれる
        val label = Regex("""\\q\[([^\]]*)""")
        for (language in Messages.LANGUAGES) {
            for ((key, value) in Messages.table(language)) {
                for (m in label.findAll(value)) {
                    val text = m.groupValues[1].substringBefore(",pono:").substringBefore(",%")
                    assertTrue("$language の $key: $text", ',' !in text)
                }
            }
        }
    }

    @Test
    fun noValueStartsWithABracket() {
        // \_q などのタグの直後に置く値が [ で始まると、タグの引数として読まれて消える
        for (language in Messages.LANGUAGES) {
            for ((key, value) in Messages.table(language)) {
                assertTrue("$language の $key: $value", !value.startsWith("["))
            }
        }
    }

    @Test
    fun matchesTextMadeFromAValue() {
        val pattern = Messages.patternOf("%1: %2%%（%3分）")
        assertTrue(pattern.matches("a.b: 50%（3分）"))
        assertTrue(pattern.matches(": %（分）"))
        assertTrue(!pattern.matches("a: 50（3分）"))
        assertTrue(Messages.matches("log.shell.notfound", Messages.get("log.shell.notfound", "x(1)")))
        assertTrue(!Messages.matches("log.shell.notfound", Messages.get("log.balloon.notfound", "x")))
        assertTrue(!Messages.matches("no.such.key", "no.such.key"))
    }

    @Test
    fun formatsArgumentsAndFallsBack() {
        assertEquals("a-b-%", Messages.format("%1-%2-%%", arrayOf("a", "b")))
        assertEquals("50%（3）", Messages.format("%1%（%2）", arrayOf(50, 3)))
        assertEquals("no.such.key", Messages.get("no.such.key"))
        Messages.language = "xx"
        assertEquals("en", Messages.language)
        assertEquals("English", Messages.sspName())
        assertEquals(1041, Messages.sspId("ja"))
        assertEquals(1028, Messages.sspId("zh-Hant"))
    }

    @Test
    fun keepsSpacesAroundValues() {
        assertEquals(mapOf("a" to " x ", "b" to "1,2"), Messages.parse("// c\r\na, x \r\n\r\nb,1,2\r\n"))
    }

    private fun arguments(text: String) = Regex("%[1-9]").findAll(text).map { it.value }.toSortedSet()
}
