package net.pono.baseware.ghost

import net.pono.baseware.sakura.BalloonImageOptions
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ChoiceLooks
import net.pono.baseware.sakura.TextAlign
import net.pono.baseware.sakura.TextStyle
import net.pono.baseware.sakura.VerticalAlign

/**
 * バルーンの中身を「行 → 列 → 文字と選択肢の並び」に組み立てる。描くのはプラットフォーム側。
 * 選択肢やリンクは文の中に置いたまま（行を分けない）。\_l で横の位置を指定されたら同じ行の次の列にする。
 */
object BalloonLayout {
    sealed interface Run {
        data class Text(val text: String, val style: TextStyle = TextStyle.Default) : Run

        /** [marked] は \![*] の印を付けるか、[looks] は \f[cursor*] などの見た目 */
        data class ChoiceRun(val choice: Choice, val marked: Boolean, val looks: ChoiceLooks = ChoiceLooks.Default) : Run

        /** \_b[ファイル,inline]。文字の中に1文字として置く画像 */
        data class Image(val path: String, val opaque: Boolean, val options: BalloonImageOptions = BalloonImageOptions()) : Run
    }

    /**
     * 1行。列が1つなら普通の行、2つ以上なら横に並べる。[align] は \f[align] の寄せ方。
     * [gap] は前の行からの改行の幅（ふつうの改行に対する %。\n[half] なら 50）、[valign] は \f[valign]（null なら既定）
     */
    data class Line(
        val columns: List<List<Run>>,
        val align: TextAlign = TextAlign.LEFT,
        val gap: Int = 100,
        val valign: VerticalAlign? = null,
    )

    /** \_b[ファイル,x,y]。バルーンの x, y（文字の下）に置く画像 */
    data class PlacedImage(val path: String, val x: Int, val y: Int, val opaque: Boolean, val options: BalloonImageOptions = BalloonImageOptions())

    fun lines(items: List<BalloonItem>): List<Line> {
        val lines = mutableListOf<Line>()
        var columns = mutableListOf<MutableList<Run>>(mutableListOf())
        var marker = false
        var align = TextAlign.LEFT
        var gap = 100
        // \f[valign] は改行しても続く
        var valign: VerticalAlign? = null

        fun current() = columns.last()
        fun endLine(nextGap: Int = 100) {
            lines += Line(columns.map { it.toList() }, align, gap, valign)
            columns = mutableListOf(mutableListOf())
            marker = false
            align = TextAlign.LEFT
            gap = nextGap
        }

        for (item in items) {
            when (item) {
                is BalloonItem.Text -> {
                    val last = current().lastOrNull()
                    if (last is Run.Text && last.style == item.style) {
                        current()[current().lastIndex] = Run.Text(last.text + item.text, item.style)
                    } else {
                        current() += Run.Text(item.text, item.style)
                    }
                }
                BalloonItem.NewLine -> endLine()
                is BalloonItem.LineBreak -> endLine(item.percent)
                is BalloonItem.VAlign -> valign = item.align
                BalloonItem.Marker -> marker = true
                // 行の頭での \_l は位置合わせだけなので、列は増やさない。\_l の後は左寄せに戻る（SSP と同じ）
                BalloonItem.Column -> {
                    if (current().isNotEmpty()) columns += mutableListOf<Run>()
                    align = TextAlign.LEFT
                }
                is BalloonItem.Align -> align = item.align
                is BalloonItem.ChoiceItem -> {
                    // 等幅で揃えるための空白が後ろに付いていることがあるので落とす
                    current() += Run.ChoiceRun(item.choice.copy(title = item.choice.title.trim()), marker, item.looks)
                    marker = false
                }
                is BalloonItem.Image -> if (item.x == null || item.y == null) current() += Run.Image(item.path, item.opaque, item.options)
            }
        }
        // 最後の \n の後ろの空行は出さない
        if (columns.size > 1 || current().isNotEmpty()) endLine()
        return lines
    }

    /** 文字の中ではなく、位置を指定して置く画像（\_b[ファイル,x,y]） */
    fun placedImages(items: List<BalloonItem>): List<PlacedImage> =
        items.filterIsInstance<BalloonItem.Image>().mapNotNull { img ->
            if (img.x == null || img.y == null) null else PlacedImage(img.path, img.x, img.y, img.opaque, img.options)
        }
}
