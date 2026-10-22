package net.pono.droid.overlay

import android.graphics.Bitmap
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
        // レイヤーの番号の小さい順に重ねる（着せ替えは負の番号で、普通のアニメーションより下）
        val ordered = layers.toSortedMap().values
        // "base" のコマは土台のサーフェスを差し替える
        val baseOverride = ordered.lastOrNull { it.method == "base" && it.surface >= 0 }
        val base = baseImage(baseOverride?.surface ?: surfaceId) ?: return null
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for (frame in ordered) {
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

    /** シェルは seriko.use_self_alpha で決め打ち（0 なら PNG にアルファがあっても .pna か左上の色で抜く） */
    private fun load(file: File): Bitmap? = images.getOrPut(file) {
        ImageTransparency.load(file, shell.pnaFor(file), selfAlpha = shell.useSelfAlpha)
    }
}
