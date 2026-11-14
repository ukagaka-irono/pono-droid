package net.pono.shiori.yaya

import net.pono.baseware.text.TextCharsets
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShioriEngineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun declaredNashiWorksWithoutProbingTheDictionary() {
        assertEquals(ShioriEngine.NASHI, ShioriEngine.forGhost(" NASHI.DLL ", temp.root))
    }

    @Test fun renamedNashiDllRecognizesJsoncAndCp932() {
        temp.newFile("ghost.json").writeBytes("""
            /* なし栞 */ {
                "format": "nashi/1", // 日本語
                "meta": {"url":"https://example.com/", "name":"髙①ｶﾅ"},
                "scripts": []
            }
        """.trimIndent().toByteArray(TextCharsets.CP932))
        assertEquals(ShioriEngine.NASHI, ShioriEngine.forGhost("shiori.dll", temp.root))
    }

    @Test fun unrelatedJsonDoesNotSelectNashi() {
        temp.newFile("ghost.json").writeText("""{"name":"other","scripts":[],"note":"\"format\":\"nashi/1\""}""")
        assertNull(ShioriEngine.forGhost("shiori.dll", temp.root))
    }

    @Test fun invalidJsonDoesNotSelectNashi() {
        temp.newFile("nashi.json").writeText("""{"format":"nashi/1", "scripts": [] /*""")
        assertNull(ShioriEngine.forGhost("shiori.dll", temp.root))
    }

    @Test fun utf8BomAndAlternateFilenameAreRecognized() {
        temp.newFile("nashi.json").writeText("\uFEFF" + """{"format":"nashi/1","scripts":[]}""")
        assertEquals(ShioriEngine.NASHI, ShioriEngine.forGhost("shiori.dll", temp.root))
    }

    @Test fun declaredEngineTakesPriorityOverAnAuxiliaryJsonFile() {
        temp.newFile("ghost.json").writeText("""{"format":"nashi/1","scripts":[]}""")
        assertEquals(ShioriEngine.YAYA, ShioriEngine.forGhost("yaya.dll", temp.root))
    }
}
