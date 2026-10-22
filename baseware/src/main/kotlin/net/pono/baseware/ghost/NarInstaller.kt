package net.pono.baseware.ghost

import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import net.pono.baseware.update.NetworkUpdater
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** .nar（zip）をゴースト置き場に展開する。今はゴーストのみ対応する。 */
class NarInstaller(private val ghostRoot: File) {

    class InstallException(message: String) : IOException(message)

    /** @return 展開先のゴーストディレクトリ */
    fun install(nar: File): File {
        // 古いアーカイバで作られた nar はファイル名が CP932 のことがある
        val zip = openZip(nar, Charsets.UTF_8) ?: openZip(nar, TextCharsets.CP932)
            ?: throw InstallException("nar を開けません")
        zip.use {
            val entries = zip.entries().toList().filterNot { it.isDirectory }
            val installEntry = entries.firstOrNull { normalize(it.name).equals("install.txt", ignoreCase = true) }
                ?: throw InstallException("install.txt がありません")
            val install = Descript.parse(TextCharsets.decode(zip.getInputStream(installEntry).readBytes()))
            val type = install["type"]?.lowercase()
            if (type != "ghost") throw InstallException("ゴースト以外（type=$type）はまだ対応していません")
            val dirName = install["directory"]?.let(::safeName) ?: throw InstallException("install.txt に directory がありません")

            val target = File(ghostRoot, dirName)
            val canonicalTarget = target.canonicalFile
            for (entry in entries) {
                val out = File(target, normalize(entry.name)).canonicalFile
                if (!out.path.startsWith(canonicalTarget.path + File.separator)) {
                    throw InstallException("不正なパス: ${entry.name}")
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            // 上書きインストールで古いファイルを消す指示があれば従う
            NetworkUpdater.applyDeleteList(target)
            return target
        }
    }

    private fun openZip(file: File, charset: Charset): ZipFile? =
        runCatching { ZipFile(file, charset).also { z -> z.entries().toList() } }.getOrNull()

    private fun normalize(name: String) = name.replace('\\', '/').trimStart('/')

    private fun safeName(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
}
