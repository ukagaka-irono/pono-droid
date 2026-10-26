package net.pono.baseware.balloon

/**
 * 縦書き（バルーンの vertical,1）で1文字をどう出すか。[text] は出す字（句読点やかっこは縦書き用の字形）、
 * [rotate] は 90° 回して出すもの（長音・ダッシュ・英数字など）、[small] は小さい仮名（列の右上に寄せる）
 */
data class VerticalGlyph(val text: String, val rotate: Boolean = false, val small: Boolean = false)

object VerticalText {
    /** 縦書き用の字形があるもの（Unicode の縦書き用の互換文字） */
    private val FORMS = mapOf(
        '、' to "︑", '。' to "︒", '，' to "︐", '．' to "︒", '：' to "︓", '；' to "︔", '！' to "︕", '？' to "︖",
        '「' to "﹁", '」' to "﹂", '『' to "﹃", '』' to "﹄", '（' to "︵", '）' to "︶", '｛' to "︷", '｝' to "︸",
        '〔' to "︹", '〕' to "︺", '【' to "︻", '】' to "︼", '《' to "︽", '》' to "︾", '〈' to "︿", '〉' to "﹀",
        '［' to "﹇", '］' to "﹈", '…' to "︙", '‥' to "︰", '〖' to "︗", '〗' to "︘",
    )

    /** 回して出す字（長音・波線・ダッシュ・矢印など、横に長いもの） */
    private val ROTATED = setOf('ー', '～', '〜', '―', '‐', '－', '—', '–', '=', '＝', '→', '←', '⇒', '-', '~', '(', ')', '[', ']', '{', '}', '<', '>')

    private const val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ"

    fun glyph(ch: Char): VerticalGlyph {
        FORMS[ch]?.let { return VerticalGlyph(it) }
        if (ch in ROTATED) return VerticalGlyph(ch.toString(), rotate = true)
        // 半角の英数字や記号（全角にしないもの）は、横書きのまま回す
        if (ch.code in 0x21..0x7E) return VerticalGlyph(ch.toString(), rotate = true)
        return VerticalGlyph(ch.toString(), small = ch in SMALL_KANA)
    }

    /** [text] を1文字ずつにする（サロゲートペアと結合文字は1文字のまま） */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != java.text.BreakIterator.DONE) {
            out += text.substring(start, end)
            start = end
            end = it.next()
        }
        return out
    }
}
