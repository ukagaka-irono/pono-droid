package net.pono.baseware.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

/** --source（PSD のレイヤー・TIFF のページ・ICO・EXE / DLL のアイコン）の取り出し */
class SourceImagesTest {
    private fun pixels(p: SourceImages.Picture?): SourceImages.Picture.Pixels =
        p as? SourceImages.Picture.Pixels ?: throw AssertionError("not pixels: $p")

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    // ---- TIFF（Pillow で作ったもの。1 ページ目は 4x3 の RGBA、2 ページ目は 5x2 の RGB） ----

    private fun page0(p: SourceImages.Picture.Pixels) {
        assertEquals(4, p.width)
        assertEquals(3, p.height)
        for (y in 0 until 3) for (x in 0 until 4) {
            assertEquals("$x,$y", argb(if (x < 3) 255 else 128, x * 60, y * 80, 200), p.argb[y * 4 + x])
        }
    }

    @Test fun tiffPagesUncompressedAndLzw() {
        for (data in listOf(tiffRaw, tiffLzw)) {
            val bytes = Base64.getDecoder().decode(data)
            assertTrue(SourceImages.isMultiSource(bytes))
            page0(pixels(SourceImages.extract(bytes, null)))
            page0(pixels(SourceImages.extract(bytes, "0")))
            val p1 = pixels(SourceImages.extract(bytes, "1"))
            assertEquals(5, p1.width)
            assertEquals(2, p1.height)
            for (y in 0 until 2) for (x in 0 until 5) assertEquals(argb(255, 10, 20 + x * 40, 30 + y * 100), p1.argb[y * 5 + x])
            assertNull(SourceImages.extract(bytes, "2"))
        }
    }

    @Test fun tiffPackBitsAndDeflate() {
        page0(pixels(SourceImages.extract(Base64.getDecoder().decode(tiffPackbits), null)))
        page0(pixels(SourceImages.extract(Base64.getDecoder().decode(tiffDeflate), null)))
    }

    // ---- ICO（16x16 と 32x32 の PNG） ----

    @Test fun icoPicksLargestOrIndex() {
        val bytes = Base64.getDecoder().decode(ico)
        val largest = SourceImages.extract(bytes, null) as SourceImages.Picture.Encoded
        val first = SourceImages.extract(bytes, "0") as SourceImages.Picture.Encoded
        assertEquals(32, pngWidth(largest.bytes))
        assertEquals(16, pngWidth(first.bytes))
    }

    private fun pngWidth(png: ByteArray) =
        ((png[16].toInt() and 0xFF) shl 24) or ((png[17].toInt() and 0xFF) shl 16) or ((png[18].toInt() and 0xFF) shl 8) or (png[19].toInt() and 0xFF)

    // ---- PSD（4x3 の背景と、グループ「顔」の中の 2x2 の「頭」） ----

    @Test fun psdLayersAndGroups() {
        val bytes = psd()
        assertTrue(SourceImages.isMultiSource(bytes))
        // --source が無ければ合成済みの絵
        val merged = pixels(SourceImages.extract(bytes, null))
        assertEquals(4, merged.width)
        assertEquals(argb(255, 1, 2, 3), merged.argb[0])
        // 背景（RLE で圧縮していない、アルファの無いレイヤー）
        val back = pixels(SourceImages.extract(bytes, "背景"))
        assertEquals(4, back.width)
        assertEquals(3, back.height)
        assertEquals(argb(255, 9, 9, 9), back.argb[5])
        assertEquals("overlay", back.method)
        // グループの中は「グループ::レイヤー」。位置・不透明度・描画モード（乗算）も読む
        val head = pixels(SourceImages.extract(bytes, "顔::頭"))
        assertEquals(2, head.width)
        assertEquals(1, head.left)
        assertEquals(1, head.top)
        assertEquals(128, head.opacity)
        assertEquals("blend-multiply", head.method)
        assertArrayEquals(intArrayOf(argb(200, 50, 60, 70), argb(200, 50, 60, 70), argb(100, 51, 60, 70), argb(100, 51, 60, 70)), head.argb)
        // グループの外には無い名前・グループそのものは取れない
        assertNull(SourceImages.extract(bytes, "頭"))
        assertNull(SourceImages.extract(bytes, "顔"))
    }

    private fun psd(): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeBytes("8BPS"); d.writeShort(1); d.write(ByteArray(6)); d.writeShort(3); d.writeInt(3); d.writeInt(4); d.writeShort(8); d.writeShort(3)
        d.writeInt(0) // color mode data
        d.writeInt(0) // image resources
        // レイヤー（下から上）: 背景・グループの終わり・頭・グループ「顔」
        class L(val name: String, val rect: IntArray, val channels: List<Pair<Int, ByteArray>>, val blend: String, val opacity: Int, val divider: Int?)
        fun raw(v: Int, n: Int) = byteArrayOf(0, 0) + ByteArray(n) { v.toByte() }
        // RLE: 行ごとの長さ（2 行 x 2 バイト）と、行ごとの PackBits（2 回繰り返す）
        fun rle(row0: Int, row1: Int) = byteArrayOf(0, 1, 0, 2, 0, 2, 0xFF.toByte(), row0.toByte(), 0xFF.toByte(), row1.toByte())
        val empty = listOf(-1 to byteArrayOf(0, 0), 0 to byteArrayOf(0, 0), 1 to byteArrayOf(0, 0), 2 to byteArrayOf(0, 0))
        val layers = listOf(
            L("背景", intArrayOf(0, 0, 3, 4), listOf(0 to raw(9, 12), 1 to raw(9, 12), 2 to raw(9, 12)), "norm", 255, null),
            L("</Layer group>", intArrayOf(0, 0, 0, 0), empty, "norm", 255, 3),
            L("頭", intArrayOf(1, 1, 3, 3), listOf(-1 to rle(200, 100), 0 to rle(50, 51), 1 to raw(60, 4), 2 to raw(70, 4)), "mul ", 128, null),
            L("顔", intArrayOf(0, 0, 0, 0), empty, "pass", 255, 1),
        )
        val info = ByteArrayOutputStream()
        val li = DataOutputStream(info)
        li.writeShort(layers.size)
        for (l in layers) {
            for (v in l.rect) li.writeInt(v)
            li.writeShort(l.channels.size)
            for ((id, data) in l.channels) { li.writeShort(id); li.writeInt(data.size) }
            li.writeBytes("8BIM"); li.writeBytes(l.blend); li.write(l.opacity); li.write(0); li.write(0); li.write(0)
            val extra = ByteArrayOutputStream()
            val e = DataOutputStream(extra)
            e.writeInt(0); e.writeInt(0)
            // パスカル文字列の名前（4 バイトにそろえる）。本当の名前は luni に
            e.write(1); e.write('x'.code); e.write(0); e.write(0)
            val utf16 = l.name.toByteArray(Charsets.UTF_16BE)
            e.writeBytes("8BIM"); e.writeBytes("luni"); e.writeInt(4 + utf16.size); e.writeInt(l.name.length); e.write(utf16)
            l.divider?.let { e.writeBytes("8BIM"); e.writeBytes("lsct"); e.writeInt(4); e.writeInt(it) }
            li.writeInt(extra.size()); li.write(extra.toByteArray())
        }
        for (l in layers) for ((_, data) in l.channels) li.write(data)
        val layerInfo = info.toByteArray()
        d.writeInt(4 + layerInfo.size + 4)
        d.writeInt(layerInfo.size); d.write(layerInfo)
        d.writeInt(0) // global layer mask
        // 合成済みの絵（圧縮なし、平面ごと）
        d.writeShort(0)
        d.write(ByteArray(12) { 1 }); d.write(ByteArray(12) { 2 }); d.write(ByteArray(12) { 3 })
        return out.toByteArray()
    }

    // ---- EXE / DLL のアイコン（作った PE。グループ 5 は PNG と DIB、グループ 6 は DIB だけ） ----

    @Test fun peIconsByIndexAndId() {
        val pe = pe()
        assertTrue(SourceImages.isMultiSource(pe))
        // 0 番目のグループのいちばん大きい絵は 16x16 の PNG
        val png = SourceImages.extract(pe, "0") as SourceImages.Picture.Encoded
        assertEquals(16, pngWidth(png.bytes))
        // 1 番目（と ID 6）は 2x2 の DIB。左上は AND のマスクで透明
        for (s in listOf("1", "-6")) {
            val dib = pixels(SourceImages.extract(pe, s))
            assertEquals(2, dib.width)
            assertEquals(2, dib.height)
            assertEquals(0, dib.argb[0])
            assertEquals(argb(255, 30, 20, 10), dib.argb[1])
            assertEquals(argb(255, 60, 50, 40), dib.argb[2])
        }
        assertNull(SourceImages.extract(pe, "2"))
    }

    private fun le(v: Int, n: Int) = ByteArray(n) { ((v ushr (8 * it)) and 0xFF).toByte() }

    private fun pe(): ByteArray {
        // DIB 2x2 24bpp（下の行から）と AND のマスク（上の行の左が透明）
        val dib = le(40, 4) + le(2, 4) + le(4, 4) + le(1, 2) + le(24, 2) + ByteArray(24) +
            byteArrayOf(40, 50, 60, 70, 80, 90, 0, 0) + byteArrayOf(0, 0, 0, 10, 20, 30, 0, 0) +
            byteArrayOf(0, 0, 0, 0) + byteArrayOf(0x80.toByte(), 0, 0, 0)
        val png = Base64.getDecoder().decode(ico).let { b ->
            val size = (b[14].toInt() and 0xFF) or ((b[15].toInt() and 0xFF) shl 8)
            val off = (b[18].toInt() and 0xFF) or ((b[19].toInt() and 0xFF) shl 8)
            b.copyOfRange(off, off + size)
        }
        fun group(vararg icons: Triple<Int, Int, Int>) = le(0, 2) + le(1, 2) + le(icons.size, 2) + icons.fold(ByteArray(0)) { acc, (w, bpp, id) ->
            acc + byteArrayOf(w.toByte(), w.toByte(), 0, 0) + le(1, 2) + le(bpp, 2) + le(0, 4) + le(id, 2)
        }
        val datas = listOf(dib, png, group(Triple(2, 24, 1), Triple(16, 32, 2)), group(Triple(2, 24, 1)))
        // 資源の木（.rsrc の中の位置）。根: 3（アイコン）と 14（グループ）
        val rsrc = ByteArrayOutputStream()
        fun dir(n: Int) = ByteArray(12) + le(0, 2) + le(n, 2)
        fun entry(id: Int, off: Int, isDir: Boolean) = le(id, 4) + le(if (isDir) off or Int.MIN_VALUE else off, 4)
        // 並び: 根(16+16) 3 の木(16+16) 14 の木(16+16) 言語 x4 (16+8 ずつ) データの項目 x4 (16 ずつ) データ
        val rootAt = 0; val iconDir = 32; val groupDir = 64; val langAt = 96; val dataEntryAt = langAt + 4 * 24; val dataAt = dataEntryAt + 4 * 16
        rsrc.write(dir(2)); rsrc.write(entry(3, iconDir, true)); rsrc.write(entry(14, groupDir, true))
        rsrc.write(dir(2)); rsrc.write(entry(1, langAt, true)); rsrc.write(entry(2, langAt + 24, true))
        rsrc.write(dir(2)); rsrc.write(entry(5, langAt + 48, true)); rsrc.write(entry(6, langAt + 72, true))
        for (i in 0 until 4) { rsrc.write(dir(1)); rsrc.write(entry(0x411, dataEntryAt + i * 16, false)) }
        val rva = 0x1000
        var at = dataAt
        for (data in datas) { rsrc.write(le(rva + at, 4) + le(data.size, 4) + ByteArray(8)); at += data.size }
        for (data in datas) rsrc.write(data)
        check(rootAt == 0)
        val res = rsrc.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte()) + ByteArray(0x3A) + le(64, 4))
        out.write("PE\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
        out.write(le(0x14c, 2) + le(1, 2) + ByteArray(12) + le(224, 2) + le(0x2102, 2))
        val opt = ByteArray(224)
        le(0x10b, 2).copyInto(opt, 0)
        le(rva, 4).copyInto(opt, 96 + 16)
        le(res.size, 4).copyInto(opt, 96 + 20)
        out.write(opt)
        out.write(".rsrc\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + le(res.size, 4) + le(rva, 4) + le(res.size, 4) + le(0x200, 4) + ByteArray(16))
        out.write(ByteArray(0x200 - out.size()))
        out.write(res)
        return out.toByteArray()
    }

    private companion object {
        private val tiffRaw = (
            "SUkqAAgAAAALAAABBAABAAAABAAAAAEBBAABAAAAAwAAAAIBAwAEAAAAkgAAAAMBAwABAAAAAQAAAAYBAwABAAAAAgAAABEBBAAB" +
            "AAAAmgAAABUBAwABAAAABAAAABYBBAABAAAAAwAAABcBBAABAAAAMAAAABwBAwABAAAAAQAAAFIBAwABAAAAAgAAANgAAAAIAAgA" +
            "CAAIAAAAyP88AMj/eADI/7QAyIAAUMj/PFDI/3hQyP+0UMiAAKDI/zygyP94oMj/tKDIgAAAAAAAAElJKgAIAAAACgAAAQQAAQAA" +
            "AAUAAAABAQQAAQAAAAIAAAACAQMAAwAAAFYBAAADAQMAAQAAAAEAAAAGAQMAAQAAAAIAAAARAQQAAQAAAFwBAAAVAQMAAQAAAAMA" +
            "AAAWAQQAAQAAAAIAAAAXAQQAAQAAAB4AAAAcAQMAAQAAAAEAAAAAAAAACAAIAAgAChQeCjweCmQeCoweCrQeChSCCjyCCmSCCoyC" +
            "CrSCAAAAAAAA"
        )
        private val tiffLzw = (
            "SUkqACwAAACAAAAMh/jyBv88QdaQNAAAoQQeQ+ERJaQ+GqCIRiERpaRhAQELAAABAwABAAAABAAAAAEBAwABAAAAAwAAAAIBAwAE" +
            "AAAAtgAAAAMBAwABAAAABQAAAAYBAwABAAAAAgAAABEBBAABAAAACAAAABUBAwABAAAABAAAABYBAwABAAAAAwAAABcBBAABAAAA" +
            "JAAAABwBAwABAAAAAQAAAFIBAwABAAAAAgAAAOQAAAAIAAgACAAIAAAASUkqACQAAACAAoKB4FDyCGSCIyCLSCBRBQWHmSHoyHrR" +
            "BQEACgAAAQMAAQAAAAUAAAABAQMAAQAAAAIAAAACAQMAAwAAAGIBAAADAQMAAQAAAAUAAAAGAQMAAQAAAAIAAAARAQQAAQAAAMgA" +
            "AAAVAQMAAQAAAAMAAAAWAQMAAQAAAAIAAAAXAQQAAQAAABsAAAAcAQMAAQAAAAEAAAAAAAAACAAIAAgAAAAAAAAAAAA="
        )
        private val tiffPackbits = (
            "SUkqADwAAAD/AA3I/zwAyP94AMj/tADIgA8AUMj/PFDI/3hQyP+0UMiADwCgyP88oMj/eKDI/7SgyIAACwAAAQMAAQAAAAQAAAAB" +
            "AQMAAQAAAAMAAAACAQMABAAAAMYAAAADAQMAAQAAAAWAAAAGAQMAAQAAAAIAAAARAQQAAQAAAAgAAAAVAQMAAQAAAAQAAAAWAQMA" +
            "AQAAAAMAAAAXAQQAAQAAADMAAAAcAQMAAQAAAAEAAABSAQMAAQAAAAIAAAAAAAAACAAIAAgACAA="
        )
        private val tiffDeflate = (
            "SUkqADgAAAB4nAXBQQEAMAjEsBOGCOTwRAoiJqKuuiTBCk7whU1jNU7jazaHdTiH79gPcE0b0AALAAABAwABAAAABAAAAAEBAwAB" +
            "AAAAAwAAAAIBAwAEAAAAwgAAAAMBAwABAAAACAAAAAYBAwABAAAAAgAAABEBBAABAAAACAAAABUBAwABAAAABAAAABYBAwABAAAA" +
            "AwAAABcBBAABAAAALwAAABwBAwABAAAAAQAAAFIBAwABAAAAAgAAAAAAAAAIAAgACAAIAA=="
        )
        private val ico = (
            "AAABAAIAEBAAAAAAIABeAAAAJgAAACAgAAAAACAAbAAAAIQAAACJUE5HDQoaCgAAAA1JSERSAAAAEAAAABAIBgAAAB/z/2EAAAAl" +
            "SURBVHicY/zPwPCfgQLARInmUQMggImBQsA0agDDaBgwUB4GAFhtAh7mU7z8AAAAAElFTkSuQmCCiVBORw0KGgoAAAANSUhEUgAA" +
            "ACAAAAAgCAYAAABzenr0AAAAM0lEQVR4nO3QQQ0AAAgDsYF/zxBU8OkM3NKaZPK4/ow7QIAAAQIECBAgQIAAAQIECJzAAjDjAj7x" +
            "vfnCAAAAAElFTkSuQmCC"
        )
    }
}
