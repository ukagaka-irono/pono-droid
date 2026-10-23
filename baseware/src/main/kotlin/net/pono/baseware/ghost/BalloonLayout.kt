package net.pono.baseware.ghost

import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.TextAlign
import net.pono.baseware.sakura.TextStyle

/**
 * バルーンの中身を「行 → 列 → 文字と選択肢の並び」に組み立てる。描くのはプラットフォーム側。
 * 選択肢やリンクは文の中に置いたまま（行を分けない）。\_l で横の位置を指定されたら同じ行の次の列にする。
 */
object BalloonLayout {
    sealed interface Run {
        data class Text(val text: String, val style: TextStyle = TextStyle.Default) : Run

        /** [marked] は \![*] の印を付けるか */
        data class ChoiceRun(val choice: Choice, val marked: Boolean) : Run
    }

    /** 1行。列が1つなら普通の行、2つ以上なら横に並べる。[align] は \f[align] の寄せ方 */
    data class Line(val columns: List<List<Run>>, val align: TextAlign = TextAlign.LEFT)

    fun lines(items: List<BalloonItem>): List<Line> {
        val lines = mutableListOf<Line>()
        var columns = mutableListOf<MutableList<Run>>(mutableListOf())
        var marker = false
        var align = TextAlign.LEFT

        fun current() = columns.last()
        fun endLine() {
            lines += Line(columns.map { it.toList() }, align)
            columns = mutableListOf(mutableListOf())
            marker = false
            align = TextAlign.LEFT
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
                BalloonItem.Marker -> marker = true
                // 行の頭での \_l は位置合わせだけなので、列は増やさない。\_l の後は左寄せに戻る（SSP と同じ）
                BalloonItem.Column -> {
                    if (current().isNotEmpty()) columns += mutableListOf<Run>()
                    align = TextAlign.LEFT
                }
                is BalloonItem.Align -> align = item.align
                is BalloonItem.ChoiceItem -> {
                    // 等幅で揃えるための空白が後ろに付いていることがあるので落とす
                    current() += Run.ChoiceRun(item.choice.copy(title = item.choice.title.trim()), marker)
                    marker = false
                }
            }
        }
        // 最後の \n の後ろの空行は出さない
        if (columns.size > 1 || current().isNotEmpty()) endLine()
        return lines
    }
}
