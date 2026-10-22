package net.pono.baseware.balloon

import net.pono.baseware.text.Descript
import java.io.File

/** 色（0〜255）。descript.txt の xxx.r / xxx.g / xxx.b から作る */
data class BalloonColor(val r: Int, val g: Int, val b: Int)

/** テキストを書ける範囲（バルーン画像の左上からのピクセル） */
data class TextArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** バルーン画像1枚。[index] は balloons0.png の 0 で、小さい順に並んでいる（偶数と奇数で向きが違う） */
data class BalloonImage(val file: File, val index: Int)

/**
 * インストール済みのバルーン（SSP 形式）。balloons*.png が \0、balloonk*.png が \1 用で、
 * 文字を書ける範囲や色は descript.txt に書いてある。
 */
class Balloon(val dir: File) {
    val descript: Descript = Descript.read(File(dir, "descript.txt"))

    val name: String get() = descript["name"] ?: dir.name
    val isValid: Boolean get() = File(dir, "descript.txt").isFile && images(0).isNotEmpty()

    private val files: Map<String, File> by lazy { dir.listFiles()?.associateBy { it.name.lowercase() }.orEmpty() }

    /**
     * そのキャラクター用の画像を小さい順に返す。\0 は偶数番（右側のキャラクター用）、\1 は奇数番を使い、
     * 無ければもう片方の番号や \0 用の画像で代わりにする。
     */
    fun images(scope: Int): List<BalloonImage> {
        val prefixes = if (scope == 0) listOf("balloons") else listOf("balloonk", "balloons")
        val parity = if (scope == 0) 0 else 1
        for (prefix in prefixes) {
            val all = files.values.mapNotNull { f ->
                Regex("""^${prefix}(\d+)\.png$""").matchEntire(f.name.lowercase())?.let { BalloonImage(f, it.groupValues[1].toInt()) }
            }.sortedBy { it.index }
            if (all.isEmpty()) continue
            return all.filter { it.index % 2 == parity }.ifEmpty { all }
        }
        return emptyList()
    }

    /** 画像と同名の .pna（アルファ用のグレースケール画像） */
    fun pnaFor(png: File): File? = files[png.nameWithoutExtension.lowercase() + ".pna"]

    /**
     * 文字を書ける範囲。validrect（無ければ origin と wordwrappoint）で決まり、負の値は右端・下端からの距離。
     */
    fun textArea(imageWidth: Int, imageHeight: Int): TextArea {
        fun fromRight(v: Int) = if (v < 0) imageWidth + v else v
        fun fromBottom(v: Int) = if (v < 0) imageHeight + v else v
        val left = descript.int("validrect.left") ?: descript.int("origin.x") ?: DEFAULT_MARGIN
        val top = descript.int("validrect.top") ?: descript.int("origin.y") ?: DEFAULT_MARGIN
        val right = (descript.int("validrect.right") ?: descript.int("wordwrappoint.x"))?.let(::fromRight) ?: (imageWidth - DEFAULT_MARGIN)
        val bottom = descript.int("validrect.bottom")?.let(::fromBottom) ?: (imageHeight - DEFAULT_MARGIN)
        // 範囲がおかしいバルーンでも文字が消えないよう、画像の中に最低限の大きさは残す
        val minW = minOf(MIN_SIZE, imageWidth)
        val minH = minOf(MIN_SIZE, imageHeight)
        val l = left.coerceIn(0, imageWidth - minW)
        val t = top.coerceIn(0, imageHeight - minH)
        return TextArea(l, t, right.coerceIn(l + minW, imageWidth), bottom.coerceIn(t + minH, imageHeight))
    }

    /** 文字の大きさ（ピクセル） */
    val fontHeight: Int get() = descript.int("font.height") ?: 12

    val fontColor: BalloonColor? get() = color("font.color")

    /** \_a のリンクの色 */
    val anchorColor: BalloonColor? get() = color("anchor.font.color")

    /**
     * 押せる部分（選択肢とリンク）の色。タッチ操作ではカーソルを乗せて色を変えられないので、
     * 最初から地の文と見分けられる色にする: リンクの色 → カーソルの枠の色 → カーソルが乗ったときの文字の色
     */
    val choiceColor: BalloonColor?
        get() = anchorColor ?: color("cursor.pen.color") ?: color("cursor.font.color")

    private fun color(key: String): BalloonColor? {
        val r = descript.int("$key.r") ?: return null
        val g = descript.int("$key.g") ?: return null
        val b = descript.int("$key.b") ?: return null
        return BalloonColor(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private companion object {
        const val DEFAULT_MARGIN = 16
        const val MIN_SIZE = 16
    }
}

class BalloonRepository(val root: File) {
    init {
        root.mkdirs()
    }

    fun list(): List<Balloon> =
        root.listFiles { f -> f.isDirectory }?.map(::Balloon)?.filter { it.isValid }?.sortedBy { it.name }.orEmpty()

    fun find(dirName: String): Balloon? = Balloon(File(root, dirName)).takeIf { it.isValid }

    /** バルーンの名前（descript.txt の name）で探す。ゴーストの descript.txt の balloon 指定に使う */
    fun findByName(name: String): Balloon? = list().firstOrNull { it.name == name || it.dir.name == name }
}
