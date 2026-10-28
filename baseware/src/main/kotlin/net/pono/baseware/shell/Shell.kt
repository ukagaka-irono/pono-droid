package net.pono.baseware.shell

import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import java.io.File

/**
 * 着せ替えのパーツ。[thumbnail] は bindgroup*.name の3つ目（サムネイル名）、[addIds] は bindgroup*.addid
 * （このパーツを付けたら一緒に付くもの）
 */
/** seriko.use_self_alpha の3通り */
enum class SelfAlpha { OFF, ON, FULL }

data class BindGroup(
    val id: Int,
    val category: String,
    val part: String,
    val default: Boolean,
    val thumbnail: String? = null,
    val addIds: Set<Int> = emptySet(),
)

/** bindoption*.group,カテゴリ,オプション。[mustSelect] なら必ず1つ付け、[multiple] なら同じカテゴリから何個でも付けられる */
data class BindOption(val mustSelect: Boolean = false, val multiple: Boolean = false) {
    /** OnNotifyDressupInfo のオプション（mustselect+multiple のように + でつなぐ） */
    val text: String get() = listOfNotNull("mustselect".takeIf { mustSelect }, "multiple".takeIf { multiple }).joinToString("+")
}

/** 着せ替えメニューの1行（menuitem* / menuitemex*）。[id] が null なら区切り線、[submenu] があればその名前の階層の中 */
data class DressupMenuItem(val id: Int?, val submenu: String? = null)

/** シェル（見た目）のディレクトリ。画像の読み込みはプラットフォーム側で行う。 */
class Shell(val dir: File) {
    val descript: Descript = Descript.read(File(dir, "descript.txt"))
    /**
     * surfaces.txt と、surfaces_xxx.txt のように分けて書かれた定義（SSP と同じく surfaces*.txt を全部読む）。
     * 古いシェルの alias.txt（surface.alias だけを書いたファイル）も一緒に読む。
     * ファイルごとに文字コードが違うことがあるので、それぞれ読んでからつなぐ
     */
    val surfaces: SurfaceTable = dir.listFiles { f -> f.isFile && DEFINITION_FILE.matches(f.name.lowercase()) }
        ?.sortedWith(compareBy({ it.name.lowercase() != "surfaces.txt" }, { it.name.lowercase() }))
        ?.takeIf { it.isNotEmpty() }
        // ファイルの境目に印を入れる（descript ブレスはファイルごと）
        ?.joinToString("\n" + SurfaceTable.FILE_BREAK + "\n") { TextCharsets.decode(it.readBytes()) }
        ?.let { text ->
            // surface.append は、画像があるサーフェスにも足せる
            val pngs = dir.listFiles()?.mapNotNull { SURFACE_FILE.matchEntire(it.name.lowercase())?.groupValues?.get(1)?.toIntOrNull() }.orEmpty()
            SurfaceTable.parse(text, pngs.toSet())
        }
        ?: SurfaceTable.EMPTY

    val name: String get() = descript["name"] ?: dir.name

    /**
     * 発言履歴の顔（正方形）を [surfaceId] の絵（幅 [width]・高さ [height]）から切り出す矩形。SSP と同じく、icon.rect があればそれを、
     * 無ければ当たり判定の face / 顔、次に head / 頭 を少し広げて使う。長い辺に合わせて中心から正方形にし、絵からはみ出さないようずらす。
     * どれも無ければ null
     */
    fun faceRect(surfaceId: Int, width: Int, height: Int): SurfaceRect? {
        if (width <= 0 || height <= 0) return null
        val def = surfaces[surfaceId]
        val explicit = def?.iconRect
        val rect = explicit ?: run {
            val collisions = def?.collisions?.values.orEmpty()
            val c = collisions.firstOrNull { it.id.lowercase() == "face" || it.id == "顔" }
                ?: collisions.firstOrNull { it.id.lowercase() == "head" || it.id == "頭" }
                ?: return null
            // 当たり判定は顔ぎりぎりのことが多いので、長い辺の 4 分の 1 ずつ余白を足す
            val margin = maxOf(c.right - c.left, c.bottom - c.top) / 4
            SurfaceRect(c.left - margin, c.top - margin, c.right + margin, c.bottom + margin)
        }
        val side = minOf(maxOf(rect.right - rect.left, rect.bottom - rect.top), width, height)
        if (side <= 0) return null
        val left = ((rect.left + rect.right - side) / 2).coerceIn(0, width - side)
        val top = ((rect.top + rect.bottom - side) / 2).coerceIn(0, height - side)
        return SurfaceRect(left, top, left + side, top + side)
    }

    /** 発言履歴の顔の枠の色（[scope] の icon.frame.color.r / g / b、0xRRGGBB）。書いていなければ null */
    fun iconFrameColor(scope: Int): Int? {
        val parts = listOf("r", "g", "b").map { c -> descript.int("${scopeName(scope)}.icon.frame.color.$c") }
        if (parts.all { it == null }) return null
        val (r, g, b) = parts.map { (it ?: 0).coerceIn(0, 255) }
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * seriko.use_self_alpha。1 / true なら PNG のアルファを使い（無ければ .pna、それも無ければ左上の色で抜く）、
     * full ならアルファの無い画像は左上の色で抜かずに不透明のまま。0 なら PNG のアルファは使わず、.pna か左上の色
     */
    val selfAlpha: SelfAlpha get() = when (descript["seriko.use_self_alpha"]?.trim()?.lowercase()) {
        "1", "true" -> SelfAlpha.ON
        "full" -> SelfAlpha.FULL
        else -> SelfAlpha.OFF
    }

    /**
     * seriko.paint_transparent_region_black。1 なら左上の色の画素を黒にしてから透明度を掛け、0 なら色のまま。
     * 書いていなければ null（.pna の画像は 1、アルファのある画像は 0 のように扱う）
     */
    val paintTransparentBlack: Boolean? get() = descript.int("seriko.paint_transparent_region_black")?.let { it != 0 }

    /** seriko.dpi（シェルを作った画面の DPI。既定は 96）に合わせて、絵を 96 / dpi 倍で出す */
    val dpiFactor: Float get() = descript.int("seriko.dpi")?.takeIf { it in 24..960 }?.let { 96f / it } ?: 1f

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
            val addIds = descript["$prefix$id.addid"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it != id }.toSet()
            BindGroup(id, parts[0], parts[1], descript.int("$prefix$id.default") == 1, parts.getOrNull(2)?.takeIf { it.isNotEmpty() }, addIds)
        }.sortedBy { it.id }
    }

    /** カテゴリごとの着せ替えのオプション（`sakura.bindoption0.group,カテゴリ,mustselect+multiple`） */
    fun bindOptions(scope: Int): Map<String, BindOption> {
        val pattern = Regex("""^${Regex.escape(scopeName(scope))}\.bindoption\d+\.group$""")
        val out = HashMap<String, BindOption>()
        for ((key, value) in descript.entries) {
            if (!pattern.matches(key)) continue
            val category = value.substringBefore(',').trim()
            val options = value.substringAfter(',', "").split('+').map { it.trim().lowercase() }
            if (category.isNotEmpty()) out[category] = BindOption("mustselect" in options, "multiple" in options)
        }
        return out
    }

    /** 着せ替えメニューの並び（menuitem* と menuitemex* を番号の順に）。どちらも書いていなければ空 */
    fun dressupMenuItems(scope: Int): List<DressupMenuItem> {
        val pattern = Regex("""^${Regex.escape(scopeName(scope))}\.menuitem(ex)?(\d+)$""")
        return descript.entries.mapNotNull { (key, value) ->
            val m = pattern.matchEntire(key) ?: return@mapNotNull null
            val index = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val item = if (m.groupValues[1].isEmpty()) {
                DressupMenuItem(value.trim().takeIf { it != "-" }?.toIntOrNull() ?: if (value.trim() == "-") null else return@mapNotNull null)
            } else {
                val name = value.substringBefore(',').trim()
                val id = value.substringAfter(',', "").trim()
                if (name.isEmpty()) return@mapNotNull null
                DressupMenuItem(id.takeIf { it != "-" }?.toIntOrNull() ?: if (id == "-") null else return@mapNotNull null, name)
            }
            index to item
        }.sortedBy { it.first }.map { it.second }
    }

    /** シェルの descript.txt で決めてある最初のサーフェス。無ければ null（ゴーストの descript.txt か既定の値を使う） */
    fun defaultSurfaceOrNull(scope: Int): Int? = descript.int("${scopeName(scope)}.seriko.defaultsurface")

    /** 最初のサーフェス。\0 は 0、\1 は 10、3人目からは決めていなければ出さない（-1） */
    fun defaultSurface(scope: Int): Int = defaultSurfaceOrNull(scope) ?: when (scope) {
        0 -> 0
        1 -> 10
        else -> -1
    }

    private val files: Map<String, File> by lazy {
        dir.listFiles()?.associateBy { it.name.lowercase() }.orEmpty()
    }

    /** surfacetable.txt（サーフェステストの名前とグループ）。無ければ null */
    val surfaceNames: SurfaceNameTable? by lazy { files["surfacetable.txt"]?.let(SurfaceNameTable::read) }

    /** ファイル名の大文字小文字は区別しない（Windows 由来のため）。シェルのフォルダの外を指していれば null */
    fun file(name: String): File? {
        val path = name.replace('\\', '/')
        return if ('/' in path) {
            val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
            val file = runCatching { File(dir, path).canonicalFile }.getOrNull() ?: return null
            file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
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

    /** あるサーフェスの番号（画像があるものと、surfaces.txt で element を組むもの）。OnNotifyShellInfo で伝える */
    fun surfaceIds(): List<Int> {
        val fromFiles = files.keys.mapNotNull { SURFACE_FILE.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        val fromTable = surfaces.definitions.filterValues { it.elements.isNotEmpty() }.keys
        return (fromFiles + fromTable).toSortedSet().toList()
    }

    companion object {
        /**
         * [dir] のシェルの名前（descript.txt の name、無ければディレクトリ名）。[Shell] を作ると surfaces*.txt をすべて読むので、
         * 名前だけ要るとき（一覧やメニュー）はこちら
         */
        fun nameOf(dir: File): String = runCatching { Descript.read(File(dir, "descript.txt"))["name"] }.getOrNull() ?: dir.name

        private val SURFACE_FILE = Regex("""^surface0*(\d+)\.png$""")

        /** サーフェスの定義ファイル（surfaces.txt、surfaces_xxx.txt、surfaces2.txt など）と alias.txt */
        private val DEFINITION_FILE = Regex("""^(surfaces.*|alias)\.txt$""")

        fun scopeName(scope: Int) = when (scope) {
            0 -> "sakura"
            1 -> "kero"
            else -> "char$scope"
        }
    }
}
