package net.pono.baseware.balloon

import net.pono.baseware.sakura.LinkLook
import net.pono.baseware.text.Descript
import java.io.File

/** 色（0〜255）。descript.txt の xxx.r / xxx.g / xxx.b から作る */
data class BalloonColor(val r: Int, val g: Int, val b: Int) {
    /** 0xRRGGBB */
    val rgb: Int get() = (r shl 16) or (g shl 8) or b
}

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
            // 番号が桁あふれするファイル名（細工されたバルーン）は無いものとして扱う
            val all = files.values.mapNotNull { f ->
                Regex("""^${prefix}(\d+)\.png$""").matchEntire(f.name.lowercase())?.groupValues?.get(1)?.toIntOrNull()?.let { BalloonImage(f, it) }
            }.sortedBy { it.index }
            if (all.isEmpty()) continue
            return all.filter { it.index % 2 == parity }.ifEmpty { all }
        }
        return emptyList()
    }

    /**
     * \b[n] で選ばれた画像。n はバルーンの組の番号で、向きに合わせて n か n+1 を使う（\0 は偶数、\1 は奇数）。
     * そのキャラクター用の画像に無ければ null（文字の量で選ぶ）。
     */
    fun imageForId(scope: Int, id: Int): BalloonImage? {
        val images = images(scope)
        val base = id - id % 2
        return images.firstOrNull { it.index == base + scope % 2 } ?: images.firstOrNull { it.index == id }
    }

    /** 画像と同名の .pna（アルファ用のグレースケール画像） */
    fun pnaFor(png: File): File? = files[png.nameWithoutExtension.lowercase() + ".pna"]

    /** スクロールの矢印。0 が上向き（前に戻れる）、1 が下向き（続きがある） */
    fun arrowImage(which: Int): File? = files["arrow$which.png"]

    /**
     * 矢印を置く位置（画像の左上からのピクセル。負の値は右端・下端から）。
     * 書いてなければ、文字の範囲の右上と右下に置く。
     */
    fun arrowPosition(which: Int, imageWidth: Int, imageHeight: Int, arrowWidth: Int, arrowHeight: Int): Pair<Int, Int> {
        val area = textArea(imageWidth, imageHeight)
        val x = descript.int("arrow$which.x")?.let { if (it < 0) imageWidth + it else it } ?: (area.right - arrowWidth)
        val y = descript.int("arrow$which.y")?.let { if (it < 0) imageHeight + it else it }
            ?: if (which == 0) area.top else area.bottom - arrowHeight
        return x.coerceIn(0, maxOf(0, imageWidth - arrowWidth)) to y.coerceIn(0, maxOf(0, imageHeight - arrowHeight))
    }

    /**
     * 文字を書ける範囲。SSP と同じく、書き始めは origin、折り返しは wordwrappoint.x（無ければ validrect）。
     * 右と下は 0 以下なら右端・下端からの距離（validrect.right,0 は右端）。
     */
    fun textArea(imageWidth: Int, imageHeight: Int): TextArea = area(imageWidth, imageHeight, fromOrigin = true)

    /**
     * 文字を書ける範囲の全体（validrect）。書き始め（origin、[textArea] の左上）は範囲の途中のこともある。
     * validrect の左・上が無ければ origin から
     */
    fun validArea(imageWidth: Int, imageHeight: Int): TextArea = area(imageWidth, imageHeight, fromOrigin = false)

    private fun area(imageWidth: Int, imageHeight: Int, fromOrigin: Boolean): TextArea {
        fun fromRight(v: Int) = if (v <= 0) imageWidth + v else v
        fun fromBottom(v: Int) = if (v <= 0) imageHeight + v else v
        val originX = descript.int("origin.x")
        val originY = descript.int("origin.y")
        val left = (if (fromOrigin) originX ?: descript.int("validrect.left") else descript.int("validrect.left") ?: originX) ?: DEFAULT_MARGIN
        val top = (if (fromOrigin) originY ?: descript.int("validrect.top") else descript.int("validrect.top") ?: originY) ?: DEFAULT_MARGIN
        val right = (descript.int("wordwrappoint.x") ?: descript.int("validrect.right"))?.let(::fromRight) ?: (imageWidth - DEFAULT_MARGIN)
        val bottom = descript.int("validrect.bottom")?.let(::fromBottom) ?: (imageHeight - DEFAULT_MARGIN)
        // 範囲がおかしいバルーンでも文字が消えないよう、画像の中に最低限の大きさは残す
        val minW = minOf(MIN_SIZE, imageWidth)
        val minH = minOf(MIN_SIZE, imageHeight)
        val l = left.coerceIn(0, imageWidth - minW)
        val t = top.coerceIn(0, imageHeight - minH)
        return TextArea(l, t, right.coerceIn(l + minW, imageWidth), bottom.coerceIn(t + minH, imageHeight))
    }

    // ---- 入力の箱（SSP の communicatebox / inputbox など） ----

    /**
     * 入力の箱の画像。1行の箱は balloonc0.png、[multiLine]（textareabox）は balloonc5.png（無ければ balloonc0.png）。
     * 無いバルーンなら null（pono-droid の簡易な見た目で出す）
     */
    fun communicateImage(multiLine: Boolean): File? =
        (if (multiLine) files["balloonc5.png"] else null) ?: files["balloonc0.png"]

    /**
     * 入力欄を置く範囲（画像の左上からのピクセル）。画像ごとの balloonc*s.txt、なければ descript.txt の
     * communicatebox.x / y / width / height。書いてなければ画像の四辺から 8 ピクセル内側（SSP と同じ）
     */
    fun communicateBox(image: File, imageWidth: Int, imageHeight: Int): TextArea {
        val own = files[image.nameWithoutExtension.lowercase() + "s.txt"]?.let(Descript::read)
        fun int(key: String) = own?.int(key) ?: descript.int(key)
        val left = int("communicatebox.x") ?: COMMUNICATE_MARGIN
        val top = int("communicatebox.y") ?: COMMUNICATE_MARGIN
        val width = int("communicatebox.width") ?: (imageWidth - left - COMMUNICATE_MARGIN)
        val height = int("communicatebox.height") ?: (imageHeight - top - COMMUNICATE_MARGIN)
        // 画像からはみ出す指定でも、入力欄は画像の中に最低限の大きさで残す
        val minW = minOf(MIN_SIZE, imageWidth)
        val minH = minOf(MIN_SIZE, imageHeight)
        val l = left.coerceIn(0, imageWidth - minW)
        val t = top.coerceIn(0, imageHeight - minH)
        return TextArea(l, t, (l + width).coerceIn(l + minW, imageWidth), (t + height).coerceIn(t + minH, imageHeight))
    }

    /** 入力欄の文字の色（communicatebox.font.color） */
    val communicateFontColor: BalloonColor? get() = color("communicatebox.font.color")

    /** 入力欄の背景の色（communicatebox.background.color）。null なら画像の上に透かして書く */
    val communicateBackground: BalloonColor? get() = color("communicatebox.background.color")

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

    // ---- 文字の既定の飾り（\f[default] で戻る先） ----

    /** font.name。カンマ区切りの候補で、バルーンのフォルダのフォントファイル（の絶対パス）か名前 */
    val fontNames: List<String>
        get() = descript["font.name"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
            if (name.substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "ttc")) fontFile(name)?.path.orEmpty() else name
        }

    /** バルーンのフォルダの中のフォントファイル（フォルダの外を指していたら null） */
    private fun fontFile(name: String): File? {
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val file = runCatching { File(dir, name.replace('\\', '/')).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    /** 文字の影の色（font.shadowcolor）。null なら影なし */
    val shadowColor: BalloonColor? get() = color("font.shadowcolor")

    /** 影の付け方（font.shadowstyle）。outline なら縁取り、それ以外は右下にずらす */
    val shadowOutline: Boolean get() = descript["font.shadowstyle"]?.trim()?.lowercase() == "outline"

    val bold: Boolean get() = descript.int("font.bold") == 1
    val italic: Boolean get() = descript.int("font.italic") == 1
    val underline: Boolean get() = descript.int("font.underline") == 1
    val strike: Boolean get() = descript.int("font.strike") == 1

    /** 白抜き（font.outline） */
    val outline: Boolean get() = descript.int("font.outline") == 1

    /** \f[disable] の薄い文字の色（disable.font.color） */
    val disabledColor: BalloonColor? get() = color("disable.font.color")

    /**
     * 選択肢やリンクのある状態の見た目。[prefix] は cursor / cursor.notselect / anchor / anchor.notselect / anchor.visited で、
     * その .style（square / underline / square+underline / none）と .brush.color / .pen.color / .font.color
     */
    fun linkLook(prefix: String): LinkLook = LinkLook(
        shape = descript["$prefix.style"]?.trim()?.lowercase()?.takeIf { it in setOf("square", "underline", "square+underline", "none") },
        brush = color("$prefix.brush.color")?.rgb,
        pen = color("$prefix.pen.color")?.rgb,
        font = color("$prefix.font.color")?.rgb,
    )

    // ---- \![set,balloonmarker] と \![set,balloonnum] の文字 ----

    /** 印の文字を置く位置（sstpmessage.x / y。負なら右端・下端から）。書いてなければ null（文字の範囲の左上） */
    fun markerPosition(imageWidth: Int, imageHeight: Int): Pair<Int, Int>? = position("sstpmessage.x", "sstpmessage.y", imageWidth, imageHeight)

    /** 数の右端の位置（number.x か number.xr と number.y）。書いてなければ null（文字の範囲の右下） */
    fun numberPosition(imageWidth: Int, imageHeight: Int): Pair<Int, Int>? =
        position(if (descript["number.xr"] != null) "number.xr" else "number.x", "number.y", imageWidth, imageHeight)

    val markerFontHeight: Int? get() = descript.int("sstpmessage.font.height")
    val markerColor: BalloonColor? get() = color("sstpmessage.font.color")
    val numberFontHeight: Int? get() = descript.int("number.font.height")
    val numberColor: BalloonColor? get() = color("number.font.color")

    private fun position(xKey: String, yKey: String, imageWidth: Int, imageHeight: Int): Pair<Int, Int>? {
        val x = descript.int(xKey) ?: return null
        val y = descript.int(yKey) ?: return null
        return (if (x < 0) imageWidth + x else x) to (if (y < 0) imageHeight + y else y)
    }

    // ---- オンラインの印（ネットワーク更新と \![enter,onlinemode] の間に出す） ----

    /**
     * オンラインの印の絵（onlinemarker.filename、既定は online）。online0.png・online1.png… の順に並べたもので、
     * キャラクターごとの onlines / onlinek / onlinepN があればそちらを使う
     */
    fun onlineImages(scope: Int): List<File> {
        val base = descript["onlinemarker.filename"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "online"
        val own = when (scope) {
            0 -> base + "s"
            1 -> base + "k"
            else -> base + "p$scope"
        }
        fun frames(prefix: String) = generateSequence(0) { it + 1 }.map { files["$prefix$it.png"] }.takeWhile { it != null }.filterNotNull().toList()
        return frames(own).ifEmpty { frames(base) }
    }

    /** オンラインの印の位置（onlinemarker.x / y、負なら右端・下端から）。書いてなければ左上 */
    fun onlinePosition(imageWidth: Int, imageHeight: Int): Pair<Int, Int> {
        val x = descript.int("onlinemarker.x")?.let { if (it < 0) imageWidth + it else it } ?: 0
        val y = descript.int("onlinemarker.y")?.let { if (it < 0) imageHeight + it else it } ?: 0
        return x to y
    }

    /** オンラインの印のコマを替える間隔（50 ミリ秒より短くはしない） */
    val onlineInterval: Long get() = (descript.int("onlinemarker.interval") ?: 500).toLong().coerceAtLeast(50)

    private fun color(key: String): BalloonColor? {
        val r = descript.int("$key.r") ?: return null
        val g = descript.int("$key.g") ?: return null
        val b = descript.int("$key.b") ?: return null
        return BalloonColor(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private companion object {
        const val DEFAULT_MARGIN = 16
        const val MIN_SIZE = 16

        /** communicatebox の指定が無いときの、入力欄と画像の端の間（SSP と同じ 8 ピクセル） */
        const val COMMUNICATE_MARGIN = 8
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

    /** バルーンを消す（元に戻せない）。置き場の外は消さない。選んでいたゴーストは次から簡易バルーンか指定のものに戻る */
    fun uninstall(balloon: Balloon): Boolean {
        val dir = balloon.dir.canonicalFile
        if (dir.parentFile != root.canonicalFile) return false
        return dir.deleteRecursively()
    }
}
