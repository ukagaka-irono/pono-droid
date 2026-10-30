package net.pono.baseware.ghost

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BugSweepNarTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun nar(name: String, entries: List<Pair<String, String>>): File {
        val f = File(tmp.root, name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, text) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    /** 入っているゴースト（second）と、そこへ入れる NarInstaller */
    private fun installed(): Pair<NarInstaller, File> {
        val ghosts = tmp.newFolder("ghost")
        val balloons = tmp.newFolder("balloon")
        val dir = File(ghosts, "second")
        File(dir, "ghost/master").mkdirs()
        File(dir, "ghost/master/keep.dic").writeText("old")
        File(dir, "pono").mkdirs()
        File(dir, "pono/profile.properties").writeText("x=1")
        return NarInstaller(ghosts, balloons) to dir
    }

    @Test
    fun refreshDoesNotDestroyTheGhostWhenTheNarIsRejected() {
        val (installer, dir) = installed()
        val bad = nar(
            "bad.nar",
            listOf(
                "install.txt" to "type,ghost\r\ndirectory,second\r\nrefresh,1\r\n",
                "ghost/master/new.dic" to "new",
                "../escape.txt" to "x",
            ),
        )
        assertTrue(runCatching { installer.install(bad) }.isFailure)
        // 断られた .nar のせいで、前のゴーストが消えてはいけない
        assertTrue(File(dir, "ghost/master/keep.dic").isFile)
    }

    @Test
    fun refreshKeepsPonoDirectory() {
        val (installer, dir) = installed()
        installer.install(
            nar(
                "ok.nar",
                listOf("install.txt" to "type,ghost\r\ndirectory,second\r\nrefresh,1\r\n", "ghost/master/new.dic" to "new"),
            ),
        )
        assertTrue(File(dir, "pono/profile.properties").isFile)
    }

    @Test
    fun longJapaneseNamesFitInOneFileName() {
        val name = FileDrop.safeName("あ".repeat(120) + ".txt", 1)
        val bytes = name.toByteArray(Charsets.UTF_8).size
        assertTrue("$bytes バイト", bytes <= 255)
    }
}
