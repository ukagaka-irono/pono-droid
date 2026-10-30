package net.pono.baseware.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BugSweepShellTest {
    @Test fun h02_hugeWaitRange() {
        val f = SurfaceTable.parse(
            "surface0 {\nanimation0.interval,always\nanimation0.pattern0,overlay,100,0-2147483647,0,0\n}",
        )[0]!!.animations[0]!!.frames[0]
        kotlin.random.Random.nextInt(f.waitMinMs, f.waitMaxMs + 1)
    }

    @Test fun h03_pnaNextToImage() {
        val dir = kotlin.io.path.createTempDirectory("shell").toFile()
        try {
            val parts = File(dir, "parts").apply { mkdirs() }
            File(parts, "eye.png").writeBytes(ByteArray(1))
            File(parts, "eye.pna").writeBytes(ByteArray(1))
            val shell = Shell(dir)
            val png = shell.file("parts\\eye.png")!!
            assertEquals(File(parts, "eye.pna").canonicalFile, shell.pnaFor(png)?.canonicalFile)
        } finally { dir.deleteRecursively() }
    }

    @Test fun h05_rangeHeadersMultiply() {
        val text = "surface0-9999,surface10000-19999 {\n" +
            (0 until 20).joinToString("\n") { "element$it,overlay,a.png,0,0" } + "\n}"
        val table = SurfaceTable.parse(text)
        val total = table.definitions.values.sumOf { it.elements.size }
        assertTrue("total=$total", total <= 100_000)
    }

    @Test fun h11_localeDigits() {
        val saved = java.util.Locale.getDefault()
        val dir = kotlin.io.path.createTempDirectory("shell").toFile()
        try {
            File(dir, "surface0005.png").writeBytes(ByteArray(1))
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertNotNull(Shell(dir).surfaceImage(5))
        } finally { java.util.Locale.setDefault(saved); dir.deleteRecursively() }
    }

    @Test fun h17_trailingComments() {
        val t = SurfaceNameTable.parse("group,通常 \n{ // 開く\n0,素 // メモ\n}\n")
        assertEquals("素", t.groups.single().entries.single().name)
    }
}
