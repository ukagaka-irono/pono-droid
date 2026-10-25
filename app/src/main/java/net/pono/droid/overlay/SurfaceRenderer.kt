package net.pono.droid.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import net.pono.baseware.shell.PatternFrame
import net.pono.baseware.shell.Shell
import net.pono.baseware.shell.SortOrder
import java.io.File

/**
 * シェルの画像を読み込み、サーフェス（element と SERIKO のレイヤーを重ねたもの）を合成する。
 * 画像の透過は伺かの流儀に従う: use_self_alpha なら PNG のアルファ、そうでなければ .pna か左上の色。
 * 重ね方（描画メソッド）は SSP と同じものを使える。blend-* は [Blend] で1画素ずつ計算する
 */
class SurfaceRenderer(private val shell: Shell) {
    private val images = HashMap<File, Bitmap?>()
    private val opaqueImages = HashMap<File, Bitmap?>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private fun mode(m: PorterDuff.Mode) = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(m) }
    private val replacePaint = mode(PorterDuff.Mode.SRC)
    private val atopPaint = mode(PorterDuff.Mode.SRC_ATOP)
    private val behindPaint = mode(PorterDuff.Mode.DST_OVER)
    private val reducePaint = mode(PorterDuff.Mode.DST_IN)

    /** @param layers アニメーション ID ごとの、いま表示しているコマ */
    @Synchronized
    fun render(surfaceId: Int, layers: Map<Int, PatternFrame>): Bitmap? {
        if (surfaceId < 0) return null
        val def = shell.surfaces[surfaceId]
        // アニメーション番号の順に重ねる（animation-sort が descend なら大きい番号が手前、ascend なら小さい番号が手前）。
        // 着せ替えのコマはもとのアニメーションの番号の位置に、\![anim,add] のものはいちばん上に
        val descend = def?.animationSort != SortOrder.ASCEND
        val sorted = layers.entries.sortedWith(
            compareBy<Map.Entry<Int, PatternFrame>>(
                { if (it.key >= ADD_LAYER_BASE) 1 else 0 },
                { (it.value.layerOf ?: it.key).let { id -> if (descend) id else -id } },
                { it.key },
            ),
        ).associate { it.key to it.value }
        // "base" のコマは土台のサーフェスを差し替える
        val baseOverride = sorted.values.lastOrNull { it.method == "base" && it.surface >= 0 }
        val base = baseImage(baseOverride?.surface ?: surfaceId) ?: return null
        val animations = def?.animations.orEmpty()
        // option,background のアニメーションは土台の裏に重ねる
        val (back, front) = sorted.entries.partition { (id, _) -> animations[id]?.background == true }
        // move のコマは、土台ごと絵をずらす
        val moves = sorted.values.filter { it.method == "move" }
        val dx = moves.sumOf { it.x }.toFloat()
        val dy = moves.sumOf { it.y }.toFloat()
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(dx, dy)
        for ((_, frame) in back) draw(canvas, out, frame, dx, dy)
        canvas.drawBitmap(base, 0f, 0f, paint)
        for ((_, frame) in front) {
            if (frame === baseOverride) continue
            draw(canvas, out, frame, dx, dy)
        }
        return out
    }

    /** 1コマを重ねる。[dx] / [dy] は move でずらしている分（1画素ずつ計算するときに使う） */
    private fun draw(canvas: Canvas, out: Bitmap, frame: PatternFrame, dx: Float, dy: Float) {
        if (frame.surface < 0 || frame.method in NOT_DRAWN) return
        val method = frame.method
        val x = frame.x.toFloat()
        val y = frame.y.toFloat()
        if (method == "asis") {
            // 透過を無視して重ねる
            opaqueImage(frame.surface)?.let { canvas.drawBitmap(it, x, y, paint) }
            return
        }
        // --alpha は、絵の不透明度を下げてから重ねる
        val img = baseImage(frame.surface)?.let { if (frame.alpha < 255) withAlpha(it, frame.alpha) else it } ?: return
        when {
            method == "replace" -> canvas.drawBitmap(img, x, y, replacePaint)
            method == "overlay-fast" || method == "overlayfast" -> canvas.drawBitmap(img, x, y, atopPaint)
            method == "interpolate" -> canvas.drawBitmap(img, x, y, behindPaint)
            method == "reduce" -> canvas.drawBitmap(img, x, y, reducePaint)
            method == "overlaymultiply" -> Blend.apply(out, img, (x + dx).toInt(), (y + dy).toInt(), "multiply", fast = true)
            // overlayscreen は blend-screen-fast と同じ
            method == "overlayscreen" -> Blend.apply(out, img, (x + dx).toInt(), (y + dy).toInt(), "screen", fast = true)
            method.startsWith("blend-") -> {
                val fast = method.endsWith("-fast")
                val name = method.removePrefix("blend-").removeSuffix("-fast").removeSuffix("-glow")
                Blend.apply(out, img, (x + dx).toInt(), (y + dy).toInt(), name, fast)
            }
            // overlay / add / bind / auto など
            else -> canvas.drawBitmap(img, x, y, paint)
        }
    }

    /** [b] の不透明度を [alpha]（0〜255）倍にしたもの */
    private fun withAlpha(b: Bitmap, alpha: Int): Bitmap {
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint().apply { this.alpha = alpha })
        return out
    }

    /** element を重ねた、アニメーションなしのサーフェス。 */
    private val bases = HashMap<Int, Bitmap?>()

    private fun baseImage(id: Int): Bitmap? = bases.getOrPut(id) {
        val elements = shell.surfaces[id]?.elements.orEmpty()
        // element0 があれば surfaceN.png は使わない（SSP と同じ。element0 で置き換える）
        val png = if (0 in elements) null else shell.surfaceImage(id)?.let(::load)
        if (elements.isEmpty()) return@getOrPut png
        val parts = elements.values.mapNotNull { e -> shell.file(e.file)?.let(::load)?.let { e to it } }
        val width = maxOf(png?.width?.toLong() ?: 0L, parts.maxOfOrNull { (e, b) -> e.x.toLong() + b.width } ?: 0L)
        val height = maxOf(png?.height?.toLong() ?: 0L, parts.maxOfOrNull { (e, b) -> e.y.toLong() + b.height } ?: 0L)
        if (width <= 0 || height <= 0) return@getOrPut png
        // surfaces.txt の座標は信用しない（巨大な値で画像を作らせない）
        if (width > ImageTransparency.MAX_SIDE || height > ImageTransparency.MAX_SIDE) {
            Log.w("pono", "surface $id is too large to compose: ${width}x$height")
            return@getOrPut png
        }
        val out = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        png?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        for ((i, pair) in parts.withIndex()) {
            val (e, b) = pair
            val x = e.x.toFloat()
            val y = e.y.toFloat()
            // base は element0 にしか使えない（それ以外は overlay）
            val method = if (e.method == "base" && i > 0) "overlay" else e.method
            when {
                method == "replace" -> canvas.drawBitmap(b, x, y, replacePaint)
                method == "overlay-fast" || method == "overlayfast" -> canvas.drawBitmap(b, x, y, atopPaint)
                method == "interpolate" -> canvas.drawBitmap(b, x, y, behindPaint)
                method == "reduce" -> canvas.drawBitmap(b, x, y, reducePaint)
                method == "asis" -> canvas.drawBitmap(opaque(b), x, y, paint)
                method == "overlaymultiply" -> Blend.apply(out, b, e.x, e.y, "multiply", fast = true)
                method == "overlayscreen" -> Blend.apply(out, b, e.x, e.y, "screen", fast = true)
                method.startsWith("blend-") -> Blend.apply(
                    out, b, e.x, e.y, method.removePrefix("blend-").removeSuffix("-fast").removeSuffix("-glow"), method.endsWith("-fast"),
                )
                else -> canvas.drawBitmap(b, x, y, paint)
            }
        }
        out
    }

    /** asis で重ねる、透過をやめたサーフェス */
    private fun opaqueImage(id: Int): Bitmap? {
        val file = shell.surfaceImage(id) ?: return baseImage(id)?.let(::opaque)
        return opaqueImages.getOrPut(file) { load(file)?.let(::opaque) }
    }

    private fun opaque(b: Bitmap): Bitmap {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (i in px.indices) px[i] = px[i] or 0xFF000000.toInt()
        return Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    /** シェルは seriko.use_self_alpha で決め打ち（0 なら PNG にアルファがあっても .pna か左上の色で抜く） */
    private fun load(file: File): Bitmap? = images.getOrPut(file) {
        ImageTransparency.load(file, shell.pnaFor(file), selfAlpha = shell.useSelfAlpha)
    }

    private companion object {
        /** \![anim,add] のレイヤーの番号（SerikoAnimator と同じ）。これより上はいちばん上に重ねる */
        const val ADD_LAYER_BASE = 1_000_000

        /** 絵は重ねないメソッド（move はずらすだけ、scaling はウィンドウの大きさを変えるだけ、import は別に流す） */
        val NOT_DRAWN = setOf("move", "scaling", "base", "import", "start", "stop", "alternativestart", "alternativestop", "parallelstart", "parallelstop", "insert")
    }
}
