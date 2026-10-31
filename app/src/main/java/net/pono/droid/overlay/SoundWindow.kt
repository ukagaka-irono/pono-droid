package net.pono.droid.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 簡易バルーンと同じ縁と文字の色 */
private val SoundBorder = Color(0xFF8D6E63)
private val SoundText = Color(0xFF3E2723)

/**
 * \![sound,play,ファイル,--window=true] の再生のウインドウ（動画は --sound-only でなければいつも）。ファイル名・動画の絵・
 * シークバー・一時停止と閉じるのボタンを、簡易バルーンと同じ見た目の箱で他のアプリの上に出す。ファイル名をつかんで動かせる。
 * [index] 番目のウインドウは、少しずつずらして出す
 */
class SoundWindow(context: Context, private val player: SoundPlayer, sound: SoundPlayer.Windowed, index: Int) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private val view = ComposeView(context)
    private val sound = mutableStateOf(sound)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val density = context.resources.displayMetrics.density
        y = ((FIRST_TOP_DP + index * STAGGER_DP) * density).roundToInt()
        x = (index * STAGGER_DP * density).roundToInt()
        // ウィンドウの出入りや移動のアニメーションは要らない（ドラッグのたびに走ると重い）
        windowAnimations = 0
        if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
    }

    fun show() {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
        view.setContent { SoundPanel(sound.value, player, ::moveBy) }
        owner.resume()
        runCatching { windowManager.addView(view, params) }
    }

    /** 動画の大きさが分かったときなど */
    fun update(sound: SoundPlayer.Windowed) {
        this.sound.value = sound
    }

    fun dismiss() {
        owner.destroy()
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun moveBy(dx: Int, dy: Int) {
        params.x += dx
        params.y += dy
        if (view.isAttachedToWindow) windowManager.updateViewLayout(view, params)
    }

    private companion object {
        /** 1つめのウインドウの、画面の上の端からの位置と、2つめからずらす分（dp） */
        const val FIRST_TOP_DP = 64
        const val STAGGER_DP = 24
    }
}

@Composable
private fun SoundPanel(sound: SoundPlayer.Windowed, player: SoundPlayer, onDrag: (Int, Int) -> Unit) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    // シークバーを動かしている間の位置（離したら移る）
    var seeking by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(sound.key) {
        while (true) {
            player.progress(sound.key)?.let { (p, d) ->
                if (seeking == null) position = p
                duration = d.coerceAtLeast(0)
            }
            paused = player.isPaused(sound.key)
            delay(PROGRESS_INTERVAL_MS)
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier.width(balloonMaxWidth()).border(1.5.dp, SoundBorder, RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            val fieldModifier = Modifier.padding(end = 8.dp)
            Text(
                sound.file.name,
                color = SoundText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = fieldModifier.fillMaxWidth().windowDrag(onDrag),
            )
            if (sound.video) {
                val ratio = if (sound.videoWidth > 0 && sound.videoHeight > 0) sound.videoWidth.toFloat() / sound.videoHeight else 16f / 9f
                VideoPicture(sound.key, player, fieldModifier.padding(top = 4.dp).fillMaxWidth().aspectRatio(ratio))
            }
            // 入力の箱のスライダーと同じく、値の文字はシークバーの上に
            Text(
                "${playTime(seeking?.toLong() ?: position)} / ${playTime(duration)}",
                color = SoundText,
                fontSize = 14.sp,
                modifier = fieldModifier,
            )
            Slider(
                value = (seeking ?: position.toFloat()).coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let {
                        player.seek(sound.key, it.toLong())
                        position = it.toLong()
                    }
                    seeking = null
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                enabled = duration > 0,
                modifier = fieldModifier.fillMaxWidth(),
            )
            Row(Modifier.align(Alignment.End)) {
                TextButton(onClick = {
                    player.togglePause(sound.key)
                    paused = !paused
                }) { Text(if (paused) "再生" else "一時停止", color = SoundBorder) }
                TextButton(onClick = { player.close(sound.key) }) { Text("閉じる", color = SoundBorder) }
            }
        }
    }
}

/** 動画の絵。描く先ができたら [SoundPlayer] に渡し、消えたら外す */
@Composable
private fun VideoPicture(key: String, player: SoundPlayer, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        surface = Surface(texture).also { player.setSurface(key, it) }
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        player.setSurface(key, null)
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * つかんで動かす。ウィンドウが指について動くので、画面の上の位置で測る（バルーンを動かすのと同じ）
 */
@Composable
private fun Modifier.windowDrag(onDrag: (Int, Int) -> Unit): Modifier {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val currentDrag by rememberUpdatedState(onDrag)
    return onGloballyPositioned { coords[0] = it }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            fun screen(p: Offset) = coords[0]?.takeIf { it.isAttached }?.localToScreen(p) ?: p
            var last = screen(down.position)
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val now = screen(change.position)
                val dx = (now.x - last.x).roundToInt()
                val dy = (now.y - last.y).roundToInt()
                if (dx != 0 || dy != 0) {
                    change.consume()
                    currentDrag(dx, dy)
                    last = Offset(last.x + dx, last.y + dy)
                }
            }
        }
    }
}

/** 再生の位置（ミリ秒）を m:ss に */
private fun playTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** シークバーと時間を読み直す間隔 */
private const val PROGRESS_INTERVAL_MS = 250L
