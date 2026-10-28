package net.pono.baseware.shell

import net.pono.baseware.text.Descript
import java.io.File

/** カーソルを出す場面（surfaces.txt の cursor ブレスの項目） */
enum class CursorAction(val key: String) {
    /** 当たり判定の上に乗った */
    UP("mouseup"),

    /** 当たり判定の上でボタンを押した */
    DOWN("mousedown"),

    /** 右ボタンを押した */
    RIGHT_DOWN("mouserightdown"),

    /** ホイールを回した */
    WHEEL("mousewheel"),

    /** しばらく動かさない */
    HOVER("mousehover"),
}

/** cursor ブレスの1行（mouseup0,Head,system:hand）。[cursor] はファイル名か system:名前 */
data class CursorRule(val action: CursorAction, val collision: String, val cursor: String)

/** 出すカーソル */
sealed interface CursorChoice {
    /** system:arrow など。[name] は arrow / cross / no / hand / grip / finger / text / wait / help / move */
    data class System(val name: String) : CursorChoice

    /** カーソルファイル（.cur / .ani / .ico） */
    data class FromFile(val file: File) : CursorChoice
}

/**
 * カーソルファイルから読んだ絵。[argb] は左上から1行ずつ（0xAARRGGBB）、PNG の入ったカーソルなら [png] にその中身（どちらか一方）。
 * [hotX] / [hotY] は指す点
 */
class CursorImage(val width: Int, val height: Int, val argb: IntArray?, val png: ByteArray?, val hotX: Int, val hotY: Int)

object Cursors {
    /** cursor ブレスの1行を読む。読めなければ null */
    fun parseRule(line: String): CursorRule? {
        val parts = line.split(',').map { it.trim() }
        if (parts.size < 3) return null
        val key = RULE_KEY.matchEntire(parts[0].lowercase()) ?: return null
        val action = CursorAction.entries.firstOrNull { it.key == key.groupValues[1] } ?: return null
        val cursor = parts.drop(2).joinToString(",").trim().takeIf { it.isNotEmpty() } ?: return null
        return CursorRule(action, parts[1], cursor)
    }

    /**
     * キャラクターの上で出すカーソル。cursor ブレス（[rules]）に [collision] の [action] があればそれ、無ければ SSP の既定
     * （乗った・押した・右ボタンは finger、Bust は乗ったら hand・押したら grip、ホイールと止まったら arrow。当たり判定の外は arrow）。
     * system:名前 は、ゴーストの descript.txt（[ghost]）に mousecursor（cursor）・mousecursor.hand などがあればそのファイルにする。
     * ブレスに書いたファイルは [shellFile] で、descript.txt のファイルは [ghostFile] で探す
     */
    fun forCharacter(
        rules: List<CursorRule>,
        collision: String?,
        action: CursorAction,
        ghost: Descript,
        shellFile: (String) -> File?,
        ghostFile: (String) -> File?,
    ): CursorChoice {
        val written = collision?.let { c -> rules.firstOrNull { it.action == action && it.collision.equals(c, ignoreCase = true) }?.cursor }
        val spec = written ?: "system:" + when {
            collision == null -> "arrow"
            action == CursorAction.WHEEL || action == CursorAction.HOVER -> "arrow"
            collision.equals("bust", ignoreCase = true) && action == CursorAction.UP -> "hand"
            collision.equals("bust", ignoreCase = true) && action == CursorAction.DOWN -> "grip"
            else -> "finger"
        }
        if (!spec.startsWith("system:", ignoreCase = true)) {
            return shellFile(spec)?.let { CursorChoice.FromFile(it) } ?: fromDescripts("finger", listOf(ghost to ghostFile))
        }
        return fromDescripts(spec.substringAfter(':').trim().lowercase(), listOf(ghost to ghostFile))
    }

    /**
     * system:[name] のカーソル。[sources] の descript.txt（バルーン、次にゴーストなど）に、その形のカーソルファイル
     * （finger は mousecursor か cursor、hand は mousecursor.hand など）があれば最初のもの、無ければ Android の同じ形
     */
    fun fromDescripts(name: String, sources: List<Pair<Descript, (String) -> File?>>): CursorChoice {
        for ((descript, find) in sources) {
            for (key in KEYS[name].orEmpty()) {
                val file = descript[key]?.trim()?.takeIf { it.isNotEmpty() }?.let(find) ?: continue
                return CursorChoice.FromFile(file)
            }
        }
        return CursorChoice.System(name)
    }

    /** バルーンの選択肢・リンクのカーソル。バルーンの cursor（mousecursor）、ゴーストの cursor、無ければ矢印（SSP の既定と同じ） */
    fun forBalloonChoice(sources: List<Pair<Descript, (String) -> File?>>): CursorChoice =
        (fromDescripts("finger", sources) as? CursorChoice.FromFile) ?: CursorChoice.System("arrow")

    /** .cur / .ico / .ani を読む。いちばん大きい絵を使う（.ani は最初のコマ）。読めなければ null */
    fun decode(bytes: ByteArray): CursorImage? = runCatching {
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "ACON") {
            firstAniFrame(bytes)?.let(::decodeIcon)
        } else {
            decodeIcon(bytes)
        }
    }.getOrNull()

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)

    /** RIFF の ACON から、最初の icon の塊 */
    private fun firstAniFrame(b: ByteArray): ByteArray? {
        var at = 12
        while (at + 8 <= b.size) {
            val id = String(b, at, 4, Charsets.ISO_8859_1)
            val size = u32(b, at + 4)
            if (size < 0 || at + 8 + size > b.size) return null
            if (id == "LIST" && size >= 4 && String(b, at + 8, 4, Charsets.ISO_8859_1) == "fram") {
                var p = at + 12
                while (p + 8 <= at + 8 + size) {
                    val cid = String(b, p, 4, Charsets.ISO_8859_1)
                    val csize = u32(b, p + 4)
                    if (csize < 0 || p + 8 + csize > b.size) return null
                    if (cid == "icon") return b.copyOfRange(p + 8, p + 8 + csize)
                    p += 8 + csize + (csize and 1)
                }
                return null
            }
            at += 8 + size + (size and 1)
        }
        return null
    }

    /** ICONDIR（.ico は種類 1、.cur は 2 で、.cur は指す点を持つ） */
    private fun decodeIcon(b: ByteArray): CursorImage? {
        if (b.size < 6 || u16(b, 0) != 0) return null
        val type = u16(b, 2)
        if (type != 1 && type != 2) return null
        val count = u16(b, 4)
        if (count <= 0 || 6 + count * 16 > b.size) return null
        // いちばん大きい絵（同じ大きさなら後のもの）
        val best = (0 until count).maxByOrNull { i ->
            val e = 6 + i * 16
            val w = (b[e].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val h = (b[e + 1].toInt() and 0xFF).let { if (it == 0) 256 else it }
            w * h
        } ?: return null
        val e = 6 + best * 16
        val hotX = if (type == 2) u16(b, e + 4) else 0
        val hotY = if (type == 2) u16(b, e + 6) else 0
        val size = u32(b, e + 8)
        val offset = u32(b, e + 12)
        if (size <= 0 || offset < 0 || offset + size > b.size) return null
        val data = b.copyOfRange(offset, offset + size)
        if (data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte()) {
            val w = if (data.size >= 24) (u16be(data, 16) shl 16) or u16be(data, 18) else 0
            val h = if (data.size >= 24) (u16be(data, 20) shl 16) or u16be(data, 22) else 0
            return CursorImage(w, h, null, data, hotX, hotY)
        }
        return decodeDib(data, hotX, hotY)
    }

    private fun u16be(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    /** BITMAPINFOHEADER の絵（高さは色と AND マスクの2枚ぶん、下の行から）。1・4・8・24・32 ビット */
    private fun decodeDib(d: ByteArray, hotX: Int, hotY: Int): CursorImage? {
        if (d.size < 40) return null
        val header = u32(d, 0)
        val w = u32(d, 4)
        val h = u32(d, 8) / 2
        val bpp = u16(d, 14)
        if (header < 40 || w !in 1..MAX_SIZE || h !in 1..MAX_SIZE || u32(d, 16) != 0) return null
        if (bpp !in setOf(1, 4, 8, 24, 32)) return null
        val colors = if (bpp <= 8) (u32(d, 32).takeIf { it > 0 } ?: (1 shl bpp)).coerceAtMost(256) else 0
        val palette = header
        val pixels = palette + colors * 4
        val stride = ((w * bpp + 31) / 32) * 4
        val maskStride = ((w + 31) / 32) * 4
        val mask = pixels + stride * h
        if (pixels + stride * h > d.size) return null
        val hasMask = mask + maskStride * h <= d.size
        val out = IntArray(w * h)
        var anyAlpha = false
        for (y in 0 until h) {
            val row = pixels + (h - 1 - y) * stride
            for (x in 0 until w) {
                val argb = when (bpp) {
                    32 -> {
                        val p = row + x * 4
                        val a = d[p + 3].toInt() and 0xFF
                        if (a != 0) anyAlpha = true
                        (a shl 24) or ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p].toInt() and 0xFF)
                    }
                    24 -> {
                        val p = row + x * 3
                        (0xFF shl 24) or ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p].toInt() and 0xFF)
                    }
                    else -> {
                        val bit = x * bpp
                        val byte = d[row + bit / 8].toInt() and 0xFF
                        val index = (byte shr (8 - bpp - bit % 8)) and ((1 shl bpp) - 1)
                        if (index >= colors) 0 else {
                            val p = palette + index * 4
                            (0xFF shl 24) or ((d[p + 2].toInt() and 0xFF) shl 16) or ((d[p + 1].toInt() and 0xFF) shl 8) or (d[p].toInt() and 0xFF)
                        }
                    }
                }
                out[y * w + x] = argb
            }
        }
        // 32 ビットで透明度を持っていればそのまま。持っていなければ AND マスクの 1 を透明にする
        if (!(bpp == 32 && anyAlpha) && hasMask) {
            for (y in 0 until h) {
                val row = mask + (h - 1 - y) * maskStride
                for (x in 0 until w) {
                    val transparent = ((d[row + x / 8].toInt() shr (7 - x % 8)) and 1) == 1
                    out[y * w + x] = if (transparent) 0 else out[y * w + x] or (0xFF shl 24)
                }
            }
        }
        return CursorImage(w, h, out, null, hotX.coerceIn(0, w - 1), hotY.coerceIn(0, h - 1))
    }

    private val RULE_KEY = Regex("""^(mouseup|mousedown|mouserightdown|mousewheel|mousehover)\d+$""")

    /** system:名前 → それを替える descript.txt の項目（先にあるものを使う） */
    private val KEYS = mapOf(
        "finger" to listOf("mousecursor", "cursor"),
        "hand" to listOf("mousecursor.hand"),
        "grip" to listOf("mousecursor.grip"),
        "arrow" to listOf("mousecursor.arrow"),
        "text" to listOf("mousecursor.text"),
        "wait" to listOf("mousecursor.wait"),
    )

    /** 読むカーソルの絵の大きさの上限（px） */
    private const val MAX_SIZE = 256
}
