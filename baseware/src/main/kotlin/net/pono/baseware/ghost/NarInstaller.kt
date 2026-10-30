package net.pono.baseware.ghost

import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import net.pono.baseware.update.NetworkUpdater
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * .nar（zip）を展開する。ゴーストはゴースト置き場に、バルーンはバルーン置き場に入れる。シェル（type,shell）と追加ファイル（supplement）は
 * 受け取るゴースト（[install] の target。install.txt の accept と名前が合うもの）に入れ、package は中の .nar を順に入れる。
 * ゴーストやシェルの .nar に同梱のバルーンがあれば、そのバルーンもバルーン置き場に入れる（SSP と同じ）。
 */
class NarInstaller(private val ghostRoot: File, private val balloonRoot: File) {

    open class InstallException(message: String) : IOException(message)

    /** install.txt の accept が受け取るゴーストと合わない（OnInstallRefuse） */
    class RefusedException(val accept: String, val type: String, val name: String) : InstallException("refused")

    enum class Type(val id: String) { GHOST("ghost"), BALLOON("balloon"), SHELL("shell"), SUPPLEMENT("supplement") }

    /** 入れたもの1つ。[name] は install.txt の name（無ければ directory） */
    data class Installed(val type: Type, val name: String, val dir: File)

    /**
     * [type] と [dir] は最初に入れたもの、[installed] は同梱のバルーンや package の中身も含めた全部。
     * [bootGhost] は package の install.txt の bootghost（入れた後、ゴーストが動いていなければ起動するゴーストのディレクトリ名）
     */
    data class Result(
        val type: Type,
        val dir: File,
        val installed: List<Installed> = listOf(Installed(type, dir.name, dir)),
        val bootGhost: String? = null,
    )

    /** @param target シェルと追加ファイルを受け取るゴースト（たいてい起動中のゴースト）。無ければそれらは入れられない */
    fun install(nar: File, target: Ghost? = null): Result = install(nar, target, depth = 0)

    private fun install(nar: File, target: Ghost?, depth: Int): Result {
        // 古いアーカイバで作られた nar はファイル名が CP932 のことがある
        val zip = openZip(nar, Charsets.UTF_8) ?: openZip(nar, TextCharsets.CP932)
            ?: throw InstallException("nar を開けません")
        zip.use {
            val entries = zip.entries().toList().filterNot { it.isDirectory }
            val installEntry = entries.firstOrNull { normalize(it.name).equals("install.txt", ignoreCase = true) }
                ?: throw InstallException("install.txt がありません")
            val install = Descript.parse(TextCharsets.decode(zip.getInputStream(installEntry).readBytes()))
            val typeName = install["type"]?.trim()?.lowercase()
            if (typeName == "package") return installPackage(zip, entries, target, depth, install["bootghost"]?.let(::safeName))
            val type = when (typeName) {
                "ghost" -> Type.GHOST
                "balloon" -> Type.BALLOON
                "shell" -> Type.SHELL
                "supplement" -> Type.SUPPLEMENT
                else -> throw InstallException("ゴースト・シェル・バルーン・追加ファイル以外（type=${install["type"]}）は入れられません")
            }
            val dirName = install["directory"]?.let(::safeName) ?: throw InstallException("install.txt に directory がありません")
            val name = install["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: dirName
            // シェルと追加ファイルは、accept で名指しされたゴーストにだけ入れる
            if (type == Type.SHELL || type == Type.SUPPLEMENT) {
                val accept = install["accept"]?.trim().orEmpty()
                if (target == null || (accept.isNotEmpty() && !accepts(target, accept))) throw RefusedException(accept, type.id, name)
            }
            val target2 = when (type) {
                Type.GHOST -> File(ghostRoot, dirName)
                Type.BALLOON -> File(balloonRoot, dirName)
                Type.SHELL -> File(target!!.dir, "shell/$dirName")
                Type.SUPPLEMENT -> target!!.dir
            }
            // ゴーストのフォルダの pono/ は本体の置き場（設定・予定表など）。.nar からは書かせない
            val intoGhostDir = type == Type.GHOST || type == Type.SUPPLEMENT
            val files = entries.filterNot { intoGhostDir && normalize(it.name).substringBefore('/').equals(NetworkUpdater.PONO_DIR, ignoreCase = true) }
            // 先に全部のパスを確かめ、作業用のフォルダに展開し切ってから入れ替える。不正なパスや壊れた .nar で失敗しても、
            // refresh で前の中身を消してしまったり、半分だけ書いたりしない
            val staging = kotlin.io.path.createTempDirectory("nar").toFile()
            try {
                val canonicalStaging = staging.canonicalFile
                val outs = files.map { entry ->
                    val out = File(staging, normalize(entry.name)).canonicalFile
                    if (!out.path.startsWith(canonicalStaging.path + File.separator)) throw InstallException("不正なパス: ${entry.name}")
                    entry to out
                }
                for ((entry, out) in outs) {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                if (install["refresh"]?.trim() == "1" && type != Type.SUPPLEMENT) {
                    refresh(target2, install["refreshundeletemask"], keepDir = if (intoGhostDir) NetworkUpdater.PONO_DIR else null)
                }
                target2.mkdirs()
                staging.copyRecursively(target2, overwrite = true)
            } finally {
                staging.deleteRecursively()
            }
            // 上書きインストールで古いファイルを消す指示があれば従う（前の版が残した delete.txt ではなく、この .nar の中のものだけ）
            if (files.any { NetworkUpdater.DELETE_LISTS.matches(normalize(it.name).lowercase()) }) NetworkUpdater.applyDeleteList(target2)
            val installed = mutableListOf(Installed(type, name, target2))
            if (type == Type.GHOST || type == Type.SHELL) installed += installBundledBalloons(target2, install)
            return Result(type, target2, installed)
        }
    }

    /** accept がゴーストの本体側の名前か、descript.txt の install.accept と合うか */
    private fun accepts(ghost: Ghost, accept: String): Boolean =
        accept == ghost.sakuraName || accept == ghost.name || ghost.descript["install.accept"]?.split(',')?.any { it.trim() == accept } == true

    /** refresh,1。入れる前に中身を消す（refreshundeletemask の名前のファイルと、[keepDir] のフォルダの中は残す） */
    private fun refresh(dir: File, mask: String?, keepDir: String? = null) {
        if (!dir.isDirectory) return
        val keep = mask.orEmpty().split(':').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val kept = keepDir?.let { File(dir, it) }
        dir.walkBottomUp().onEnter { it != kept }.forEach { f ->
            if (f == dir || f == kept) return@forEach
            if (f.isFile && f.name.lowercase() !in keep) f.delete()
            if (f.isDirectory && f.listFiles().isNullOrEmpty()) f.delete()
        }
    }

    /** type,package。中の .nar を順に入れる（package の中の package は入れない） */
    private fun installPackage(zip: ZipFile, entries: List<java.util.zip.ZipEntry>, target: Ghost?, depth: Int, bootGhost: String?): Result {
        if (depth > 0) throw InstallException("package の中の package は入れられません")
        val nars = entries.filter { it.name.lowercase().endsWith(".nar") }.take(MAX_PACKAGE_NARS)
        if (nars.isEmpty()) throw InstallException("package に .nar がありません")
        val installed = mutableListOf<Installed>()
        for (entry in nars) {
            val tmp = File.createTempFile("package", ".nar")
            try {
                zip.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                installed += install(tmp, target, depth + 1).installed
            } finally {
                tmp.delete()
            }
        }
        // bootghost は入れたゴーストの中のものだけ
        val boot = bootGhost?.takeIf { b -> installed.any { it.type == Type.GHOST && it.dir.name == b } }
        return Result(installed.first().type, installed.first().dir, installed, boot)
    }

    /**
     * ゴーストやシェルに同梱のバルーンをバルーン置き場に写す。install.txt の balloon.directory（と balloon0.directory、balloon1… の続き）
     * があれば、.nar の中の *.source.directory のフォルダ（書いてなければ *.directory と同じ名前）を、*.directory の名前で入れる
     * （SSP と同じ。Emily/Phase4.5 など）。どれも無ければ、balloon/名前/descript.txt の形で入っているものをその名前で入れる
     */
    private fun installBundledBalloons(dir: File, install: Descript): List<Installed> {
        val keys = listOf("balloon") + generateSequence(0) { it + 1 }.map { "balloon$it" }.takeWhile { install["$it.directory"] != null }
        val named = keys.filter { install["$it.directory"] != null }
        val out = mutableListOf<Installed>()
        val root = dir.canonicalFile
        for (key in named) {
            val name = install["$key.directory"]?.let(::safeName) ?: continue
            val sourceName = install["$key.source.directory"]?.let(::normalize)?.trim()?.takeIf { it.isNotEmpty() } ?: name
            val source = File(root, sourceName).canonicalFile
            // .nar の外（ゴーストのフォルダの外）を指していたら写さない
            if (!source.path.startsWith(root.path + File.separator) || !File(source, "descript.txt").isFile) continue
            val dest = File(balloonRoot, name)
            if (install["$key.refresh"]?.trim() == "1") refresh(dest, install["$key.refreshundeletemask"])
            source.copyRecursively(dest, overwrite = true)
            out += Installed(Type.BALLOON, name, dest)
        }
        if (named.isNotEmpty()) return out
        val bundled = File(dir, "balloon").listFiles { f -> File(f, "descript.txt").isFile }.orEmpty()
        for (b in bundled) {
            val name = safeName(b.name) ?: continue
            val dest = File(balloonRoot, name)
            b.copyRecursively(dest, overwrite = true)
            out += Installed(Type.BALLOON, name, dest)
        }
        return out
    }

    private fun openZip(file: File, charset: Charset): ZipFile? =
        runCatching { ZipFile(file, charset).also { z -> z.entries().toList() } }.getOrNull()

    private fun normalize(name: String) = name.replace('\\', '/').trimStart('/')

    private fun safeName(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it }

    private companion object {
        /** package に入れられる .nar の数 */
        const val MAX_PACKAGE_NARS = 64
    }
}
