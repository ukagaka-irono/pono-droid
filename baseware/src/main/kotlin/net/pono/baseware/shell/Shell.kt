package net.pono.baseware.shell

import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import java.io.File

data class BindGroup(val id: Int, val category: String, val part: String, val default: Boolean)

/** シェル（見た目）のディレクトリ。画像の読み込みはプラットフォーム側で行う。 */
class Shell(val dir: File) {
    val descript: Descript = Descript.read(File(dir, "descript.txt"))
    val surfaces: SurfaceTable = File(dir, "surfaces.txt").takeIf { it.isFile }
        ?.let { SurfaceTable.parse(TextCharsets.decode(it.readBytes())) }
        ?: SurfaceTable.EMPTY

    val name: String get() = descript["name"] ?: dir.name

    /** 1 なら PNG のアルファをそのまま使う。0 なら .pna か左上の色で透過する。 */
    val useSelfAlpha: Boolean get() = descript.int("seriko.use_self_alpha") == 1

    /**
     * 着せ替えのパーツ。descript.txt の `sakura.bindgroupN.name,カテゴリ,パーツ` と `sakura.bindgroupN.default,1`。
     * N は surfaces.txt の interval が bind のアニメーション番号に対応する。
     */
    fun bindGroups(scope: Int): List<BindGroup> {
        val prefix = "${scopeName(scope)}.bindgroup"
        val pattern = Regex("""^${Regex.escape(prefix)}(\d+)\.name$""")
        return descript.entries.mapNotNull { (key, value) ->
            val id = pattern.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val parts = value.split(',').map { it.trim() }
            if (parts.size < 2) return@mapNotNull null
            BindGroup(id, parts[0], parts[1], descript.int("$prefix$id.default") == 1)
        }.sortedBy { it.id }
    }

    fun defaultSurface(scope: Int): Int =
        descript.int("${scopeName(scope)}.seriko.defaultsurface") ?: if (scope == 0) 0 else 10

    private val files: Map<String, File> by lazy {
        dir.listFiles()?.associateBy { it.name.lowercase() }.orEmpty()
    }

    /** ファイル名の大文字小文字は区別しない（Windows 由来のため）。 */
    fun file(name: String): File? {
        val path = name.replace('\\', '/')
        return if ('/' in path) {
            File(dir, path).takeIf { it.isFile }
        } else {
            files[path.lowercase()]
        }
    }

    /** surfaceN.png。surface0000.png のような0埋めの名前も探す。 */
    fun surfaceImage(id: Int): File? =
        file("surface$id.png") ?: file("surface%04d.png".format(id))

    /** 画像と同名の .pna（アルファ用のグレースケール画像） */
    fun pnaFor(png: File): File? = file(png.nameWithoutExtension + ".pna")

    fun hasSurface(id: Int): Boolean = surfaceImage(id) != null || surfaces[id]?.elements?.isNotEmpty() == true

    companion object {
        fun scopeName(scope: Int) = when (scope) {
            0 -> "sakura"
            1 -> "kero"
            else -> "char$scope"
        }
    }
}
