package net.pono.baseware.ghost

import net.pono.baseware.sakura.Choice

sealed interface BalloonItem {
    data class Text(val text: String) : BalloonItem
    data object NewLine : BalloonItem
    data class ChoiceItem(val choice: Choice) : BalloonItem
}

/** キャラクター（\0, \1 ...）1人分の表示状態。[surface] が -1 なら非表示。 */
data class CharacterState(
    val scope: Int,
    val surface: Int,
    val balloon: List<BalloonItem>? = null,
    /** \i[n] で再生を頼まれたアニメーションと、その回数（同じ ID を続けて頼まれても区別するため） */
    val animationRequest: Pair<Int, Int>? = null,
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
) {
    fun update(scope: Int, f: (CharacterState) -> CharacterState): GhostState {
        val current = characters[scope] ?: CharacterState(scope, -1)
        return copy(characters = characters + (scope to f(current)))
    }
}
