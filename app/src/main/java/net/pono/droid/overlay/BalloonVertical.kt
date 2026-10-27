package net.pono.droid.overlay

import net.pono.baseware.sakura.BalloonImageOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import net.pono.baseware.balloon.VerticalText
import net.pono.baseware.ghost.BalloonItem
import net.pono.baseware.ghost.BalloonLayout
import net.pono.baseware.sakura.TextAlign
import net.pono.baseware.sakura.TextStyle
import kotlin.math.roundToInt

/** 縦書きの1マス。[line] はスクリプトの行の番号（行が替わったら列も替える）、[choice] は選択肢の番号 */
private sealed interface Cell {
    val line: Int

    data class Glyph(override val line: Int, val text: String, val style: TextStyle?, val choice: Int?, val rotate: Boolean, val small: Boolean) : Cell
    data class Picture(override val line: Int, val path: String, val opaque: Boolean, val options: BalloonImageOptions = BalloonImageOptions()) : Cell
}

/**
 * バルーンの vertical,1 の縦書き。字を上から下へ送り、列を右から左へ送る。列の長さは [columnLength]（px）で、
 * それを越えたら次の列にする。\f[align] は列の中の寄せ（left が上、right が下）。句読点やかっこは縦書き用の字形にし、
 * 長音や英数字は回す。選択肢は押すと選ぶ（長押しのカーソルは無い）
 */
@Composable
fun VerticalBalloonBody(items: List<BalloonItem>, choices: ChoiceActions, style: BalloonTextStyle, columnLength: Int) {
    val lines = BalloonLayout.lines(items)
    val cells = ArrayList<Cell>()
    val choiceList = ArrayList<BalloonLayout.Run.ChoiceRun>()
    val aligns = lines.map { it.align }
    lines.forEachIndexed { li, line ->
        for (run in line.columns.flatten()) {
            when (run) {
                is BalloonLayout.Run.Text -> for (ch in VerticalText.split(run.text)) {
                    val g = if (ch.length == 1) VerticalText.glyph(ch[0]) else null
                    cells += Cell.Glyph(li, g?.text ?: ch, run.style, null, g?.rotate == true, g?.small == true)
                }
                is BalloonLayout.Run.ChoiceRun -> {
                    val k = choiceList.size
                    choiceList += run
                    if (run.marked) cells += Cell.Glyph(li, "▼", null, k, rotate = false, small = false)
                    for (ch in VerticalText.split(run.choice.title)) {
                        val g = if (ch.length == 1) VerticalText.glyph(ch[0]) else null
                        cells += Cell.Glyph(li, g?.text ?: ch, null, k, g?.rotate == true, g?.small == true)
                    }
                }
                is BalloonLayout.Run.Image -> cells += Cell.Picture(li, run.path, run.opaque, run.options)
            }
        }
    }
    val density = LocalDensity.current
    val gap = with(density) { (style.fontSize.toPx() * 0.4f).roundToInt() }
    Layout(
        content = {
            for (cell in cells) {
                when (cell) {
                    is Cell.Glyph -> VerticalGlyphCell(cell, choiceList, choices, style)
                    is Cell.Picture -> BalloonPicture(cell.path, cell.opaque, style.spPerPx, Modifier, cell.options)
                }
            }
        },
    ) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        // 列に分ける（行が替わるか、列の長さを越えたら次の列）
        data class Column(val start: Int, var end: Int, var width: Int, var height: Int)
        val columns = ArrayList<Column>()
        var lastLine = -1
        placeables.forEachIndexed { i, p ->
            val line = cells[i].line
            val current = columns.lastOrNull()
            if (current == null || line != lastLine || current.height + p.height > columnLength) {
                // 行の頭が列からはみ出すほど大きい字でも、1字は置く
                columns += Column(i, i + 1, p.width, p.height)
            } else {
                current.end = i + 1
                current.width = maxOf(current.width, p.width)
                current.height += p.height
            }
            lastLine = line
        }
        // 何も無い行（改行だけ）も1列分空ける
        val empty = lines.indices.count { li -> cells.none { it.line == li } }
        val cellWidth = with(density) { style.fontSize.roundToPx() }
        val total = columns.sumOf { it.width } + (columns.size - 1).coerceAtLeast(0) * gap + empty * (cellWidth + gap)
        val height = minOf(columnLength, columns.maxOfOrNull { it.height } ?: 0).coerceAtLeast(0)
        layout(total, height) {
            var right = total
            for (c in columns) {
                val x = right - c.width
                val align = aligns.getOrNull(cells[c.start].line) ?: TextAlign.LEFT
                var y = when (align) {
                    TextAlign.CENTER -> (height - c.height) / 2
                    TextAlign.RIGHT -> height - c.height
                    TextAlign.LEFT -> 0
                }.coerceAtLeast(0)
                for (i in c.start until c.end) {
                    val p = placeables[i]
                    p.place(x + (c.width - p.width) / 2, y)
                    y += p.height
                }
                right = x - gap
            }
        }
    }
}

/** 縦書きの1字。回す字は 90° 回し、小さい仮名は右上に寄せる。選択肢の字は押すと選ぶ */
@Composable
private fun VerticalGlyphCell(cell: Cell.Glyph, choiceList: List<BalloonLayout.Run.ChoiceRun>, choices: ChoiceActions, style: BalloonTextStyle) {
    val choice = cell.choice?.let(choiceList::getOrNull)
    val text = buildAnnotatedString {
        when {
            choice != null -> {
                val look = style.links.resolve(choice.looks, choice.choice.anchor, selected = false, visited = choice.choice.id in choices.visited)
                withStyle(SpanStyle(color = look.font, fontWeight = FontWeight.Medium, shadow = style.linkShadow(look))) { append(cell.text) }
            }
            cell.style != null && cell.style != TextStyle.Default -> withStyle(style.span(cell.style)) { append(cell.text) }
            else -> append(cell.text)
        }
    }
    val size = with(LocalDensity.current) { style.fontSize.toDp() }
    var modifier: Modifier = Modifier
    if (choice != null) modifier = modifier.clickable { choices.onChoice(choice.choice) }
    if (cell.small) modifier = modifier.offset(x = size * 0.12f, y = -size * 0.12f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = style.fontSize,
            color = style.textColor,
            style = style.base(),
            softWrap = false,
            maxLines = 1,
            modifier = if (cell.rotate) Modifier.rotate(90f).offset(y = 1.dp) else Modifier,
        )
    }
}
