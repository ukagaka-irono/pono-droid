package net.pono.droid

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import net.pono.baseware.ghost.HanayuGraph
import java.lang.ref.WeakReference
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pono.baseware.ghost.AiGraph
import net.pono.baseware.ghost.AiGraphLogo
import net.pono.droid.overlay.ImageTransparency
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * AI グラフ（getaistate / getaistateex。メニューの「AI グラフ」と \![open,aigraph]）。SSP と同じく 288×288 の面にレーダーチャートを描き、
 * descript.txt の shiori.logo.file の画像を shiori.logo.align / x / y の位置に敷く（画像の1ピクセルを 1dp に）。
 * 互換モジュールの hanayu（花柚）のグラフ（radar はレーダーチャート、usetime / bar は棒、line は折れ線）もこの画面で出す
 */
class AiGraphActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graphs = readGraphs(intent)
        val logos = intent.getStringArrayExtra(EXTRA_LOGOS).orEmpty().map { it.takeIf(String::isNotEmpty)?.let(::File) }
        val align = intent.getStringExtra(EXTRA_ALIGN) ?: "lefttop"
        val logoX = intent.getIntExtra(EXTRA_X, 0)
        val logoY = intent.getIntExtra(EXTRA_Y, 0)
        val hanayu = readHanayu(intent)
        if (hanayu != null) {
            shownHanayu?.get()?.takeIf { it !== this }?.finish()
            shownHanayu = WeakReference(this)
        }
        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(hanayu?.title?.takeIf { it.isNotEmpty() } ?: "AI グラフ", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            actions = { TextButton(onClick = { finish() }) { Text("閉じる") } },
                        )
                    },
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (hanayu != null) {
                            HanayuChart(hanayu)
                        } else if (graphs.isEmpty()) {
                            Text("AI グラフがありません", style = MaterialTheme.typography.bodyMedium)
                        }
                        graphs.forEachIndexed { i, graph ->
                            // グラフが何枚もあるときだけ見出しを付ける
                            if (graphs.size > 1) {
                                Text("グラフ ${i + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                            }
                            RadarChart(graph, logos.getOrNull(i), align, logoX, logoY)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (shownHanayu?.get() === this) shownHanayu = null
        super.onDestroy()
    }

    companion object {
        /** 出している hanayu のグラフの画面（hide で閉じる） */
        private var shownHanayu: WeakReference<AiGraphActivity>? = null

        private const val EXTRA_HANAYU_KIND = "hanayu.kind"
        private const val EXTRA_HANAYU_TITLE = "hanayu.title"
        private const val EXTRA_HANAYU_LABELS = "hanayu.labels"
        private const val EXTRA_HANAYU_VALUES = "hanayu.values"
        private const val EXTRA_HANAYU_BACKGROUND = "hanayu.background"

        /** hanayu の show。前に出していたものは閉じる */
        fun startHanayu(context: Context, graph: HanayuGraph) {
            context.startActivity(
                Intent(context, AiGraphActivity::class.java)
                    .putExtra(EXTRA_HANAYU_KIND, graph.kind)
                    .putExtra(EXTRA_HANAYU_TITLE, graph.title)
                    .putExtra(EXTRA_HANAYU_LABELS, graph.points.take(AiGraph.MAX_AXES).map { it.first }.toTypedArray())
                    .putExtra(EXTRA_HANAYU_VALUES, graph.points.take(AiGraph.MAX_AXES).map { it.second }.toDoubleArray())
                    .putExtra(EXTRA_HANAYU_BACKGROUND, graph.background?.path.orEmpty())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** hanayu の hide。出していなければ false */
        fun hideHanayu(): Boolean {
            val activity = shownHanayu?.get() ?: return false
            activity.runOnUiThread { activity.finish() }
            return true
        }

        private fun readHanayu(intent: Intent): HanayuGraph? {
            val kind = intent.getStringExtra(EXTRA_HANAYU_KIND) ?: return null
            val labels = intent.getStringArrayExtra(EXTRA_HANAYU_LABELS).orEmpty()
            val values = intent.getDoubleArrayExtra(EXTRA_HANAYU_VALUES) ?: DoubleArray(0)
            val points = values.indices.map { labels.getOrElse(it) { "" } to values[it] }
            val background = intent.getStringExtra(EXTRA_HANAYU_BACKGROUND)?.takeIf { it.isNotEmpty() }?.let(::File)
            return HanayuGraph(kind, intent.getStringExtra(EXTRA_HANAYU_TITLE).orEmpty(), points, background)
        }

        private const val EXTRA_COUNT = "count"
        private const val EXTRA_LOGOS = "logos"
        private const val EXTRA_ALIGN = "align"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"

        /** 表示値・ラベル・加算値・最大値（グラフごとに、名前の後ろに番号） */
        private const val EXTRA_VALUES = "values"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_ADDED = "added"
        private const val EXTRA_MAX = "max"

        /** 画面に出すグラフの数の上限（Intent に載せる大きさを抑える） */
        private const val MAX_GRAPHS = 16

        fun start(context: Context, graphs: List<AiGraph>, logo: AiGraphLogo?) {
            val shown = graphs.take(MAX_GRAPHS)
            val intent = Intent(context, AiGraphActivity::class.java)
                .putExtra(EXTRA_COUNT, shown.size)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            shown.forEachIndexed { i, g ->
                intent.putExtra("$EXTRA_VALUES$i", g.values.toDoubleArray())
                    .putExtra("$EXTRA_LABELS$i", g.labels.toTypedArray())
                    .putExtra("$EXTRA_ADDED$i", g.added.toDoubleArray())
                    .putExtra("$EXTRA_MAX$i", g.max.toDoubleArray())
            }
            if (logo != null) {
                intent.putExtra(EXTRA_LOGOS, shown.indices.map { logo.files.getOrNull(it)?.path.orEmpty() }.toTypedArray())
                    .putExtra(EXTRA_ALIGN, logo.align)
                    .putExtra(EXTRA_X, logo.x)
                    .putExtra(EXTRA_Y, logo.y)
            }
            context.startActivity(intent)
        }

        private fun readGraphs(intent: Intent): List<AiGraph> = (0 until intent.getIntExtra(EXTRA_COUNT, 0).coerceIn(0, MAX_GRAPHS)).mapNotNull { i ->
            val values = intent.getDoubleArrayExtra("$EXTRA_VALUES$i")?.toList() ?: return@mapNotNull null
            AiGraph(
                values,
                intent.getStringArrayExtra("$EXTRA_LABELS$i")?.toList().orEmpty(),
                intent.getDoubleArrayExtra("$EXTRA_ADDED$i")?.toList().orEmpty(),
                intent.getDoubleArrayExtra("$EXTRA_MAX$i")?.toList().orEmpty(),
            )
        }
    }
}

/** SSP の AI グラフの描く面の大きさ（dp。背景の画像はこの大きさで面いっぱい） */
private const val CHART_SIZE = 288

/** 軸の長さ（面の半分から、ラベルの分を引いた dp） */
private const val CHART_RADIUS = 100

/** 目盛りの多角形の数（25%・50%・75%・100%） */
private const val GRID_STEPS = 4

/**
 * レーダーチャート1枚。軸を真上から時計回りに並べ、加算値（表示値＋加算値）を tertiary、表示値を primary の多角形で重ねる。
 * 背景の画像は align の角から x / y の位置に置く
 */
@Composable
private fun RadarChart(graph: AiGraph, logo: File?, align: String, logoX: Int, logoY: Int) {
    val background by produceState<ImageBitmap?>(null, logo) {
        value = logo?.let { f -> withContext(Dispatchers.IO) { runCatching { ImageTransparency.size(f)?.let { BitmapFactory.decodeFile(f.path) }?.asImageBitmap() }.getOrNull() } }
    }
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.size(CHART_SIZE.dp)) {
        background?.let { img -> drawLogo(img, align, logoX, logoY) }
        val n = graph.values.size
        if (n == 0) return@Canvas
        val center = Offset(size.width / 2, size.height / 2)
        val radius = CHART_RADIUS.dp.toPx()
        fun point(i: Int, ratio: Double): Offset {
            val angle = -PI / 2 + 2 * PI * i / n
            val r = radius * ratio.coerceIn(0.0, 1.0)
            return Offset(center.x + (r * cos(angle)).toFloat(), center.y + (r * sin(angle)).toFloat())
        }
        fun polygon(ratio: (Int) -> Double) = Path().apply {
            for (i in 0 until n) point(i, ratio(i)).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
            close()
        }
        val line = 1.dp.toPx()
        // 目盛りと軸
        for (step in 1..GRID_STEPS) drawPath(polygon { step.toDouble() / GRID_STEPS }, colors.outlineVariant, style = Stroke(line))
        for (i in 0 until n) drawLine(colors.outlineVariant, center, point(i, 1.0), line)
        fun ratio(v: Double, i: Int) = graph.max.getOrNull(i)?.takeIf { it > 0 }?.let { v / it } ?: 0.0
        // 加算値は表示値の外側に、別の色で
        if (graph.added.any { it != 0.0 }) {
            val withAdded = polygon { ratio(graph.values[it] + graph.added.getOrElse(it) { 0.0 }, it) }
            drawPath(withAdded, colors.tertiary.copy(alpha = 0.3f))
            drawPath(withAdded, colors.tertiary, style = Stroke(line * 2))
        }
        val values = polygon { ratio(graph.values[it], it) }
        drawPath(values, colors.primary.copy(alpha = 0.3f))
        drawPath(values, colors.primary, style = Stroke(line * 2))
        // ラベルは軸の先に、はみ出さないよう面の中に収める
        val maxLabel = (size.width / 3).roundToInt()
        for (i in 0 until n) {
            val text = measurer.measure(graph.labels.getOrElse(i) { (i + 1).toString() }, labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = maxLabel))
            val tip = point(i, 1.0)
            val dx = tip.x - center.x
            val dy = tip.y - center.y
            val gap = 4.dp.toPx()
            val x = when {
                dx > gap -> tip.x + gap
                dx < -gap -> tip.x - gap - text.size.width
                else -> tip.x - text.size.width / 2
            }
            val y = when {
                dy > gap -> tip.y + gap
                dy < -gap -> tip.y - gap - text.size.height
                else -> tip.y - text.size.height / 2
            }
            drawText(text, topLeft = Offset(x.coerceIn(0f, size.width - text.size.width), y.coerceIn(0f, size.height - text.size.height)))
        }
    }
}

/** hanayu の描く面の大きさ（radar 以外。背景の画像はこの大きさで面いっぱい） */
private const val HANAYU_WIDTH = 450
private const val HANAYU_HEIGHT = 340

/** hanayu のグラフ。radar は AI グラフと同じレーダーチャート、usetime / bar は棒、line は折れ線 */
@Composable
private fun HanayuChart(graph: HanayuGraph) {
    if (graph.kind == "radar") {
        val values = graph.points.map { it.second }
        val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        RadarChart(AiGraph(values, graph.points.map { it.first }, values.map { 0.0 }, values.map { top }), graph.background, "lefttop", 0, 0)
        return
    }
    val background by produceState<ImageBitmap?>(null, graph.background) {
        value = graph.background?.let { f -> withContext(Dispatchers.IO) { runCatching { ImageTransparency.size(f)?.let { BitmapFactory.decodeFile(f.path) }?.asImageBitmap() }.getOrNull() } }
    }
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurfaceVariant)
    val measurer = rememberTextMeasurer()
    // 横に広い面は、画面の幅に合わせて縮める（縦横の比は保つ）
    Canvas(Modifier.fillMaxWidth().aspectRatio(HANAYU_WIDTH.toFloat() / HANAYU_HEIGHT)) {
        background?.let { img -> drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
        val n = graph.points.size
        if (n == 0) return@Canvas
        val line = 1.dp.toPx()
        val labelHeight = measurer.measure("0", labelStyle).size.height.toFloat()
        val gap = 4.dp.toPx()
        val left = gap
        val right = size.width - gap
        val top = gap
        val bottom = size.height - labelHeight - gap * 2
        val maxValue = graph.points.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
        drawLine(colors.outlineVariant, Offset(left, bottom), Offset(right, bottom), line)
        val slot = (right - left) / n
        fun y(v: Double) = bottom - ((v.coerceAtLeast(0.0) / maxValue) * (bottom - top)).toFloat()
        if (graph.kind == "line") {
            val path = Path()
            graph.points.forEachIndexed { i, (_, v) ->
                val x = left + slot * (i + 0.5f)
                if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
            }
            drawPath(path, colors.primary, style = Stroke(line * 2))
            // 点は線の上に（8dp の丸）
            graph.points.forEachIndexed { i, (_, v) ->
                drawCircle(colors.primary, radius = 4.dp.toPx(), center = Offset(left + slot * (i + 0.5f), y(v)))
            }
        } else {
            graph.points.forEachIndexed { i, (_, v) ->
                val x = left + slot * i + slot * 0.2f
                drawRect(colors.primary, topLeft = Offset(x, y(v)), size = androidx.compose.ui.geometry.Size(slot * 0.6f, bottom - y(v)))
            }
        }
        graph.points.forEachIndexed { i, (label, _) ->
            val text = measurer.measure(label, labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = slot.roundToInt().coerceAtLeast(1)))
            drawText(text, topLeft = Offset(left + slot * i + (slot - text.size.width) / 2, bottom + gap))
        }
    }
}

/** 背景の画像を、[align] の角から [x] / [y]（画像の1ピクセルを 1dp）の位置に描く */
private fun DrawScope.drawLogo(img: ImageBitmap, align: String, x: Int, y: Int) {
    val w = img.width.dp.toPx()
    val h = img.height.dp.toPx()
    val left = if (align.startsWith("right")) size.width - w - x.dp.toPx() else x.dp.toPx()
    val top = if (align.endsWith("bottom")) size.height - h - y.dp.toPx() else y.dp.toPx()
    drawImage(img, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()))
}
