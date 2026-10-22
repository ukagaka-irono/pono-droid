package net.pono.droid.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pono.baseware.ghost.BalloonItem
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.sakura.Choice

/** シェル画像の 1px を何 dp で表示するか。 */
private const val DP_PER_IMAGE_PX = 0.5f

@Composable
fun CharacterContent(
    scopeId: Int,
    controller: GhostController,
    renderer: SurfaceRenderer,
    alignEnd: Boolean,
    touchHandler: SurfaceTouchHandler,
) {
    val state by controller.state.collectAsState()
    val character = state.characters[scopeId] ?: return
    val coroutineScope = rememberCoroutineScope()
    val animator = remember { SerikoAnimator(coroutineScope) }
    val layers by animator.layers.collectAsState()

    LaunchedEffect(character.surface) {
        animator.show(controller.shell.surfaces[character.surface])
    }
    LaunchedEffect(character.animationRequest) {
        character.animationRequest?.let { (id, _) -> animator.start(id) }
    }

    val bitmap by produceState<Bitmap?>(null, character.surface, layers) {
        value = withContext(Dispatchers.Default) { renderer.render(character.surface, layers) }
    }

    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        character.balloon?.let { items ->
            Balloon(
                items = items,
                onClick = { controller.onBalloonClick(scopeId) },
                onChoice = controller::onChoice,
            )
        }
        val bmp = bitmap ?: return@Column
        val density = LocalDensity.current
        val pxPerImagePx = with(density) { DP_PER_IMAGE_PX.dp.toPx() }
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = controller.ghost.name,
            modifier = Modifier
                .size((bmp.width * DP_PER_IMAGE_PX).dp, (bmp.height * DP_PER_IMAGE_PX).dp)
                .surfaceTouch(touchHandler) { x, y, double ->
                    val ix = (x / pxPerImagePx).toInt()
                    val iy = (y / pxPerImagePx).toInt()
                    val collision = controller.shell.surfaces[character.surface]?.hitTest(ix, iy)
                    controller.onMouseClick(scopeId, ix, iy, collision, double)
                },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.surfaceTouch(handler: SurfaceTouchHandler, onTap: (Float, Float, Boolean) -> Unit) =
    pointerInteropFilter { handler.onTouch(it, onTap) }

@Composable
private fun Balloon(items: List<BalloonItem>, onClick: () -> Unit, onChoice: (Choice) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(items.size, (items.lastOrNull() as? BalloonItem.Text)?.text?.length) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .widthIn(min = 120.dp, max = 260.dp)
            .border(1.5.dp, Color(0xFF8D6E63), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.heightIn(max = 220.dp).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column {
                for (line in toLines(items)) {
                    when (line) {
                        is Line.TextLine -> Text(line.text, fontSize = 14.sp, color = Color(0xFF3E2723))
                        is Line.ChoiceLine -> Text(
                            text = "▶ " + line.choice.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1565C0),
                            modifier = Modifier
                                .clickable { onChoice(line.choice) }
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private sealed interface Line {
    data class TextLine(val text: String) : Line
    data class ChoiceLine(val choice: Choice) : Line
}

/** 選択肢は1行ずつのボタンにし、それ以外は改行で区切ったテキストにする。 */
private fun toLines(items: List<BalloonItem>): List<Line> {
    val lines = mutableListOf<Line>()
    val current = StringBuilder()
    // 選択肢の直後の \n は、選択肢自体が1行なので空行にしない
    var afterChoice = false
    for (item in items) {
        when (item) {
            is BalloonItem.Text -> {
                current.append(item.text)
                afterChoice = false
            }
            BalloonItem.NewLine -> {
                if (!afterChoice || current.isNotEmpty()) lines += Line.TextLine(current.toString())
                current.clear()
                afterChoice = false
            }
            is BalloonItem.ChoiceItem -> {
                if (current.isNotBlank()) lines += Line.TextLine(current.toString())
                current.clear()
                lines += Line.ChoiceLine(item.choice)
                afterChoice = true
            }
        }
    }
    if (current.isNotEmpty()) lines += Line.TextLine(current.toString())
    return lines
}
