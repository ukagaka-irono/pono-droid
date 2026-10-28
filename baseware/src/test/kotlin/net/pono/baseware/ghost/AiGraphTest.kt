package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** AI グラフ（getaistate / getaistateex）の読み方と、背景の画像（shiori.logo.*） */
class AiGraphTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesValuesLabelsAddedAndMax() {
        val g = AiGraph.parse("1250,1500,45\u0001馬力,rpm\u0001100,50,x\u00011350,1550,130")
        assertEquals(listOf(1250.0, 1500.0, 45.0), g.values)
        // ラベルが足りなければ番号、加算値の数でないものは 0
        assertEquals(listOf("馬力", "rpm", "3"), g.labels)
        assertEquals(listOf(100.0, 50.0, 0.0), g.added)
        assertEquals(listOf(1350.0, 1550.0, 130.0), g.max)
        // 省いたら Neuron などのラベルで、最大値は（表示値＋加算値）の最大
        val plain = AiGraph.parse("10,30.5")
        assertEquals(listOf("Neuron", "NeuronM"), plain.labels)
        assertEquals(listOf(30.5, 30.5), plain.max)
        assertEquals(listOf(0.0, 0.0), plain.added)
    }

    private class GraphPlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        val opened: MutableList<Pair<List<AiGraph>, AiGraphLogo?>> = Collections.synchronizedList(mutableListOf())

        override fun openAiGraph(title: String, graphs: List<AiGraph>, logo: AiGraphLogo?): Boolean {
            opened += graphs to logo
            return true
        }
    }

    @Test
    fun opensTheGraphScreenWithNumberedLogos() = runBlocking {
        val shiori = ScriptedShiori(mapOf("OnTest" to """\![open,aigraph]\e"""))
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost(extraDescript = "shiori.logo.file,graph.png\r\nshiori.logo.align,rightbottom\r\nshiori.logo.x,8\r\nshiori.logo.y,4\r\n")
            File(ghost.masterDir, "graph0.png").writeBytes(byteArrayOf(0))
            val platform = GraphPlatform()
            // 2枚目には画像が無い
            val c = testController(ghost, MultiGraphShiori(shiori), scope, platform)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            c.raise("OnTest", emptyList())
            withTimeout(5000) { while (platform.opened.isEmpty()) delay(10) }
            val (graphs, logo) = platform.opened.single()
            assertEquals(listOf(listOf(1.0, 2.0), listOf(3.0)), graphs.map { it.values })
            assertEquals(listOf("graph0.png", null), logo!!.files.map { it?.name })
            assertEquals(Triple("rightbottom", 8, 4), Triple(logo.align, logo.x, logo.y))
        } finally {
            scope.cancel()
        }
    }

    /** getaistateex で2枚答える SHIORI */
    private class MultiGraphShiori(private val inner: ScriptedShiori) : net.pono.baseware.shiori.Shiori by inner {
        override fun request(request: ByteArray): ByteArray {
            val text = request.toString(Charsets.UTF_8)
            if ("\r\nID: getaistateex\r\n" in text) {
                val graph = when {
                    "\r\nReference0: 0\r\n" in text -> "1,2"
                    "\r\nReference0: 1\r\n" in text -> "3"
                    else -> return "SHIORI/3.0 204 No Content\r\nCharset: UTF-8\r\n\r\n".toByteArray()
                }
                return "SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\nValue: $graph\r\n\r\n".toByteArray()
            }
            return inner.request(request)
        }
    }

    @Test
    fun noLogoWhenNoImageExists() {
        assertNull(AiGraph.parse("").values.firstOrNull())
    }
}
