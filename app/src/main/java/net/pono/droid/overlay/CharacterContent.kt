package net.pono.droid.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pono.baseware.ghost.GhostController

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

/**
 * キャラクターのバルーン。話していないときは何も描かない（ウィンドウの大きさが 0 になり、タッチも奪わない）。
 * [skin] があればゴーストのバルーン画像で、無ければ pono-droid の簡易バルーンで描く。
 */
@Composable
fun CharacterBalloon(scopeId: Int, controller: GhostController, skin: BalloonSkin?) {
    val state by controller.state.collectAsState()
    val character = state.characters[scopeId] ?: return
    val items = character.balloon ?: return
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
    onTap: (Float, Float, Int) -> Unit,
    onLongPress: (Float, Float) -> Unit,
) = pointerInteropFilter { handler.onTouch(it, onTap, onLongPress) }

