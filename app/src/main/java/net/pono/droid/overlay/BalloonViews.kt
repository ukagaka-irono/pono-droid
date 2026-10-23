package net.pono.droid.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import net.pono.baseware.sakura.TextAlign
import net.pono.baseware.sakura.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.balloon.BalloonColor
import net.pono.baseware.balloon.TextArea
import net.pono.baseware.ghost.BalloonItem
import net.pono.baseware.ghost.BalloonLayout
import net.pono.baseware.sakura.Choice

/** バルーンの中の文の色と大きさ。[spPerPx] は \f[height,px] のピクセルを sp にする倍率 */
data class BalloonTextStyle(
    val textColor: Color,
    val choiceColor: Color,
    val fontSize: TextUnit,
    val spPerPx: Float = 1f,
) {
    /** \f の飾りを Compose の文字飾りにする */
    fun span(style: TextStyle): SpanStyle {
        val base = fontSize.value
        val size = when (style.heightMode) {
            TextStyle.HeightMode.ABSOLUTE -> style.height?.let { it * spPerPx }
            TextStyle.HeightMode.RELATIVE -> style.height?.let { base + it * spPerPx }
            TextStyle.HeightMode.PERCENT -> style.height?.let { base * it / 100f }
        }?.coerceIn(6f, 72f) ?: base
        val decorations = listOfNotNull(
            TextDecoration.Underline.takeIf { style.underline },
            TextDecoration.LineThrough.takeIf { style.strike },
        )
        return SpanStyle(
            color = style.color?.let { Color(it or 0xFF000000.toInt()) } ?: Color.Unspecified,
            fontWeight = if (style.bold) FontWeight.Bold else null,
            fontStyle = if (style.italic) FontStyle.Italic else null,
            fontSize = (if (style.sub || style.sup) size * 0.75f else size).sp,
            baselineShift = when {
                style.sub -> BaselineShift.Subscript
                style.sup -> BaselineShift.Superscript
                else -> null
            },
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        )
    }

    companion object {
        val Simple = BalloonTextStyle(Color(0xFF3E2723), Color(0xFF1565C0), 14.sp)
    }
}

private fun BalloonColor.toColor() = Color(r, g, b)

/**
 * \0 と \1 は画面の右端・左端から出るので、画面の半分から余白を引いた幅に収めれば2人が同時に話しても重ならない。
 * 幅は固定にする（文字が増えるたびにウィンドウの幅が変わらないように。高さは行が増えたときだけ変わる）
 */
@Composable
private fun balloonMaxWidth(): Dp = (LocalConfiguration.current.screenWidthDp / 2 - 24).coerceIn(120, 280).dp

/** 文字を出している間だけ最後まで追いかける。選択肢で終わるとき（メニューなど）は先頭の問いかけを見せたいので動かさない */
@Composable
private fun AutoScroll(items: List<BalloonItem>, scroll: ScrollState) {
    val last = items.lastOrNull()
    LaunchedEffect(items.size, (last as? BalloonItem.Text)?.text?.length) {
        if (last !is BalloonItem.ChoiceItem) scroll.animateScrollTo(scroll.maxValue)
    }
}

/** pono-droid の簡易バルーン（ゴーストのバルーン画像が無いとき） */
@Composable
fun SimpleBalloon(items: List<BalloonItem>, onClick: () -> Unit, onChoice: (Choice) -> Unit) {
    val scroll = rememberScrollState()
    AutoScroll(items, scroll)
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 2).coerceIn(220, 400).dp
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .width(balloonMaxWidth())
            .border(1.5.dp, Color(0xFF8D6E63), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.heightIn(max = maxHeight).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
            BalloonBody(items, onChoice, BalloonTextStyle.Simple)
        }
    }
}

/**
 * ゴーストのバルーン画像で描く。画像は画面の半分の幅に収まるよう縮め、文字が収まるいちばん小さい大きさを選ぶ
 * （どれにも収まらなければいちばん大きい画像で、文字の範囲をスクロールする）。
 * [balloonId] は \b[n] の指定で、その画像があれば文字の量に関係なくそれを使う。
 */
@Composable
fun SkinBalloon(
    items: List<BalloonItem>,
    skin: BalloonSkin,
    scopeId: Int,
    balloonId: Int?,
    onClick: () -> Unit,
    onChoice: (Choice) -> Unit,
) {
    val images by produceState<List<BalloonSkin.Image>?>(null, skin, scopeId) {
        value = withContext(Dispatchers.IO) { skin.images(scopeId) }
    }
    val candidates = images ?: return
    if (candidates.isEmpty()) {
        SimpleBalloon(items, onClick, onChoice)
        return
    }
    val density = LocalDensity.current
    val maxWidthPx = with(density) { balloonMaxWidth().toPx() }
    // 1枚ごとに縮めると大きさで文字の大きさが変わるので、いちばん広い画像に合わせて同じ比率で縮める
    val scale = minOf(density.density, maxWidthPx / candidates.maxOf { it.bitmap.width })
    val style = skin.textStyle(scale / density.density)
    val scroll = rememberScrollState()
    AutoScroll(items, scroll)

    // \b[n] で選ばれた画像（無ければ文字の量で選ぶ）
    val requested = balloonId?.let { id -> skin.balloon.imageForId(scopeId, id) }
        ?.let { img -> candidates.indexOfFirst { it.index == img.index } }?.takeIf { it >= 0 }

    SubcomposeLayout(Modifier.clickable(onClick = onClick)) { _ ->
        // 文字の高さを測って、収まる画像を選ぶ
        val chosen = requested ?: candidates.indices.firstOrNull { i ->
            val area = candidates[i].area
            val measured = subcompose("measure$i") { BalloonBody(items, onChoice, style) }
                .first().measure(Constraints(minWidth = 0, maxWidth = (area.width * scale).toInt()))
            measured.height <= area.height * scale
        } ?: candidates.lastIndex
        val image = candidates[chosen]
        val placeable = subcompose("balloon") {
            SkinFrame(image, skin, scale, scroll) { BalloonBody(items, onChoice, style) }
        }.first().measure(Constraints())
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/** バルーン画像を敷き、文字の範囲に中身を置く。続きや前があれば矢印を出し、押すと1画面分スクロールする */
@Composable
private fun SkinFrame(image: BalloonSkin.Image, skin: BalloonSkin, scale: Float, scroll: ScrollState, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    fun px(v: Int) = with(density) { (v * scale).toDp() }
    val w = image.bitmap.width
    val h = image.bitmap.height
    Box(Modifier.size(px(w), px(h))) {
        Image(image.bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.size(px(w), px(h)))
        Box(
            Modifier
                .offset(px(image.area.left), px(image.area.top))
                .size(px(image.area.width), px(image.area.height))
                .verticalScroll(scroll),
        ) {
            content()
        }
        val page = (image.area.height * scale * 0.8f).toInt()
        for ((which, visible) in listOf(0 to (scroll.value > 0), 1 to (scroll.value < scroll.maxValue))) {
            val arrow = skin.arrow(which) ?: continue
            if (!visible) continue
            val (x, y) = skin.balloon.arrowPosition(which, w, h, arrow.width, arrow.height)
            // 矢印は縮めると指より小さくなるので、押せる範囲は矢印を中心に 40dp 取る
            val arrowW = px(arrow.width)
            val arrowH = px(arrow.height)
            val touch = 40.dp
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(px(x) + arrowW / 2 - touch / 2, px(y) + arrowH / 2 - touch / 2)
                    .size(touch)
                    .clickable {
                        coroutineScope.launch { scroll.animateScrollTo(scroll.value + if (which == 0) -page else page) }
                    },
            ) {
                Image(
                    arrow,
                    contentDescription = if (which == 0) "前へ" else "続き",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.size(arrowW, arrowH),
                )
            }
        }
    }
}

/** バルーンの中身。行ごとに文を並べ、\_l で分けた列は横に等分して並べる */
@Composable
fun BalloonBody(items: List<BalloonItem>, onChoice: (Choice) -> Unit, style: BalloonTextStyle) {
    // \_b[ファイル,x,y] の画像は文字の下に置く
    Box {
        for (img in BalloonLayout.placedImages(items)) {
            BalloonPicture(img.path, img.opaque, style.spPerPx, Modifier.offset((img.x * style.spPerPx).dp, (img.y * style.spPerPx).dp))
        }
        BalloonLines(items, onChoice, style)
    }
}

@Composable
private fun BalloonLines(items: List<BalloonItem>, onChoice: (Choice) -> Unit, style: BalloonTextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in BalloonLayout.lines(items)) {
            val align = when (line.align) {
                TextAlign.CENTER -> ComposeTextAlign.Center
                TextAlign.RIGHT -> ComposeTextAlign.End
                TextAlign.LEFT -> ComposeTextAlign.Start
            }
            if (line.columns.size == 1) {
                BalloonText(line.columns[0], onChoice, style, align, Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in line.columns) {
                        BalloonText(column, onChoice, style, align, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 1列分の文。選択肢とリンクは文の中で押せるようにする（\![*] があれば印を付ける）。\f の飾りも付ける */
@Composable
private fun BalloonText(
    runs: List<BalloonLayout.Run>,
    onChoice: (Choice) -> Unit,
    style: BalloonTextStyle,
    align: ComposeTextAlign,
    modifier: Modifier = Modifier,
) {
    val links = TextLinkStyles(
        style = SpanStyle(color = style.choiceColor, fontWeight = FontWeight.Medium),
        pressedStyle = SpanStyle(color = style.choiceColor, background = style.choiceColor.copy(alpha = 0.15f)),
    )
    val density = LocalDensity.current
    // \_b[ファイル,inline] の画像は1文字として文の中に置く（大きさは先に画像の寸法だけ読んで決める）
    val inlineContent = HashMap<String, InlineTextContent>()
    val text = buildAnnotatedString {
        runs.forEachIndexed { index, run ->
            when (run) {
                is BalloonLayout.Run.Text -> {
                    if (run.style == TextStyle.Default) append(run.text) else withStyle(style.span(run.style)) { append(run.text) }
                }
                is BalloonLayout.Run.ChoiceRun -> {
                    if (run.marked) append("▸ ")
                    withLink(LinkAnnotation.Clickable(tag = run.choice.id, styles = links) { onChoice(run.choice) }) {
                        append(run.choice.title)
                    }
                }
                is BalloonLayout.Run.Image -> {
                    val (w, h) = ImageTransparency.size(java.io.File(run.path)) ?: return@forEachIndexed
                    val id = "img$index"
                    appendInlineContent(id, "□")
                    inlineContent[id] = InlineTextContent(
                        with(density) { Placeholder((w * style.spPerPx).dp.toSp(), (h * style.spPerPx).dp.toSp(), PlaceholderVerticalAlign.TextBottom) },
                    ) { BalloonPicture(run.path, run.opaque, style.spPerPx, Modifier) }
                }
            }
        }
    }
    // 選択肢を含む行は指で押しやすいよう少し高さを取る
    val hasChoice = runs.any { it is BalloonLayout.Run.ChoiceRun }
    Text(
        text = text,
        fontSize = style.fontSize,
        lineHeight = style.fontSize * (if (hasChoice) 1.7f else 1.4f),
        color = style.textColor,
        textAlign = align,
        inlineContent = inlineContent,
        modifier = modifier.padding(vertical = if (hasChoice) 2.dp else 0.dp),
    )
}

/** \_b の画像。[dpPerPx] は画像の 1px を何 dp で描くか。読めない・大きすぎる画像は出さない */
@Composable
private fun BalloonPicture(path: String, opaque: Boolean, dpPerPx: Float, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path, opaque) {
        // 左上の色を透過色にする（opaque なら抜かない）。PNG にアルファがあればそれを使う
        value = withContext(Dispatchers.IO) {
            ImageTransparency.load(java.io.File(path), null, selfAlpha = if (opaque) true else null)?.asImageBitmap()
        }
    }
    val bmp = bitmap ?: return
    Image(bmp, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = modifier.size((bmp.width * dpPerPx).dp, (bmp.height * dpPerPx).dp))
}

/** インストール済みのバルーンを、画面に出せる形にしたもの。画像は使うときに読み込んで覚えておく */
class BalloonSkin(val balloon: Balloon) {
    /** [index] は balloons2.png の 2（\b[n] で選ぶときに使う） */
    class Image(val bitmap: ImageBitmap, val area: TextArea, val index: Int)

    private val cache = HashMap<Int, List<Image>>()
    private val arrows = HashMap<Int, ImageBitmap?>()

    @Synchronized
    fun images(scope: Int): List<Image> = cache.getOrPut(scope) {
        // 矢印も一緒に読んでおく（描くときに読み込みで待たせない）
        arrow(0)
        arrow(1)
        balloon.images(scope).mapNotNull { img ->
            val bmp = ImageTransparency.load(img.file, balloon.pnaFor(img.file), selfAlpha = null) ?: return@mapNotNull null
            Image(bmp.asImageBitmap(), balloon.textArea(bmp.width, bmp.height), img.index)
        }
    }

    /** スクロールの矢印（0 が上、1 が下）。無いバルーンなら null */
    @Synchronized
    fun arrow(which: Int): ImageBitmap? = arrows.getOrPut(which) {
        balloon.arrowImage(which)?.let { ImageTransparency.load(it, balloon.pnaFor(it), selfAlpha = null) }?.asImageBitmap()
    }

    /** [dpPerPx] は画像の 1px が何 dp になるか。字が小さくなりすぎないよう 12sp は残す */
    fun textStyle(dpPerPx: Float): BalloonTextStyle {
        val simple = BalloonTextStyle.Simple
        return BalloonTextStyle(
            textColor = balloon.fontColor?.toColor() ?: simple.textColor,
            choiceColor = balloon.choiceColor?.toColor() ?: simple.choiceColor,
            fontSize = maxOf(balloon.fontHeight * dpPerPx, 12f).sp,
            spPerPx = dpPerPx,
        )
    }
}

/** 伺かの画像の透過。PNG にアルファがあればそのまま、無ければ .pna か左上の色で抜く */
object ImageTransparency {
    /** 1辺の上限。ゴーストの画像は信用しないので、細工された巨大な PNG でメモリを使い切らせない */
    const val MAX_SIDE = 4096
    const val MAX_PIXELS = 8_000_000L

    /** @param selfAlpha true / false で決め打ち、null なら画像にアルファがあるかで決める */
    fun load(file: java.io.File, pna: java.io.File?, selfAlpha: Boolean?): Bitmap? {
        if (!fits(file)) return null
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        if (selfAlpha ?: bmp.hasAlpha()) return bmp
        val pnaBmp = pna?.takeIf(::fits)?.let { BitmapFactory.decodeFile(it.path) }
        if (pnaBmp != null && pnaBmp.width == bmp.width && pnaBmp.height == bmp.height) applyPna(bmp, pnaBmp) else applyColorKey(bmp)
        return bmp
    }

    /** 画像の寸法（読めないか大きすぎるなら null）。中身は読まない */
    fun size(file: java.io.File): Pair<Int, Int>? {
        if (!fits(file)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /** 画像の大きさだけ先に読んで、大きすぎるもの（と読めないもの）は弾く */
    private fun fits(file: java.io.File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        val ok = w in 1..MAX_SIDE && h in 1..MAX_SIDE && w.toLong() * h <= MAX_PIXELS
        if (!ok) android.util.Log.w("pono", "image skipped (unreadable or too large): ${file.name} ${w}x$h")
        return ok
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
            px[i] = (px[i] and 0x00FFFFFF) or ((alpha[i] and 0xFF) shl 24)
        }
        bmp.setHasAlpha(true)
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }
}
