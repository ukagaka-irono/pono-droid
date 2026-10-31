package net.pono.droid.overlay

import net.pono.baseware.sakura.BalloonImageOptions
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.pono.baseware.balloon.VerticalText
import net.pono.baseware.ghost.BalloonItem
import net.pono.baseware.ghost.BalloonLayout
import net.pono.baseware.sakura.TextAlign
import net.pono.baseware.sakura.TextStyle
import net.pono.baseware.sakura.VerticalAlign
import kotlin.math.roundToInt

/**
 * 縦書きの1マス。[line] は行の番号（行が替わったら列も替える）、[choice] は選択肢の番号。
 * [marker] は \![*] の印、[first] / [last] は選択肢の最初と最後のマス（囲みの上と下の線を引く）
 */
private sealed interface Cell {
    val line: Int

    data class Glyph(
        override val line: Int,
        val text: String,
        val style: TextStyle?,
        val choice: Int?,
        val rotate: Boolean,
        val small: Boolean,
        val marker: Boolean = false,
        val first: Boolean = false,
        val last: Boolean = false,
    ) : Cell
    data class Picture(override val line: Int, val path: String, val opaque: Boolean, val options: BalloonImageOptions = BalloonImageOptions()) : Cell
}

/** \_l で分けたまとまり1つと、その行の番号 */
private class Block(val segment: BalloonLayout.Segment, val lines: IntRange)

/**
 * バルーンの vertical,1 の縦書き。字を上から下へ送り、列を右から左へ送る。列の長さは [columnLength]（px）で、
 * それを越えたら次の列にする。\f[align] は列の中の寄せ（left が上、right が下）、\f[valign] は列の厚み方向の寄せ
 * （top が右、bottom が左。書いていなければ真ん中）。句読点やかっこは縦書き用の字形にし、長音や英数字は回す。
 * 下線は列の右に、取り消し線は字の真ん中に縦に引く。
 *
 * \_l[x,y] の座標の原点は文字の範囲の右上（[areaWidth] が文字の範囲の幅）で、x は右、y は下が正。x は列の右の端、
 * y は字の上の端になる。省いた方と @ の基準は、前のまとまりの最後の字の下（改行で終わったなら、次の列の頭）。
 *
 * 選択肢は押すと選び、長押しするとカーソルを乗せたことにして（OnChoiceEnter / OnChoiceHover）、離すと選ばずに外れる
 */
@Composable
fun VerticalBalloonBody(items: List<BalloonItem>, choices: ChoiceActions, style: BalloonTextStyle, columnLength: Int, areaWidth: Int) {
    val cells = ArrayList<Cell>()
    val choiceList = ArrayList<BalloonLayout.Run.ChoiceRun>()
    val lines = ArrayList<BalloonLayout.Line>()
    val lineCells = ArrayList<IntRange>()
    val blocks = BalloonLayout.segments(items).map { seg ->
        val firstLine = lines.size
        for (line in seg.lines) {
            val li = lines.size
            lines += line
            val start = cells.size
            for (run in line.columns.flatten()) {
                when (run) {
                    is BalloonLayout.Run.Text -> for (ch in VerticalText.split(run.text)) {
                        val g = if (ch.length == 1) VerticalText.glyph(ch[0]) else null
                        cells += Cell.Glyph(li, g?.text ?: ch, run.style, null, g?.rotate == true, g?.small == true)
                    }
                    is BalloonLayout.Run.ChoiceRun -> {
                        val k = choiceList.size
                        choiceList += run
                        val from = cells.size
                        if (run.marked) cells += Cell.Glyph(li, "▼", null, k, rotate = false, small = false, marker = true)
                        for (ch in VerticalText.split(run.choice.title)) {
                            val g = if (ch.length == 1) VerticalText.glyph(ch[0]) else null
                            cells += Cell.Glyph(li, g?.text ?: ch, null, k, g?.rotate == true, g?.small == true)
                        }
                        if (cells.size > from) {
                            cells[from] = (cells[from] as Cell.Glyph).copy(first = true)
                            cells[cells.lastIndex] = (cells[cells.lastIndex] as Cell.Glyph).copy(last = true)
                        }
                    }
                    is BalloonLayout.Run.Image -> cells += Cell.Picture(li, run.path, run.opaque, run.options)
                }
            }
            lineCells += start until cells.size
        }
        Block(seg, firstLine until lines.size)
    }
    var pressed by remember(items) { mutableStateOf<Int?>(null) }
    val density = LocalDensity.current
    val center = LocalBalloonCenter.current
    val gap = with(density) { (style.fontSize.toPx() * 0.4f).roundToInt() }
    Layout(
        content = {
            for (cell in cells) {
                val valign = lines[cell.line].valign
                when (cell) {
                    is Cell.Glyph -> VerticalGlyphCell(cell, choiceList, choices, style, valign, pressed) { pressed = it }
                    // 読めない（まだ読めていない）画像は何も出さないので、Box で包んで cell と measurable の数をそろえる
                    is Cell.Picture -> Box(contentAlignment = thicknessAlignment(valign)) {
                        BalloonPicture(cell.path, cell.opaque, style.spPerPx, Modifier, cell.options)
                    }
                }
            }
        },
    ) { measurables, _ ->
        // 列の幅に合わせてマスを広げるので、先に字の大きさだけを聞いて列に分ける
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val heights = measurables.mapIndexed { i, m -> m.maxIntrinsicHeight(widths[i]) }
        val cellWidth = style.fontSize.roundToPx()
        val pxPerImagePx = style.spPerPx.dp.toPx()

        /** 1列。[right] は右の端（原点は文字の範囲の右上で、左は負）、[top] は最初の字の上の端 */
        class Column(val right: Float, val top: Int, val line: Int) {
            val cells = ArrayList<Int>()
            var width = 0
            var height = 0
            val left: Float get() = right - width
        }
        val columns = ArrayList<Column>()
        var penX = 0f
        var penY = 0f
        for (b in blocks) {
            val em = style.size(b.segment.style).sp.toPx()
            val lineHeight = em * LINE_HEIGHT
            var right = (b.segment.x?.resolve(em, lineHeight, pxPerImagePx, penX, center?.let { it.x - areaWidth } ?: (-areaWidth / 2f)) ?: penX)
                .coerceAtMost(0f)
            var top = (b.segment.y?.resolve(em, lineHeight, pxPerImagePx, penY, center?.y ?: penY) ?: penY).coerceAtLeast(0f)
            var col: Column? = null
            for (li in b.lines) {
                // 行が替わったら次の列の頭から
                col?.let {
                    right = it.left - gap
                    top = 0f
                }
                var c = Column(right, top.roundToInt(), li).also { columns += it }
                col = c
                // 何も無い行（改行だけ）も1列分空ける
                if (lineCells[li].isEmpty()) c.width = cellWidth
                for (i in lineCells[li]) {
                    // 列の長さを越えたら次の列（列の頭からはみ出すほど大きい字でも、1字は置く）
                    if (c.cells.isNotEmpty() && c.top + c.height + heights[i] > columnLength) {
                        c = Column(c.left - gap, 0, li).also { columns += it }
                        col = c
                    }
                    c.cells += i
                    c.width = maxOf(c.width, widths[i])
                    c.height += heights[i]
                }
            }
            val last = col
            when {
                last == null -> {
                    penX = right
                    penY = top
                }
                b.segment.breakAfter -> {
                    penX = last.left - gap
                    penY = 0f
                }
                else -> {
                    penX = last.right
                    penY = (last.top + last.height).toFloat()
                }
            }
        }
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        for (c in columns) for (i in c.cells) placeables[i] = measurables[i].measure(Constraints(minWidth = c.width, maxWidth = c.width))
        val total = (-(columns.minOfOrNull { it.left } ?: 0f)).roundToInt().coerceAtLeast(0)
        // \f[align] の寄せは列の長さの中で
        val aligned = columns.any { it.top == 0 && lines[it.line].align != TextAlign.LEFT }
        val bottom = columns.maxOfOrNull { c -> c.top + c.cells.sumOf { placeables[it]?.height ?: 0 } } ?: 0
        val height = if (aligned) maxOf(bottom, columnLength) else bottom
        layout(total, height.coerceAtLeast(0)) {
            for (c in columns) {
                val x = (total + c.left).roundToInt()
                val length = c.cells.sumOf { placeables[it]?.height ?: 0 }
                var y = if (c.top != 0) {
                    c.top
                } else {
                    when (lines[c.line].align) {
                        TextAlign.CENTER -> (height - length) / 2
                        TextAlign.RIGHT -> height - length
                        TextAlign.LEFT -> 0
                    }.coerceAtLeast(0)
                }
                for (i in c.cells) {
                    val p = placeables[i] ?: continue
                    p.place(x, y)
                    y += p.height
                }
            }
        }
    }
}

/** \f[valign] の縦書きでの寄せ（列の厚み方向。top は右、bottom は左、書いていなければ真ん中） */
private fun thicknessAlignment(valign: VerticalAlign?): Alignment = when (valign) {
    VerticalAlign.TOP -> Alignment.CenterEnd
    VerticalAlign.BOTTOM -> Alignment.CenterStart
    else -> Alignment.Center
}

/**
 * 縦書きの1字。マスは列の幅いっぱいで、字は [valign] で寄せる。回す字は 90° 回し、小さい仮名は右上に寄せる。
 * 選択肢の字は、押している間は「選択中」の見た目にする
 */
@Composable
private fun VerticalGlyphCell(
    cell: Cell.Glyph,
    choiceList: List<BalloonLayout.Run.ChoiceRun>,
    choices: ChoiceActions,
    style: BalloonTextStyle,
    valign: VerticalAlign?,
    pressed: Int?,
    onPress: (Int?) -> Unit,
) {
    val k = cell.choice
    val choice = k?.let(choiceList::getOrNull)
    val look = choice?.let { style.links.resolve(it.looks, it.choice.anchor, selected = pressed == k, visited = it.choice.id in choices.visited) }
    val span = if (look == null && cell.style != null && cell.style != TextStyle.Default) style.span(cell.style) else null
    val base = style.base()
    // 下線と取り消し線は字の向きに合わせて自分で引く
    val decoration = span?.textDecoration ?: base.textDecoration
    val underline = look?.underline == true || decoration?.contains(TextDecoration.Underline) == true
    val strike = decoration?.contains(TextDecoration.LineThrough) == true
    val text = buildAnnotatedString {
        when {
            look != null -> withStyle(SpanStyle(color = look.font, fontWeight = FontWeight.Medium, shadow = style.linkShadow(look), textDecoration = TextDecoration.None)) {
                append(cell.text)
            }
            span != null -> withStyle(span.copy(textDecoration = TextDecoration.None)) { append(cell.text) }
            else -> append(cell.text)
        }
    }
    val lineColor = look?.let { it.pen ?: it.font } ?: span?.color?.takeIf { it.isSpecified } ?: style.textColor
    val density = LocalDensity.current
    val size = with(density) { style.fontSize.toDp() }
    val stroke = with(density) { 1.dp.toPx() }
    val currentChoices by rememberUpdatedState(choices)
    val currentPress by rememberUpdatedState(onPress)
    val cursors = LocalBalloonCursors.current
    var modifier: Modifier = Modifier
        .drawBehind {
            if (look == null || !look.square) return@drawBehind
            // 選択肢の囲み。続くマスと合わせて1つの枠にする
            look.brush?.let { drawRect(it, blendMode = look.blend ?: DrawScope.DefaultBlendMode) }
            look.pen?.let { pen ->
                drawLine(pen, Offset(stroke / 2, 0f), Offset(stroke / 2, this.size.height), stroke)
                drawLine(pen, Offset(this.size.width - stroke / 2, 0f), Offset(this.size.width - stroke / 2, this.size.height), stroke)
                if (cell.first) drawLine(pen, Offset(0f, stroke / 2), Offset(this.size.width, stroke / 2), stroke)
                if (cell.last) drawLine(pen, Offset(0f, this.size.height - stroke / 2), Offset(this.size.width, this.size.height - stroke / 2), stroke)
            }
        }
        .drawWithContent {
            drawContent()
            if (underline) drawLine(lineColor, Offset(this.size.width - stroke, 0f), Offset(this.size.width - stroke, this.size.height), stroke)
            if (strike) drawLine(lineColor, Offset(this.size.width / 2, 0f), Offset(this.size.width / 2, this.size.height), stroke)
        }
    if (choice != null && k != null) {
        if (cursors != null) modifier = modifier.pointerHoverIcon(cursors.choice)
        modifier = modifier.pointerInput(choice.choice, k) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                currentPress(k)
                var held = false
                val up = try {
                    withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                } catch (e: PointerEventTimeoutCancellationException) {
                    held = true
                    null
                }
                if (held) {
                    // 長押しはカーソルを乗せたのと同じ。離したら外れる（選ばない）
                    currentChoices.onHover(choice.choice)
                    waitForUpOrCancellation()?.consume()
                    currentChoices.onHover(null)
                } else if (up != null) {
                    up.consume()
                    currentChoices.onChoice(choice.choice)
                }
                currentPress(null)
            }
        }
    }
    Box(modifier, contentAlignment = thicknessAlignment(valign)) {
        val mark = style.markerImage
        if (cell.marker && mark != null) {
            // \![*] の印。バルーンに marker.png があれば画像で
            Image(mark, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.size((mark.width * style.spPerPx).dp, (mark.height * style.spPerPx).dp))
            return@Box
        }
        Text(
            text,
            fontSize = style.fontSize,
            color = style.textColor,
            style = base.copy(textDecoration = TextDecoration.None),
            softWrap = false,
            maxLines = 1,
            modifier = when {
                cell.rotate -> Modifier.rotate(90f).offset(y = 1.dp)
                cell.small -> Modifier.offset(x = size * 0.12f, y = -size * 0.12f)
                else -> Modifier
            },
        )
    }
}

/**
 * 縦書きのバルーンの印の文字と数（\![set,balloonmarker] / \![set,balloonnum]）。上から下へ1字ずつ置き、
 * 英数字は回す。[modifier] で置く場所と、切る長さを決める
 */
@Composable
internal fun VerticalLabel(text: String, fontSize: TextUnit, color: Color, family: FontFamily?, modifier: Modifier) {
    val size = with(LocalDensity.current) { fontSize.toDp() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for (ch in VerticalText.split(text)) {
            val g = if (ch.length == 1) VerticalText.glyph(ch[0]) else null
            Text(
                g?.text ?: ch, fontSize = fontSize, color = color, fontFamily = family, maxLines = 1, softWrap = false,
                modifier = when {
                    g?.rotate == true -> Modifier.rotate(90f)
                    g?.small == true -> Modifier.offset(x = size * 0.12f, y = -size * 0.12f)
                    else -> Modifier
                },
            )
        }
    }
}
