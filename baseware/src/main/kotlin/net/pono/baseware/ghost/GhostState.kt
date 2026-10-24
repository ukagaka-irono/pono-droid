package net.pono.baseware.ghost

import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ChoiceLooks
import net.pono.baseware.sakura.MoveCommand
import net.pono.baseware.sakura.TextAlign
import net.pono.baseware.sakura.TextStyle
import net.pono.baseware.sakura.VerticalAlign

sealed interface BalloonItem {
    /** [style] は \f で決めた飾り */
    data class Text(val text: String, val style: TextStyle = TextStyle.Default) : BalloonItem
    data object NewLine : BalloonItem

    /** \n[half] / \n[150] など。[percent] はふつうの改行に対する割合（負なら上へ戻る） */
    data class LineBreak(val percent: Int) : BalloonItem

    /** \f[valign,...]。この行から後の、行の中の縦のそろえ方 */
    data class VAlign(val align: VerticalAlign) : BalloonItem

    /** \q の選択肢と、\_a / \__q のリンク。文の中にそのまま置く。[looks] は \f[cursor*] などで決めた見た目 */
    data class ChoiceItem(val choice: Choice, val looks: ChoiceLooks = ChoiceLooks.Default) : BalloonItem

    /** \![*]。次の選択肢の前に付ける印 */
    data object Marker : BalloonItem

    /** \_l[x,y]。同じ行の中で x を指定されたら、そこから次の列にする（狭いバルーンでは座標どおりに置けないので、列に分けて並べる） */
    data object Column : BalloonItem

    /** \f[align,...]。今の行の寄せ方（次の行からは左寄せに戻る） */
    data class Align(val align: TextAlign) : BalloonItem

    /**
     * \_b。[path] はゴーストのディレクトリの中の画像。[x] / [y] があればバルーンのその位置（文字の下）、
     * 無ければ文字の中に1文字として置く。[opaque] なら左上の色を抜かない
     */
    data class Image(val path: String, val x: Int?, val y: Int?, val opaque: Boolean) : BalloonItem
}

/** SERIKO への頼み（\i[n]、\![anim,...]）。表示する側が順番に実行する */
sealed interface AnimationCommand {
    data class Start(val id: Int) : AnimationCommand

    /** \![anim,offset,ID,x,y]。そのアニメーションのコマを x, y だけずらして出す */
    data class Offset(val id: Int, val x: Int, val y: Int) : AnimationCommand

    /** \![anim,clear,ID]。止めて、出していたコマを消す */
    data class Clear(val id: Int) : AnimationCommand

    /** \![anim,stop]。動いているアニメーションを全部止める */
    data object StopAll : AnimationCommand
}

/** キャラクター（\0, \1 ...）1人分の表示状態。[surface] が -1 なら非表示。 */
data class CharacterState(
    val scope: Int,
    val surface: Int,
    val balloon: List<BalloonItem>? = null,
    /**
     * SERIKO への頼みと通し番号。1つのスクリプトで何十個も \i を出すゴーストもあるので、最近のものを並べておき、
     * 表示する側はまだ実行していない番号のものを順に実行する
     */
    val animationCommands: List<Pair<Int, AnimationCommand>> = emptyList(),
    /** 着せ替えで身に着けているもの（bindgroup の番号。SERIKO の interval が bind のアニメーション番号と同じ） */
    val binds: Set<Int> = emptySet(),
    /** \b[n] で選ばれたバルーンの番号。null なら文字の量に合わせて選ぶ、-1 ならバルーンを出さない */
    val balloonId: Int? = null,
    /** \![set,scaling,横,縦] の倍率（1 で等倍、負なら反転） */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** \![set,alpha,n] の不透明度（0〜1） */
    val alpha: Float = 1f,
    /** いちばん新しい \![move] と通し番号。表示する側は、まだ動かしていない番号なら動かす */
    val move: Pair<Int, MoveCommand>? = null,
    /** \![set,position,x,y] で固定した画面の位置（ウィンドウの左上）。固定している間はドラッグで動かせない */
    val pinned: Pair<Int, Int>? = null,
    /** このキャラクターがこれまでに話した文字の数。SERIKO の talk,N（N 文字ごとに動く口パクなど）に使う */
    val talkCount: Int = 0,
    /** 話し始めた回数と、話し終えた（ほかのキャラクターに移った・スクリプトが終わった）回数。SERIKO の starttalk / endtalk に使う */
    val talkStarts: Int = 0,
    val talkEnds: Int = 0,
    /** いちばん新しい \4（true、相手から離れる）・\5（false、相手のそばへ寄る）と通し番号 */
    val nudge: Pair<Int, Boolean>? = null,
    /** \![set,alignmenttodesktop]。top / bottom / left / right なら画面のその端に付け、その向きにはドラッグで動かさない。null なら自由 */
    val alignment: String? = null,
    /** \![set,autoscroll,disable] の間は false。文字が増えてもバルーンを最後までスクロールしない */
    val autoScroll: Boolean = true,
    /** \![set,balloonoffset,x,y]。バルーンをいつもの位置からずらす量（シェル画像のピクセル、右と下が正） */
    val balloonOffset: Pair<Int, Int>? = null,
    /** \![set,balloonalign,...]。left / center / right / bottom ならキャラクターのその側にバルーンを出す。null ならいつもの上 */
    val balloonAlign: String? = null,
    /** \![set,balloonmarker,文字]。バルーンに出す印の文字（SSP では SSTP の送り主を出すところ） */
    val balloonMarker: String? = null,
    /** \![set,balloonnum,名前,今,最大]。バルーンに出す数（SSP ではファイルを受け取るときの「名前 今/最大」） */
    val balloonNum: String? = null,
) {
    /** [countTalk] が false（\![set,serikotalk,false] の間）なら、口パクの文字数に数えない */
    fun appendText(text: String, style: TextStyle = TextStyle.Default, countTalk: Boolean = true): CharacterState {
        val items = balloon.orEmpty()
        val last = items.lastOrNull()
        return copy(
            balloon = if (last is BalloonItem.Text && last.style == style) {
                items.dropLast(1) + BalloonItem.Text(last.text + text, style)
            } else {
                items + BalloonItem.Text(text, style)
            },
            talkCount = if (countTalk) talkCount + text.length else talkCount,
        )
    }

    fun append(item: BalloonItem) = copy(balloon = balloon.orEmpty() + item)

    fun command(serial: Int, command: AnimationCommand) =
        copy(animationCommands = (animationCommands + (serial to command)).takeLast(MAX_ANIMATION_COMMANDS))

    companion object {
        const val MAX_ANIMATION_COMMANDS = 256
    }
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
    /** \![enter,passivemode]。バルーンと選択肢が時間で消えず、ゴーストは「話せない」と伝えられる */
    val passive: Boolean = false,
    /** SHIORI やシェルを読み込んでいる間に出す文。null なら読み込み中ではない */
    val loading: String? = null,
    /** 最後まで話し終えた（\e まで来た）スクリプトの数。SERIKO の yen-e（話し終わりに動く）に使う */
    val scriptEnds: Int = 0,
    /** キャラクターのウィンドウ全体にかかること（重なり順・一緒に動く組・最小化など） */
    val window: WindowState = WindowState(),
    /** \t の間。マウス（タッチ）のイベントを送らない */
    val timeCritical: Boolean = false,
    /** \![lock,balloonrepaint] / \![lock,repaint] の間は、止めたときのキャラクターたち。バルーンはこの見た目のまま描く */
    val balloonSnapshot: Map<Int, CharacterState>? = null,
    /** \![lock,repaint] の間は、止めたときのキャラクターたち。サーフェスもこの見た目のまま描く */
    val surfaceSnapshot: Map<Int, CharacterState>? = null,
    /** \![enter,onlinemode] の間と、ネットワーク更新の間。バルーンにオンラインの印を出す */
    val online: Boolean = false,
    /** \![enter,nouserbreakmode] の間。バルーンを叩いてもスクリプトを止めない */
    val noUserBreak: Boolean = false,
    /** \![enter,inductionmode] の間。パッシブモードと同じく「話せない」と伝えるが、バルーンや選択肢は時間で消える */
    val induction: Boolean = false,
    /** 選んだことのある \_a のリンクの ID（訪問済みの見た目にする） */
    val visitedAnchors: Set<String> = emptySet(),
) {
    fun update(scope: Int, f: (CharacterState) -> CharacterState): GhostState {
        val current = characters[scope] ?: CharacterState(scope, -1)
        return copy(characters = characters + (scope to f(current)))
    }
}
