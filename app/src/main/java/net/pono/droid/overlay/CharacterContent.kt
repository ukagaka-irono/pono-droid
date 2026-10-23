package net.pono.droid.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pono.baseware.ghost.AnimationCommand
import net.pono.baseware.ghost.CharacterState
import net.pono.baseware.ghost.GhostController
import kotlin.math.abs

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
    // まだ実行していない番号の頼みを、出された順に実行する（1つのスクリプトの \i をすべて）
    var handled by remember { mutableIntStateOf(character.animationCommands.lastOrNull()?.first ?: 0) }
    LaunchedEffect(character.animationCommands) {
        for ((serial, command) in character.animationCommands) {
            if (serial <= handled) continue
            when (command) {
                is AnimationCommand.Start -> animator.start(command.id)
                is AnimationCommand.Offset -> animator.setOffset(command.id, command.x, command.y)
                is AnimationCommand.Clear -> animator.clear(command.id)
                AnimationCommand.StopAll -> animator.stopRunning()
            }
            handled = serial
        }
    }

    val bitmap by produceState<Bitmap?>(null, character.surface, layers) {
        value = withContext(Dispatchers.Default) { renderer.render(character.surface, layers) }
    }

    val bmp = bitmap ?: return
    val density = LocalDensity.current
    // \![set,scaling] の倍率（負なら反転）。大きさは絶対値で決め、向きは描くときに裏返す
    val sx = character.scaleX.takeIf { it != 0f } ?: 1f
    val sy = character.scaleY.takeIf { it != 0f } ?: 1f
    val pxPerImagePxX = with(density) { (dpPerImagePx * abs(sx)).dp.toPx() }
    val pxPerImagePxY = with(density) { (dpPerImagePx * abs(sy)).dp.toPx() }

    /** 指の位置（ウィンドウの px）をシェル画像の座標にする。反転していれば裏返す */
    fun imagePoint(x: Float, y: Float): Pair<Int, Int> {
        val ix = (x / pxPerImagePxX).toInt().let { if (sx < 0) bmp.width - 1 - it else it }
        val iy = (y / pxPerImagePxY).toInt().let { if (sy < 0) bmp.height - 1 - it else it }
        return ix to iy
    }
    fun hit(p: Pair<Int, Int>) = controller.shell.surfaces[character.surface]?.hitTest(p.first, p.second)
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = controller.ghost.name,
        // 拡大するときはドット絵がぼやけないよう補間しない
        filterQuality = if (minOf(pxPerImagePxX, pxPerImagePxY) >= 1.5f) FilterQuality.None else FilterQuality.Low,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .size((bmp.width * dpPerImagePx * abs(sx)).dp, (bmp.height * dpPerImagePx * abs(sy)).dp)
            .graphicsLayer {
                scaleX = if (sx < 0) -1f else 1f
                scaleY = if (sy < 0) -1f else 1f
                alpha = character.alpha
            }
            .onSizeChanged { onHeightChanged(it.height) }
            .surfaceTouch(
                touchHandler,
                onDown = { x, y ->
                    val p = imagePoint(x, y)
                    controller.onMouseDown(scopeId, p.first, p.second, hit(p))
                },
                onTap = { x, y, count ->
                    val p = imagePoint(x, y)
                    controller.onMouseUp(scopeId, p.first, p.second, hit(p), count)
                },
                onLongPress = { x, y ->
                    val p = imagePoint(x, y)
                    controller.onMouseLongPress(scopeId, p.first, p.second, hit(p))
                },
            ),
    )
}

/**
 * キャラクターのバルーン。話していないときは何も描かない（ウィンドウの大きさが 0 になり、タッチも奪わない）。
 * [skin] があればゴーストのバルーン画像で、無ければ pono-droid の簡易バルーンで描く。
 */
@Composable
fun CharacterBalloon(scopeId: Int, controller: GhostController, skin: BalloonSkin?) {
    val state by controller.state.collectAsState()
    val character = state.characters[scopeId] ?: return
    // 入力の箱は SSP と同じく \0 のバルーンの位置に、話している文の下に出す
    val input = state.input?.takeIf { scopeId == 0 }
    Column(horizontalAlignment = if (scopeId == 1) Alignment.Start else Alignment.End) {
        TalkBalloon(scopeId, character, state.loading, controller, skin)
        if (input != null) InputBalloon(input, skin, controller::onInputSubmit, controller::onInputCancel)
    }
}

@Composable
private fun TalkBalloon(scopeId: Int, character: CharacterState, loading: String?, controller: GhostController, skin: BalloonSkin?) {
    val items = character.balloon ?: run {
        // 読み込み中は \0 の上に小さな吹き出しで知らせる（話し始めたら普通のバルーンに替わる）
        if (scopeId == 0) loading?.let { LoadingBalloon(it) }
        return
    }
    // \b[-1] はバルーンを隠す（文字は流れ続ける）
    if (character.balloonId == -1) return
    val onClick = { controller.onBalloonClick(scopeId) }
    if (skin != null) {
        SkinBalloon(items, skin, scopeId, character.balloonId, onClick, controller::onChoice)
    } else {
        SimpleBalloon(items, onClick, controller::onChoice)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.surfaceTouch(
    handler: SurfaceTouchHandler,
    onDown: (Float, Float) -> Unit,
    onTap: (Float, Float, Int) -> Unit,
    onLongPress: (Float, Float) -> Unit,
) = pointerInteropFilter { handler.onTouch(it, onDown, onTap, onLongPress) }

