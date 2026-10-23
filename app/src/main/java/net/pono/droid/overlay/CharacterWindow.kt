package net.pono.droid.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
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
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.sakura.MoveCommand
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
    val scopeId: Int,
    private val controller: GhostController,
    private val renderer: SurfaceRenderer,
    private val balloonSkin: BalloonSkin?,
    private val prefs: SharedPreferences,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private val surfaceView = ComposeView(context)
    private val balloonView = ComposeView(context)
    // \0 は右下、\1 は左下。3人目以降は \0 の左に並べる（ドラッグで動かせる）
    private val alignEnd = scopeId != 1

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
        x = prefs.getInt(key("x"), defaultX(density))
        y = prefs.getInt(key("y"), defaultY(density))
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
        moveAnimator?.cancel()
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

    // ---- 入力の箱 ----

    /**
     * 入力の箱（\![open,inputbox] など）を開いた・閉じた。バルーンのウィンドウはふだんキーボードの入力を受けない
     * （受けると他のアプリで文字が打てなくなる）ので、開いている間だけ受けるようにする。
     * [secure] ならスクリーンショットや画面の記録に写さない（パスワード）。
     * キーボードが出ると、オーバーレイのウィンドウはシステムがキーボードの上に寄せるので、こちらで持ち上げなくてよい
     */
    fun setInputOpen(open: Boolean, secure: Boolean) {
        var flags = balloonParams.flags
        flags = if (open) flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        flags = if (open && secure) flags or WindowManager.LayoutParams.FLAG_SECURE else flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        balloonParams.flags = flags
        balloonParams.softInputMode = if (open) {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        if (balloonView.isAttachedToWindow) windowManager.updateViewLayout(balloonView, balloonParams)
    }

    // ---- \![move] と \![set,position] ----

    /** \![set,position] で固定されている間はドラッグで動かさない */
    private var pinned = false
    private var moveAnimator: ValueAnimator? = null

    /** 画面全体（ナビゲーションバーなども含む）。SSP の screen と同じく左上が原点 */
    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= 30) {
            windowManager.currentWindowMetrics.bounds
        } else {
            context.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
        }

    /** [origin] を測ったときの画面の大きさ。回転などで変わったら測り直す */
    private var originFor: Rect? = null
    private var originCache: Point? = null

    /**
     * ウィンドウの x / y を数える原点（下と右か左の端）の画面での位置。
     * ナビゲーションバーなどの分だけ画面の端からずれているので、実際のウィンドウの位置から割り出す。
     * 画面上の位置は配置が終わるまで params に追いつかないので、画面の大きさが変わらない限り最初に測ったものを使う
     */
    private fun origin(): Point {
        val bounds = screenBounds()
        originCache?.takeIf { originFor == bounds }?.let { return it }
        val loc = IntArray(2).also(surfaceView::getLocationOnScreen)
        val x = if (alignEnd) loc[0] + surfaceView.width + params.x else loc[0] - params.x
        return Point(x, loc[1] + surfaceView.height + params.y).also {
            originCache = it
            originFor = bounds
        }
    }

    /** サーフェスのウィンドウの、画面での矩形（SSP と同じく左上が原点） */
    fun screenRect(): Rect {
        val w = surfaceView.width
        val h = surfaceView.height
        val o = origin()
        val left = if (alignEnd) o.x - params.x - w else o.x + params.x
        val top = o.y - params.y - h
        return Rect(left, top, left + w, top + h)
    }

    /**
     * \![move]。[baseRect] は基準にするキャラクターの矩形（無ければ画面を基準にする）。
     * まだ大きさが決まっていなければ、測り終わってから動かす
     */
    fun move(command: MoveCommand, baseRect: (Int) -> Rect?) {
        surfaceView.doOnLayout {
            val own = screenRect()
            val base = when (val b = command.base) {
                MoveCommand.Base.Screen -> screenBounds()
                MoveCommand.Base.Me -> own
                is MoveCommand.Base.Scope -> baseRect(b.id) ?: screenBounds()
            }
            val left = command.x?.let { base.left + anchorX(base, command.baseOffset.h) + it - anchorX(own, command.moveOffset.h) }
            val top = command.y?.let { base.top + anchorY(base, command.baseOffset.v) + it - anchorY(own, command.moveOffset.v) }
            animateTo(left ?: own.left, top ?: own.top, command.timeMs, save = true)
        }
    }

    /** \![set,position,x,y]（null なら固定をやめる） */
    fun setPinned(position: Pair<Int, Int>?) {
        pinned = position != null
        position ?: return
        surfaceView.doOnLayout { animateTo(position.first, position.second, 0, save = false) }
    }

    /** 矩形の中の、横の基準の位置（矩形の左からの距離）。base は真ん中とする */
    private fun anchorX(r: Rect, edge: MoveCommand.Edge) = when (edge) {
        MoveCommand.Edge.START -> 0
        MoveCommand.Edge.END -> r.width()
        MoveCommand.Edge.CENTER, MoveCommand.Edge.BASE -> r.width() / 2
    }

    /** 矩形の中の、縦の基準の位置（矩形の上からの距離）。base は足元（下端）とする */
    private fun anchorY(r: Rect, edge: MoveCommand.Edge) = when (edge) {
        MoveCommand.Edge.START -> 0
        MoveCommand.Edge.CENTER -> r.height() / 2
        MoveCommand.Edge.END, MoveCommand.Edge.BASE -> r.height()
    }

    /** 左上を ([left], [top]) にする。画面の外には出さない。[timeMs] が 0 ならすぐに */
    private fun animateTo(left: Int, top: Int, timeMs: Long, save: Boolean) {
        moveAnimator?.cancel()
        val w = surfaceView.width
        val h = surfaceView.height
        val o = origin()
        // 下はウィンドウを置ける範囲の下端（ナビゲーションバーの上）まで
        fun clamp(v: Int, size: Int, min: Int, max: Int) = if (size >= max - min) v.coerceIn(max - size, min) else v.coerceIn(min, max - size)
        val bounds = screenBounds()
        val toLeft = clamp(left, w, bounds.left, bounds.right)
        val toTop = clamp(top, h, bounds.top, minOf(bounds.bottom, o.y))
        val toX = if (alignEnd) o.x - toLeft - w else toLeft - o.x
        val toY = o.y - toTop - h
        fun apply(x: Int, y: Int) {
            params.x = x
            params.y = y
            if (surfaceView.isAttachedToWindow) windowManager.updateViewLayout(surfaceView, params)
            placeBalloon()
        }
        fun finish() {
            apply(toX, toY)
            if (save) prefs.edit().putInt(key("x"), params.x).putInt(key("y"), params.y).apply()
        }
        if (timeMs <= 0) return finish()
        val fromX = params.x
        val fromY = params.y
        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = timeMs
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                apply(fromX + ((toX - fromX) * t).toInt(), fromY + ((toY - fromY) * t).toInt())
            }
            doOnEnd { finish() }
            start()
        }
    }

    private fun key(name: String) = "window.$scopeId.$name"

    /** 3人目以降の最初の位置。画面の右から 1/4 ずつずらし、3人ごとに一段上げる */
    private fun defaultX(density: Float): Int {
        if (scopeId < 2) return (16 * density).toInt()
        val width = context.resources.displayMetrics.widthPixels
        return (16 * density).toInt() + ((scopeId - 2) % 3 + 1) * width / 4
    }

    private fun defaultY(density: Float): Int = ((48 + if (scopeId < 2) 0 else (scopeId - 2) / 3 * 160) * density).toInt()

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
            onDown: (x: Float, y: Float) -> Unit,
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
                    onDown(x, y)
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
                    if (!dragging && !longPressed && !pinned && (abs(dx) > slop || abs(dy) > slop)) {
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
    /** [onDown] は指が触れたとき、[onTap] は動かさずに離したとき（[count] は続けて叩いた回数）、[onLongPress] は長押し */
    fun onTouch(
        event: MotionEvent,
        onDown: (x: Float, y: Float) -> Unit,
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
