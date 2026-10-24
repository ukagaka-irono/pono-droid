package net.pono.droid.overlay

import androidx.compose.ui.graphics.Color
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ChoiceLooks
import net.pono.baseware.sakura.LinkLook

/** 選択肢とリンクへの操作（選ぶ・長押しでカーソルを乗せる）と、選んだことのあるリンクの ID */
data class ChoiceActions(
    val onChoice: (Choice) -> Unit,
    val onHover: (Choice?) -> Unit = {},
    val visited: Set<String> = emptySet(),
)

/** 選択肢かリンクを、いまの状態で描く見た目。[square] なら矩形（[brush] で塗り [pen] で囲む）、[underline] なら下線 */
class ResolvedLook(val square: Boolean, val underline: Boolean, val brush: Color?, val pen: Color?, val font: Color)

/**
 * バルーンの descript.txt の cursor.* / cursor.notselect.* / anchor.* / anchor.notselect.* / anchor.visited.*。
 * 書いていないところは pono-droid の既定（押している選択肢は薄く塗り、リンクは下線、文字はどちらも [choiceColor] / [anchorColor]）
 */
data class LinkDefaults(
    val choiceColor: Color,
    val anchorColor: Color = choiceColor,
    val cursor: LinkLook = LinkLook(),
    val cursorNotSelect: LinkLook = LinkLook(),
    val anchor: LinkLook = LinkLook(),
    val anchorNotSelect: LinkLook = LinkLook(),
    val anchorVisited: LinkLook = LinkLook(),
) {
    /** スクリプトの \f[cursor*] などを先に、無ければバルーンの指定を使う */
    fun resolve(script: ChoiceLooks, isAnchor: Boolean, selected: Boolean, visited: Boolean): ResolvedLook {
        val look = when {
            !isAnchor && selected -> script.cursor.or(cursor)
            !isAnchor -> script.cursorNotSelect.or(cursorNotSelect)
            selected -> script.anchor.or(anchor)
            visited -> script.anchorVisited.or(anchorVisited).or(script.anchorNotSelect).or(anchorNotSelect)
            else -> script.anchorNotSelect.or(anchorNotSelect)
        }
        val base = if (isAnchor) anchorColor else choiceColor
        val shape = look.shape ?: when {
            isAnchor -> "underline"
            selected -> "square"
            else -> "none"
        }
        val square = "square" in shape
        return ResolvedLook(
            square = square,
            underline = "underline" in shape,
            brush = look.brush?.let(::rgb) ?: if (square && selected) base.copy(alpha = 0.15f) else null,
            pen = look.pen?.let(::rgb),
            font = look.font?.let(::rgb) ?: if (isAnchor && visited && !selected) base.copy(alpha = 0.7f) else base,
        )
    }

    private fun rgb(v: Int) = Color(v or 0xFF000000.toInt())
}
