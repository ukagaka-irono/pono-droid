package net.pono.baseware.ghost

import net.pono.baseware.sakura.Choice

sealed interface BalloonItem {
    data class Text(val text: String) : BalloonItem
    data object NewLine : BalloonItem

    /** \q の選択肢と、\_a / \__q のリンク。文の中にそのまま置く */
    data class ChoiceItem(val choice: Choice) : BalloonItem

    /** \![*]。次の選択肢の前に付ける印 */
    data object Marker : BalloonItem

    /** \_l[x,y]。同じ行の中で x を指定されたら、そこから次の列にする（狭いバルーンでは座標どおりに置けないので、列に分けて並べる） */
    data object Column : BalloonItem
}

/** キャラクター（\0, \1 ...）1人分の表示状態。[surface] が -1 なら非表示。 */
data class CharacterState(
    val scope: Int,
    val surface: Int,
    val balloon: List<BalloonItem>? = null,
    /** \i[n] で再生を頼まれたアニメーションと、その回数（同じ ID を続けて頼まれても区別するため） */
    val animationRequest: Pair<Int, Int>? = null,
    /** 着せ替えで身に着けているもの（bindgroup の番号。SERIKO の interval が bind のアニメーション番号と同じ） */
    val binds: Set<Int> = emptySet(),
    /** \b[n] で選ばれたバルーンの番号。null なら文字の量に合わせて選ぶ、-1 ならバルーンを出さない */
    val balloonId: Int? = null,
) {
    fun appendText(text: String): CharacterState {
        val items = balloon.orEmpty()
        val last = items.lastOrNull()
        return copy(
            balloon = if (last is BalloonItem.Text) items.dropLast(1) + BalloonItem.Text(last.text + text) else items + BalloonItem.Text(text),
        )
    }

    fun append(item: BalloonItem) = copy(balloon = balloon.orEmpty() + item)
}

data class GhostState(
    val characters: Map<Int, CharacterState> = emptyMap(),
    val talking: Boolean = false,
    val waitingClick: Boolean = false,
    val waitingChoice: Boolean = false,
    /** 画面が消えている間は false。タイマーのイベントと SERIKO のアニメーションを止める */
    val screenOn: Boolean = true,
    /** 開いている入力の箱（\![open,inputbox,...] など）。答えるか取り消すまで残る */
    val input: InputRequest? = null,
) {
    fun update(scope: Int, f: (CharacterState) -> CharacterState): GhostState {
        val current = characters[scope] ?: CharacterState(scope, -1)
        return copy(characters = characters + (scope to f(current)))
    }
}
