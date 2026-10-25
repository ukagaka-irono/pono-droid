package net.pono.baseware.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** element のオプション（--clipping / --alpha / --scaling / --source）、collisionex の region、動く画像の見分け */
class SurfacePartsTest {
    @Test
    fun elementOptions() {
        val def = SurfaceTable.parse(
            """
            surface0 {
            element0,overlay,face.png,0,0,--clipping,10,10,50,50,--alpha,128
            element1,auto,body.psd,5,6,--source,顔::頭,--scaling,50%,0
            element2,overlay,x.png,0,0,--clipping,10,10,5,5
            }
            """.trimIndent(),
        )[0]!!
        val e0 = def.elements[0]!!
        assertEquals(SurfaceRect(10, 10, 50, 50), e0.clip)
        assertEquals(128, e0.alpha)
        val e1 = def.elements[1]!!
        assertEquals("顔::頭", e1.source)
        assertEquals(ElementScaling.Size(50f, true, 0f, false), e1.scaling)
        // 逆さまの矩形は使わない
        assertNull(def.elements[2]!!.clip)
    }

    @Test
    fun scalingSizes() {
        fun s(a: String, b: String, w: Int, h: Int) = ElementScaling.parse(a, b)!!.resize(w, h)
        assertEquals(150 to 100, s("50%", "50%", 300, 200))
        assertEquals(150 to 100, s("150", "0", 300, 200))
        assertEquals(-150 to 100, s("-150px", "100", 300, 200))
        assertEquals(300 to 225, s("300", "long", 400, 300))
        assertEquals(300 to 225, s("300", "longmax", 400, 300))
        assertEquals(200 to 150, s("300", "longmax", 200, 150))
        assertEquals(400 to 300, s("300", "shortmin", 400, 300))
        // max だけなら long の max
        assertEquals(300 to 225, s("300", "max", 400, 300))
        assertEquals(-300 to -225, s("-300", "long", 400, 300))
        assertEquals(ElementScaling.MAX to 1, s("100000", "1", 10, 10))
    }

    @Test
    fun regionCollisions() {
        val def = SurfaceTable.parse(
            """
            surface0 {
            collisionex0,Head,region,atari.png,255,0,0
            collisionex1,Body,region,atari.png,255,0,0,true
            }
            """.trimIndent(),
        )[0]!!
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        // 2x1 の画像。左が赤、右が青
        val masks = { r: CollisionRegion -> RegionMask.of(2, 1, intArrayOf(red, blue), r) }
        assertEquals(CollisionShape.REGION, def.collisions[0]!!.shape)
        assertEquals("Head", def.hitTest(0, 0, masks = masks))
        assertEquals("Body", def.hitTest(1, 0, masks = masks))
        assertNull(def.hitTest(5, 5, masks = masks))
        // 画像が読めないうちは当たらない
        assertNull(def.hitTest(0, 0))
    }

    @Test
    fun animatedImages() {
        fun png(vararg chunks: Pair<String, ByteArray>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            for ((type, data) in chunks) {
                out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array())
                out.write(type.toByteArray())
                out.write(data)
                out.write(ByteArray(4))
            }
            return out.toByteArray()
        }
        val ihdr = "IHDR" to ByteArray(13)
        assertTrue(AnimatedImages.isAnimated(png(ihdr, "acTL" to byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0), "IDAT" to ByteArray(2))))
        assertFalse(AnimatedImages.isAnimated(png(ihdr, "acTL" to byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0), "IDAT" to ByteArray(2))))
        assertFalse(AnimatedImages.isAnimated(png(ihdr, "IDAT" to ByteArray(2))))
        // GIF: 色表なしで、コマが2つ
        val frame = byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 1, 0, 0)
        val gif = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0, 0, 0)
        assertTrue(AnimatedImages.isAnimated(gif + frame + frame + byteArrayOf(0x3B)))
        assertFalse(AnimatedImages.isAnimated(gif + frame + byteArrayOf(0x3B)))
        // WebP: VP8X のアニメーションの旗
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0, 0x02) + ByteArray(9)
        assertTrue(AnimatedImages.isAnimated(webp))
        assertFalse(AnimatedImages.isAnimated("not an image".toByteArray()))
    }
}
