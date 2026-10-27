package net.pono.baseware.sakura

/**
 * \_l[x,y] の座標の片方。[unit] の単位の [value]（PX はバルーンの画像のピクセル、EM は文字の高さ、LH は行の高さ、
 * PERCENT は文字の高さの %、CENTER はバルーンの画像の真ん中）。[relative] なら今の文字の位置から（@）
 */
data class BalloonCoord(val value: Float, val unit: Unit, val relative: Boolean = false) {
    enum class Unit { PX, EM, LH, PERCENT, CENTER }

    companion object {
        private val NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)""")

        /** 書かれた座標を読む。省略（空）・読めないものは null（動かさない） */
        fun parse(raw: String?): BalloonCoord? {
            var s = raw?.trim()?.lowercase() ?: return null
            if (s.isEmpty()) return null
            if (s == "centerx" || s == "centery") return BalloonCoord(0f, Unit.CENTER)
            val relative = s.startsWith("@")
            if (relative) s = s.substring(1).trim()
            val unit = when {
                s.endsWith("em") -> Unit.EM
                s.endsWith("lh") -> Unit.LH
                s.endsWith("%") -> Unit.PERCENT
                else -> Unit.PX
            }
            val number = when (unit) {
                Unit.EM, Unit.LH -> s.dropLast(2)
                Unit.PERCENT -> s.dropLast(1)
                else -> s.removeSuffix("px")
            }.trim()
            if (!NUMBER.matches(number)) return null
            return BalloonCoord(number.toFloat(), unit, relative)
        }
    }

    /**
     * ピクセル（描くときの単位）にする。[em] は1文字の高さ、[lineHeight] は行の高さ、[pxPerImagePx] はバルーンの画像の 1 ピクセル、
     * [pen] は今の文字の位置（@ の基準）、[center] はバルーンの画像の真ん中（文字の始まりから）
     */
    fun resolve(em: Float, lineHeight: Float, pxPerImagePx: Float, pen: Float, center: Float): Float {
        val v = when (unit) {
            Unit.PX -> value * pxPerImagePx
            Unit.EM -> value * em
            Unit.LH -> value * lineHeight
            Unit.PERCENT -> value / 100f * em
            Unit.CENTER -> return center
        }
        return if (relative) pen + v else v
    }
}
