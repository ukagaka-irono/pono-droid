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
 * windowposition.x / y。[anchor] が center ならシェルの中央上、bottom なら中央下に置き、そこから [x] / [y] ずらす。
 * null なら、いつもの位置から [x]（いつも右が正）/ [y]（下が正）ずらす
 */
data class BalloonWindowPosition(val x: Int, val y: Int, val anchor: String?)

/** seriko.use_self_alpha と同じ3通り（バルーンの use_self_alpha） */
enum class BalloonSelfAlpha { OFF, ON, FULL }

/**
 * インストール済みのバルーン（SSP 形式）。balloons*.png が \0、balloonk*.png が \1 用で、
 * 文字を書ける範囲や色は descript.txt に書いてある。画像ごとの balloons0s.txt などは [variant] で重ねる
 */
class Balloon private constructor(val dir: File, private val base: Descript, private val override: Descript?, private val parent: Balloon?) {
    constructor(dir: File) : this(dir, Descript.read(File(dir, "descript.txt")), null, null)

    /** descript.txt（バルーン全体の設定）。画像ごとの上書きは含まない */
    val descript: Descript get() = base

    val name: String get() = base["name"] ?: dir.name
    val isValid: Boolean get() = File(dir, "descript.txt").isFile && images(0).isNotEmpty()

    private val files: Map<String, File> by lazy { parent?.files ?: dir.listFiles()?.associateBy { it.name.lowercase() }.orEmpty() }

    /** 画像ごとの上書き（balloons0s.txt）を先に、無ければ descript.txt */
    private fun value(key: String): String? = override?.get(key) ?: base[key]

    private fun int(key: String): Int? = value(key)?.trim()?.toIntOrNull()

    private val variants = HashMap<String, Balloon>()

    /**
     * [image]（balloons0.png など）に balloons0s.txt があれば、それで上書きした設定のバルーン。無ければこのバルーンのまま。
     * name / craftman / readme などバルーン全体のものは上書きしない
     */
    fun variant(image: File): Balloon {
        val root = parent ?: this
        return synchronized(root.variants) {
            root.variants.getOrPut(image.name.lowercase()) {
                val own = root.files[image.nameWithoutExtension.lowercase() + "s.txt"]?.let(Descript::read)
                if (own == null) root else Balloon(dir, base, own, root)
            }
        }
    }

    /**
     * 座標（[key] の値）。負の数は画像の右端・下端から（[size] は画像の幅か高さ）、--数値 は本当の負の数（index.md の *1）
     */
    private fun coord(key: String, size: Int): Int? {
        val s = value(key)?.trim() ?: return null
        if (s.startsWith("--")) return s.removePrefix("--").toIntOrNull()?.let { -it }
        val n = s.toIntOrNull() ?: return null
        return if (n < 0) size + n else n
    }

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

    // ---- 画像のパーツ（矢印・\![*] の印・クリック待ち・SSTP の印） ----

    /**
     * パーツの画像。[filenameKey]（arrow.filename など。拡張子なし）か [default] を名前にし、キャラクターごとの
     * 名前s / 名前k / 名前pN を先に探す。[suffix] は arrow0 の 0 のような後ろに付くもの
     */
    private fun part(filenameKey: String, default: String, scope: Int, suffix: String = ""): File? {
        val base = value(filenameKey)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: default
        val own = when (scope) {
            0 -> base + "s"
            1 -> base + "k"
            else -> base + "p$scope"
        }
        return files["$own$suffix.png"] ?: files["$base$suffix.png"]
    }

    /** スクロールの矢印。0 が上向き（前に戻れる）、1 が下向き（続きがある）。arrow.filename と arrows / arrowk / arrowpN */
    fun arrowImage(which: Int, scope: Int = 0): File? = part("arrow.filename", "arrow", scope, which.toString())

    /** \![*] の印の画像（marker.filename と markers / markerk / markerpN）。無ければ null（文字の印で出す） */
    fun markerImage(scope: Int): File? = part("marker.filename", "marker", scope)

    /** クリック待ちの印の画像（clickwaitmarker.filename と clickwaits / clickwaitk / clickwaitpN） */
    fun clickWaitImage(scope: Int): File? = part("clickwaitmarker.filename", "clickwait", scope)

    /** SSTP で届いたスクリプトの印（sstpmarker.filename。既定は sstp_new、無ければ sstp） */
    fun sstpMarkerImage(scope: Int): File? = part("sstpmarker.filename", "sstp_new", scope) ?: part("sstpmarker.filename", "sstp", scope)

    /**
     * 矢印を置く位置（画像の左上からのピクセル。負の値は右端・下端から）。
     * 書いてなければ、文字の範囲の右上と右下に置く。
     */
    fun arrowPosition(which: Int, imageWidth: Int, imageHeight: Int, arrowWidth: Int, arrowHeight: Int): Pair<Int, Int> {
        val area = textArea(imageWidth, imageHeight)
        val x = coord("arrow$which.x", imageWidth) ?: (area.right - arrowWidth)
        val y = coord("arrow$which.y", imageHeight) ?: if (which == 0) area.top else area.bottom - arrowHeight
        return x.coerceIn(0, maxOf(0, imageWidth - arrowWidth)) to y.coerceIn(0, maxOf(0, imageHeight - arrowHeight))
    }

    /** クリック待ちの印の位置（clickwaitmarker.x / y）。書いてなければ下向きの矢印と同じところ */
    fun clickWaitPosition(imageWidth: Int, imageHeight: Int, markWidth: Int, markHeight: Int): Pair<Int, Int> {
        val (ax, ay) = arrowPosition(1, imageWidth, imageHeight, markWidth, markHeight)
        return (coord("clickwaitmarker.x", imageWidth) ?: ax) to (coord("clickwaitmarker.y", imageHeight) ?: ay)
    }

    /** SSTP の印の位置（sstpmarker.x / y）。書いてなければ文字の範囲の左上 */
    fun sstpMarkerPosition(imageWidth: Int, imageHeight: Int): Pair<Int, Int> {
        val area = validArea(imageWidth, imageHeight)
        return (coord("sstpmarker.x", imageWidth) ?: area.left) to (coord("sstpmarker.y", imageHeight) ?: area.top)
    }

    /**
     * 文字を書ける範囲。SSP と同じく、書き始めは origin、折り返しは wordwrappoint.x（無ければ validrect）。
     * 負の値は右端・下端から（右と下は 0 でも右端・下端）、--数値 は本当の負の数。
     */
    fun textArea(imageWidth: Int, imageHeight: Int): TextArea = area(imageWidth, imageHeight, fromOrigin = true)

    /**
     * 文字を書ける範囲の全体（validrect）。書き始め（origin、[textArea] の左上）は範囲の途中のこともある。
     * validrect の左・上が無ければ origin から
     */
    fun validArea(imageWidth: Int, imageHeight: Int): TextArea = area(imageWidth, imageHeight, fromOrigin = false)

    private fun area(imageWidth: Int, imageHeight: Int, fromOrigin: Boolean): TextArea {
        fun end(key: String, size: Int): Int? {
            val s = value(key)?.trim() ?: return null
            if (s.startsWith("--")) return s.removePrefix("--").toIntOrNull()?.let { -it }
            val n = s.toIntOrNull() ?: return null
            return if (n <= 0) size + n else n
        }
        val originX = coord("origin.x", imageWidth)
        val originY = coord("origin.y", imageHeight)
        val rectLeft = coord("validrect.left", imageWidth)
        val rectTop = coord("validrect.top", imageHeight)
        val left = (if (fromOrigin) originX ?: rectLeft else rectLeft ?: originX) ?: DEFAULT_MARGIN
        val top = (if (fromOrigin) originY ?: rectTop else rectTop ?: originY) ?: DEFAULT_MARGIN
        val right = (end("wordwrappoint.x", imageWidth) ?: end("validrect.right", imageWidth)) ?: (imageWidth - DEFAULT_MARGIN)
        val bottom = end("validrect.bottom", imageHeight) ?: (imageHeight - DEFAULT_MARGIN)
        // 範囲がおかしいバルーンでも文字が消えないよう、画像の中に最低限の大きさは残す
        val minW = minOf(MIN_SIZE, imageWidth)
        val minH = minOf(MIN_SIZE, imageHeight)
        val l = left.coerceIn(0, imageWidth - minW)
        val t = top.coerceIn(0, imageHeight - minH)
        return TextArea(l, t, right.coerceIn(l + minW, imageWidth), bottom.coerceIn(t + minH, imageHeight))
    }

    // ---- バルーンの置き方・見え方 ----

    /** windowposition.x / y（画像ごとの上書きと descript.txt）。x の center / top はシェルの中央上、bottom は中央下 */
    val windowPosition: BalloonWindowPosition
        get() {
            val xs = value("windowposition.x")?.trim()?.lowercase()
            val anchor = when (xs) {
                "center", "top" -> "center"
                "bottom" -> "bottom"
                else -> null
            }
            val x = if (anchor != null) 0 else xs?.toIntOrNull() ?: 0
            return BalloonWindowPosition(x, int("windowposition.y") ?: 0, anchor)
        }

    /** windowposition.limit が 0 なら、画面からはみ出してもよい */
    val keepOnScreen: Boolean get() = int("windowposition.limit") != 0

    /** dpi（バルーンを作った画面の DPI。既定は 96）に合わせて、96 / dpi 倍で出す */
    val dpiFactor: Float get() = base.int("dpi")?.takeIf { it in 24..960 }?.let { 96f / it } ?: 1f

    /** use_self_alpha（バルーン全体だけ）。0 なら画像のアルファは使わず、.pna か左上の色で抜く */
    val selfAlpha: BalloonSelfAlpha
        get() = when (base["use_self_alpha"]?.trim()?.lowercase()) {
            "1", "true" -> BalloonSelfAlpha.ON
            "full" -> BalloonSelfAlpha.FULL
            else -> BalloonSelfAlpha.OFF
        }

    /** paint_transparent_region_black。書いていなければ null（.pna のときは黒にする、アルファの画像はしない） */
    val paintTransparentBlack: Boolean? get() = base.int("paint_transparent_region_black")?.let { it != 0 }

    /** overlay_outside_balloon が 1 なら、オンライン・SSTP の印などをバルーンの外にはみ出して描ける */
    val overlayOutside: Boolean get() = base.int("overlay_outside_balloon") == 1

    /** use_input_alpha が 1 なら、入力の箱もバルーンと同じに半透明にする */
    val useInputAlpha: Boolean get() = base.int("use_input_alpha") == 1

    /** recommended.ghost（このバルーンを使うよう作られたゴーストの名前）と recommended.ghost.path（そのフォルダ） */
    val recommendedGhost: String? get() = base["recommended.ghost"]?.trim()?.takeIf { it.isNotEmpty() }
    val recommendedGhostPath: String? get() = base["recommended.ghost.path"]?.trim()?.takeIf { it.isNotEmpty() }

    // ---- 入力の箱（SSP の communicatebox / inputbox など） ----

    /**
     * 入力の箱の画像。1行の箱は balloonc0.png、[multiLine]（textareabox）は balloonc5.png（無ければ balloonc0.png）。
     * 無いバルーンなら null（pono-droid の簡易な見た目で出す）
     */
    fun communicateImage(multiLine: Boolean): File? =
        (if (multiLine) files["balloonc5.png"] else null) ?: files["balloonc0.png"]

    /**
     * 入力欄を置く範囲（画像の左上からのピクセル）。画像ごとの balloonc*s.txt、なければ descript.txt の
     * communicatebox.x / y / width / height。書いてなければ画像の四辺から 8 ピクセル内側（SSP と同じ）。
     * balloonc5s.txt は descript.txt の値を引き継がない（1行の箱向けなので）
     */
    fun communicateBox(image: File, imageWidth: Int, imageHeight: Int): TextArea {
        val own = files[image.nameWithoutExtension.lowercase() + "s.txt"]?.let(Descript::read)
        val inherit = image.nameWithoutExtension.lowercase() != "balloonc5"
        fun int(key: String) = own?.int(key) ?: if (inherit) base.int(key) else null
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

    /** 入力欄の書体（communicatebox.font.name。無ければ font.name） */
    val communicateFontNames: List<String> get() = fontNamesOf("communicatebox.font.name").ifEmpty { fontNames }

    /** 入力欄が空のときの案内文（--placeholder）の色。null なら文字の色を背景に寄せた色 */
    val placeholderColor: BalloonColor? get() = color("communicatebox.placeholder.color")

    /** 文字の大きさ（ピクセル） */
    val fontHeight: Int get() = int("font.height") ?: 12

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
    val fontNames: List<String> get() = fontNamesOf("font.name")

    /** [key]（font.name / number.font.name など）の書体の候補 */
    private fun fontNamesOf(key: String): List<String> =
        value(key).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { name ->
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
    val shadowOutline: Boolean get() = value("font.shadowstyle")?.trim()?.lowercase() == "outline"

    val bold: Boolean get() = int("font.bold") == 1
    val italic: Boolean get() = int("font.italic") == 1
    val underline: Boolean get() = int("font.underline") == 1
    val strike: Boolean get() = int("font.strike") == 1

    /** 白抜き（font.outline） */
    val outline: Boolean get() = int("font.outline") == 1

    /** \f[disable] の薄い文字の色（disable.font.color） */
    val disabledColor: BalloonColor? get() = color("disable.font.color")

    /** 無効表示の飾り（disable.font.bold など）。書いていなければ null（ふつうの文字と同じ） */
    fun disabledFlag(name: String): Boolean? = int("disable.font.$name")?.let { it == 1 }

    /** 無効表示の書体（disable.font.name）。無ければ空（ふつうの文字と同じ） */
    val disabledFontNames: List<String> get() = fontNamesOf("disable.font.name")

    /** 無効表示の文字の大きさ（disable.font.height）。無ければ null */
    val disabledFontHeight: Int? get() = int("disable.font.height")

    /**
     * 選択肢やリンクのある状態の見た目。[prefix] は cursor / cursor.notselect / anchor / anchor.notselect / anchor.visited で、
     * その .style（square / underline / square+underline / none）と .brush.color / .pen.color / .font.color、
     * .font.shadowcolor / .font.shadowstyle（無ければ font.shadowstyle）、.blendmethod（cursor / anchor だけ）
     */
    fun linkLook(prefix: String): LinkLook = LinkLook(
        shape = value("$prefix.style")?.trim()?.lowercase()?.takeIf { it in setOf("square", "underline", "square+underline", "none") },
        brush = color("$prefix.brush.color")?.rgb,
        pen = color("$prefix.pen.color")?.rgb,
        font = color("$prefix.font.color")?.rgb,
        shadow = color("$prefix.font.shadowcolor")?.rgb,
        shadowOutline = (value("$prefix.font.shadowstyle") ?: value("font.shadowstyle"))?.trim()?.lowercase()?.let { it == "outline" },
        blend = value("${prefix.substringBefore('.')}.blendmethod")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "none" },
    )

    // ---- \![set,balloonmarker] と \![set,balloonnum] の文字 ----

    /** 印の文字を置く位置（sstpmessage.x / y。負なら右端・下端から）。書いてなければ null（文字の範囲の左上） */
    fun markerPosition(imageWidth: Int, imageHeight: Int): Pair<Int, Int>? = position("sstpmessage.x", "sstpmessage.y", imageWidth, imageHeight)

    /** 印の文字の右の端（sstpmessage.xr）。ここで切る。書いてなければ null（画像の右端まで） */
    fun markerEnd(imageWidth: Int): Int? = coord("sstpmessage.xr", imageWidth)

    /** 数の右端の位置（number.x か number.xr と number.y）。書いてなければ null（文字の範囲の右下） */
    fun numberPosition(imageWidth: Int, imageHeight: Int): Pair<Int, Int>? =
        position(if (value("number.xr") != null) "number.xr" else "number.x", "number.y", imageWidth, imageHeight)

    val markerFontHeight: Int? get() = int("sstpmessage.font.height")
    val markerColor: BalloonColor? get() = color("sstpmessage.font.color")
    val markerFontNames: List<String> get() = fontNamesOf("sstpmessage.font.name")
    val numberFontHeight: Int? get() = int("number.font.height")
    val numberColor: BalloonColor? get() = color("number.font.color")
    val numberFontNames: List<String> get() = fontNamesOf("number.font.name")

    private fun position(xKey: String, yKey: String, imageWidth: Int, imageHeight: Int): Pair<Int, Int>? {
        val x = coord(xKey, imageWidth) ?: return null
        val y = coord(yKey, imageHeight) ?: return null
        return x to y
    }

    // ---- オンラインの印（ネットワーク更新と \![enter,onlinemode] の間に出す） ----

    /**
     * オンラインの印の絵（onlinemarker.filename、既定は online）。online0.png・online1.png… の順に並べたもので、
     * キャラクターごとの onlines / onlinek / onlinepN があればそちらを使う
     */
    fun onlineImages(scope: Int): List<File> {
        val base = value("onlinemarker.filename")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "online"
        val own = when (scope) {
            0 -> base + "s"
            1 -> base + "k"
            else -> base + "p$scope"
        }
        fun frames(prefix: String) = generateSequence(0) { it + 1 }.map { files["$prefix$it.png"] }.takeWhile { it != null }.filterNotNull().toList()
        return frames(own).ifEmpty { frames(base) }
    }

    /** オンラインの印の位置（onlinemarker.x / y、負なら右端・下端から）。書いてなければ左上 */
    fun onlinePosition(imageWidth: Int, imageHeight: Int): Pair<Int, Int> =
        (coord("onlinemarker.x", imageWidth) ?: 0) to (coord("onlinemarker.y", imageHeight) ?: 0)

    /** オンラインの印のコマを替える間隔（50 ミリ秒より短くはしない） */
    val onlineInterval: Long get() = (int("onlinemarker.interval") ?: 500).toLong().coerceAtLeast(50)

    private fun color(key: String): BalloonColor? {
        val r = int("$key.r") ?: return null
        val g = int("$key.g") ?: return null
        val b = int("$key.b") ?: return null
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
