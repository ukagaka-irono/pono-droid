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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextLayoutResult
import net.pono.baseware.sakura.TextAlign
import net.pono.baseware.sakura.TextStyle
import net.pono.baseware.sakura.VerticalAlign
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

/**
 * バルーンの中の文の色と大きさと、バルーンの descript.txt の既定の飾り（font.name・影・白抜きなど）。
 * [spPerPx] は \f[height,px] のピクセルを sp にする倍率、[pxPerPx] はバルーン画像の 1px が画面の何 px か（影のずれに使う）
 */
data class BalloonTextStyle(
    val textColor: Color,
    val choiceColor: Color,
    val fontSize: TextUnit,
    val spPerPx: Float = 1f,
    val pxPerPx: Float = 2f,
    val fontFamily: FontFamily? = null,
    val shadowColor: Color? = null,
    val shadowOutline: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val outline: Boolean = false,
    val disabledColor: Color = Color(0xFF9E9E9E),
    val links: LinkDefaults = LinkDefaults(choiceColor),
) {
    /** 文全体に付ける既定の飾り */
    fun base(): ComposeTextStyle = ComposeTextStyle(
        fontFamily = fontFamily,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = decoration(underline, strike),
        shadow = shadowColor?.let { shadow(it, shadowOutline) },
        drawStyle = if (outline) outlineStroke() else null,
    )

    /** \f[shadowstyle,outline] は縁取り（ずらさずにぼかす）、それ以外は右下にずらす */
    private fun shadow(color: Color, outline: Boolean) =
        if (outline) Shadow(color, Offset.Zero, blurRadius = 2.5f * pxPerPx) else Shadow(color, Offset(pxPerPx, pxPerPx), blurRadius = 1f)

    /** 白抜き（\f[outline,1]・font.outline）。縁だけ描く */
    private fun outlineStroke() = Stroke(width = maxOf(1f, pxPerPx * 0.75f))

    private fun decoration(underline: Boolean, strike: Boolean): TextDecoration? {
        val decorations = listOfNotNull(TextDecoration.Underline.takeIf { underline }, TextDecoration.LineThrough.takeIf { strike })
        return if (decorations.isEmpty()) null else TextDecoration.combine(decorations)
    }

    /** \f[height] を考えた文字の大きさ（sp。下付き・上付きなら小さく） */
    fun size(style: TextStyle): Float {
        val base = fontSize.value
        val size = when (style.heightMode) {
            TextStyle.HeightMode.ABSOLUTE -> style.height?.let { it * spPerPx }
            TextStyle.HeightMode.RELATIVE -> style.height?.let { base + it * spPerPx }
            TextStyle.HeightMode.PERCENT -> style.height?.let { base * it / 100f }
        }?.coerceIn(6f, 72f) ?: base
        return if (style.sub || style.sup) size * 0.75f else size
    }

    /** \f の飾りを Compose の文字飾りにする。指定していないもの（null）はバルーンの既定のまま */
    fun span(style: TextStyle, baselineShift: BaselineShift? = null): SpanStyle {
        val shadow = when {
            style.noShadow -> Shadow.None
            style.shadowColor != null || style.shadowStyle != null ->
                (style.shadowColor?.let { Color(it or 0xFF000000.toInt()) } ?: shadowColor)
                    ?.let { shadow(it, style.shadowStyle?.let { s -> s == TextStyle.ShadowStyle.OUTLINE } ?: shadowOutline) }
            else -> null
        }
        return SpanStyle(
            color = when {
                style.disabled -> disabledColor
                else -> style.color?.let { Color(it or 0xFF000000.toInt()) } ?: Color.Unspecified
            },
            fontWeight = if (style.bold) FontWeight.Bold else null,
            fontStyle = if (style.italic) FontStyle.Italic else null,
            fontSize = size(style).sp,
            fontFamily = style.font?.let(BalloonFonts::family),
            baselineShift = when {
                style.sub -> BaselineShift.Subscript
                style.sup -> BaselineShift.Superscript
                else -> baselineShift
            },
            textDecoration = decoration(style.underline, style.strike),
            shadow = shadow,
            drawStyle = style.outline?.let { if (it) outlineStroke() else Fill },
        )
    }

    companion object {
        val Simple = BalloonTextStyle(Color(0xFF3E2723), Color(0xFF1565C0), 14.sp)
    }
}

internal fun BalloonColor.toColor() = Color(r, g, b)

/**
 * \0 と \1 は画面の右端・左端から出るので、画面の半分から余白を引いた幅に収めれば2人が同時に話しても重ならない。
 * 幅は固定にする（文字が増えるたびにウィンドウの幅が変わらないように。高さは行が増えたときだけ変わる）
 */
@Composable
internal fun balloonMaxWidth(): Dp {
    val screen = LocalConfiguration.current.screenWidthDp
    // アプリで変えたバルーンの大きさを掛ける（画面からははみ出さない）
    return ((screen / 2 - 24).coerceIn(120, 280) * LocalBalloonScale.current).coerceAtMost(screen - 32f).dp
}

/** アプリの画面で変えたバルーンの大きさ（倍率） */
val LocalBalloonScale = staticCompositionLocalOf { 1f }

/**
 * 文字を出している間だけ最後まで追いかける。選択肢で終わるとき（メニューなど）は先頭の問いかけを見せたいので動かさない。
 * [enabled] が false（\![set,autoscroll,disable]）なら追いかけない
 */
@Composable
private fun AutoScroll(items: List<BalloonItem>, scroll: ScrollState, enabled: Boolean) {
    val last = items.lastOrNull()
    LaunchedEffect(items.size, (last as? BalloonItem.Text)?.text?.length, enabled) {
        if (enabled && last !is BalloonItem.ChoiceItem) scroll.animateScrollTo(scroll.maxValue)
    }
}

/** オンラインの印（ネットワーク更新と \![enter,onlinemode] の間）。簡易バルーンでは、点滅する小さな文字にする */
@Composable
private fun SimpleOnlineMarker(modifier: Modifier) {
    val alpha by rememberInfiniteTransition(label = "online").animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "online",
    )
    Text("● オンライン", fontSize = 10.sp, color = Color(0xFF8D6E63), modifier = modifier.graphicsLayer { this.alpha = alpha })
}

/** バルーンに出す印の文字（\![set,balloonmarker]）と数（\![set,balloonnum]） */
data class BalloonLabels(val marker: String? = null, val number: String? = null) {
    val isEmpty: Boolean get() = marker == null && number == null
}

/** pono-droid の簡易バルーン（ゴーストのバルーン画像が無いとき）。印の文字と数は下の端に小さく出す */
@Composable
fun SimpleBalloon(
    items: List<BalloonItem>,
    onClick: () -> Unit,
    choices: ChoiceActions,
    autoScroll: Boolean = true,
    online: Boolean = false,
    labels: BalloonLabels = BalloonLabels(),
) {
    val scroll = rememberScrollState()
    AutoScroll(items, scroll, autoScroll)
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
        Box {
            Box(Modifier.heightIn(max = maxHeight).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
                val s = LocalBalloonScale.current
                BalloonBody(items, choices, BalloonTextStyle.Simple.let { it.copy(fontSize = it.fontSize * s, spPerPx = it.spPerPx * s) })
            }
            if (online) SimpleOnlineMarker(Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 2.dp))
            if (!labels.isEmpty) {
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp)) {
                    Text(labels.marker.orEmpty(), fontSize = 10.sp, color = Color(0xFF8D6E63), maxLines = 1, modifier = Modifier.weight(1f))
                    labels.number?.let { Text(it, fontSize = 10.sp, color = Color(0xFF8D6E63), maxLines = 1) }
                }
            }
        }
    }
}

/** バルーン画像の上の小さな文字（印の文字・数）。[height] はバルーン画像のピクセル、無ければ本文より小さく */
@Composable
private fun BalloonLabel(text: String, height: Int?, color: BalloonColor?, style: BalloonTextStyle, modifier: Modifier) {
    val size = height?.let { maxOf(it * style.spPerPx, 9f) } ?: (style.fontSize.value * 0.8f)
    Text(text, fontSize = size.sp, color = color?.toColor() ?: style.textColor, maxLines = 1, softWrap = false, modifier = modifier)
}

/** SHIORI やシェルを読み込んでいる間に出す、くるくる回る印と [text] だけの小さな吹き出し（簡易バルーンと同じ見た目） */
@Composable
fun LoadingBalloon(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .border(1.5.dp, Color(0xFF8D6E63), RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color(0xFF8D6E63), strokeWidth = 2.dp)
            Text(text, color = Color(0xFF3E2723), fontSize = 14.sp)
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
    choices: ChoiceActions,
    autoScroll: Boolean = true,
    online: Boolean = false,
    labels: BalloonLabels = BalloonLabels(),
) {
    val images by produceState<List<BalloonSkin.Image>?>(null, skin, scopeId) {
        value = withContext(Dispatchers.IO) { skin.images(scopeId) }
    }
    val candidates = images ?: return
    if (candidates.isEmpty()) {
        SimpleBalloon(items, onClick, choices, autoScroll, online, labels)
        return
    }
    val density = LocalDensity.current
    val maxWidthPx = with(density) { balloonMaxWidth().toPx() }
    // 1枚ごとに縮めると大きさで文字の大きさが変わるので、いちばん広い画像に合わせて同じ比率で縮める
    val scale = minOf(density.density * LocalBalloonScale.current, maxWidthPx / candidates.maxOf { it.bitmap.width })
    val style = skin.textStyle(scale / density.density, scale)
    val scroll = rememberScrollState()
    AutoScroll(items, scroll, autoScroll)
    val onlineFrames by produceState<List<ImageBitmap>>(emptyList(), skin, scopeId, online) {
        value = if (online) withContext(Dispatchers.IO) { skin.onlineFrames(scopeId) } else emptyList()
    }

    // \b[n] で選ばれた画像（無ければ文字の量で選ぶ）
    val requested = balloonId?.let { id -> skin.balloon.imageForId(scopeId, id) }
        ?.let { img -> candidates.indexOfFirst { it.index == img.index } }?.takeIf { it >= 0 }

    SubcomposeLayout(Modifier.clickable(onClick = onClick)) { _ ->
        // 文字の高さを測って、収まる画像を選ぶ
        val chosen = requested ?: candidates.indices.firstOrNull { i ->
            val area = candidates[i].area
            val measured = subcompose("measure$i") { BalloonBody(items, choices, style) }
                .first().measure(Constraints(minWidth = 0, maxWidth = (area.width * scale).toInt()))
            measured.height <= area.height * scale
        } ?: candidates.lastIndex
        val image = candidates[chosen]
        val placeable = subcompose("balloon") {
            SkinFrame(image, skin, scale, scroll, onlineFrames, labels, style) { BalloonBody(items, choices, style) }
        }.first().measure(Constraints())
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * バルーン画像を敷き、文字の範囲に中身を置く。続きや前があれば矢印を出し、押すと1画面分スクロールする。
 * [onlineFrames] があれば、オンラインの印として onlinemarker.x / y にコマを替えながら出す。
 * [labels] の印の文字は sstpmessage.x / y（無ければ文字の範囲の左上の上）、数は number.x / y を右端にして（無ければ右下）出す
 */
@Composable
private fun SkinFrame(
    image: BalloonSkin.Image,
    skin: BalloonSkin,
    scale: Float,
    scroll: ScrollState,
    onlineFrames: List<ImageBitmap>,
    labels: BalloonLabels,
    style: BalloonTextStyle,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    fun px(v: Int) = with(density) { (v * scale).toDp() }
    val w = image.bitmap.width
    val h = image.bitmap.height
    Box(Modifier.size(px(w), px(h))) {
        Image(image.bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.size(px(w), px(h)))
        if (onlineFrames.isNotEmpty()) {
            val frame by produceState(0, onlineFrames) {
                while (true) {
                    delay(skin.balloon.onlineInterval)
                    value = (value + 1) % onlineFrames.size
                }
            }
            val bmp = onlineFrames[frame.coerceIn(0, onlineFrames.lastIndex)]
            val (x, y) = skin.balloon.onlinePosition(w, h)
            Image(
                bmp,
                contentDescription = "オンライン",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.offset(px(x), px(y)).size(px(bmp.width), px(bmp.height)),
            )
        }
        val b = skin.balloon
        labels.marker?.let { marker ->
            val (x, y) = b.markerPosition(w, h) ?: (image.area.left to maxOf(0, image.area.top - b.fontHeight - 2))
            BalloonLabel(marker, b.markerFontHeight, b.markerColor, style, Modifier.offset(px(x), px(y)))
        }
        labels.number?.let { number ->
            val (x, y) = b.numberPosition(w, h) ?: (image.area.right to maxOf(0, image.area.bottom - b.fontHeight))
            val right = with(density) { (x * scale).toInt() }
            BalloonLabel(
                number, b.numberFontHeight, b.numberColor, style,
                // 数は右端を x にそろえる
                Modifier.layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0))
                    layout(p.width, p.height) { p.place(right - p.width, with(density) { (y * scale).toInt() }) }
                },
            )
        }
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
fun BalloonBody(items: List<BalloonItem>, choices: ChoiceActions, style: BalloonTextStyle) {
    // \_b[ファイル,x,y] の画像は文字の下に置く
    Box {
        for (img in BalloonLayout.placedImages(items)) {
            BalloonPicture(img.path, img.opaque, style.spPerPx, Modifier.offset((img.x * style.spPerPx).dp, (img.y * style.spPerPx).dp))
        }
        BalloonLines(items, choices, style)
    }
}

@Composable
private fun BalloonLines(items: List<BalloonItem>, choices: ChoiceActions, style: BalloonTextStyle) {
    val lineHeightPx = with(LocalDensity.current) { (style.fontSize * LINE_HEIGHT).toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in BalloonLayout.lines(items)) {
            val align = when (line.align) {
                TextAlign.CENTER -> ComposeTextAlign.Center
                TextAlign.RIGHT -> ComposeTextAlign.End
                TextAlign.LEFT -> ComposeTextAlign.Start
            }
            // \n[half] / \n[150] は、ふつうの1行との差だけ行を上下にずらす（負なら前の行に重なる）
            val shift = ((line.gap - 100) / 100f * lineHeightPx).roundToInt()
            val gap = if (shift == 0) {
                Modifier
            } else {
                Modifier.layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    layout(p.width, (p.height + shift).coerceAtLeast(0)) { p.place(0, shift) }
                }
            }
            Box(gap) {
                if (line.columns.size == 1) {
                    BalloonText(line.columns[0], choices, style, align, line.valign, Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (column in line.columns) {
                            BalloonText(column, choices, style, align, line.valign, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** ふつうの行の高さ（文字の大きさに対する倍率） */
private const val LINE_HEIGHT = 1.4f

/**
 * 1列分の文。選択肢とリンクは文の中で押せるようにする（\![*] があれば印を付ける）。\f の飾りも付ける。
 * [valign] は \f[valign]。大きさの違う文字が混じる行で、小さい文字を上か真ん中に寄せる（null と bottom は下にそろえる）。
 * \_n の文字がある行は折り返さない。
 *
 * 選択肢とリンクは、押している間は「選択中」、それ以外は「非選択中」（選んだことのあるリンクは「訪問済み」）の見た目
 * （\f[cursor*] / \f[anchor*]、無ければバルーンの cursor.* / anchor.*）で描く。長押しするとカーソルを乗せたことにして
 * OnChoiceEnter / OnChoiceHover を送り、指を離すと選ばずに外れる
 */
@Composable
private fun BalloonText(
    runs: List<BalloonLayout.Run>,
    choices: ChoiceActions,
    style: BalloonTextStyle,
    align: ComposeTextAlign,
    valign: VerticalAlign?,
    modifier: Modifier = Modifier,
) {
    val textRuns = runs.filterIsInstance<BalloonLayout.Run.Text>()
    val largest = textRuns.maxOfOrNull { style.size(it.style) } ?: style.fontSize.value
    /** 小さい文字を上（か真ん中）に寄せる分。BaselineShift はその文字の大きさに対する割合で、上が正 */
    fun shiftFor(s: TextStyle): BaselineShift? {
        val size = style.size(s)
        if (size >= largest || valign == null || valign == VerticalAlign.BOTTOM) return null
        val up = (largest - size) * 0.8f / size
        return BaselineShift(if (valign == VerticalAlign.CENTER) up / 2 else up)
    }
    val noWrap = textRuns.any { it.style.noWrap }
    val choiceRuns = runs.filterIsInstance<BalloonLayout.Run.ChoiceRun>()
    var pressed by remember(choiceRuns) { mutableStateOf<Int?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    /** k 番目の選択肢を、いまの状態で描く見た目 */
    fun lookOf(k: Int): ResolvedLook {
        val run = choiceRuns[k]
        return style.links.resolve(run.looks, run.choice.anchor, selected = pressed == k, visited = run.choice.id in choices.visited)
    }
    val density = LocalDensity.current
    // \_b[ファイル,inline] の画像は1文字として文の中に置く（大きさは先に画像の寸法だけ読んで決める）
    val inlineContent = HashMap<String, InlineTextContent>()
    val ranges = ArrayList<IntRange>()
    val text = buildAnnotatedString {
        runs.forEachIndexed { index, run ->
            when (run) {
                is BalloonLayout.Run.Text -> {
                    val shift = shiftFor(run.style)
                    if (run.style == TextStyle.Default && shift == null) {
                        append(run.text)
                    } else {
                        withStyle(style.span(run.style, shift)) { append(run.text) }
                    }
                }
                is BalloonLayout.Run.ChoiceRun -> {
                    if (run.marked) append("▸ ")
                    val look = lookOf(ranges.size)
                    val start = length
                    withStyle(SpanStyle(color = look.font, fontWeight = FontWeight.Medium)) { append(run.choice.title) }
                    ranges += start until length
                }
                is BalloonLayout.Run.Image -> {
                    val (w, h) = ImageTransparency.size(java.io.File(run.path)) ?: return@forEachIndexed
                    val id = "img$index"
                    appendInlineContent(id, "□")
                    val imageAlign = when (valign) {
                        VerticalAlign.TOP -> PlaceholderVerticalAlign.Top
                        VerticalAlign.CENTER -> PlaceholderVerticalAlign.Center
                        else -> PlaceholderVerticalAlign.TextBottom
                    }
                    inlineContent[id] = InlineTextContent(
                        with(density) { Placeholder((w * style.spPerPx).dp.toSp(), (h * style.spPerPx).dp.toSp(), imageAlign) },
                    ) { BalloonPicture(run.path, run.opaque, style.spPerPx, Modifier) }
                }
            }
        }
    }
    // 選択肢を含む行は指で押しやすいよう少し高さを取る
    val hasChoice = choiceRuns.isNotEmpty()
    val stroke = with(density) { 1.dp.toPx() }
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis
    // 指の操作は選択肢が変わるまで同じものを使い続けるので、範囲と操作は最新のものを読む
    val currentRanges by rememberUpdatedState(ranges)
    val currentChoices by rememberUpdatedState(choices)
    Text(
        text = text,
        fontSize = style.fontSize,
        lineHeight = style.fontSize * (if (hasChoice) 1.7f else LINE_HEIGHT),
        color = style.textColor,
        textAlign = align,
        inlineContent = inlineContent,
        softWrap = !noWrap,
        maxLines = if (noWrap) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Clip,
        style = style.base(),
        onTextLayout = { layout = it },
        modifier = modifier
            .padding(vertical = if (hasChoice) 2.dp else 0.dp)
            .drawBehind {
                val l = layout ?: return@drawBehind
                ranges.forEachIndexed { k, r ->
                    if (r.isEmpty() || r.last >= l.layoutInput.text.length) return@forEachIndexed
                    val look = lookOf(k)
                    if (look.square) {
                        val path = l.getPathForRange(r.first, r.last + 1)
                        look.brush?.let { drawPath(path, it) }
                        look.pen?.let { drawPath(path, it, style = Stroke(stroke)) }
                    }
                    if (look.underline) {
                        val color = look.pen ?: look.font
                        for (line in l.getLineForOffset(r.first)..l.getLineForOffset(r.last)) {
                            val left = if (line == l.getLineForOffset(r.first)) l.getHorizontalPosition(r.first, true) else l.getLineLeft(line)
                            val right = if (line == l.getLineForOffset(r.last)) l.getHorizontalPosition(r.last + 1, true) else l.getLineRight(line)
                            val y = l.getLineBaseline(line) + stroke * 2
                            drawLine(color, Offset(left, y), Offset(right, y), stroke)
                        }
                    }
                }
            }
            .pointerInput(choiceRuns) {
                if (choiceRuns.isEmpty()) return@pointerInput
                awaitEachGesture {
                    // 選択肢の上で触れたときだけ受け取る（それ以外はバルーンを叩いたことにする）
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val l = layout ?: return@awaitEachGesture
                    val at = l.getOffsetForPosition(down.position)
                    val k = currentRanges.indexOfFirst {
                        at in it && l.getPathForRange(it.first, it.last + 1).getBounds().inflate(stroke * 6).contains(down.position)
                    }
                    if (k < 0) return@awaitEachGesture
                    down.consume()
                    pressed = k
                    val choice = choiceRuns[k].choice
                    var held = false
                    val up = try {
                        withTimeout(longPress) { waitForUpOrCancellation() }
                    } catch (e: PointerEventTimeoutCancellationException) {
                        held = true
                        null
                    }
                    if (held) {
                        // 長押しはカーソルを乗せたのと同じ。離したら外れる（選ばない）
                        currentChoices.onHover(choice)
                        waitForUpOrCancellation()?.consume()
                        currentChoices.onHover(null)
                    } else if (up != null) {
                        up.consume()
                        currentChoices.onChoice(choice)
                    }
                    pressed = null
                }
            },
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

    private val communicates = HashMap<Boolean, Image?>()

    /**
     * 入力の箱の画像（balloonc0.png、[multiLine] なら balloonc5.png）。[Image.area] は入力欄の範囲。
     * 無いバルーンか読めなければ null
     */
    @Synchronized
    fun communicate(multiLine: Boolean): Image? = communicates.getOrPut(multiLine) {
        val file = balloon.communicateImage(multiLine) ?: return@getOrPut null
        val bmp = ImageTransparency.load(file, balloon.pnaFor(file), selfAlpha = null) ?: return@getOrPut null
        Image(bmp.asImageBitmap(), balloon.communicateBox(file, bmp.width, bmp.height), 0)
    }

    /** スクロールの矢印（0 が上、1 が下）。無いバルーンなら null */
    @Synchronized
    fun arrow(which: Int): ImageBitmap? = arrows.getOrPut(which) {
        balloon.arrowImage(which)?.let { ImageTransparency.load(it, balloon.pnaFor(it), selfAlpha = null) }?.asImageBitmap()
    }

    private val online = HashMap<Int, List<ImageBitmap>>()

    /** オンラインの印のコマ（online0.png…）。無いバルーンなら空 */
    @Synchronized
    fun onlineFrames(scope: Int): List<ImageBitmap> = online.getOrPut(scope) {
        balloon.onlineImages(scope).mapNotNull { ImageTransparency.load(it, balloon.pnaFor(it), selfAlpha = null)?.asImageBitmap() }
    }

    /**
     * [dpPerPx] は画像の 1px が何 dp になるか、[pxPerPx] は画面の何 px になるか。字が小さくなりすぎないよう 12sp は残す。
     * font.name・影・太字などの既定もバルーンの descript.txt から取る
     */
    fun textStyle(dpPerPx: Float, pxPerPx: Float): BalloonTextStyle {
        val simple = BalloonTextStyle.Simple
        val choiceColor = balloon.choiceColor?.toColor() ?: simple.choiceColor
        return BalloonTextStyle(
            textColor = balloon.fontColor?.toColor() ?: simple.textColor,
            choiceColor = choiceColor,
            links = LinkDefaults(
                choiceColor = choiceColor,
                anchorColor = balloon.anchorColor?.toColor() ?: choiceColor,
                cursor = balloon.linkLook("cursor"),
                cursorNotSelect = balloon.linkLook("cursor.notselect"),
                anchor = balloon.linkLook("anchor"),
                anchorNotSelect = balloon.linkLook("anchor.notselect"),
                anchorVisited = balloon.linkLook("anchor.visited"),
            ),
            fontSize = maxOf(balloon.fontHeight * dpPerPx, 12f).sp,
            spPerPx = dpPerPx,
            pxPerPx = pxPerPx,
            fontFamily = BalloonFonts.family(balloon.fontNames),
            shadowColor = balloon.shadowColor?.toColor(),
            shadowOutline = balloon.shadowOutline,
            bold = balloon.bold,
            italic = balloon.italic,
            underline = balloon.underline,
            strike = balloon.strike,
            outline = balloon.outline,
            disabledColor = balloon.disabledColor?.toColor() ?: simple.disabledColor,
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
