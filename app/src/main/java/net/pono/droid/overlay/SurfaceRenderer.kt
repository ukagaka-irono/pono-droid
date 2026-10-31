package net.pono.droid.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Log
import net.pono.baseware.shell.AnimatedImages
import net.pono.baseware.shell.CollisionRegion
import net.pono.baseware.shell.PatternFrame
import net.pono.baseware.shell.RegionMask
import net.pono.baseware.shell.SelfAlpha
import net.pono.baseware.shell.Shell
import net.pono.baseware.shell.SortOrder
import net.pono.baseware.shell.SurfaceElement
import java.io.File

/**
 * シェルの画像を読み込み、サーフェス（element と SERIKO のレイヤーを重ねたもの）を合成する。
 * 画像の透過は伺かの流儀に従う: use_self_alpha なら PNG のアルファ、そうでなければ .pna か左上の色。
 * 重ね方（描画メソッド）は SSP と同じものを使える。blend-* は [Blend] で1画素ずつ計算する
 */
class SurfaceRenderer(private val shell: Shell) {
    // 読んだ絵は使った順に覚え、合計の大きさで古いものから捨てる（dumpsurface で全サーフェスを読んでも残り続けない）
    private val images = BitmapCache<File>(MAX_IMAGE_BYTES)
    private val opaqueImages = BitmapCache<File>(MAX_OPAQUE_BYTES)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private fun mode(m: PorterDuff.Mode) = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(m) }
    private val replacePaint = mode(PorterDuff.Mode.SRC)
    private val atopPaint = mode(PorterDuff.Mode.SRC_ATOP)
    private val behindPaint = mode(PorterDuff.Mode.DST_OVER)
    private val reducePaint = mode(PorterDuff.Mode.DST_IN)

    /**
     * @param layers アニメーション ID ごとの、いま表示しているコマ
     * @param binds 身に着けている着せ替え（コマに使ったサーフェスの着せ替えも重ねる。多重着せ替え）
     * @param live 動く画像（GIF / APNG / WebP）のいまのコマ（rememberLiveFrames）。surface*.png・element・import のコマの
     *   その位置に重ねる。無い画像は最初のコマを重ねる
     */
    @Synchronized
    fun render(surfaceId: Int, layers: Map<Int, PatternFrame>, binds: Set<Int> = emptySet(), live: Map<File, Bitmap> = emptyMap()): Bitmap? {
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
        val baseId = baseOverride?.surface ?: surfaceId
        // 動く画像のコマがあれば、その都度重ね直す（覚えておくのは止まっている絵だけ）
        val base = (if (live.isNotEmpty() && animatedParts(baseId).any { it.file in live }) composeBase(baseId, live) else baseImage(baseId))
            ?: return null
        val animations = def?.animations.orEmpty()
        // option,background のアニメーションは土台の裏に重ねる
        val (back, front) = sorted.entries.partition { (id, f) -> animations[f.layerOf ?: id]?.background == true }
        // move のコマは、土台ごと絵をずらす
        val moves = sorted.values.filter { it.method == "move" }
        val dx = moves.sumOf { it.x }.toFloat()
        val dy = moves.sumOf { it.y }.toFloat()
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.translate(dx, dy)
        for ((_, frame) in back) draw(canvas, out, frame, dx, dy, binds, live = live)
        canvas.drawBitmap(base, 0f, 0f, paint)
        for ((_, frame) in front) {
            if (frame === baseOverride) continue
            draw(canvas, out, frame, dx, dy, binds, live = live)
        }
        return out
    }

    /** コマに使うサーフェス [id] の絵。そのサーフェスの着せ替えのうち身に着けているものも重ねる（循環は [visiting] で止める） */
    private fun partImage(id: Int, binds: Set<Int>, visiting: Set<Int> = emptySet()): Bitmap? {
        val base = baseImage(id) ?: return null
        if (binds.isEmpty() || id in visiting) return base
        val frames = shell.surfaces[id]?.bindFrames(binds).orEmpty()
        if (frames.isEmpty()) return base
        val key = id to binds
        parts[key]?.let { return it }
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        for (f in frames) draw(canvas, out, f, 0f, 0f, binds, visiting + id)
        if (parts.size >= MAX_PARTS) parts.clear()
        parts[key] = out
        return out
    }

    private val parts = HashMap<Pair<Int, Set<Int>>, Bitmap>()

    /** 1コマを重ねる。[dx] / [dy] は move でずらしている分（1画素ずつ計算するときに使う） */
    private fun draw(
        canvas: Canvas, out: Bitmap, frame: PatternFrame, dx: Float, dy: Float, binds: Set<Int>, visiting: Set<Int> = emptySet(),
        live: Map<File, Bitmap> = emptyMap(),
    ) {
        // import のコマは動く画像（いまのコマ、まだ無ければ最初のコマ）を、ほかのコマと同じ重なりの位置に
        if (frame.method == "import") {
            val file = frame.file?.let(shell::file) ?: return
            (live[file] ?: importImage(file))?.let { canvas.drawBitmap(it, frame.x.toFloat(), frame.y.toFloat(), paint) }
            return
        }
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
        val img = partImage(frame.surface, binds, visiting)?.let { if (frame.alpha < 255) withAlpha(it, frame.alpha) else it } ?: return
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
                val name = method.removePrefix("blend-").removeSuffix("-fast")
                Blend.apply(out, img, (x + dx).toInt(), (y + dy).toInt(), name.removeSuffix("-glow"), fast, glow = name.endsWith("-glow"))
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
    private val bases = BitmapCache<Int>(MAX_BASE_BYTES)

    private fun baseImage(id: Int): Bitmap? = bases.getOrPut(id) { composeBase(id, emptyMap()) }

    /** surfaceN.png と element を重ねた土台。動く画像は [live] のいまのコマ（無ければ最初のコマ） */
    private fun composeBase(id: Int, live: Map<File, Bitmap>): Bitmap? {
        val elements = shell.surfaces[id]?.elements.orEmpty()
        // element0 があれば surfaceN.png は使わない（SSP と同じ。element0 で置き換える）
        val png = if (0 in elements) null else shell.surfaceImage(id)?.let { live[it] ?: load(it) }
        if (elements.isEmpty()) return png
        val parts = elements.values.mapNotNull { e -> shell.file(e.file)?.let { elementPart(e, it, live) } }
        val width = maxOf(png?.width?.toLong() ?: 0L, parts.maxOfOrNull { it.x.toLong() + it.image.width } ?: 0L)
        val height = maxOf(png?.height?.toLong() ?: 0L, parts.maxOfOrNull { it.y.toLong() + it.image.height } ?: 0L)
        if (width <= 0 || height <= 0) return png
        // surfaces.txt の座標は信用しない（巨大な値で画像を作らせない）。読む画像と同じく、1辺と画素数の両方に上限
        if (width > ImageTransparency.MAX_SIDE || height > ImageTransparency.MAX_SIDE || width * height > ImageTransparency.MAX_PIXELS) {
            Log.w("pono", "surface $id is too large to compose: ${width}x$height")
            return png
        }
        val out = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        png?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
        for ((i, part) in parts.withIndex()) {
            val b = part.image
            val x = part.x.toFloat()
            val y = part.y.toFloat()
            // base は element0 にしか使えない（それ以外は overlay）
            val method = if (part.method == "base" && i > 0) "overlay" else part.method
            when {
                method == "replace" -> canvas.drawBitmap(b, x, y, replacePaint)
                method == "overlay-fast" || method == "overlayfast" -> canvas.drawBitmap(b, x, y, atopPaint)
                method == "interpolate" -> canvas.drawBitmap(b, x, y, behindPaint)
                method == "reduce" -> canvas.drawBitmap(b, x, y, reducePaint)
                method == "asis" -> canvas.drawBitmap(opaque(b), x, y, paint)
                method == "overlaymultiply" -> Blend.apply(out, b, part.x, part.y, "multiply", fast = true)
                method == "overlayscreen" -> Blend.apply(out, b, part.x, part.y, "screen", fast = true)
                method.startsWith("blend-") -> method.removePrefix("blend-").removeSuffix("-fast").let { name ->
                    Blend.apply(out, b, part.x, part.y, name.removeSuffix("-glow"), method.endsWith("-fast"), glow = name.endsWith("-glow"))
                }
                else -> canvas.drawBitmap(b, x, y, paint)
            }
        }
        return out
    }

    // import の動く画像の最初のコマ（Android 9 より前と、まだコマが来ていないとき）。画像のアルファを使う
    private val importImages = BitmapCache<File>(MAX_IMAGE_BYTES)

    private fun importImage(file: File): Bitmap? =
        importImages.getOrPut(file) { ImageTransparency.load(file, null, selfAlpha = true, colorKey = false) }

    /** element の絵と、重ねる位置・描画メソッド */
    private class ElementPart(val image: Bitmap, val x: Int, val y: Int, val method: String)

    /**
     * element の絵。PSD・TIFF・ICO・EXE / DLL は --source の 1 枚（PSD のレイヤーはその位置にずらし、--alpha が無ければ
     * レイヤーの不透明度、auto ならレイヤーの描画モード）。ほかは [live] のいまのコマか、読んだ絵。--clipping / --scaling を
     * 付けた動く画像は動かさない（animatedParts と同じ）
     */
    private fun elementPart(e: SurfaceElement, file: File, live: Map<File, Bitmap>): ElementPart? {
        if (e.source != null || isMultiSource(file)) {
            val (bmp, picture) = sourceImage(file, e.source) ?: return null
            val px = picture as? net.pono.baseware.shell.SourceImages.Picture.Pixels
            val alpha = if (e.alphaGiven) e.alpha else px?.opacity ?: 255
            val method = if (e.method == "auto") px?.method ?: "overlay" else e.method
            return ElementPart(elementImage(e, bmp, alpha), e.x + (px?.left ?: 0), e.y + (px?.top ?: 0), method)
        }
        val src = (if (e.clip == null && e.scaling == null) live[file] else null) ?: load(file) ?: return null
        return ElementPart(elementImage(e, src, e.alpha), e.x, e.y, e.method)
    }

    private val multiSource = HashMap<File, Boolean>()

    private fun isMultiSource(file: File): Boolean = multiSource.getOrPut(file) { net.pono.baseware.shell.SourceImages.isMultiSource(file) }

    private val sources = HashMap<Pair<File, String?>, Pair<Bitmap, net.pono.baseware.shell.SourceImages.Picture>?>()

    private fun sourceImage(file: File, source: String?) = sources.getOrPut(file to source) {
        if (sources.size >= MAX_PARTS) sources.clear()
        ImageTransparency.loadSource(file, source)
    }

    /** element の --clipping（切り抜き）・--scaling（拡大縮小と裏返し）・[alpha]（不透明度）を、この順に当てた絵 */
    private fun elementImage(e: SurfaceElement, src: Bitmap, alpha: Int): Bitmap {
        var b = src
        e.clip?.let { c ->
            val l = c.left.coerceIn(0, b.width)
            val t = c.top.coerceIn(0, b.height)
            val r = c.right.coerceIn(l, b.width)
            val bt = c.bottom.coerceIn(t, b.height)
            if (r > l && bt > t) b = Bitmap.createBitmap(b, l, t, r - l, bt - t)
        }
        e.scaling?.let { s ->
            val (w, h) = s.resize(b.width, b.height)
            if (kotlin.math.abs(w) != b.width || kotlin.math.abs(h) != b.height || w < 0 || h < 0) {
                val m = Matrix().apply { setScale(w.toFloat() / b.width, h.toFloat() / b.height) }
                b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            }
        }
        if (alpha < 255) b = withAlpha(b, alpha)
        return b
    }

    /** 動く画像の、シェル画像の中の位置 */
    data class AnimatedPart(val file: File, val x: Int, val y: Int)

    private val animated = HashMap<Int, List<AnimatedPart>>()

    /** サーフェス [id] の surface*.png と element のうち、動く画像（--clipping / --scaling を付けたものは動かさない） */
    @Synchronized
    fun animatedParts(id: Int): List<AnimatedPart> = animated.getOrPut(id) {
        val elements = shell.surfaces[id]?.elements.orEmpty()
        buildList {
            if (0 !in elements) shell.surfaceImage(id)?.takeIf(AnimatedImages::isAnimated)?.let { add(AnimatedPart(it, 0, 0)) }
            for (e in elements.values) {
                if (e.clip != null || e.scaling != null || e.source != null) continue
                shell.file(e.file)?.takeIf(AnimatedImages::isAnimated)?.let { add(AnimatedPart(it, e.x, e.y)) }
            }
        }
    }

    private val masks = HashMap<CollisionRegion, RegionMask?>()

    /** collisionex の region の画像の地図。読めなければ null（当たらない） */
    @Synchronized
    fun regionMask(region: CollisionRegion): RegionMask? = masks.getOrPut(region) {
        val file = shell.file(region.file) ?: return@getOrPut null
        // 細工された巨大な画像は読まない（大きさだけ先に見る）
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..ImageTransparency.MAX_SIDE || bounds.outHeight !in 1..ImageTransparency.MAX_SIDE) return@getOrPut null
        if (bounds.outWidth.toLong() * bounds.outHeight > ImageTransparency.MAX_PIXELS) return@getOrPut null
        val b = runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull() ?: return@getOrPut null
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        RegionMask.of(b.width, b.height, px, region)
    }

    /** asis で重ねる、透過をやめたサーフェス（左上の色や .pna で抜く前の、元の色のまま） */
    private fun opaqueImage(id: Int): Bitmap? {
        val file = shell.surfaceImage(id) ?: return baseImage(id)?.let(::opaque)
        return opaqueImages.getOrPut(file) { ImageTransparency.opaque(file) }
    }

    private fun opaque(b: Bitmap): Bitmap {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        for (i in px.indices) px[i] = px[i] or 0xFF000000.toInt()
        return Bitmap.createBitmap(px, b.width, b.height, Bitmap.Config.ARGB_8888)
    }

    /**
     * シェルは seriko.use_self_alpha で決め打ち（0 なら PNG にアルファがあっても .pna か左上の色で抜く。
     * 1 / true はアルファが無ければ .pna か左上の色、full はアルファが無ければ .pna か不透明）
     */
    private fun load(file: File): Bitmap? = images.getOrPut(file) {
        val mode = shell.selfAlpha
        ImageTransparency.load(
            file, shell.pnaFor(file), selfAlpha = mode != SelfAlpha.OFF, colorKey = mode != SelfAlpha.FULL, paintBlack = shell.paintTransparentBlack,
        )
    }

    private companion object {
        /** \![anim,add] のレイヤーの番号（SerikoAnimator と同じ）。これより上はいちばん上に重ねる */
        const val ADD_LAYER_BASE = 1_000_000

        /** 多重着せ替えで作った絵を覚えておく数 */
        const val MAX_PARTS = 64

        /** 覚えておく絵の大きさの合計（バイト） */
        const val MAX_IMAGE_BYTES = 96L * 1024 * 1024
        const val MAX_OPAQUE_BYTES = 32L * 1024 * 1024
        const val MAX_BASE_BYTES = 64L * 1024 * 1024

        /** 絵は重ねないメソッド（move はずらすだけ、scaling はウィンドウの大きさを変えるだけ。import は draw が動く画像を重ねる） */
        val NOT_DRAWN = setOf("move", "scaling", "base", "import", "start", "stop", "alternativestart", "alternativestop", "parallelstart", "parallelstop", "insert")
    }
}

/** 使った順に覚え、絵の大きさの合計が [maxBytes] を超えたら古いものから捨てる（読めなかった null も覚える） */
private class BitmapCache<K>(private val maxBytes: Long) {
    private val map = LinkedHashMap<K, Bitmap?>(16, 0.75f, true)
    private var bytes = 0L

    fun getOrPut(key: K, make: () -> Bitmap?): Bitmap? {
        if (map.containsKey(key)) return map[key]
        val value = make()
        map[key] = value
        bytes += value?.allocationByteCount?.toLong() ?: 0L
        val it = map.entries.iterator()
        while (bytes > maxBytes && map.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= e.value?.allocationByteCount?.toLong() ?: 0L
            it.remove()
        }
        return value
    }
}
