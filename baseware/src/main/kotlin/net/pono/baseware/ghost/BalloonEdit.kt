package net.pono.baseware.ghost

/**
 * \c[char,...] / \c[line,...]。バルーンの中身の一部を消す。
 * 文字はスクリプトに出てきた順に数え、文の中に置いた画像（\_b[...,inline]）と選択肢は1文字とする
 */
object BalloonEdit {
    /** [start] が null なら最後の [count] 文字、あれば頭から [start] 文字目（0 から）から [count] 文字を消す */
    fun removeChars(items: List<BalloonItem>, count: Int, start: Int?): List<BalloonItem> {
        val total = items.sumOf(::units)
        val from = (start ?: (total - count)).coerceIn(0, total)
        val to = (from.toLong() + count).coerceAtMost(total.toLong()).toInt()
        if (from >= to) return items
        val out = mutableListOf<BalloonItem>()
        var pos = 0
        for (item in items) {
            val n = units(item)
            val itemFrom = pos
            pos += n
            if (n == 0 || pos <= from || itemFrom >= to) {
                out += item
                continue
            }
            // この要素の中で消す範囲（コードポイントで数える）
            if (item is BalloonItem.Text) {
                val cut = (from - itemFrom).coerceAtLeast(0) until (to - itemFrom).coerceAtMost(n)
                val kept = StringBuilder()
                var i = 0
                var cp = 0
                while (i < item.text.length) {
                    val len = Character.charCount(item.text.codePointAt(i))
                    if (cp !in cut) kept.append(item.text, i, i + len)
                    i += len
                    cp++
                }
                if (kept.isNotEmpty()) out += item.copy(text = kept.toString())
            }
            // 画像と選択肢は1文字なので、範囲にかかっていれば丸ごと消す
        }
        return out
    }

    /**
     * [start] が null なら最後の [count] 行（カーソルのある行から前へ）、あれば頭から [start] 行目（0 から）から [count] 行を消す。
     * 行は \n と \_l で区切ったもの。ただし \n で区切った中身の無いところは行に数えない
     */
    fun removeLines(items: List<BalloonItem>, count: Int, start: Int?): List<BalloonItem> {
        val lines = segments(items).filter { it.counts }
        if (lines.isEmpty()) return items
        return if (start == null) {
            // 消した行の頭にカーソルが戻るので、その前の区切りは残す
            val first = lines[(lines.size - count).coerceAtLeast(0)]
            items.subList(0, first.from)
        } else {
            val removed = lines.drop(start).take(count)
            if (removed.isEmpty()) return items
            // 行の後ろの区切りも一緒に消す（次の行が詰まる）
            val from = removed.first().from
            val to = removed.last().let { if (it.separator) it.to + 1 else it.to }.coerceAtMost(items.size)
            items.subList(0, from) + items.subList(to, items.size)
        }
    }

    /** 1行分。[from] から [to] の手前までが中身、[separator] なら [to] に区切り（\n か \_l）がある */
    private data class Segment(val from: Int, val to: Int, val separator: Boolean, val counts: Boolean)

    private fun segments(items: List<BalloonItem>): List<Segment> {
        val out = mutableListOf<Segment>()
        var from = 0
        var units = 0
        for ((i, item) in items.withIndex()) {
            val column = item is BalloonItem.Locate
            if (column || item is BalloonItem.NewLine || item is BalloonItem.LineBreak) {
                // \_l で区切ったところは中身が無くても行に数える
                out += Segment(from, i, separator = true, counts = units > 0 || column)
                from = i + 1
                units = 0
            } else {
                units += units(item)
            }
        }
        if (from < items.size) out += Segment(from, items.size, separator = false, counts = units > 0)
        return out
    }

    /** 文字として数える数。文はコードポイントの数、文の中の画像と選択肢は1 */
    private fun units(item: BalloonItem): Int = when (item) {
        is BalloonItem.Text -> item.text.codePointCount(0, item.text.length)
        is BalloonItem.ChoiceItem -> 1
        is BalloonItem.Image -> if (item.x == null || item.y == null) 1 else 0
        else -> 0
    }
}
