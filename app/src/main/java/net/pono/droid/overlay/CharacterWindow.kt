package net.pono.droid.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.ComposeView
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
 * キャラクター1人分のオーバーレイウィンドウ。サーフェスとバルーンを縦に並べる。
 * ウィンドウは画面の下端を基準に置くので、バルーンが出てもキャラクターの位置は動かない。
 */
class CharacterWindow(
    private val context: Context,
    private val scopeId: Int,
    private val controller: GhostController,
    private val renderer: SurfaceRenderer,
    private val prefs: SharedPreferences,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayOwner()
    private val view = ComposeView(context)
    private val alignEnd = scopeId == 0

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or (if (alignEnd) Gravity.END else Gravity.START)
        val density = context.resources.displayMetrics.density
        x = prefs.getInt(key("x"), (16 * density).toInt())
        y = prefs.getInt(key("y"), (48 * density).toInt())
    }

    // アプリの画面で大きさを変えたら、起動中のゴーストにもすぐ反映する
    private val scale = mutableFloatStateOf(OverlaySettings.characterScale(prefs))
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == OverlaySettings.KEY_CHARACTER_SCALE) scale.floatValue = OverlaySettings.characterScale(p)
    }

    fun show() {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setContent {
            CharacterContent(
                scopeId = scopeId,
                controller = controller,
                renderer = renderer,
                alignEnd = alignEnd,
                dpPerImagePx = scale.floatValue,
                touchHandler = touchHandler,
            )
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        owner.resume()
        windowManager.addView(view, params)
    }

    fun dismiss() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        owner.destroy()
        runCatching { windowManager.removeView(view) }
    }

    private fun key(name: String) = "window.$scopeId.$name"

    /**
     * サーフェスに触れたときの処理。指の動きに合わせてウィンドウ自体を動かすので、画面座標（raw）で扱う。
     * タップは SSP と同じく1回ごとに知らせ、続けて叩いた回数を数える（1: クリック、2: ダブル、3以上: 多重）。
     */
    val touchHandler = object : SurfaceTouchHandler {
        private val config = ViewConfiguration.get(context)
        private val slop = config.scaledTouchSlop
        private val multiTapSlop = config.scaledDoubleTapSlop
        private val multiTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var tapCount = 0
        private var lastTapTime = 0L
        private var lastTapRawX = 0f
        private var lastTapRawY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(event: MotionEvent, onTap: (x: Float, y: Float, count: Int) -> Unit): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        // END / BOTTOM 基準なので、x と y は右下からの距離
                        params.x = startX + (if (alignEnd) -dx else dx).toInt()
                        params.y = startY - dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) {
                    prefs.edit().putInt(key("x"), params.x).putInt(key("y"), params.y).apply()
                    tapCount = 0
                } else {
                    val continued = event.eventTime - lastTapTime <= multiTapTimeout &&
                        abs(event.rawX - lastTapRawX) <= multiTapSlop && abs(event.rawY - lastTapRawY) <= multiTapSlop
                    tapCount = if (continued) tapCount + 1 else 1
                    lastTapTime = event.eventTime
                    lastTapRawX = event.rawX
                    lastTapRawY = event.rawY
                    onTap(event.x, event.y, tapCount)
                }
                MotionEvent.ACTION_CANCEL -> tapCount = 0
            }
            return true
        }
    }
}

interface SurfaceTouchHandler {
    fun onTouch(event: MotionEvent, onTap: (x: Float, y: Float, count: Int) -> Unit): Boolean
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
