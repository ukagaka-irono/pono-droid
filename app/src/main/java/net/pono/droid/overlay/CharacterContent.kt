package net.pono.droid.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pono.baseware.ghost.BalloonItem
import net.pono.baseware.ghost.BalloonLayout
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.sakura.Choice

/**
 * キャラクターのサーフェス（SERIKO のアニメーション込み）。
 * @param dpPerImagePx シェル画像の 1px を何 dp で表示するか
 * @param onHeightChanged 表示しているサーフェスの高さ（px）。バルーンのウィンドウをその上に置くのに使う
 */
@Composable
fun CharacterSurface(
    scopeId: Int,
    controller: GhostController,
    renderer: SurfaceRenderer,
    dpPerImagePx: Float,
    touchHandler: SurfaceTouchHandler,
    onHeightChanged: (Int) -> Unit,
) {
    val state by controller.state.collectAsState()
    val character = state.characters[scopeId] ?: return
    val coroutineScope = rememberCoroutineScope()
    val animator = remember { SerikoAnimator(coroutineScope) }
    val layers by animator.layers.collectAsState()

    // 画面が消えている間はアニメーションも止める
    LaunchedEffect(character.surface, character.binds, state.screenOn) {
        if (state.screenOn) animator.show(controller.shell.surfaces[character.surface], character.binds) else animator.stop()
    }
    LaunchedEffect(character.animationRequest) {
        character.animationRequest?.let { (id, _) -> animator.start(id) }
    }

    val bitmap by produceState<Bitmap?>(null, character.surface, layers) {
        value = withContext(Dispatchers.Default) { renderer.render(character.surface, layers) }
    }

    val bmp = bitmap ?: return
    val density = LocalDensity.current
    val pxPerImagePx = with(density) { dpPerImagePx.dp.toPx() }
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = controller.ghost.name,
        // 拡大するときはドット絵がぼやけないよう補間しない
        filterQuality = if (pxPerImagePx >= 1.5f) FilterQuality.None else FilterQuality.Low,
        modifier = Modifier
            .size((bmp.width * dpPerImagePx).dp, (bmp.height * dpPerImagePx).dp)
            .onSizeChanged { onHeightChanged(it.height) }
            .surfaceTouch(
                touchHandler,
                onTap = { x, y, count ->
                    val (ix, iy) = (x / pxPerImagePx).toInt() to (y / pxPerImagePx).toInt()
                    controller.onMouseClick(scopeId, ix, iy, controller.shell.surfaces[character.surface]?.hitTest(ix, iy), count)
                },
                onLongPress = { x, y ->
                    val (ix, iy) = (x / pxPerImagePx).toInt() to (y / pxPerImagePx).toInt()
                    controller.onMouseLongPress(scopeId, ix, iy, controller.shell.surfaces[character.surface]?.hitTest(ix, iy))
                },
            ),
    )
}

/** キャラクターのバルーン。話していないときは何も描かない（ウィンドウの大きさが 0 になり、タッチも奪わない）。 */
@Composable
fun CharacterBalloon(scopeId: Int, controller: GhostController) {
    val state by controller.state.collectAsState()
    val items = state.characters[scopeId]?.balloon ?: return
    Balloon(
        items = items,
        onClick = { controller.onBalloonClick(scopeId) },
        onChoice = controller::onChoice,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.surfaceTouch(
    handler: SurfaceTouchHandler,
    onTap: (Float, Float, Int) -> Unit,
    onLongPress: (Float, Float) -> Unit,
) = pointerInteropFilter { handler.onTouch(it, onTap, onLongPress) }

@Composable
private fun Balloon(items: List<BalloonItem>, onClick: () -> Unit, onChoice: (Choice) -> Unit) {
    val scroll = rememberScrollState()
    // 文字を出している間だけ最後まで追いかける。選択肢で終わるとき（メニューなど）は先頭の問いかけを見せたいので動かさない
    val last = items.lastOrNull()
    LaunchedEffect(items.size, (last as? BalloonItem.Text)?.text?.length) {
        if (last !is BalloonItem.ChoiceItem) scroll.animateScrollTo(scroll.maxValue)
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 2).coerceIn(220, 400).dp
    // \0 は右端、\1 は左端から（既定ではそれぞれ 16dp 内側に）出るので、
    // 画面の半分から余白を引いた幅に収めれば2人が同時に話しても重ならない。
    // 幅は固定にする（文字が増えるたびにウィンドウの幅が変わらないように。高さは行が増えたときだけ変わる）
    val width = (LocalConfiguration.current.screenWidthDp / 2 - 24).coerceIn(120, 280).dp
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .width(width)
            .border(1.5.dp, Color(0xFF8D6E63), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.heightIn(max = maxHeight).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (line in BalloonLayout.lines(items)) {
                    if (line.columns.size == 1) {
                        BalloonText(line.columns[0], onChoice)
                    } else {
                        // \_l で並べた列は、バルーンの幅を等分して横に並べる
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (column in line.columns) {
                                BalloonText(column, onChoice, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TextColor = Color(0xFF3E2723)
private val ChoiceStyle = TextLinkStyles(
    style = SpanStyle(color = Color(0xFF1565C0), fontWeight = FontWeight.Medium),
    pressedStyle = SpanStyle(color = Color(0xFF1565C0), background = Color(0x221565C0)),
)

/** 1列分の文。選択肢とリンクは文の中で押せるようにする（\![*] があれば印を付ける） */
@Composable
private fun BalloonText(runs: List<BalloonLayout.Run>, onChoice: (Choice) -> Unit, modifier: Modifier = Modifier) {
    val text = buildAnnotatedString {
        for (run in runs) {
            when (run) {
                is BalloonLayout.Run.Text -> append(run.text)
                is BalloonLayout.Run.ChoiceRun -> {
                    if (run.marked) append("▸ ")
                    withLink(LinkAnnotation.Clickable(tag = run.choice.id, styles = ChoiceStyle) { onChoice(run.choice) }) {
                        append(run.choice.title)
                    }
                }
            }
        }
    }
    // 選択肢を含む行は指で押しやすいよう少し高さを取る
    val hasChoice = runs.any { it is BalloonLayout.Run.ChoiceRun }
    Text(
        text = text,
        fontSize = 14.sp,
        lineHeight = if (hasChoice) 24.sp else 20.sp,
        color = TextColor,
        modifier = modifier.padding(vertical = if (hasChoice) 2.dp else 0.dp),
    )
}
