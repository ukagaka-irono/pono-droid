package net.pono.droid.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import net.pono.baseware.shell.PatternFrame
import net.pono.baseware.shell.Shell
import java.io.File

/**
 * シェルの画像を読み込み、サーフェス（element と SERIKO のレイヤーを重ねたもの）を合成する。
 * 画像の透過は伺かの流儀に従う: use_self_alpha なら PNG のアルファ、そうでなければ .pna か左上の色。
 */
class SurfaceRenderer(private val shell: Shell) {
    private val images = HashMap<File, Bitmap?>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val replacePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /** @param layers アニメーション ID ごとの、いま表示しているコマ */
    @Synchronized
    fun render(surfaceId: Int, layers: Map<Int, PatternFrame>): Bitmap? {
        if (surfaceId < 0) return null
        // "base" のコマは土台のサーフェスを差し替える
        val baseOverride = layers.values.lastOrNull { it.method == "base" && it.surface >= 0 }
        val base = baseImage(baseOverride?.surface ?: surfaceId) ?: return null
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for (frame in layers.values) {
            if (frame.surface < 0 || frame === baseOverride) continue
            val img = baseImage(frame.surface) ?: continue
            val p = if (frame.method == "replace") replacePaint else paint
            canvas.drawBitmap(img, frame.x.toFloat(), frame.y.toFloat(), p)
        }
        return out
    }

    /** element を重ねた、アニメーションなしのサーフェス。 */
    private val bases = HashMap<Int, Bitmap?>()

    private fun baseImage(id: Int): Bitmap? = bases.getOrPut(id) {
        val png = shell.surfaceImage(id)?.let(::load)
        val elements = shell.surfaces[id]?.elements?.values.orEmpty()
        if (elements.isEmpty()) return@getOrPut png
        val parts = elements.mapNotNull { e -> shell.file(e.file)?.let(::load)?.let { e to it } }
        val width = maxOf(png?.width ?: 0, parts.maxOfOrNull { (e, b) -> e.x + b.width } ?: 0)
        val height = maxOf(png?.height ?: 0, parts.maxOfOrNull { (e, b) -> e.y + b.height } ?: 0)
        if (width <= 0 || height <= 0) return@getOrPut png
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        png?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        for ((e, b) in parts) {
            canvas.drawBitmap(b, e.x.toFloat(), e.y.toFloat(), if (e.method == "replace") replacePaint else paint)
        }
        out
    }

    private fun load(file: File): Bitmap? = images.getOrPut(file) {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return@getOrPut null
        if (!shell.useSelfAlpha) {
            val pna = shell.pnaFor(file)?.let { BitmapFactory.decodeFile(it.path) }
            if (pna != null && pna.width == bmp.width && pna.height == bmp.height) applyPna(bmp, pna) else applyColorKey(bmp)
        }
        bmp
    }

    private fun applyColorKey(bmp: Bitmap) {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val key = px[0] or 0xFF000000.toInt()
        for (i in px.indices) {
            if ((px[i] or 0xFF000000.toInt()) == key) px[i] = 0
        }
        bmp.setHasAlpha(true)
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }

    /** .pna はグレースケールで、明るさがそのままアルファになる。 */
    private fun applyPna(bmp: Bitmap, pna: Bitmap) {
        val px = IntArray(bmp.width * bmp.height)
        val alpha = IntArray(px.size)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        pna.getPixels(alpha, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) {
            val a = alpha[i] and 0xFF
            px[i] = (px[i] and 0x00FFFFFF) or (a shl 24)
        }
        bmp.setHasAlpha(true)
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }
}
