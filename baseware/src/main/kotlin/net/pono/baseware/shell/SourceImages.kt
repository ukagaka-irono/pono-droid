package net.pono.baseware.shell

import java.io.File
import java.nio.charset.Charset
import java.util.zip.Inflater

/**
 * 複数の絵を持つ画像から 1 枚を取り出す（element の --source,値 と \_b の --source=値・exe / dll のリソース。SSP 2.8.41）。
 *   - PSD: レイヤーの名前（グループの中は「グループ::レイヤー」、入れ子も）。--source が無ければ合成済みの絵
 *   - マルチページの TIFF: 0 から数えたページ番号
 *   - ICO / CUR: 0 から数えた絵の番号（無ければいちばん大きい絵）
 *   - EXE / DLL: 0 から数えたアイコンの番号。負の数ならリソース ID（\_b[ファイル,-10]）
 * 中身は信用しない（大きさに上限を設け、壊れていれば null）。PNG と BMP は [Picture.Encoded] のまま返し、読むのは呼んだ側
 */
object SourceImages {
    /** 1 辺と画素数の上限（アプリの画像の読み込みと同じ） */
    const val MAX_SIDE = 4096
    const val MAX_PIXELS = 8_000_000L

    /** 読むファイルの大きさの上限 */
    private const val MAX_FILE_BYTES = 128L * 1024 * 1024

    sealed class Picture {
        /**
         * ARGB の画素（アルファは乗算していない）。[left] / [top] は PSD のレイヤーのキャンバスの中の位置、
         * [opacity] はレイヤーの不透明度（0〜255）、[method] はレイヤーの描画モードから推した描画メソッド（auto 用）
         */
        class Pixels(
            val width: Int,
            val height: Int,
            val argb: IntArray,
            val left: Int = 0,
            val top: Int = 0,
            val opacity: Int = 255,
            val method: String? = null,
        ) : Picture()

        /** そのまま画像として読めるもの（ICO・EXE の中の PNG） */
        class Encoded(val bytes: ByteArray) : Picture()
    }

    /** 先頭のバイトで、ここで読む形か */
    fun isMultiSource(head: ByteArray): Boolean = kind(head) != null

    fun isMultiSource(file: File): Boolean = runCatching {
        file.inputStream().use { s -> val b = ByteArray(8); val n = s.read(b); n >= 4 && isMultiSource(b.copyOf(n)) }
    }.getOrDefault(false)

    private enum class Kind { PSD, TIFF, ICO, PE }

    private fun kind(b: ByteArray): Kind? = when {
        b.size >= 4 && b[0] == '8'.code.toByte() && b[1] == 'B'.code.toByte() && b[2] == 'P'.code.toByte() && b[3] == 'S'.code.toByte() -> Kind.PSD
        b.size >= 4 && ((b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() && b[2].toInt() == 42 && b[3].toInt() == 0) ||
            (b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() && b[2].toInt() == 0 && b[3].toInt() == 42)) -> Kind.TIFF
        b.size >= 4 && b[0].toInt() == 0 && b[1].toInt() == 0 && (b[2].toInt() == 1 || b[2].toInt() == 2) && b[3].toInt() == 0 -> Kind.ICO
        b.size >= 2 && b[0] == 'M'.code.toByte() && b[1] == 'Z'.code.toByte() -> Kind.PE
        else -> null
    }

    /** [file] から [source]（無ければ既定の 1 枚）を取り出す。読めなければ null */
    fun extract(file: File, source: String?): Picture? = runCatching {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        extract(file.readBytes(), source)
    }.getOrNull()

    fun extract(bytes: ByteArray, source: String?): Picture? = runCatching {
        when (kind(bytes)) {
            Kind.PSD -> Psd(bytes).picture(source)
            Kind.TIFF -> Tiff(bytes).page(source?.trim()?.toIntOrNull() ?: 0)
            Kind.ICO -> Ico.picture(bytes, 0, source?.trim()?.toIntOrNull())
            Kind.PE -> Pe(bytes).icon(source?.trim()?.toIntOrNull() ?: 0)
            null -> null
        }
    }.getOrNull()

    private fun checkSize(w: Int, h: Int): Boolean = w in 1..MAX_SIDE && h in 1..MAX_SIDE && w.toLong() * h <= MAX_PIXELS

    // ---- 読み出しの道具 ----

    private class Reader(val b: ByteArray, val little: Boolean = false) {
        fun u8(at: Int): Int = b[at].toInt() and 0xFF
        fun u16(at: Int): Int = if (little) u8(at) or (u8(at + 1) shl 8) else (u8(at) shl 8) or u8(at + 1)
        fun s16(at: Int): Int = u16(at).toShort().toInt()
        fun u32(at: Int): Long = if (little) {
            (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
        } else {
            (u16(at).toLong() shl 16) or u16(at + 2).toLong()
        }
        fun s32(at: Int): Int = u32(at).toInt()
        fun ascii(at: Int, n: Int): String = String(b, at, n, Charsets.ISO_8859_1)
    }

    /** PackBits（PSD の RLE・TIFF の 32773）を [out] の [outAt] から [n] バイト分ほどく。読んだ入力の終わりを返す */
    private fun unpackBits(src: ByteArray, from: Int, end: Int, out: ByteArray, outAt: Int, n: Int): Int {
        var i = from
        var o = outAt
        val stop = outAt + n
        while (o < stop && i < end) {
            val c = src[i++].toInt()
            if (c >= 0) {
                val len = minOf(c + 1, stop - o, end - i)
                System.arraycopy(src, i, out, o, len)
                i += c + 1
                o += len
            } else if (c != -128) {
                val len = minOf(1 - c, stop - o)
                if (i >= end) break
                val v = src[i++]
                java.util.Arrays.fill(out, o, o + len, v)
                o += len
            }
        }
        return i
    }

    private fun inflate(src: ByteArray, from: Int, len: Int, size: Int): ByteArray? {
        val inf = Inflater()
        return try {
            inf.setInput(src, from, len)
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inf.finished()) {
                val r = inf.inflate(out, n, size - n)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            out
        } finally {
            inf.end()
        }
    }

    // ---- PSD ----

    private class Psd(bytes: ByteArray) {
        private val r = Reader(bytes)
        private val b = bytes
        private val width: Int
        private val height: Int
        private val channels: Int
        private val colorMode: Int
        private val layerInfoAt: Int
        private val mergedAt: Int

        init {
            require(r.u16(4) == 1) { "PSB is not supported" }
            channels = r.u16(12)
            height = r.s32(14)
            width = r.s32(18)
            require(r.u16(22) == 8) { "only 8-bit PSD" }
            colorMode = r.u16(24)
            require(colorMode == 3 || colorMode == 1) { "only RGB / grayscale PSD" }
            var at = 26
            at += 4 + r.s32(at) // color mode data
            at += 4 + r.s32(at) // image resources
            val layerAndMaskLen = r.s32(at)
            layerInfoAt = at + 4
            mergedAt = at + 4 + layerAndMaskLen
        }

        /** [group] はグループの始まりと終わりの印（絵は無い） */
        class Layer(val path: List<String>, val top: Int, val left: Int, val bottom: Int, val right: Int,
                    val channels: List<Pair<Int, Int>>, val blend: String, val opacity: Int, val group: Boolean = false)

        fun picture(source: String?): Picture? {
            if (source.isNullOrBlank()) return merged()
            val want = source.split("::").map { it.trim() }
            val (layers, dataAt) = layers() ?: return null
            // 名前の一致するいちばん上のレイヤー（リストは下から上の順）
            var at = dataAt
            val starts = layers.map { l -> at.also { at += l.channels.sumOf { it.second } } }
            val i = layers.indices.lastOrNull { !layers[it].group && layers[it].path == want } ?: return null
            return layerPixels(layers[i], starts[i])
        }

        /** レイヤーの一覧（下から上）と、その画像のデータの始まり */
        private fun layers(): Pair<List<Layer>, Int>? {
            val infoLen = r.s32(layerInfoAt)
            if (infoLen <= 0) return null
            var at = layerInfoAt + 4
            val count = kotlin.math.abs(r.s16(at))
            at += 2
            val raw = ArrayList<Layer>()
            val dividers = ArrayList<Int>()
            for (n in 0 until count) {
                val top = r.s32(at); val left = r.s32(at + 4); val bottom = r.s32(at + 8); val right = r.s32(at + 12)
                at += 16
                val nch = r.u16(at)
                at += 2
                val chans = (0 until nch).map { k -> r.s16(at + k * 6) to r.s32(at + k * 6 + 2) }
                at += nch * 6
                at += 4 // 8BIM
                val blend = r.ascii(at, 4)
                at += 4
                val opacity = r.u8(at)
                at += 4
                val extraLen = r.s32(at)
                at += 4
                val extraEnd = at + extraLen
                var p = at
                p += 4 + r.s32(p) // layer mask
                p += 4 + r.s32(p) // blending ranges
                val nameLen = r.u8(p)
                var name = String(b, p + 1, nameLen, Charset.forName("windows-31j"))
                p += ((1 + nameLen + 3) / 4) * 4
                var divider = 0
                while (p + 12 <= extraEnd) {
                    val sig = r.ascii(p, 4)
                    if (sig != "8BIM" && sig != "8B64") break
                    val key = r.ascii(p + 4, 4)
                    val len = r.s32(p + 8)
                    val data = p + 12
                    when (key) {
                        "luni" -> {
                            val chars = r.s32(data)
                            if (chars in 0..1024) name = String(b, data + 4, chars * 2, Charsets.UTF_16BE).trimEnd('\u0000')
                        }
                        "lsct", "lsdk" -> divider = r.s32(data)
                    }
                    p = data + len + (len and 1)
                }
                at = extraEnd
                raw += Layer(listOf(name), top, left, bottom, right, chans, blend, opacity)
                dividers += divider
            }
            // グループ: 下から上の並びでは、終わりの印（3）が先に、フォルダ（1・2）が後に来る。上から見て名前を積む
            val paths = arrayOfNulls<List<String>>(raw.size)
            val stack = ArrayList<String>()
            for (i in raw.indices.reversed()) {
                when (dividers[i]) {
                    1, 2 -> { paths[i] = stack + raw[i].path; stack += raw[i].path[0] }
                    3 -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); paths[i] = emptyList() }
                    else -> paths[i] = stack + raw[i].path
                }
            }
            val layers = raw.mapIndexed { i, l ->
                Layer(paths[i]!!, l.top, l.left, l.bottom, l.right, l.channels, l.blend, l.opacity, group = dividers[i] in 1..3)
            }
            return layers to at
        }

        private fun layerPixels(l: Layer, start: Int): Picture? {
            val w = l.right - l.left
            val h = l.bottom - l.top
            if (!checkSize(w, h)) return null
            val planes = HashMap<Int, ByteArray>()
            var at = start
            for ((id, len) in l.channels) {
                planes[id] = channel(at, len, w, h) ?: return null
                at += len
            }
            val argb = IntArray(w * h)
            val gray = colorMode == 1
            val rr = planes[0]
            val gg = if (gray) rr else planes[1]
            val bb = if (gray) rr else planes[2]
            val aa = planes[-1]
            if (rr == null || gg == null || bb == null) return null
            for (i in argb.indices) {
                val a = aa?.let { it[i].toInt() and 0xFF } ?: 255
                argb[i] = (a shl 24) or ((rr[i].toInt() and 0xFF) shl 16) or ((gg[i].toInt() and 0xFF) shl 8) or (bb[i].toInt() and 0xFF)
            }
            return Picture.Pixels(w, h, argb, l.left, l.top, l.opacity, methodOf(l.blend))
        }

        /** 1 チャンネル（先頭の 2 バイトが圧縮の種類） */
        private fun channel(at: Int, len: Int, w: Int, h: Int): ByteArray? {
            val size = w * h
            val out = ByteArray(size)
            val data = at + 2
            val end = at + len
            when (r.u16(at)) {
                0 -> System.arraycopy(b, data, out, 0, minOf(size, end - data))
                1 -> {
                    var p = data + h * 2
                    for (y in 0 until h) {
                        val rowLen = r.u16(data + y * 2)
                        unpackBits(b, p, minOf(p + rowLen, end), out, y * w, w)
                        p += rowLen
                    }
                }
                2, 3 -> {
                    val raw = inflate(b, data, end - data, size) ?: return null
                    if (r.u16(at) == 3) for (y in 0 until h) for (x in 1 until w) {
                        val i = y * w + x
                        raw[i] = (raw[i] + raw[i - 1]).toByte()
                    }
                    return raw
                }
                else -> return null
            }
            return out
        }

        /** 合成済みの絵（ファイルの最後。チャンネルは平面ごとに並ぶ） */
        private fun merged(): Picture? {
            if (!checkSize(width, height)) return null
            val size = width * height
            val compression = r.u16(mergedAt)
            var at = mergedAt + 2
            val planes = ArrayList<ByteArray>()
            val n = minOf(channels, if (colorMode == 1) 2 else 4)
            if (compression == 1) {
                val counts = at
                at += channels * height * 2
                for (c in 0 until n) {
                    val out = ByteArray(size)
                    for (y in 0 until height) {
                        val rowLen = r.u16(counts + (c * height + y) * 2)
                        unpackBits(b, at, minOf(at + rowLen, b.size), out, y * width, width)
                        at += rowLen
                    }
                    planes += out
                }
            } else if (compression == 0) {
                for (c in 0 until n) {
                    planes += b.copyOfRange(at, minOf(at + size, b.size)).copyOf(size)
                    at += size
                }
            } else {
                return null
            }
            val gray = colorMode == 1
            val argb = IntArray(size)
            for (i in 0 until size) {
                val rv = planes[0][i].toInt() and 0xFF
                val gv = if (gray) rv else planes.getOrNull(1)?.get(i)?.toInt()?.and(0xFF) ?: rv
                val bv = if (gray) rv else planes.getOrNull(2)?.get(i)?.toInt()?.and(0xFF) ?: rv
                val av = planes.getOrNull(if (gray) 1 else 3)?.get(i)?.toInt()?.and(0xFF) ?: 255
                argb[i] = (av shl 24) or (rv shl 16) or (gv shl 8) or bv
            }
            return Picture.Pixels(width, height, argb)
        }

        /** PSD の描画モードを、SSP の描画メソッドの名前にする（auto） */
        private fun methodOf(key: String): String = when (key) {
            "mul " -> "blend-multiply"
            "scrn" -> "blend-screen"
            "over" -> "blend-overlay"
            "dark" -> "blend-darken"
            "lite" -> "blend-lighten"
            "div " -> "blend-color-dodge"
            "idiv" -> "blend-color-burn"
            "hLit" -> "blend-hard-light"
            "sLit" -> "blend-soft-light"
            "diff" -> "blend-difference"
            "smud" -> "blend-exclusion"
            "lddg" -> "blend-add"
            "lbrn" -> "blend-linear-burn"
            "lLit" -> "blend-linear-light"
            "vLit" -> "blend-vivid-light"
            "pLit" -> "blend-pin-light"
            "hMix" -> "blend-hard-mix"
            "fsub" -> "blend-subtract"
            "fdiv" -> "blend-divide"
            "hue " -> "blend-hue"
            "sat " -> "blend-saturation"
            "colr" -> "blend-color"
            "lum " -> "blend-luminosity"
            "dkCl" -> "blend-darker-color"
            "lgCl" -> "blend-lighter-color"
            else -> "overlay"
        }
    }

    // ---- TIFF ----

    private class Tiff(bytes: ByteArray) {
        private val b = bytes
        private val r = Reader(bytes, little = bytes[0] == 'I'.code.toByte())

        fun page(index: Int): Picture? {
            var ifd = r.u32(4).toInt()
            var n = 0
            while (ifd in 8 until b.size && n < index) {
                ifd = r.u32(ifd + 2 + r.u16(ifd) * 12).toInt()
                n++
            }
            if (ifd !in 8 until b.size) return null
            return decode(ifd)
        }

        private fun values(entry: Int): List<Long> {
            val type = r.u16(entry + 2)
            val count = r.u32(entry + 4).toInt()
            val size = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9 -> 4; else -> return emptyList() }
            if (count !in 0..1_000_000) return emptyList()
            val at = if (size * count <= 4) entry + 8 else r.u32(entry + 8).toInt()
            return (0 until count).map { i ->
                when (size) { 1 -> r.u8(at + i).toLong(); 2 -> r.u16(at + i * 2).toLong(); else -> r.u32(at + i * 4) }
            }
        }

        private fun decode(ifd: Int): Picture? {
            val tags = HashMap<Int, List<Long>>()
            for (i in 0 until r.u16(ifd)) {
                val e = ifd + 2 + i * 12
                tags[r.u16(e)] = values(e)
            }
            fun tag(id: Int, default: Long = 0) = tags[id]?.firstOrNull() ?: default
            val w = tag(256).toInt()
            val h = tag(257).toInt()
            if (!checkSize(w, h)) return null
            val bits = tags[258]?.firstOrNull() ?: 1
            if (bits != 8L) return null
            val compression = tag(259, 1).toInt()
            val photometric = tag(262, 2).toInt()
            val spp = tag(277, 1).toInt()
            if (tag(284, 1) != 1L || spp !in 1..4) return null
            val predictor = tag(317, 1).toInt()
            val extra = tags[338]?.firstOrNull() ?: 0
            val offsets = tags[273] ?: return null // タイルは読まない
            val counts = tags[279] ?: return null
            val rowsPerStrip = tag(278, h.toLong()).toInt().coerceAtLeast(1)
            val rowBytes = w * spp
            val raw = ByteArray(rowBytes * h)
            for ((s, off) in offsets.withIndex()) {
                val y0 = s * rowsPerStrip
                if (y0 >= h) break
                val rows = minOf(rowsPerStrip, h - y0)
                val need = rows * rowBytes
                val start = off.toInt()
                val len = counts.getOrNull(s)?.toInt() ?: break
                if (start < 0 || start + len > b.size) return null
                val strip = when (compression) {
                    1 -> b.copyOfRange(start, start + minOf(len, need))
                    32773 -> ByteArray(need).also { unpackBits(b, start, start + len, it, 0, need) }
                    8, 32946 -> inflate(b, start, len, need) ?: return null
                    5 -> lzw(start, len, need)
                    else -> return null
                }
                if (predictor == 2) for (y in 0 until rows) for (x in spp until rowBytes) {
                    val i = y * rowBytes + x
                    if (i < strip.size) strip[i] = (strip[i] + strip[i - spp]).toByte()
                }
                System.arraycopy(strip, 0, raw, y0 * rowBytes, minOf(strip.size, need))
            }
            val palette = tags[320]
            val argb = IntArray(w * h)
            for (i in argb.indices) {
                val p = i * spp
                fun c(k: Int) = raw[p + k].toInt() and 0xFF
                argb[i] = when {
                    photometric == 3 && palette != null -> {
                        val v = c(0)
                        val n = palette.size / 3
                        if (v >= n) 0xFF000000.toInt() else {
                            (0xFF shl 24) or ((palette[v].toInt() shr 8) shl 16) or ((palette[n + v].toInt() shr 8) shl 8) or (palette[2 * n + v].toInt() shr 8)
                        }
                    }
                    photometric <= 1 -> {
                        val g = if (photometric == 0) 255 - c(0) else c(0)
                        val a = if (spp >= 2) c(1) else 255
                        (a shl 24) or (g shl 16) or (g shl 8) or g
                    }
                    else -> {
                        var a = if (spp >= 4) c(3) else 255
                        var rv = c(0); var gv = c(1); var bv = c(2)
                        // 1 はアルファを掛けてある
                        if (spp >= 4 && extra == 1L && a in 1..254) {
                            rv = minOf(255, rv * 255 / a); gv = minOf(255, gv * 255 / a); bv = minOf(255, bv * 255 / a)
                        }
                        if (spp < 4) a = 255
                        (a shl 24) or (rv shl 16) or (gv shl 8) or bv
                    }
                }
            }
            return Picture.Pixels(w, h, argb)
        }

        /** TIFF の LZW（上位ビットから読む。早めに符号の長さを変える） */
        private fun lzw(start: Int, len: Int, need: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream(need)
            val table = ArrayList<ByteArray>(4096)
            fun reset() {
                table.clear()
                for (i in 0 until 256) table += byteArrayOf(i.toByte())
                table += ByteArray(0)
                table += ByteArray(0)
            }
            reset()
            var bitPos = start.toLong() * 8
            val end = (start + len).toLong() * 8
            var width = 9
            var prev: ByteArray? = null
            while (bitPos + width <= end && out.size() < need) {
                var code = 0
                for (k in 0 until width) {
                    val p = bitPos + k
                    val bit = (b[(p / 8).toInt()].toInt() shr (7 - (p % 8).toInt())) and 1
                    code = (code shl 1) or bit
                }
                bitPos += width
                if (code == 257) break
                if (code == 256) {
                    reset(); width = 9; prev = null; continue
                }
                val entry = when {
                    code < table.size -> table[code]
                    prev != null -> prev + prev[0]
                    else -> break
                }
                out.write(entry)
                if (prev != null && table.size < 4096) table += prev + entry[0]
                prev = entry
                width = when {
                    table.size + 1 >= 2048 -> 12
                    table.size + 1 >= 1024 -> 11
                    table.size + 1 >= 512 -> 10
                    else -> 9
                }
            }
            return out.toByteArray().copyOf(need)
        }
    }

    // ---- ICO / CUR と、EXE・DLL の中のアイコン ----

    private object Ico {
        /** ICO の [at] からのディレクトリから、[index] 番目（無ければいちばん大きい絵） */
        fun picture(b: ByteArray, at: Int, index: Int?): Picture? {
            val r = Reader(b, little = true)
            val count = r.u16(at + 4)
            if (count <= 0) return null
            val entries = (0 until count).map { i ->
                val e = at + 6 + i * 16
                val w = r.u8(e).let { if (it == 0) 256 else it }
                val h = r.u8(e + 1).let { if (it == 0) 256 else it }
                Triple(w * h * 64 + r.u16(e + 6), r.u32(e + 8).toInt(), r.u32(e + 12).toInt())
            }
            val pick = if (index != null) entries.getOrNull(index) else entries.maxByOrNull { it.first }
            val (_, size, offset) = pick ?: return null
            if (offset < 0 || size <= 0 || offset + size > b.size) return null
            return image(b.copyOfRange(offset, offset + size))
        }

        /** アイコンの 1 枚（PNG か、ヘッダーの無い DIB） */
        fun image(data: ByteArray): Picture? {
            if (data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte()) return Picture.Encoded(data)
            return dib(data)
        }

        /** BITMAPINFOHEADER から始まる DIB（高さは XOR と AND の 2 枚分）。32 bit はアルファ、それ以外は AND のマスクで抜く */
        private fun dib(d: ByteArray): Picture? {
            val r = Reader(d, little = true)
            val headerSize = r.s32(0)
            val w = r.s32(4)
            val h = kotlin.math.abs(r.s32(8)) / 2
            val bpp = r.u16(14)
            if (!checkSize(w, h) || r.s32(16) != 0) return null
            val colors = r.s32(32).let { if (it == 0 && bpp <= 8) 1 shl bpp else it }
            val paletteAt = headerSize
            val pixelsAt = paletteAt + if (bpp <= 8) colors * 4 else 0
            val stride = ((w * bpp + 31) / 32) * 4
            val maskAt = pixelsAt + stride * h
            val maskStride = ((w + 31) / 32) * 4
            val argb = IntArray(w * h)
            var anyAlpha = false
            for (y in 0 until h) {
                val row = pixelsAt + (h - 1 - y) * stride
                for (x in 0 until w) {
                    val c = when (bpp) {
                        32 -> r.s32(row + x * 4).also { if ((it ushr 24) != 0) anyAlpha = true }
                        24 -> (0xFF shl 24) or (r.u8(row + x * 3 + 2) shl 16) or (r.u8(row + x * 3 + 1) shl 8) or r.u8(row + x * 3)
                        8, 4, 1 -> {
                            val idx = when (bpp) {
                                8 -> r.u8(row + x)
                                4 -> (r.u8(row + x / 2) shr (if (x % 2 == 0) 4 else 0)) and 0x0F
                                else -> (r.u8(row + x / 8) shr (7 - x % 8)) and 1
                            }
                            val p = paletteAt + idx * 4
                            if (p + 3 >= d.size) 0 else (0xFF shl 24) or (r.u8(p + 2) shl 16) or (r.u8(p + 1) shl 8) or r.u8(p)
                        }
                        else -> return null
                    }
                    argb[y * w + x] = c
                }
            }
            // アルファの無いアイコンは AND のマスク（1 が透明）
            if (!(bpp == 32 && anyAlpha)) {
                for (y in 0 until h) {
                    val row = maskAt + (h - 1 - y) * maskStride
                    for (x in 0 until w) {
                        val p = row + x / 8
                        val transparent = p < d.size && ((r.u8(p) shr (7 - x % 8)) and 1) == 1
                        val i = y * w + x
                        argb[i] = if (transparent) 0 else argb[i] or (0xFF shl 24)
                    }
                }
            }
            return Picture.Pixels(w, h, argb)
        }
    }

    private class Pe(bytes: ByteArray) {
        private val b = bytes
        private val r = Reader(bytes, little = true)
        private val sections = ArrayList<Triple<Long, Long, Long>>() // VirtualAddress, size, PointerToRawData
        private val resourceRva: Long

        init {
            val pe = r.s32(0x3C)
            require(r.ascii(pe, 4) == "PE\u0000\u0000")
            val nSections = r.u16(pe + 6)
            val optSize = r.u16(pe + 20)
            val opt = pe + 24
            val dirs = when (r.u16(opt)) { 0x10b -> opt + 96; 0x20b -> opt + 112; else -> error("unknown PE") }
            resourceRva = r.u32(dirs + 2 * 8)
            val sec = opt + optSize
            for (i in 0 until nSections) {
                val s = sec + i * 40
                sections += Triple(r.u32(s + 12), maxOf(r.u32(s + 8), r.u32(s + 16)), r.u32(s + 20))
            }
        }

        private fun offset(rva: Long): Int? =
            sections.firstOrNull { (va, size, _) -> rva >= va && rva < va + size }?.let { (va, _, raw) -> (rva - va + raw).toInt() }

        private val root: Int? = offset(resourceRva)

        /** ディレクトリ [dir]（ルートからの位置）の項目（ID か名前の印, 子の位置, 子がディレクトリか） */
        private fun entries(dir: Int): List<Triple<Long, Int, Boolean>> {
            val base = root ?: return emptyList()
            val at = base + dir
            val n = r.u16(at + 12) + r.u16(at + 14)
            if (n > 4096) return emptyList()
            return (0 until n).map { i ->
                val e = at + 16 + i * 8
                val off = r.u32(e + 4)
                Triple(r.u32(e), (off and 0x7FFFFFFF).toInt(), (off and 0x80000000L) != 0L)
            }
        }

        /** 種類 [type] の、名前か ID ごとの最初の言語のデータ（ID, 中身） */
        private fun resources(type: Int): List<Pair<Long, ByteArray>> {
            val base = root ?: return emptyList()
            val typeDir = entries(0).firstOrNull { it.first == type.toLong() && it.third } ?: return emptyList()
            return entries(typeDir.second).mapNotNull { (id, child, isDir) ->
                var at = child
                var dir = isDir
                // 言語のディレクトリを 1 段下りる
                if (dir) entries(at).firstOrNull()?.let { at = it.second; dir = it.third } ?: return@mapNotNull null
                if (dir) return@mapNotNull null
                val data = base + at
                val rva = r.u32(data)
                val size = r.u32(data + 4).toInt()
                val off = offset(rva) ?: return@mapNotNull null
                if (off < 0 || size <= 0 || off + size > b.size) return@mapNotNull null
                id to b.copyOfRange(off, off + size)
            }
        }

        /** [index] 番目（0 から。負なら -index の ID）のアイコンの、いちばん大きい絵 */
        fun icon(index: Int): Picture? {
            val groups = resources(14)
            val group = if (index < 0) groups.firstOrNull { it.first == (-index).toLong() } else groups.getOrNull(index)
            val g = group?.second ?: return null
            val gr = Reader(g, little = true)
            val count = gr.u16(4)
            val icons = resources(3).toMap()
            val best = (0 until count).map { i ->
                val e = 6 + i * 14
                val w = gr.u8(e).let { if (it == 0) 256 else it }
                val h = gr.u8(e + 1).let { if (it == 0) 256 else it }
                (w * h * 64 + gr.u16(e + 6)) to gr.u16(e + 12).toLong()
            }.maxByOrNull { it.first } ?: return null
            return icons[best.second]?.let(Ico::image)
        }
    }
}
