package net.pono.droid.overlay

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import net.pono.baseware.ghost.SelectRect
import net.pono.baseware.shell.Collision
import net.pono.baseware.shell.CollisionShape
import net.pono.baseware.shell.PatternFrame
import kotlin.math.roundToInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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
@OptIn(ExperimentalComposeUiApi::class)
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
    // \![lock,repaint] の間は、止めたときのサーフェスのまま描く
    val character = (state.surfaceSnapshot ?: state.characters)[scopeId] ?: return
    val coroutineScope = rememberCoroutineScope()
    val animator = remember { SerikoAnimator(coroutineScope) }
    val layers by animator.layers.collectAsState()

    // 画面が消えている間はアニメーションも止める
    LaunchedEffect(character.surface, character.binds, state.screenOn) {
        if (state.screenOn) animator.show(controller.shell.surfaces[character.surface], character.binds) else animator.stop()
    }
    // \![anim,add,text] の文字。表示時間が過ぎるか、サーフェスを替えたら消す
    val texts = remember { mutableStateListOf<AnimationCommand.AddText>() }
    LaunchedEffect(character.surface) { texts.clear() }
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
                is AnimationCommand.Pause -> animator.pause(command.id)
                is AnimationCommand.Resume -> animator.resume(command.id)
                is AnimationCommand.Add -> animator.add(command.frames)
                is AnimationCommand.AddText -> texts += command
            }
            handled = serial
        }
    }

    // \i[ID,wait] などで待てるよう、いま動いているアニメーションを知らせる
    LaunchedEffect(animator) { animator.playing.collect { controller.onAnimationsPlaying(scopeId, it) } }

    // 話した文字の数が増えたら talk,N の、話し終えたら yen-e のアニメーションを動かす（シェルを替えると数え直すので、減ったら合わせるだけ）
    var talked by remember { mutableIntStateOf(character.talkCount) }
    LaunchedEffect(character.talkCount) {
        if (character.talkCount > talked && state.screenOn) animator.talk(talked, character.talkCount)
        talked = character.talkCount
    }
    var ended by remember { mutableIntStateOf(state.scriptEnds) }
    LaunchedEffect(state.scriptEnds) {
        if (state.scriptEnds > ended && state.screenOn) animator.scriptEnd()
        ended = state.scriptEnds
    }
    // このキャラクターが話し始めた・話し終えたら starttalk / endtalk のアニメーションを動かす
    var starts by remember { mutableIntStateOf(character.talkStarts) }
    LaunchedEffect(character.talkStarts) {
        if (character.talkStarts > starts && state.screenOn) animator.startTalk()
        starts = character.talkStarts
    }
    var ends by remember { mutableIntStateOf(character.talkEnds) }
    LaunchedEffect(character.talkEnds) {
        if (character.talkEnds > ends && state.screenOn) animator.endTalk()
        ends = character.talkEnds
    }

    val bitmap by produceState<Bitmap?>(null, character.surface, layers) {
        value = withContext(Dispatchers.Default) { renderer.render(character.surface, layers) }
    }

    val bmp = bitmap ?: return
    val density = LocalDensity.current
    // \![set,scaling] の倍率（負なら反転）に、SERIKO の scaling のコマの倍率を掛ける。大きさは絶対値で決め、向きは描くときに裏返す
    val scalingFrames = layers.values.filter { it.method == "scaling" }
    val animX = scalingFrames.fold(1f) { acc, f -> acc * f.scaleX / 100f }
    val animY = scalingFrames.fold(1f) { acc, f -> acc * f.scaleY / 100f }
    val sx = (character.scaleX * animX).takeIf { it != 0f } ?: 1f
    val sy = (character.scaleY * animY).takeIf { it != 0f } ?: 1f
    val pxPerImagePxX = with(density) { (dpPerImagePx * abs(sx)).dp.toPx() }
    val pxPerImagePxY = with(density) { (dpPerImagePx * abs(sy)).dp.toPx() }

    /** 指の位置（ウィンドウの px）をシェル画像の座標にする。反転していれば裏返す */
    fun imagePoint(x: Float, y: Float): Pair<Int, Int> {
        val ix = (x / pxPerImagePxX).toInt().let { if (sx < 0) bmp.width - 1 - it else it }
        val iy = (y / pxPerImagePxY).toInt().let { if (sy < 0) bmp.height - 1 - it else it }
        return ix to iy
    }
    // base のコマで土台を替えていれば、そのサーフェスの当たり判定を使う。コマを出しているアニメーションの当たり判定も見る
    val baseSurface = layers.values.lastOrNull { it.method == "base" && it.surface >= 0 }?.surface ?: character.surface
    val activeAnimations = layers.keys.filter { it >= 0 } + character.binds
    fun hit(p: Pair<Int, Int>) = controller.shell.surfaces[baseSurface]?.hitTest(p.first, p.second, activeAnimations)
    // sakura.tooltips などで決めた、当たり判定に触れている間に出す文字
    var tooltip by remember { mutableStateOf<String?>(null) }
    fun showTooltip(collision: String?) {
        // \![set,property] で変えたものも入る
        tooltip = collision?.let { controller.tooltip(scopeId, it) }?.takeIf { it.isNotBlank() }
    }
    val selecting = state.selectMode?.takeIf { it.scope == scopeId }
    var selectStart by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /** \![enter,selectmode] の間は、指でなぞった矩形を選ぶ（ほかのタッチのイベントは送らない） */
    fun selectTouch(event: android.view.MotionEvent): Boolean {
        val p = imagePoint(event.x, event.y)
        fun rect(from: Pair<Int, Int>) =
            SelectRect(minOf(from.first, p.first), minOf(from.second, p.second), maxOf(from.first, p.first), maxOf(from.second, p.second))
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                selectStart = p
                controller.onSelectDown(scopeId, p.first, p.second)
            }
            android.view.MotionEvent.ACTION_MOVE -> selectStart?.let { controller.onSelecting(scopeId, rect(it)) }
            android.view.MotionEvent.ACTION_UP -> selectStart?.let { controller.onSelectUp(scopeId, p.first, p.second, rect(it)) }
            android.view.MotionEvent.ACTION_CANCEL -> selectStart = null
        }
        return true
    }
    // \![enter,collisionmode] の間は、当たり判定をキャラクターの上に描く
    val collisions = state.collisionMode?.let {
        val def = controller.shell.surfaces[baseSurface]
        activeAnimations.mapNotNull { id -> def?.animations?.get(id) }.flatMap { a -> a.collisions.values } + def?.orderedCollisions().orEmpty()
    }
    val labelPaint = remember { android.graphics.Paint().apply { color = android.graphics.Color.RED; isAntiAlias = true } }
    Box(contentAlignment = Alignment.TopCenter) {
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
                // 選んでいる間は透明な所も見せる（SSP と同じ）
                .then(if (selecting != null) Modifier.background(Color(0x33000000)) else Modifier)
                .drawWithContent {
                    drawContent()
                    collisions?.let { drawCollisions(it, pxPerImagePxX, pxPerImagePxY, state.collisionMode == "names", labelPaint) }
                    selecting?.selection?.let { r ->
                        drawRect(
                            Color(0xFF1565C0),
                            topLeft = Offset(r.left * pxPerImagePxX, r.top * pxPerImagePxY),
                            size = Size((r.right - r.left) * pxPerImagePxX, (r.bottom - r.top) * pxPerImagePxY),
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                }
                .pointerInteropFilter { event ->
                    if (selecting != null) return@pointerInteropFilter selectTouch(event)
                    touchHandler.onTouch(event, object : SurfaceTouchCallbacks {
                        override fun onDown(x: Float, y: Float) {
                            val p = imagePoint(x, y)
                            showTooltip(hit(p))
                            controller.onTouchEnter(scopeId, p.first, p.second, hit(p))
                            controller.onMouseDown(scopeId, p.first, p.second, hit(p))
                        }

                        override fun onTap(x: Float, y: Float, count: Int) {
                            val p = imagePoint(x, y)
                            controller.onMouseUp(scopeId, p.first, p.second, hit(p), count)
                        }

                        override fun onLongPress(x: Float, y: Float) {
                            val p = imagePoint(x, y)
                            tooltip = null
                            controller.onMouseLongPress(scopeId, p.first, p.second, hit(p))
                        }

                        // 当たり判定（頭など）の上で触れて動かすと、なでる。当たり判定の外からならウィンドウを動かす
                        override fun canStroke(x: Float, y: Float) = hit(imagePoint(x, y)) != null

                        override fun onStroke(x: Float, y: Float) {
                            val p = imagePoint(x, y)
                            showTooltip(hit(p))
                            controller.onTouchCollision(scopeId, p.first, p.second, hit(p))
                            controller.onMouseMove(scopeId, p.first, p.second, hit(p))
                        }

                        override fun onDragStart(x: Float, y: Float) {
                            val p = imagePoint(x, y)
                            controller.onMouseDragStart(scopeId, p.first, p.second, hit(p))
                        }

                        override fun onDragEnd(x: Float, y: Float) {
                            val p = imagePoint(x, y)
                            controller.onMouseDragEnd(scopeId, p.first, p.second, hit(p))
                        }

                        override fun onGesture(x: Float, y: Float, startX: Float, startY: Float, direction: String, angle: Int) {
                            val p = imagePoint(x, y)
                            val s = imagePoint(startX, startY)
                            controller.onMouseGesture(scopeId, p.first, p.second, hit(p), s.first, s.second, hit(s), direction, angle)
                        }

                        override fun onWheel(x: Float, y: Float, amount: Int) {
                            val p = imagePoint(x, y)
                            controller.onMouseWheel(scopeId, p.first, p.second, hit(p), amount)
                        }

                        override fun onMiddleClick(x: Float, y: Float) {
                            val p = imagePoint(x, y)
                            controller.onMiddleClick(scopeId, p.first, p.second, hit(p))
                        }

                        override fun onRelease(x: Float, y: Float) {
                            val p = imagePoint(x, y)
                            tooltip = null
                            controller.onTouchLeave(scopeId, p.first, p.second)
                        }
                    })
                },
        )
        // \![anim,add,text] の文字と、surfaces.txt の import のアニメーション画像を、シェル画像の座標に重ねる
    for (t in texts) {
        key(t) {
            SurfaceText(t, pxPerImagePxX, pxPerImagePxY)
            if (t.timeMs > 0) LaunchedEffect(t) {
                delay(t.timeMs)
                texts.remove(t)
            }
        }
    }
    for ((layer, frame) in layers) {
        if (frame.method != "import") continue
        val file = frame.file?.let(controller.shell::file) ?: continue
        key(layer, frame) { ImportedAnimation(file, frame, pxPerImagePxX, pxPerImagePxY) }
    }
    // 当たり判定に触れている間はそのツールチップ。無ければ、このキャラクターのバルーンで長押ししている選択肢のツールチップ
    (tooltip ?: state.choiceTooltip?.takeIf { it.first == scopeId }?.second)?.let { SurfaceTooltip(it) }
    }
}

/**
 * 当たり判定のツールチップ（sakura.tooltips など）と、バルーンで長押ししている選択肢のツールチップ（balloon_tooltip / tooltip）。
 * 指が触れている間、キャラクターの上の端に出す
 */
@Composable
private fun SurfaceTooltip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xEE3E2723),
        modifier = Modifier.padding(4.dp),
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/**
 * キャラクターのバルーン。話していないときは何も描かない（ウィンドウの大きさが 0 になり、タッチも奪わない）。
 * [skin] があればゴーストのバルーン画像で、無ければ pono-droid の簡易バルーンで描く。
 */
@Composable
fun CharacterBalloon(scopeId: Int, controller: GhostController, skin: BalloonSkin?) {
    val state by controller.state.collectAsState()
    // \![lock,balloonrepaint] の間は、止めたときのバルーンのまま描く
    val character = (state.balloonSnapshot ?: state.characters)[scopeId] ?: return
    // 入力の箱は SSP と同じく \0 のバルーンの位置に、話している文の下に出す
    val input = state.input?.takeIf { scopeId == 0 }
    Column(horizontalAlignment = if (scopeId == 1) Alignment.Start else Alignment.End) {
        TalkBalloon(scopeId, character, state.loading, state.online && scopeId == 0, state.visitedAnchors, controller, skin)
        if (input != null) InputBalloon(input, skin, controller::onInputSubmit, controller::onInputCancel)
    }
}

/** [online] なら \0 のバルーンにオンラインの印を出す（話していなくても、空のバルーンを出して印を見せる。SSP と同じ） */
@Composable
private fun TalkBalloon(
    scopeId: Int,
    character: CharacterState,
    loading: String?,
    online: Boolean,
    visited: Set<String>,
    controller: GhostController,
    skin: BalloonSkin?,
) {
    val items = character.balloon ?: if (online && loading == null) emptyList() else run {
        // 読み込み中は \0 の上に小さな吹き出しで知らせる（話し始めたら普通のバルーンに替わる）
        if (scopeId == 0) loading?.let { LoadingBalloon(it) }
        return
    }
    // \b[-1] はバルーンを隠す（文字は流れ続ける）
    if (character.balloonId == -1) return
    val onClick = { controller.onBalloonClick(scopeId) }
    val choices = ChoiceActions(controller::onChoice, { controller.onChoiceHover(it) }, visited)
    val labels = BalloonLabels(character.balloonMarker, character.balloonNum)
    if (skin != null) {
        SkinBalloon(items, skin, scopeId, character.balloonId, onClick, choices, character.autoScroll, online, labels)
    } else {
        SimpleBalloon(items, onClick, choices, character.autoScroll, online, labels)
    }
}


/** \![anim,add,text] の文字。座標と大きさはシェル画像のピクセル */
@Composable
private fun BoxScope.SurfaceText(t: AnimationCommand.AddText, pxPerX: Float, pxPerY: Float) {
    val density = LocalDensity.current
    val size = with(density) { ((t.size ?: 12) * pxPerY).toSp() }
    var modifier = Modifier
        .align(Alignment.TopStart)
        .offset { IntOffset((t.x * pxPerX).roundToInt(), (t.y * pxPerY).roundToInt()) }
    if (t.width > 0) modifier = modifier.width(with(density) { (t.width * pxPerX).toDp() })
    if (t.height > 0) modifier = modifier.height(with(density) { (t.height * pxPerY).toDp() })
    Text(
        t.text,
        color = t.color?.let { Color(it or 0xFF000000.toInt()) } ?: Color.Black,
        fontSize = size,
        fontFamily = t.font?.let { BalloonFonts.family(listOf(it)) },
        modifier = modifier,
    )
}

/**
 * surfaces.txt の import,ファイル名,待ち時間,x,y。APNG / GIF / WebP のアニメーション画像を、待ってから流し続ける
 * （元の画像の繰り返し回数は見ない。SSP と同じ）。Android 9 より前では最初のコマだけ出す
 */
@Composable
private fun BoxScope.ImportedAnimation(file: java.io.File, frame: PatternFrame, pxPerX: Float, pxPerY: Float) {
    val drawable by produceState<android.graphics.drawable.Drawable?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 28) {
                    android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(file)) { decoder, info, _ ->
                        // 細工された巨大な画像でメモリを使い切らせない
                        val w = info.size.width
                        val h = info.size.height
                        if (w > ImageTransparency.MAX_SIDE || h > ImageTransparency.MAX_SIDE) {
                            decoder.setTargetSize(minOf(w, ImageTransparency.MAX_SIDE), minOf(h, ImageTransparency.MAX_SIDE))
                        }
                    }
                } else {
                    ImageTransparency.load(file, null, selfAlpha = true)?.let { android.graphics.drawable.BitmapDrawable(null, it) }
                }
            }.getOrNull()
        }
    }
    val d = drawable ?: return
    LaunchedEffect(d) {
        delay(frame.waitMinMs.toLong())
        if (Build.VERSION.SDK_INT >= 28 && d is android.graphics.drawable.AnimatedImageDrawable) {
            d.repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE
            d.start()
        }
    }
    DisposableEffect(d) {
        onDispose { if (Build.VERSION.SDK_INT >= 28 && d is android.graphics.drawable.AnimatedImageDrawable) d.stop() }
    }
    val density = LocalDensity.current
    AndroidView(
        factory = { context -> android.widget.ImageView(context).apply { scaleType = android.widget.ImageView.ScaleType.FIT_XY } },
        update = { it.setImageDrawable(d) },
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset((frame.x * pxPerX).roundToInt(), (frame.y * pxPerY).roundToInt()) }
            .size(with(density) { (d.intrinsicWidth * pxPerX).toDp() }, with(density) { (d.intrinsicHeight * pxPerY).toDp() }),
    )
}

/** \![enter,collisionmode] の当たり判定の枠（と名前）。座標はシェル画像のピクセル */
private fun DrawScope.drawCollisions(collisions: List<Collision>, pxPerX: Float, pxPerY: Float, names: Boolean, labelPaint: android.graphics.Paint) {
    val color = Color(0xFFE53935)
    val stroke = Stroke(1.5.dp.toPx())
    labelPaint.textSize = 11.dp.toPx()
    for (c in collisions) {
        val topLeft = Offset(c.left * pxPerX, c.top * pxPerY)
        val size = Size((c.right - c.left) * pxPerX, (c.bottom - c.top) * pxPerY)
        when (c.shape) {
            CollisionShape.RECT -> drawRect(color, topLeft, size, style = stroke)
            CollisionShape.ELLIPSE, CollisionShape.CIRCLE -> drawOval(color, topLeft, size, style = stroke)
            CollisionShape.POLYGON -> {
                val path = Path()
                c.points.chunked(2).forEachIndexed { i, (x, y) ->
                    if (i == 0) path.moveTo(x * pxPerX, y * pxPerY) else path.lineTo(x * pxPerX, y * pxPerY)
                }
                path.close()
                drawPath(path, color, style = stroke)
            }
        }
        if (names) drawContext.canvas.nativeCanvas.drawText(c.id, topLeft.x + 2, topLeft.y + labelPaint.textSize, labelPaint)
    }
}
