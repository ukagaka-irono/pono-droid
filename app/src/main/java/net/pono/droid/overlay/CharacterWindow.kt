package net.pono.droid.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import net.pono.baseware.ghost.GhostController
import kotlin.math.abs

/**
 * キャラクター1人分のオーバーレイ。サーフェスとバルーンを別々のウィンドウにし、バルーンをサーフェスの真上に置く。
 *
 * Android のオーバーレイは透明な画素のタッチを下のアプリに渡せない（ウィンドウの矩形の中はすべてこちらが受け取る）。
 * 1つのウィンドウに縦に並べるとキャラクターの横の空きまでタッチを奪うので、分けてそれぞれ中身の大きさにする。
 * どちらも画面の下端を基準に置くので、バルーンが出てもキャラクターの位置は動かない。
 */
class CharacterWindow(
    private val context: Context,
    private val scopeId: Int,
    private val controller: GhostController,
    private val renderer: SurfaceRenderer,
    private val balloonSkin: BalloonSkin?,
    private val prefs: SharedPreferences,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private val surfaceView = ComposeView(context)
    private val balloonView = ComposeView(context)
    private val alignEnd = scopeId == 0

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or (if (alignEnd) Gravity.END else Gravity.START)
        // ウィンドウの出入りや移動のアニメーションは要らない（ドラッグや再描画のたびに走ると重い）
        windowAnimations = 0
        if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
    }

    private val params = overlayParams().apply {
        val density = context.resources.displayMetrics.density
        x = prefs.getInt(key("x"), (16 * density).toInt())
        y = prefs.getInt(key("y"), (48 * density).toInt())
    }
    private val balloonParams = overlayParams()

    /** サーフェスの高さ（px）。バルーンはこの分だけ上に置く */
    private var surfaceHeight = 0

    // アプリの画面で大きさを変えたら、起動中のゴーストにもすぐ反映する
    private val scale = mutableFloatStateOf(OverlaySettings.characterScale(prefs))
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == OverlaySettings.KEY_CHARACTER_SCALE) scale.floatValue = OverlaySettings.characterScale(p)
    }

    fun show() {
        for (v in listOf(surfaceView, balloonView)) {
            v.setViewTreeLifecycleOwner(owner)
            v.setViewTreeSavedStateRegistryOwner(owner)
        }
        val corner = if (alignEnd) Alignment.BottomEnd else Alignment.BottomStart
        surfaceView.setContent {
            FitWindow(corner, onSize = { w, h -> resize(surfaceView, params, w, h) }) {
                CharacterSurface(
                    scopeId = scopeId,
                    controller = controller,
                    renderer = renderer,
                    dpPerImagePx = scale.floatValue,
                    touchHandler = touchHandler,
                    onHeightChanged = { h ->
                        if (h != surfaceHeight) {
                            surfaceHeight = h
                            placeBalloon()
                        }
                    },
                )
            }
        }
        balloonView.setContent {
            FitWindow(corner, onSize = { w, h -> resize(balloonView, balloonParams, w, h) }) {
                CharacterBalloon(scopeId, controller, balloonSkin)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        owner.resume()
        windowManager.addView(surfaceView, params)
        placeBalloon(add = true)
    }

    /**
     * ウィンドウを中身と同じ大きさ（px）に固定する。
     * WRAP_CONTENT のままだと、文字が1つ増えたりアニメーションのコマが変わったりして再描画するたびに
     * ウィンドウの配置がやり直され（端末によってはそのたびにウィンドウのアニメーションも走る）、電池を食う。
     */
    private fun resize(view: ComposeView, p: WindowManager.LayoutParams, width: Int, height: Int) {
        if (p.width == width && p.height == height) return
        // 測っている最中にウィンドウを変えないよう、レイアウトが終わってから反映する
        view.post {
            if (p.width == width && p.height == height) return@post
            p.width = width
            p.height = height
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, p)
        }
    }

    /** false にすると、表示したままタッチを下（pono-droid の画面など）に通す */
    fun setTouchable(touchable: Boolean) {
        for ((view, p) in listOf(surfaceView to params, balloonView to balloonParams)) {
            val flags = if (touchable) {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (flags == p.flags) continue
            p.flags = flags
            if (view.isAttachedToWindow) windowManager.updateViewLayout(view, p)
        }
    }

    fun dismiss() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        owner.destroy()
        runCatching { windowManager.removeView(surfaceView) }
        runCatching { windowManager.removeView(balloonView) }
    }

    /** バルーンのウィンドウをサーフェスの真上に合わせる */
    private fun placeBalloon(add: Boolean = false) {
        balloonParams.x = params.x
        balloonParams.y = params.y + surfaceHeight
        if (add) {
            windowManager.addView(balloonView, balloonParams)
        } else if (balloonView.isAttachedToWindow) {
            windowManager.updateViewLayout(balloonView, balloonParams)
        }
    }

    private fun key(name: String) = "window.$scopeId.$name"

    /**
     * サーフェスに触れたときの処理。指の動きに合わせてウィンドウ自体を動かすので、画面座標（raw）で扱う。
     * タップは SSP と同じく1回ごとに知らせ、続けて叩いた回数を数える（1: クリック、2: ダブル、3以上: 多重）。
     * 動かさずに押し続けると長押し（右クリックの代わり）で、そのときはタップとして数えない。
     */
    val touchHandler = object : SurfaceTouchHandler {
        private val config = ViewConfiguration.get(context)
        private val slop = config.scaledTouchSlop
        private val multiTapSlop = config.scaledDoubleTapSlop
        private val multiTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private var pendingLongPress: Runnable? = null
        private var tapCount = 0
        private var lastTapTime = 0L
        private var lastTapRawX = 0f
        private var lastTapRawY = 0f

        private fun cancelLongPress() {
            pendingLongPress?.let(surfaceView::removeCallbacks)
            pendingLongPress = null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(
            event: MotionEvent,
            onTap: (x: Float, y: Float, count: Int) -> Unit,
            onLongPress: (x: Float, y: Float) -> Unit,
        ): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    val x = event.x
                    val y = event.y
                    cancelLongPress()
                    pendingLongPress = Runnable {
                        longPressed = true
                        tapCount = 0
                        surfaceView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        onLongPress(x, y)
                    }.also { surfaceView.postDelayed(it, longPressTimeout) }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && !longPressed && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        cancelLongPress()
                    }
                    if (dragging) {
                        // END / BOTTOM 基準なので、x と y は右下からの距離
                        params.x = startX + (if (alignEnd) -dx else dx).toInt()
                        params.y = startY - dy.toInt()
                        windowManager.updateViewLayout(surfaceView, params)
                        placeBalloon()
                    }
                }
                MotionEvent.ACTION_UP -> if (longPressed) {
                    // 長押しはもう知らせてある
                } else if (dragging) {
                    cancelLongPress()
                    prefs.edit().putInt(key("x"), params.x).putInt(key("y"), params.y).apply()
                    tapCount = 0
                } else {
                    cancelLongPress()
                    val continued = event.eventTime - lastTapTime <= multiTapTimeout &&
                        abs(event.rawX - lastTapRawX) <= multiTapSlop && abs(event.rawY - lastTapRawY) <= multiTapSlop
                    tapCount = if (continued) tapCount + 1 else 1
                    lastTapTime = event.eventTime
                    lastTapRawX = event.rawX
                    lastTapRawY = event.rawY
                    onTap(event.x, event.y, tapCount)
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    tapCount = 0
                }
            }
            return true
        }
    }
}

/**
 * 中身をウィンドウの大きさに縛られずに測り、その大きさを [onSize] で知らせる。中身が無ければ 0×0。
 * ウィンドウが中身より小さい間（大きくなる途中）は [corner] に寄せて描く。
 */
@Composable
private fun FitWindow(corner: Alignment, onSize: (Int, Int) -> Unit, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.wrapContentSize(corner, unbounded = true)) { measurables, _ ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        onSize(width, height)
        layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }
}

interface SurfaceTouchHandler {
    fun onTouch(
        event: MotionEvent,
        onTap: (x: Float, y: Float, count: Int) -> Unit,
        onLongPress: (x: Float, y: Float) -> Unit,
    ): Boolean
}

/** Service の中で Compose を動かすための LifecycleOwner / SavedStateRegistryOwner。 */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
