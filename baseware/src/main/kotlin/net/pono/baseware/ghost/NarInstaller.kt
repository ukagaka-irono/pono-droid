package net.pono.baseware.ghost

import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import net.pono.baseware.update.NetworkUpdater
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * .nar（zip）を展開する。ゴーストはゴースト置き場に、バルーンはバルーン置き場に入れる。
 * ゴーストの .nar に balloon/ フォルダが入っていれば、そのバルーンもバルーン置き場に入れる（SSP と同じ）。
 */
class NarInstaller(private val ghostRoot: File, private val balloonRoot: File) {

    class InstallException(message: String) : IOException(message)

    enum class Type { GHOST, BALLOON }

    data class Result(val type: Type, val dir: File)

    fun install(nar: File): Result {
        // 古いアーカイバで作られた nar はファイル名が CP932 のことがある
        val zip = openZip(nar, Charsets.UTF_8) ?: openZip(nar, TextCharsets.CP932)
            ?: throw InstallException("nar を開けません")
        zip.use {
            val entries = zip.entries().toList().filterNot { it.isDirectory }
            val installEntry = entries.firstOrNull { normalize(it.name).equals("install.txt", ignoreCase = true) }
                ?: throw InstallException("install.txt がありません")
            val install = Descript.parse(TextCharsets.decode(zip.getInputStream(installEntry).readBytes()))
            val type = when (install["type"]?.lowercase()) {
                "ghost" -> Type.GHOST
                "balloon" -> Type.BALLOON
                else -> throw InstallException("ゴーストとバルーン以外（type=${install["type"]}）はまだ対応していません")
            }
            val dirName = install["directory"]?.let(::safeName) ?: throw InstallException("install.txt に directory がありません")

            val target = File(if (type == Type.GHOST) ghostRoot else balloonRoot, dirName)
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
            if (type == Type.GHOST) installBundledBalloons(target)
            return Result(type, target)
        }
    }

    /** ゴーストに同梱のバルーン（balloon/名前/descript.txt）をバルーン置き場に写す */
    private fun installBundledBalloons(ghostDir: File) {
        val bundled = File(ghostDir, "balloon").listFiles { f -> File(f, "descript.txt").isFile }.orEmpty()
        for (dir in bundled) {
            val name = safeName(dir.name) ?: continue
            dir.copyRecursively(File(balloonRoot, name), overwrite = true)
        }
    }

    private fun openZip(file: File, charset: Charset): ZipFile? =
        runCatching { ZipFile(file, charset).also { z -> z.entries().toList() } }.getOrNull()

    private fun normalize(name: String) = name.replace('\\', '/').trimStart('/')

    private fun safeName(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }
}
