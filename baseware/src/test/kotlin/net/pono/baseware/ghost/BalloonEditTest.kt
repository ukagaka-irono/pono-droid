package net.pono.baseware.ghost

import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.TextStyle
import net.pono.baseware.sakura.VerticalAlign
import org.junit.Assert.assertEquals
import org.junit.Test

/** \c[char,...] / \c[line,...] でバルーンの一部を消す */
class BalloonEditTest {
    private fun text(s: String, style: TextStyle = TextStyle.Default) = BalloonItem.Text(s, style)

    @Test
    fun removesCharactersFromTheEnd() {
        // ukadoc の例: トークを3文字削除\c[char,3]削除終了。
        val items = listOf(text("トークを3文字削除"))
        assertEquals(listOf(text("トークを3文")), BalloonEdit.removeChars(items, 3, null))
        // 飾りの違う文をまたいでも、出てきた順に数える
        val styled = listOf(text("ab"), text("cd", TextStyle(bold = true)), BalloonItem.NewLine, text("e"))
        assertEquals(listOf(text("ab"), text("c", TextStyle(bold = true)), BalloonItem.NewLine), BalloonEdit.removeChars(styled, 2, null))
        // 多すぎれば全部
        assertEquals(listOf(BalloonItem.NewLine), BalloonEdit.removeChars(styled, 99, null))
    }

    @Test
    fun removesCharactersFromAPosition() {
        // ukadoc の例: トークの5文字目から、3文字削除\c[char,3,4]
        val items = listOf(text("トークの5文字目から、3文字削除"))
        assertEquals(listOf(text("トークの目から、3文字削除")), BalloonEdit.removeChars(items, 3, 4))
        // サロゲートペアは1文字
        assertEquals(listOf(text("a😀c")), BalloonEdit.removeChars(listOf(text("a😀bc")), 1, 2))
    }

    @Test
    fun inlineImagesAndChoicesCountAsOneCharacter() {
        val img = BalloonItem.Image("/x.png", null, null, false)
        val placed = BalloonItem.Image("/y.png", 3, 4, false)
        val choice = BalloonItem.ChoiceItem(Choice("はい", "OnYes"))
        val items = listOf(text("あ"), img, placed, choice, text("い"))
        // 位置を決めて置いた画像は文字に数えない
        assertEquals(listOf(text("あ"), placed), BalloonEdit.removeChars(items, 3, null))
        assertEquals(listOf(text("あ"), placed, text("い")), BalloonEdit.removeChars(items, 2, 1))
    }

    @Test
    fun removesLinesFromTheEnd() {
        val items = listOf(text("1行目"), BalloonItem.NewLine, text("2行目"), BalloonItem.NewLine, BalloonItem.NewLine, text("3行目"))
        // 空の行（\n\n の間）は数えない。消した行の頭にカーソルが戻る
        assertEquals(listOf(text("1行目"), BalloonItem.NewLine, text("2行目"), BalloonItem.NewLine, BalloonItem.NewLine), BalloonEdit.removeLines(items, 1, null))
        assertEquals(listOf(text("1行目"), BalloonItem.NewLine), BalloonEdit.removeLines(items, 2, null))
        assertEquals(emptyList<BalloonItem>(), BalloonEdit.removeLines(items, 5, null))
    }

    @Test
    fun removesLinesFromAPosition() {
        // ukadoc の例: トークを3行目から、\n1行削除。\nこの部分が\_l[,]削除される。\c[line,1,2]
        val items = listOf(
            text("トークを3行目から、"), BalloonItem.NewLine, text("1行削除。"), BalloonItem.NewLine,
            text("この部分が"), BalloonItem.Column, text("削除される。"),
        )
        assertEquals(
            listOf(text("トークを3行目から、"), BalloonItem.NewLine, text("1行削除。"), BalloonItem.NewLine, text("削除される。")),
            BalloonEdit.removeLines(items, 1, 2),
        )
        // \n[half] も行の区切り。行の中の飾りの指定も一緒に消える
        val half = listOf(text("a"), BalloonItem.LineBreak(50), BalloonItem.VAlign(VerticalAlign.TOP), text("b"), BalloonItem.NewLine, text("c"))
        assertEquals(listOf(text("a"), BalloonItem.LineBreak(50), text("c")), BalloonEdit.removeLines(half, 1, 1))
        // 範囲の外なら何もしない
        assertEquals(half, BalloonEdit.removeLines(half, 1, 9))
    }
}
