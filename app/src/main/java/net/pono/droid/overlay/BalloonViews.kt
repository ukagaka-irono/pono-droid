package net.pono.droid.overlay

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextIndent
import net.pono.baseware.sakura.BalloonImageOptions
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
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
import net.pono.baseware.balloon.BalloonSelfAlpha
import net.pono.baseware.balloon.BalloonColor
import net.pono.baseware.balloon.TextArea
import net.pono.baseware.ghost.BalloonItem
import net.pono.baseware.ghost.BalloonLayout
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ChoiceLooks

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
    /** 無効表示の飾り（disable.font.bold / italic / underline / strike）。無いものは null（ふつうの文字と同じ） */
    val disabledFlags: Map<String, Boolean?> = emptyMap(),
    /** 無効表示の書体と大きさ（disable.font.name / height）。null ならふつうの文字と同じ */
    val disabledFontFamily: FontFamily? = null,
    val disabledFontHeight: Int? = null,
    val links: LinkDefaults = LinkDefaults(choiceColor),
    /** \![*] の印の画像（marker.png）。null なら「▸」の文字で出す */
    val markerImage: ImageBitmap? = null,
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

    /** \f[color,default.anchor] などの、バルーンの決めた色 */
    private fun refColor(ref: String): Color {
        val none = ChoiceLooks.Default
        return when (ref) {
            "anchor" -> links.resolve(none, isAnchor = true, selected = true, visited = false).font
            "anchornotselect" -> links.resolve(none, isAnchor = true, selected = false, visited = false).font
            "anchorvisited" -> links.resolve(none, isAnchor = true, selected = false, visited = true).font
            "cursor" -> links.resolve(none, isAnchor = false, selected = true, visited = false).font
            "cursornotselect" -> links.resolve(none, isAnchor = false, selected = false, visited = false).font
            else -> Color.Unspecified
        }
    }

    /** 選択肢・リンクの影（cursor.font.shadowcolor など）。縁取りかどうかが決まっていなければ文の影と同じ */
    fun linkShadow(look: ResolvedLook): Shadow? = look.shadow?.let { shadow(it, look.shadowOutline ?: shadowOutline) }

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
        return if (style.sub == true || style.sup == true) size * 0.75f else size
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
        // \f[bold,disable] などは無効表示の飾り（バルーンに無ければ、ふつうの文字と同じ）
        fun flag(name: String, value: Boolean?, base: Boolean): Boolean? =
            if (name in style.disabledFlags) disabledFlags[name] ?: base else value
        val bold = flag("bold", style.bold, this.bold)
        val italic = flag("italic", style.italic, this.italic)
        val underline = flag("underline", style.underline, this.underline)
        val strike = flag("strike", style.strike, this.strike)
        return SpanStyle(
            color = when {
                style.disabled -> disabledColor
                style.colorRef != null -> refColor(style.colorRef.orEmpty())
                else -> style.color?.let { Color(it or 0xFF000000.toInt()) } ?: Color.Unspecified
            },
            // false は外す（バルーンが太字でも細くする）。null はバルーンの既定のまま
            fontWeight = bold?.let { if (it) FontWeight.Bold else FontWeight.Normal },
            fontStyle = italic?.let { if (it) FontStyle.Italic else FontStyle.Normal },
            // 無効表示の書体と大きさ（disable.font.name / height）
            fontSize = ((if (style.disabled) disabledFontHeight?.let { maxOf(it * spPerPx, 6f) } else null) ?: size(style)).sp,
            fontFamily = style.font?.let(BalloonFonts::family) ?: if (style.disabled) disabledFontFamily else null,
            baselineShift = when {
                style.sub == true -> BaselineShift.Subscript
                style.sup == true -> BaselineShift.Superscript
                else -> baselineShift
            },
            textDecoration = if (underline == null && strike == null) null else decoration(underline ?: this.underline, strike ?: this.strike)
                ?: TextDecoration.None,
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

/** 描かない印（\![execute,dumpballoon] の --hide= の balloonmarker・balloonnum） */
val LocalHiddenBalloonLabels = staticCompositionLocalOf { emptySet<String>() }

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

/** バルーンを指で動かした（右と下が正の dp。指を離したら done が true） */
typealias BalloonDrag = (dx: Int, dy: Int, done: Boolean) -> Unit

/**
 * バルーンをつかんで動かす。選択肢（[hits] に並んだ文の選択肢）の上で触れたときは動かさない。
 * 文が長くて中をスクロールできる（[scrollable]）間だけは、文字の範囲（[inText]）はスクロールのままにし、縁（その外）で動かす。
 * 中の操作より先に指を受け取り、動き出したら（タッチの遊びを超えたら）中には渡さない（止まったまま離せばタップ）。
 * ウィンドウが指について動くので、画面の上の位置で測る。[onDrag] が null なら動かせない
 */
@Composable
private fun Modifier.balloonDrag(
    onDrag: BalloonDrag?,
    hits: ChoiceRouter,
    scrollable: () -> Boolean,
    inText: (Offset, IntSize) -> Boolean,
): Modifier {
    if (onDrag == null) return this
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentDrag by rememberUpdatedState(onDrag)
    val currentInText by rememberUpdatedState(inText)
    val currentScrollable by rememberUpdatedState(scrollable)
    val slop = with(LocalDensity.current) { 6.dp.toPx() }
    return onGloballyPositioned { coords[0] = it }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (currentScrollable() && currentInText(down.position, size)) return@awaitEachGesture
            coords[0]?.takeIf { it.isAttached }?.let { from -> if (hits.find(from, down.position, slop) != null) return@awaitEachGesture }
            fun screen(p: Offset) = coords[0]?.takeIf { it.isAttached }?.localToScreen(p) ?: p
            val start = screen(down.position)
            var dragging = false
            // 送った分（dp）。端数は次に回す
            var sentX = 0
            var sentY = 0
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (dragging) change.consume()
                        break
                    }
                    val moved = screen(change.position) - start
                    if (!dragging && moved.getDistance() > viewConfiguration.touchSlop) dragging = true
                    if (!dragging) continue
                    change.consume()
                    val x = (moved.x / density).roundToInt()
                    val y = (moved.y / density).roundToInt()
                    if (x != sentX || y != sentY) currentDrag(x - sentX, y - sentY, false)
                    sentX = x
                    sentY = y
                }
            } finally {
                if (dragging) currentDrag(0, 0, true)
            }
        }
    }
}

/** バルーンのふだんのマウスカーソル（バルーンの descript.txt の mousecursor.arrow など。[LocalBalloonCursors]） */
@Composable
private fun Modifier.balloonCursor(): Modifier = LocalBalloonCursors.current?.let { pointerHoverIcon(it.arrow) } ?: this

/** pono-droid の簡易バルーン（ゴーストのバルーン画像が無いとき）。印の文字と数は下の端に小さく出す */
@Composable
fun SimpleBalloon(
    items: List<BalloonItem>,
    onClick: () -> Unit,
    choices: ChoiceActions,
    autoScroll: Boolean = true,
    online: Boolean = false,
    labels: BalloonLabels = BalloonLabels(),
    onDrag: BalloonDrag? = null,
) {
    val scroll = rememberScrollState()
    AutoScroll(items, scroll, autoScroll)
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 2).coerceIn(220, 400).dp
    val density = LocalDensity.current
    val edgeX = with(density) { 12.dp.toPx() }
    val edgeY = with(density) { 8.dp.toPx() }
    // 選択肢の上で触れたら動かさないので、中の文の選択肢を集める
    val hits = remember { ChoiceRouter() }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .width(balloonMaxWidth())
            .border(1.5.dp, Color(0xFF8D6E63), RoundedCornerShape(12.dp))
            // 読み返せるほど長いときは、文字の周りの余白（左右 12dp・上下 8dp）が縁
            .balloonDrag(onDrag, hits, { scroll.maxValue > 0 }) { p, size -> p.x >= edgeX && p.x <= size.width - edgeX && p.y >= edgeY && p.y <= size.height - edgeY }
            .balloonCursor()
            .clickable(onClick = onClick),
    ) {
        Box {
            val s = LocalBalloonScale.current
            val simpleStyle = BalloonTextStyle.Simple.let { it.copy(fontSize = it.fontSize * s, spPerPx = it.spPerPx * s) }
            Box(Modifier.heightIn(max = maxHeight).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
                CompositionLocalProvider(LocalChoiceHits provides hits) { BalloonBody(items, choices, simpleStyle) }
            }
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { FixedBalloonImages(items, simpleStyle) }
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
private fun BalloonLabel(text: String, height: Int?, color: BalloonColor?, style: BalloonTextStyle, modifier: Modifier, fontNames: List<String> = emptyList()) {
    val size = height?.let { maxOf(it * style.spPerPx, 9f) } ?: (style.fontSize.value * 0.8f)
    // sstpmessage.font.name / number.font.name（無ければ本文の書体）
    val family = BalloonFonts.family(fontNames) ?: style.fontFamily
    Text(
        text, fontSize = size.sp, color = color?.toColor() ?: style.textColor, maxLines = 1, softWrap = false, fontFamily = family,
        overflow = TextOverflow.Clip, modifier = modifier,
    )
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
    clickWait: Boolean = false,
    sstp: Boolean = false,
    onDrag: BalloonDrag? = null,
) {
    val images by produceState<List<BalloonSkin.Image>?>(null, skin, scopeId) {
        value = withContext(Dispatchers.IO) { skin.images(scopeId) }
    }
    val candidates = images ?: return
    if (candidates.isEmpty()) {
        SimpleBalloon(items, onClick, choices, autoScroll, online, labels, onDrag)
        return
    }
    val density = LocalDensity.current
    val maxWidthPx = with(density) { balloonMaxWidth().toPx() }
    // 1枚ごとに縮めると大きさで文字の大きさが変わるので、いちばん広い画像に合わせて同じ比率で縮める
    // バルーンの dpi（作った画面の DPI）の分も掛ける
    val scale = minOf(density.density * LocalBalloonScale.current * skin.balloon.dpiFactor, maxWidthPx / candidates.maxOf { it.bitmap.width })
    val style = skin.textStyle(scale / density.density, scale, scopeId)
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
            if (candidates[i].look.vertical) {
                // 縦書きは列の長さを決めて組み、幅が収まるか見る
                val measured = subcompose("measure$i") { VerticalBalloonBody(items, choices, style, (area.height * scale).toInt()) }
                    .first().measure(Constraints())
                measured.width <= area.width * scale
            } else {
                val measured = subcompose("measure$i") { BalloonBody(items, choices, style) }
                    .first().measure(Constraints(minWidth = 0, maxWidth = (area.width * scale).toInt()))
                measured.height <= area.height * scale
            }
        } ?: candidates.lastIndex
        val image = candidates[chosen]
        val placeable = subcompose("balloon") {
            SkinFrame(image, skin, scopeId, scale, scroll, onlineFrames, labels, style, clickWait, sstp, onDrag, fixed = { FixedBalloonImages(items, style) }) {
                if (image.look.vertical) VerticalBalloonBody(items, choices, style, (image.area.height * scale).toInt()) else BalloonBody(items, choices, style)
            }
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
    scopeId: Int,
    scale: Float,
    scroll: ScrollState,
    onlineFrames: List<ImageBitmap>,
    labels: BalloonLabels,
    style: BalloonTextStyle,
    clickWait: Boolean,
    sstp: Boolean,
    onDrag: BalloonDrag?,
    fixed: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    fun px(v: Int) = with(density) { (v * scale).toDp() }
    val w = image.bitmap.width
    val h = image.bitmap.height
    // 画像ごとの balloons0s.txt で上書きした位置を使う
    val look = image.look
    /** 印などの画像パーツの位置。overlay_outside_balloon が 1 でなければ、バルーンの画像からはみ出さないようにする */
    fun inside(x: Int, y: Int, pw: Int, ph: Int): Pair<Int, Int> =
        if (look.overlayOutside) x to y else x.coerceIn(0, maxOf(0, w - pw)) to y.coerceIn(0, maxOf(0, h - ph))
    // 読み返せるほど長いときは、文字の範囲（バルーン画像のピクセルを縮めた分）の外が縁
    val area = image.area
    val hits = remember { ChoiceRouter() }
    Box(
        Modifier
            .size(px(w), px(h))
            .balloonDrag(onDrag, hits, { scroll.maxValue > 0 }) { p, _ ->
                p.x >= area.left * scale && p.x <= area.right * scale && p.y >= area.top * scale && p.y <= area.bottom * scale
            }
            .balloonCursor(),
    ) {
        Image(image.bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.size(px(w), px(h)))
        if (onlineFrames.isNotEmpty()) {
            val frame by produceState(0, onlineFrames) {
                while (true) {
                    delay(skin.balloon.onlineInterval)
                    value = (value + 1) % onlineFrames.size
                }
            }
            val bmp = onlineFrames[frame.coerceIn(0, onlineFrames.lastIndex)]
            val (x, y) = look.onlinePosition(w, h).let { (ox, oy) -> inside(ox, oy, bmp.width, bmp.height) }
            Image(
                bmp,
                contentDescription = "オンライン",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.offset(px(x), px(y)).size(px(bmp.width), px(bmp.height)),
            )
        }
        val b = look
        // SSTP で届いたスクリプトの印（sstp_new.png など）
        if (sstp) skin.sstpMarker(scopeId)?.let { mark ->
            val (x, y) = b.sstpMarkerPosition(w, h).let { (sx, sy) -> inside(sx, sy, mark.width, mark.height) }
            Image(mark, contentDescription = "SSTP", contentScale = ContentScale.FillBounds, modifier = Modifier.offset(px(x), px(y)).size(px(mark.width), px(mark.height)))
        }
        labels.marker?.let { marker ->
            val (x, y) = b.markerPosition(w, h) ?: (image.area.left to maxOf(0, image.area.top - b.fontHeight - 2))
            // sstpmessage.xr があれば、そこで切る
            val end = b.markerEnd(w)?.takeIf { it > x }
            BalloonLabel(
                marker, b.markerFontHeight, b.markerColor, style,
                Modifier.offset(px(x), px(y)).let { m -> if (end != null) m.width(px(end - x)) else m }, b.markerFontNames,
            )
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
                b.numberFontNames,
            )
        }
        // 縦書きは列が右から左へ増えるので、横にスクロールする（右端が始まり）
        val vertical = look.vertical
        Box(
            Modifier
                .offset(px(image.area.left), px(image.area.top))
                .size(px(image.area.width), px(image.area.height))
                .let { if (vertical) it.horizontalScroll(scroll, reverseScrolling = true) else it.verticalScroll(scroll) },
            contentAlignment = if (vertical) Alignment.TopEnd else Alignment.TopStart,
        ) {
            // \_l[centerx,centery] の基準（文字を書き始める位置から見た、バルーンの画像の真ん中）
            val center = Offset((image.bitmap.width / 2f - image.area.left) * scale, (image.bitmap.height / 2f - image.area.top) * scale)
            CompositionLocalProvider(LocalBalloonCenter provides center, LocalChoiceHits provides hits) { content() }
        }
        // スクロールしても動かない画像（\_b の --option=fixed）
        Box(Modifier.offset(px(image.area.left), px(image.area.top)).size(px(image.area.width), px(image.area.height))) { fixed() }
        val page = ((if (vertical) image.area.width else image.area.height) * scale * 0.8f).toInt()
        // クリック待ちの印（clickwait.png など）。続きの矢印が出ているときは重ならないよう矢印を優先する
        val more = scroll.value < scroll.maxValue
        if (clickWait && !more) skin.clickWait(scopeId)?.let { mark ->
            val (x, y) = b.clickWaitPosition(w, h, mark.width, mark.height)
            Image(mark, contentDescription = "クリック待ち", contentScale = ContentScale.FillBounds, modifier = Modifier.offset(px(x), px(y)).size(px(mark.width), px(mark.height)))
        }
        for ((which, visible) in listOf(0 to (scroll.value > 0), 1 to more)) {
            val arrow = skin.arrow(which, scopeId) ?: continue
            if (!visible) continue
            val (x, y) = b.arrowPosition(which, w, h, arrow.width, arrow.height)
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
    // \_b[ファイル,x,y] の画像は文字の下（--option=foreground なら上）に置く。fixed のものはスクロールの外（[FixedBalloonImages]）
    val placed = BalloonLayout.placedImages(items).filterNot { it.options.fixed }
    Box {
        PlacedPictures(placed.filterNot { it.options.foreground }, style)
        val segments = BalloonLayout.segments(items)
        if (segments.size == 1) BalloonLines(items, choices, style) else LocatedLines(segments, choices, style)
        PlacedPictures(placed.filter { it.options.foreground }, style)
    }
}

/**
 * \_l で置いた文は重なることがあり、上の文の箱が下の文の選択肢をふさぐ。そこで、選択肢を押したかどうかは文ごとではなく
 * まとまり全体で受け、指の下に選択肢がある文（上に重なっているものから）に渡す
 */
private class ChoiceRouter {
    class Target(
        val layout: () -> TextLayoutResult?,
        val ranges: () -> List<IntRange>,
        val runs: () -> List<BalloonLayout.Run.ChoiceRun>,
        val setPressed: (Int?) -> Unit,
    ) {
        var coordinates: LayoutCoordinates? = null
    }

    val targets = ArrayList<Target>()

    /** [position]（[from] の中の位置）の下にある選択肢。上に重なっている文から探す */
    fun find(from: LayoutCoordinates, position: Offset, slop: Float): Pair<Target, Int>? {
        for (t in targets.asReversed()) {
            val c = t.coordinates?.takeIf { it.isAttached } ?: continue
            val local = c.localPositionOf(from, position)
            if (local.x < 0 || local.y < 0 || local.x > c.size.width || local.y > c.size.height) continue
            val l = t.layout() ?: continue
            val k = choiceAt(l, t.ranges(), local, slop)
            if (k >= 0) return t to k
        }
        return null
    }
}

private val LocalChoiceRouter = staticCompositionLocalOf<ChoiceRouter?> { null }

/** バルーン1つの中の文の選択肢。バルーンを動かすとき、選択肢の上で触れたかを見る（[balloonDrag]） */
private val LocalChoiceHits = staticCompositionLocalOf<ChoiceRouter?> { null }

/** [position] の下の選択肢の番号（無ければ -1）。文字の少し外（[slop]）まで押せる */
private fun choiceAt(l: TextLayoutResult, ranges: List<IntRange>, position: Offset, slop: Float): Int {
    val at = l.getOffsetForPosition(position)
    return ranges.indexOfFirst {
        at in it && it.last < l.layoutInput.text.length && l.getPathForRange(it.first, it.last + 1).getBounds().inflate(slop).contains(position)
    }
}

/** バルーンの画像の真ん中（文字を書き始める位置から、ピクセル）。\_l[centerx,centery] の基準。分からなければ null */
val LocalBalloonCenter = staticCompositionLocalOf<Offset?> { null }

/**
 * まとまりの最後の文字の後ろの位置（まとまりの左上から、ピクセル）。次の \_l[@…] の基準。
 * 文の組み方が変わらなければ組み直しの知らせは来ないので、まとまりごとに使い続ける
 */
private class Pen {
    /** 最後の文字の右 */
    var x: Float? = null

    /** 最後の行の、その文の中での上端と、まとまりの中での文の上端 */
    var lineTop: Float? = null
    var textTop: Int = 0

    /** 最後の文の行の数（1行に収まったか） */
    var lineCount: Int = 0

    val y: Float? get() = lineTop?.let { it + textTop }
}

/**
 * \_l[x,y] で分けたまとまりを、それぞれの座標から書く。まとまりの1行目は x から、2行目からは左端から。
 * x / y を省いた方は前のまとまりの最後の文字の後ろのまま、@ はそこからの相対。重なってもよい（SSP と同じ）。
 * 1行に収まるまとまりは、その幅だけの箱にして x に置く（同じ行の前のまとまりの選択肢を、上に重ねた箱でふさがない）。
 * 折り返すまとまりは、文字の範囲いっぱいの箱の1行目を字下げする
 */
@Composable
private fun LocatedLines(segments: List<BalloonLayout.Segment>, choices: ChoiceActions, style: BalloonTextStyle) {
    val density = LocalDensity.current
    val center = LocalBalloonCenter.current
    val pens = remember { HashMap<String, Pen>() }
    val router = remember { ChoiceRouter() }
    val area = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentChoices by rememberUpdatedState(choices)
    val longPress = LocalViewConfiguration.current.longPressTimeoutMillis
    val slop = with(density) { 6.dp.toPx() }
    val gestures = Modifier
        .onGloballyPositioned { area[0] = it }
        .pointerInput(router) {
            awaitEachGesture {
                // 選択肢の上で触れたときだけ受け取る（それ以外はバルーンを叩いたことにする）
                val down = awaitFirstDown(requireUnconsumed = false)
                val from = area[0] ?: return@awaitEachGesture
                val (target, k) = router.find(from, down.position, slop) ?: return@awaitEachGesture
                down.consume()
                target.setPressed(k)
                val choice = target.runs().getOrNull(k)?.choice ?: return@awaitEachGesture target.setPressed(null)
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
                target.setPressed(null)
            }
        }
    CompositionLocalProvider(LocalChoiceRouter provides router) { LocatedLayout(segments, choices, style, pens, density, center, gestures) }
}

@Composable
private fun LocatedLayout(
    segments: List<BalloonLayout.Segment>,
    choices: ChoiceActions,
    style: BalloonTextStyle,
    pens: HashMap<String, Pen>,
    density: androidx.compose.ui.unit.Density,
    center: Offset?,
    modifier: Modifier,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val pxPerImagePx = style.spPerPx.dp.toPx()
        val spacing = 2.dp.roundToPx()
        var penX = 0f
        var penY = 0f
        var bottom = 0
        var right = 0
        val placed = ArrayList<Triple<Placeable, Int, Int>>()
        segments.forEachIndexed { i, seg ->
            val em = with(density) { style.size(seg.style).sp.toPx() }
            val lineHeight = em * LINE_HEIGHT
            val x = seg.x?.resolve(em, lineHeight, pxPerImagePx, penX, center?.x ?: (width / 2f)) ?: penX
            val y = seg.y?.resolve(em, lineHeight, pxPerImagePx, penY, center?.y ?: penY) ?: penY
            // 左端より左・右端より右へは書けないので、字下げは文字の範囲に収める
            val indent = x.coerceIn(0f, if (width > 0) width.toFloat() else x.coerceAtLeast(0f))
            val top = y.roundToInt()
            val tightPen = pens.getOrPut("t$i") { Pen() }
            val tight = if (seg.lines.size == 1 && width > 0 && indent < width) {
                subcompose("t$i") { SegmentLines(seg.lines, choices, style, 0.sp, tightPen, fill = false) }
                    .first().measure(Constraints(maxWidth = (width - indent).roundToInt().coerceAtLeast(1)))
                    .takeIf { tightPen.lineCount <= 1 }
            } else {
                null
            }
            if (tight != null) {
                placed += Triple(tight, indent.roundToInt(), top)
                penX = indent + (tightPen.x ?: 0f)
                penY = y + (tightPen.y ?: 0f)
                // 改行で終わるまとまりの次は、次の行の左端から
                if (seg.breakAfter) {
                    penX = 0f
                    penY = (top + tight.height + spacing).toFloat()
                }
                bottom = maxOf(bottom, top + tight.height)
                right = maxOf(right, indent.roundToInt() + tight.width)
            } else {
                val pen = pens.getOrPut("f$i") { Pen() }
                val p = subcompose("f$i") { SegmentLines(seg.lines, choices, style, with(density) { indent.toSp() }, pen) }
                    .first().measure(Constraints(maxWidth = constraints.maxWidth))
                placed += Triple(p, 0, top)
                penX = pen.x ?: indent
                penY = y + (pen.y ?: 0f)
                if (seg.breakAfter) {
                    penX = 0f
                    penY = (top + p.height + spacing).toFloat()
                }
                bottom = maxOf(bottom, top + p.height)
                right = maxOf(right, p.width)
            }
        }
        layout(if (width > 0) width else right, bottom.coerceAtLeast(0)) {
            for ((p, left, top) in placed) p.place(left, top)
        }
    }
}

/** 1つのまとまりの行。1行目は [indent] だけ字下げし、最後の文字の後ろの位置を [pen] に書く。[fill] でなければ文の幅だけの箱にする */
@Composable
private fun SegmentLines(
    lines: List<BalloonLayout.Line>,
    choices: ChoiceActions,
    style: BalloonTextStyle,
    indent: TextUnit,
    pen: Pen,
    fill: Boolean = true,
) {
    val spacing = with(LocalDensity.current) { 2.dp.roundToPx() }
    if (lines.isEmpty()) {
        pen.x = null
        pen.lineTop = null
    }
    Layout(content = {
        lines.forEachIndexed { k, line ->
            BalloonLine(line, choices, style, if (k == 0) indent else 0.sp, if (k == lines.lastIndex) { l -> recordPen(l, pen) } else null, fill = fill)
        }
    }) { measurables, constraints ->
        val ps = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        var y = 0
        val tops = ps.mapIndexed { k, p ->
            val top = y
            y += p.height + if (k < ps.lastIndex) spacing else 0
            top
        }
        pen.textTop = tops.lastOrNull() ?: 0
        layout(ps.maxOfOrNull { it.width } ?: 0, y) { ps.forEachIndexed { k, p -> p.place(0, tops[k]) } }
    }
}

private fun recordPen(l: TextLayoutResult, pen: Pen) {
    if (l.lineCount == 0) return
    val last = l.lineCount - 1
    pen.x = l.getLineRight(last)
    pen.lineTop = l.getLineTop(last)
    pen.lineCount = l.lineCount
}

/** \_b[ファイル,x,y,--option=fixed] の画像。スクロールしても動かないよう、文字の範囲に重ねて置く */
@Composable
fun FixedBalloonImages(items: List<BalloonItem>, style: BalloonTextStyle) {
    val fixed = BalloonLayout.placedImages(items).filter { it.options.fixed }
    if (fixed.isNotEmpty()) Box { PlacedPictures(fixed, style) }
}

@Composable
private fun PlacedPictures(images: List<BalloonLayout.PlacedImage>, style: BalloonTextStyle) {
    for (img in images) {
        BalloonPicture(img.path, img.opaque, style.spPerPx, Modifier.offset((img.x * style.spPerPx).dp, (img.y * style.spPerPx).dp), img.options)
    }
}

@Composable
private fun BalloonLines(items: List<BalloonItem>, choices: ChoiceActions, style: BalloonTextStyle) {
    val lineHeightPx = with(LocalDensity.current) { (style.fontSize * LINE_HEIGHT).toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in BalloonLayout.lines(items)) BalloonLine(line, choices, style, lineHeightPx = lineHeightPx)
    }
}

/** 1行。[indent] は1行目の字下げ（\_l の x）、[onLayout] は文の組み方（最後の文字の位置を知るため） */
@Composable
private fun BalloonLine(
    line: BalloonLayout.Line,
    choices: ChoiceActions,
    style: BalloonTextStyle,
    indent: TextUnit = 0.sp,
    onLayout: ((TextLayoutResult) -> Unit)? = null,
    lineHeightPx: Float = with(LocalDensity.current) { (style.fontSize * LINE_HEIGHT).toPx() },
    fill: Boolean = true,
) {
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
            BalloonText(line.columns[0], choices, style, align, line.valign, if (fill) Modifier.fillMaxWidth() else Modifier, indent, onLayout)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, column) in line.columns.withIndex()) {
                    BalloonText(column, choices, style, align, line.valign, Modifier.weight(1f), if (k == 0) indent else 0.sp, onLayout.takeIf { k == line.columns.lastIndex })
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
    indent: TextUnit = 0.sp,
    onLayout: ((TextLayoutResult) -> Unit)? = null,
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
                    if (run.marked) {
                        // \![*] の印。バルーンに marker.png があれば画像で
                        val mark = style.markerImage
                        if (mark != null) {
                            val id = "mark$index"
                            appendInlineContent(id, "▸")
                            inlineContent[id] = InlineTextContent(
                                with(density) { Placeholder((mark.width * style.spPerPx).dp.toSp(), (mark.height * style.spPerPx).dp.toSp(), PlaceholderVerticalAlign.Center) },
                            ) { Image(mark, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.size((mark.width * style.spPerPx).dp, (mark.height * style.spPerPx).dp)) }
                            append(" ")
                        } else {
                            append("▸ ")
                        }
                    }
                    val look = lookOf(ranges.size)
                    val start = length
                    withStyle(SpanStyle(color = look.font, fontWeight = FontWeight.Medium, shadow = style.linkShadow(look))) { append(run.choice.title) }
                    ranges += start until length
                }
                is BalloonLayout.Run.Image -> {
                    val (w, h) = pictureSize(run.path, run.options) ?: return@forEachIndexed
                    val id = "img$index"
                    appendInlineContent(id, "□")
                    val imageAlign = when (valign) {
                        VerticalAlign.TOP -> PlaceholderVerticalAlign.Top
                        VerticalAlign.CENTER -> PlaceholderVerticalAlign.Center
                        else -> PlaceholderVerticalAlign.TextBottom
                    }
                    inlineContent[id] = InlineTextContent(
                        with(density) { Placeholder((w * style.spPerPx).dp.toSp(), (h * style.spPerPx).dp.toSp(), imageAlign) },
                    ) { BalloonPicture(run.path, run.opaque, style.spPerPx, Modifier, run.options) }
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
    val currentRuns by rememberUpdatedState(choiceRuns)
    // \_l のまとまりの中では、押したかどうかはまとまり全体で受ける（[ChoiceRouter]）
    val router = LocalChoiceRouter.current
    // マウスやペンが選択肢の上にあれば、選択肢のカーソル（バルーンの cursor など）にする
    val cursors = LocalBalloonCursors.current
    var overChoice by remember { mutableStateOf(false) }
    // バルーンを動かすとき、選択肢の上かを見る（[LocalChoiceHits]）
    val hits = LocalChoiceHits.current
    val target = remember(router, hits) {
        if (router != null || hits != null) ChoiceRouter.Target({ layout }, { currentRanges }, { currentRuns }, { pressed = it }) else null
    }
    if (target != null) {
        DisposableEffect(router, hits, target) {
            router?.targets?.add(target)
            hits?.targets?.add(target)
            onDispose {
                router?.targets?.remove(target)
                hits?.targets?.remove(target)
            }
        }
    }
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
        style = if (indent.value > 0f) style.base().copy(textIndent = TextIndent(firstLine = indent)) else style.base(),
        onTextLayout = {
            layout = it
            onLayout?.invoke(it)
        },
        modifier = modifier
            .padding(vertical = if (hasChoice) 2.dp else 0.dp)
            .drawBehind {
                val l = layout ?: return@drawBehind
                ranges.forEachIndexed { k, r ->
                    if (r.isEmpty() || r.last >= l.layoutInput.text.length) return@forEachIndexed
                    val look = lookOf(k)
                    if (look.square) {
                        val path = l.getPathForRange(r.first, r.last + 1)
                        // blendmethod があれば、その重ね方で塗る
                        look.brush?.let { drawPath(path, it, blendMode = look.blend ?: androidx.compose.ui.graphics.drawscope.DrawScope.DefaultBlendMode) }
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
            .let { m -> if (target != null) m.onGloballyPositioned { target.coordinates = it } else m }
            .let { m ->
                if (cursors == null || choiceRuns.isEmpty()) {
                    m
                } else {
                    m.pointerHoverIcon(if (overChoice) cursors.choice else cursors.arrow).pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Initial)
                                val c = e.changes.firstOrNull() ?: continue
                                if (c.type != PointerType.Mouse && c.type != PointerType.Stylus) continue
                                overChoice = e.type != PointerEventType.Exit &&
                                    (layout?.let { l -> choiceAt(l, currentRanges, c.position, stroke * 6) >= 0 } ?: false)
                            }
                        }
                    }
                }
            }
            .let { m -> if (router != null) m else m.pointerInput(choiceRuns) {
                if (choiceRuns.isEmpty()) return@pointerInput
                awaitEachGesture {
                    // 選択肢の上で触れたときだけ受け取る（それ以外はバルーンを叩いたことにする）
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val l = layout ?: return@awaitEachGesture
                    val k = choiceAt(l, currentRanges, down.position, stroke * 6)
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
            } },
    )
}

/** \_b の画像。[dpPerPx] は画像の 1px を何 dp で描くか。読めない・大きすぎる画像は出さない */
@Composable
internal fun BalloonPicture(path: String, opaque: Boolean, dpPerPx: Float, modifier: Modifier, options: BalloonImageOptions = BalloonImageOptions()) {
    val bitmap by produceState<ImageBitmap?>(null, path, opaque, options.selfAlpha, options.clip) {
        // 左上の色を透過色にする（opaque と --option=use_self_alpha なら抜かない）。PNG にアルファがあればそれを使う
        value = withContext(Dispatchers.IO) {
            val keep = opaque || options.selfAlpha
            ImageTransparency.load(java.io.File(path), null, selfAlpha = if (keep) true else null, colorKey = !keep)?.let { clipped(it, options.clip) }?.asImageBitmap()
        }
    }
    val bmp = bitmap ?: return
    // --scaling= の大きさ（負なら反転）
    val size = options.drawSize(bmp.width, bmp.height)
    Image(
        bmp, contentDescription = null, contentScale = ContentScale.FillBounds,
        modifier = modifier.size((size.width * dpPerPx).dp, (size.height * dpPerPx).dp).let {
            if (size.flipX || size.flipY) it.graphicsLayer { scaleX = if (size.flipX) -1f else 1f; scaleY = if (size.flipY) -1f else 1f } else it
        },
    )
}

/** --clipping= の範囲を切り抜く（画像からはみ出す分は削る） */
private fun clipped(bitmap: Bitmap, clip: BalloonImageOptions.Clip?): Bitmap {
    if (clip == null) return bitmap
    val l = clip.left.coerceIn(0, bitmap.width - 1)
    val t = clip.top.coerceIn(0, bitmap.height - 1)
    val r = clip.right.coerceIn(l + 1, bitmap.width)
    val b = clip.bottom.coerceIn(t + 1, bitmap.height)
    return Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
}

/** 文の中に置く画像の大きさ（切り抜きと拡大縮小の後。画像のピクセル）。読めなければ null */
internal fun pictureSize(path: String, options: BalloonImageOptions): Pair<Int, Int>? {
    val (w, h) = ImageTransparency.size(java.io.File(path)) ?: return null
    val clip = options.clip
    val cw = if (clip == null) w else (minOf(clip.right, w) - minOf(clip.left, w - 1)).coerceAtLeast(1)
    val ch = if (clip == null) h else (minOf(clip.bottom, h) - minOf(clip.top, h - 1)).coerceAtLeast(1)
    val size = options.drawSize(cw, ch)
    return size.width to size.height
}

/** インストール済みのバルーンを、画面に出せる形にしたもの。画像は使うときに読み込んで覚えておく */
class BalloonSkin(val balloon: Balloon) {
    /** [index] は balloons2.png の 2（\b[n] で選ぶときに使う）。[look] はその画像の balloons2s.txt で上書きした設定 */
    class Image(val bitmap: ImageBitmap, val area: TextArea, val index: Int, val look: Balloon)

    private val cache = HashMap<Int, List<Image>>()
    private val parts = HashMap<String, ImageBitmap?>()

    /** バルーンの画像を、use_self_alpha と paint_transparent_region_black に従って読む */
    private fun load(file: java.io.File): Bitmap? {
        val mode = balloon.selfAlpha
        return ImageTransparency.load(
            file, balloon.pnaFor(file), selfAlpha = mode != BalloonSelfAlpha.OFF, colorKey = mode != BalloonSelfAlpha.FULL,
            paintBlack = balloon.paintTransparentBlack,
        )
    }

    @Synchronized
    fun images(scope: Int): List<Image> = cache.getOrPut(scope) {
        // 矢印も一緒に読んでおく（描くときに読み込みで待たせない）
        arrow(0, scope)
        arrow(1, scope)
        balloon.images(scope).mapNotNull { img ->
            val bmp = load(img.file) ?: return@mapNotNull null
            val look = balloon.variant(img.file)
            Image(bmp.asImageBitmap(), look.textArea(bmp.width, bmp.height), img.index, look)
        }
    }

    /** 画像のパーツ（矢印・印など）。読んだものは覚えておく */
    @Synchronized
    private fun part(key: String, file: () -> java.io.File?): ImageBitmap? = parts.getOrPut(key) { file()?.let(::load)?.asImageBitmap() }

    /** \![*] の印（marker.png など）。無いバルーンなら null */
    fun marker(scope: Int): ImageBitmap? = part("marker$scope") { balloon.markerImage(scope) }

    /** クリック待ちの印（clickwait.png など） */
    fun clickWait(scope: Int): ImageBitmap? = part("clickwait$scope") { balloon.clickWaitImage(scope) }

    /** SSTP の印（sstp_new.png / sstp.png など） */
    fun sstpMarker(scope: Int): ImageBitmap? = part("sstp$scope") { balloon.sstpMarkerImage(scope) }

    private val communicates = HashMap<Pair<Boolean, Int?>, Image?>()

    /**
     * 入力の箱の画像（balloonc0.png、[multiLine] なら balloonc5.png。--balloon= の [id] があれば balloonc[id].png）。
     * [Image.area] は入力欄の範囲。無いバルーンか読めなければ null
     */
    @Synchronized
    fun communicate(multiLine: Boolean, id: Int? = null): Image? = communicates.getOrPut(multiLine to id) {
        val file = balloon.communicateImage(multiLine, id) ?: return@getOrPut null
        // 入力の箱は use_input_alpha が 1 のときだけバルーンと同じ透明度の扱いにする（0 なら画像のアルファをそのまま）
        val bmp = (if (balloon.useInputAlpha) load(file) else ImageTransparency.load(file, balloon.pnaFor(file), selfAlpha = null)) ?: return@getOrPut null
        Image(bmp.asImageBitmap(), balloon.communicateBox(file, bmp.width, bmp.height), 0, balloon.variant(file))
    }

    /** スクロールの矢印（0 が上、1 が下）。arrow.filename とキャラクターごとの arrows / arrowk / arrowpN。無いバルーンなら null */
    fun arrow(which: Int, scope: Int = 0): ImageBitmap? = part("arrow$which.$scope") { balloon.arrowImage(which, scope) }

    private val online = HashMap<Int, List<ImageBitmap>>()

    /** オンラインの印のコマ（online0.png…）。無いバルーンなら空 */
    @Synchronized
    fun onlineFrames(scope: Int): List<ImageBitmap> = online.getOrPut(scope) {
        balloon.onlineImages(scope).mapNotNull { load(it)?.asImageBitmap() }
    }

    /**
     * [dpPerPx] は画像の 1px が何 dp になるか、[pxPerPx] は画面の何 px になるか。字が小さくなりすぎないよう 12sp は残す。
     * font.name・影・太字などの既定もバルーンの descript.txt から取る
     */
    fun textStyle(dpPerPx: Float, pxPerPx: Float, scope: Int = 0): BalloonTextStyle {
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
            disabledFlags = listOf("bold", "italic", "underline", "strike").associateWith(balloon::disabledFlag),
            disabledFontFamily = balloon.disabledFontNames.takeIf { it.isNotEmpty() }?.let(BalloonFonts::family),
            disabledFontHeight = balloon.disabledFontHeight,
            markerImage = marker(scope),
        )
    }
}

/** 伺かの画像の透過。PNG にアルファがあればそのまま、無ければ .pna か左上の色で抜く */
object ImageTransparency {
    /** 1辺の上限。ゴーストの画像は信用しないので、細工された巨大な PNG でメモリを使い切らせない */
    const val MAX_SIDE = 4096
    const val MAX_PIXELS = 8_000_000L

    /**
     * @param selfAlpha true なら画像のアルファを使う（無ければ .pna か左上の色）、false なら使わない（.pna か左上の色）、
     * null なら画像にアルファがあればそれを、無ければ .pna か左上の色
     * @param colorKey false なら、アルファも .pna も無い画像を左上の色で抜かずに不透明のまま（use_self_alpha,full）
     * @param paintBlack seriko.paint_transparent_region_black。左上の色の画素を黒にしてから透明度を掛けるか
     * （null なら .pna のときだけ黒にする）
     */
    fun load(file: java.io.File, pna: java.io.File?, selfAlpha: Boolean?, colorKey: Boolean = true, paintBlack: Boolean? = null): Bitmap? {
        if (!fits(file)) return null
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        if (selfAlpha != false && bmp.hasAlpha()) {
            if (paintBlack == true) paintKeyBlack(bmp)
            return bmp
        }
        // selfAlpha が true でもアルファの無い画像は、.pna か左上の色で抜く
        if (selfAlpha == true && !bmp.hasAlpha() && pna == null && !colorKey) return bmp
        val pnaBmp = pna?.takeIf(::fits)?.let { BitmapFactory.decodeFile(it.path) }
        when {
            pnaBmp != null && pnaBmp.width == bmp.width && pnaBmp.height == bmp.height -> {
                if (paintBlack != false) paintKeyBlack(bmp)
                applyPna(bmp, pnaBmp)
            }
            colorKey -> applyColorKey(bmp)
        }
        return bmp
    }

    /** 左上の色と同じ色の画素を黒にする（透明度はそのまま） */
    private fun paintKeyBlack(bmp: Bitmap) {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val key = px[0] and 0x00FFFFFF
        for (i in px.indices) {
            if ((px[i] and 0x00FFFFFF) == key) px[i] = px[i] and 0xFF000000.toInt()
        }
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
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
