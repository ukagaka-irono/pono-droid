package net.pono.droid.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Constraints
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnLayout
import androidx.core.view.drawToBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import net.pono.baseware.ghost.BalloonOffsets
import net.pono.baseware.ghost.DefaultPosition
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.ScreenRect
import net.pono.baseware.ghost.WindowGeometry
import net.pono.baseware.sakura.MoveCommand
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

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
    /**
     * 位置を覚えるときの、ゴーストの名前（ディレクトリ名）。一緒に動くゴーストが同じ場所に重ならないよう、ゴーストごとに覚える。
     * [sharedPositionFallback] なら、ゴーストごとの位置がまだ無いときに、ゴーストを分けずに覚えていた前の位置を使う
     */
    private val ghostDir: String,
    private val sharedPositionFallback: Boolean,
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
        x = savedPosition("x") ?: defaultX(density)
        y = savedPosition("y") ?: defaultY(density)
    }
    private val balloonParams = overlayParams()

    /** サーフェスの高さ（px）。バルーンはこの分だけ上に置く */
    private var surfaceHeight = 0

    // アプリの画面で大きさを変えたら、起動中のゴーストにもすぐ反映する
    private val scale = mutableFloatStateOf(OverlaySettings.characterScale(prefs))
    private val balloonScale = mutableFloatStateOf(OverlaySettings.balloonScale(prefs))
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            OverlaySettings.KEY_CHARACTER_SCALE -> scale.floatValue = OverlaySettings.characterScale(p)
            OverlaySettings.KEY_BALLOON_SCALE -> balloonScale.floatValue = OverlaySettings.balloonScale(p)
        }
    }

    /** ウィンドウが動いた（画面の px、右と下が正）。sticky-window の組の相手を一緒に動かすのに使う */
    var onShift: ((dx: Int, dy: Int) -> Unit)? = null

    /** 画面の上の今の位置（プロパティシステムの currentghost.scope(ID).rect など）。出していないウィンドウは null */
    fun geometry(scale: Double, balloonScale: Double): WindowGeometry {
        fun rect(v: View): ScreenRect? {
            if (v.visibility != View.VISIBLE || v.width <= 0 || !v.isAttachedToWindow) return null
            val at = IntArray(2).also(v::getLocationOnScreen)
            return ScreenRect(at[0], at[1], at[0] + v.width, at[1] + v.height)
        }
        return WindowGeometry(rect(surfaceView), rect(balloonView), scale, balloonScale)
    }

    /** バルーンの今の絵（\![execute,dumpballoon]）。まだ描いていなければ null */
    fun balloonBitmap(): Bitmap? = balloonView.takeIf { it.width > 0 && it.height > 0 }?.drawToBitmap()

    /** ドラッグや \![move] などで動き終えた。重なりと見切れ（OnOverlap / OnOffscreen）を調べ直すのに使う */
    var onSettled: (() -> Unit)? = null

    fun show() {
        for (v in listOf(surfaceView, balloonView)) {
            v.setViewTreeLifecycleOwner(owner)
            v.setViewTreeSavedStateRegistryOwner(owner)
            // 重なり順を変えるときにウィンドウを外して付け直すので、外しただけでは中身（アニメーションなど）を捨てない
            v.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(owner))
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
                            // 上の端に付けているときは、高さが変わっても上の端に合わせ直す（ウィンドウは下を基準に置くため）
                            if (alignment == "top") snapToEdge()
                        }
                    },
                )
            }
        }
        balloonView.setContent {
            FitWindow(corner, onSize = { w, h -> resize(balloonView, balloonParams, w, h) }) {
                // descript.txt の balloon.syncscale が true なら、バルーンもキャラクターの大きさに合わせる
                val bs = if (controller.balloonSyncScale(scopeId)) scale.floatValue else balloonScale.floatValue
                CompositionLocalProvider(LocalBalloonScale provides bs) {
                    CharacterBalloon(scopeId, controller, balloonSkin)
                }
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
            // 横や下に置くバルーンは、自分の大きさで位置が変わる
            if (view === balloonView && controller.state.value.characters[scopeId]?.balloonAlign != null) placeBalloon()
        }
    }

    /** \![set,balloonalign] / \![set,balloonoffset] が変わった */
    fun refreshBalloon() = placeBalloon()

    private var touchable = true
    private var hidden = false

    /** false にすると、表示したままタッチを下（pono-droid の画面など）に通す */
    fun setTouchable(touchable: Boolean) {
        this.touchable = touchable
        applyTouchable()
    }

    /** 最小化（\![set,windowstate,minimize]・メニューの「隠す」）。隠している間は描かず、タッチも下に通す */
    fun setHidden(hidden: Boolean) {
        if (this.hidden == hidden) return
        this.hidden = hidden
        for (v in listOf(surfaceView, balloonView)) v.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        applyTouchable()
    }

    private fun applyTouchable() {
        val touchable = touchable && !hidden
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
        detach()
    }

    // ---- 重なり順（\v・\![set,zorder]） ----

    /** 重なり順を変えるために、ウィンドウを外す。後から [attach] で付け直すと、付け直した順に上に重なる */
    fun detach() {
        runCatching { windowManager.removeView(surfaceView) }
        runCatching { windowManager.removeView(balloonView) }
    }

    /** [balloon] ならバルーンの、そうでなければサーフェスのウィンドウを付け直す */
    fun attach(balloon: Boolean) {
        val (view, p) = if (balloon) balloonView to balloonParams else surfaceView to params
        if (!view.isAttachedToWindow) runCatching { windowManager.addView(view, p) }
    }

    /**
     * バルーンのウィンドウをサーフェスに合わせる。いつもは真上で、\![set,balloonalign] や descript.txt の balloon.alignment なら
     * 左・右（上の端をそろえる）・真ん中の上・下に置く（auto は画面の真ん中より左にいれば右、右にいれば左）。
     * descript.txt の balloon.offsetx などの、その向きの既定のずれと \![set,balloonoffset] の分（シェル画像のピクセル）だけずらす
     */
    private fun placeBalloon(add: Boolean = false) {
        val c = controller.state.value.characters[scopeId]
        val pxPerImagePx = scale.floatValue * context.resources.displayMetrics.density
        val sw = if (params.width > 0) params.width else surfaceView.width
        val bw = balloonParams.width.coerceAtLeast(0)
        val bh = balloonParams.height.coerceAtLeast(0)
        val align = when (c?.balloonAlign) {
            "auto" -> {
                val screenWidth = context.resources.displayMetrics.widthPixels
                val center = if (alignEnd) screenWidth - params.x - sw / 2 else params.x + sw / 2
                if (center < screenWidth / 2) "right" else "left"
            }
            else -> c?.balloonAlign
        }
        // 既定のずれは、x がシェルに近づく向きで正（右に出すときは左向き）。ここでは右向きを正にする
        val defaults = c?.balloonOffsets ?: BalloonOffsets()
        val (dx, dy) = when (align) {
            "left" -> defaults.left
            "right" -> defaults.right.let { (x, y) -> -x to y }
            "bottom" -> defaults.bottom
            else -> defaults.center
        }
        // \![set,balloonoffset] があればそれ（@ 付きの座標は既定からずらす）
        val script = c?.balloonOffset
        val relative = c?.balloonOffsetRelative ?: (false to false)
        val offX = if (script == null) dx else if (relative.first) dx + script.first else script.first
        val offY = if (script == null) dy else if (relative.second) dy + script.second else script.second
        val ox = (offX * pxPerImagePx).toInt()
        val oy = (offY * pxPerImagePx).toInt()
        // x はウィンドウを寄せた側（\0 などは右、\1 は左）の端からの距離、y は下の端からの距離
        val (x, y) = when (align) {
            "center" -> params.x + (sw - bw) / 2 to params.y + surfaceHeight
            "bottom" -> params.x + (sw - bw) / 2 to params.y - bh
            "left" -> (if (alignEnd) params.x + sw else params.x - bw) to params.y + surfaceHeight - bh
            "right" -> (if (alignEnd) params.x - bw else params.x + sw) to params.y + surfaceHeight - bh
            else -> params.x to params.y + surfaceHeight
        }
        // 右と下へのずれ。右に寄せたウィンドウでは、右へずらすと端からの距離が減る
        balloonParams.x = x + if (alignEnd) -ox else ox
        balloonParams.y = y - oy
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
    fun screenBounds(): Rect =
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
    /** @param window 基準にするキャラクター（このゴーストの \p[ID] か、ほかのゴーストのもの）のウィンドウ。いなければ null */
    fun move(command: MoveCommand, window: (MoveCommand.Base) -> CharacterWindow?) {
        surfaceView.doOnLayout {
            val own = screenRect()
            // 基準にするキャラクターの矩形と基準点（画面が基準なら基準点は中央・下）。見つからなければ画面
            val other = when (command.base) {
                is MoveCommand.Base.Scope, is MoveCommand.Base.Ghost -> window(command.base)
                MoveCommand.Base.Screen, MoveCommand.Base.Me -> null
            }
            val base = when (command.base) {
                MoveCommand.Base.Screen -> screenBounds()
                MoveCommand.Base.Me -> own
                is MoveCommand.Base.Scope, is MoveCommand.Base.Ghost -> other?.screenRect() ?: screenBounds()
            }
            val basePoint = when (command.base) {
                MoveCommand.Base.Me -> basePoint()
                is MoveCommand.Base.Scope, is MoveCommand.Base.Ghost -> other?.basePoint()
                MoveCommand.Base.Screen -> null
            }
            val left = command.x?.let {
                base.left + anchorX(base, command.baseOffset.h, basePoint?.first) + it - anchorX(own, command.moveOffset.h, basePoint().first)
            }
            val top = command.y?.let {
                base.top + anchorY(base, command.baseOffset.v, basePoint?.second) + it - anchorY(own, command.moveOffset.v, basePoint().second)
            }
            animateTo(left ?: own.left, top ?: own.top, command.timeMs, save = true, sticky = !command.ignoreSticky)
        }
    }

    /** \4（[away]）/ \5。[partner] は相手のキャラクターの矩形。\5 は相手の横に並べ、\4 は自分の幅の半分だけ離す */
    fun nudge(away: Boolean, partner: Rect) {
        whenSized {
            val own = screenRect()
            val onLeft = own.centerX() < partner.centerX()
            val left = when {
                away && onLeft -> own.left - own.width() / 2
                away -> own.left + own.width() / 2
                onLeft -> partner.left - own.width()
                else -> partner.right
            }
            animateTo(left, own.top, NUDGE_TIME_MS, save = true, sticky = false)
        }
    }

    // ---- 画面の端に付ける（\![set,alignmenttodesktop]） ----

    /** top / bottom / left / right ならその端に付け、その向きにはドラッグで動かさない。null なら自由 */
    private var alignment: String? = null

    fun setAlignment(edge: String?) {
        if (alignment == edge) return
        alignment = edge
        if (edge != null) whenSized { snapToEdge() }
    }

    private fun snapToEdge() {
        val edge = alignment ?: return
        if (surfaceView.width == 0) return
        val own = screenRect()
        // 端の先を頼み、animateTo が画面の中に収める
        when (edge) {
            "top" -> animateTo(own.left, Int.MIN_VALUE / 2, 0, save = true, sticky = false)
            "bottom" -> animateTo(own.left, Int.MAX_VALUE / 2, 0, save = true, sticky = false)
            "left" -> animateTo(Int.MIN_VALUE / 2, own.top, 0, save = true, sticky = false)
            "right" -> animateTo(Int.MAX_VALUE / 2, own.top, 0, save = true, sticky = false)
        }
    }

    // ---- 最初の位置（sakura.defaultx など）と、位置の初期化 ----

    /** 位置を覚えていなければ、ゴーストの決めた最初の位置に置く */
    fun applyDefault(position: DefaultPosition?) {
        if (position == null || position.isEmpty || savedPosition("x") != null) return
        whenSized {
            val own = screenRect()
            val bounds = screenBounds()
            val (bx, by) = basePoint()
            val left = position.windowLeft(own.width(), bounds.width(), bx) ?: own.left
            val top = position.windowTop(own.height(), bounds.height(), by) ?: own.top
            animateTo(left, top, 0, save = false, sticky = false)
        }
    }

    /** \![execute,resetwindowpos]。覚えていた位置を忘れて、最初の位置に戻す */
    fun resetPosition(position: DefaultPosition?) {
        moveAnimator?.cancel()
        val editor = prefs.edit().remove(key("x")).remove(key("y"))
        if (sharedPositionFallback) editor.remove(sharedKey("x")).remove(sharedKey("y"))
        editor.apply()
        val density = context.resources.displayMetrics.density
        setWindowPosition(defaultX(density), defaultY(density), propagate = false)
        applyDefault(position)
        snapToEdge()
        onSettled?.invoke()
    }

    /** sticky-window の組の相手が動いた分だけ、一緒に動く（画面の px、右と下が正） */
    fun shiftBy(dx: Int, dy: Int) {
        moveAnimator?.cancel()
        setWindowPosition(params.x + if (alignEnd) -dx else dx, params.y - dy, propagate = false)
    }

    /** 今の位置を覚える（一緒に動いた相手の分は、動き終えてから） */
    fun savePosition() {
        prefs.edit().putInt(key("x"), params.x).putInt(key("y"), params.y).apply()
    }

    /** サーフェスの大きさが決まってから [block] を実行する（まだ画像を描いていなければ、描いてから） */
    private fun whenSized(block: () -> Unit) {
        if (surfaceView.isLaidOut && surfaceView.width > 0) return block()
        surfaceView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                if (v.width == 0) return
                v.removeOnLayoutChangeListener(this)
                block()
            }
        })
    }

    /**
     * ウィンドウの x / y（右か左の端と下の端からの距離）を変え、バルーンも合わせる。
     * [propagate] なら動いた分を [onShift] で知らせる
     */
    private fun setWindowPosition(x: Int, y: Int, propagate: Boolean) {
        val dx = if (alignEnd) params.x - x else x - params.x
        val dy = params.y - y
        params.x = x
        params.y = y
        if (surfaceView.isAttachedToWindow) windowManager.updateViewLayout(surfaceView, params)
        placeBalloon()
        if (propagate && (dx != 0 || dy != 0)) onShift?.invoke(dx, dy)
    }

    /** \![set,position,x,y]（null なら固定をやめる） */
    fun setPinned(position: Pair<Int, Int>?) {
        pinned = position != null
        position ?: return
        surfaceView.doOnLayout { animateTo(position.first, position.second, 0, save = false, sticky = true) }
    }

    /** 矩形の中の、横の基準の位置（矩形の左からの距離）。base は [base]（point.basepos）、無ければ真ん中 */
    private fun anchorX(r: Rect, edge: MoveCommand.Edge, base: Int? = null) = when (edge) {
        MoveCommand.Edge.START -> 0
        MoveCommand.Edge.END -> r.width()
        MoveCommand.Edge.CENTER -> r.width() / 2
        MoveCommand.Edge.BASE -> base ?: (r.width() / 2)
    }

    /** 矩形の中の、縦の基準の位置（矩形の上からの距離）。base は [base]（point.basepos）、無ければ足元（下端） */
    private fun anchorY(r: Rect, edge: MoveCommand.Edge, base: Int? = null) = when (edge) {
        MoveCommand.Edge.START -> 0
        MoveCommand.Edge.CENTER -> r.height() / 2
        MoveCommand.Edge.END -> r.height()
        MoveCommand.Edge.BASE -> base ?: r.height()
    }

    /**
     * いまのサーフェスの基準点（surfaces.txt の point.basepos。無ければ中央・下）の、ウィンドウの左上からの位置（px）。
     * SSP と同じく、最初の位置や \![move] の base はここを基準にする
     */
    fun basePoint(): Pair<Int, Int> {
        val c = controller.state.value.characters[scopeId]
        val def = c?.let { controller.shell.surfaces[it.surface] }
        val px = scale.floatValue * context.resources.displayMetrics.density
        val w = surfaceView.width
        val h = surfaceView.height
        val x = def?.baseposX?.let { (it * px * abs(c.scaleX)).toInt() } ?: (w / 2)
        val y = def?.baseposY?.let { (it * px * abs(c.scaleY)).toInt() } ?: h
        return x to y
    }

    /**
     * 左上を ([left], [top]) にする。画面の外には出さない。[timeMs] が 0 ならすぐに。
     * [sticky] なら sticky-window の組の相手も同じだけ動かす
     */
    private fun animateTo(left: Int, top: Int, timeMs: Long, save: Boolean, sticky: Boolean) {
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
        fun apply(x: Int, y: Int) = setWindowPosition(x, y, propagate = sticky)
        fun finish() {
            apply(toX, toY)
            if (save) savePosition()
            onSettled?.invoke()
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

    private fun key(name: String) = "window.$ghostDir.$scopeId.$name"

    /** ゴーストを分けずに覚えていたころの名前 */
    private fun sharedKey(name: String) = "window.$scopeId.$name"

    private fun savedPosition(name: String): Int? = when {
        prefs.contains(key(name)) -> prefs.getInt(key(name), 0)
        sharedPositionFallback && prefs.contains(sharedKey(name)) -> prefs.getInt(sharedKey(name), 0)
        else -> null
    }

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
     * 当たり判定の上をさっと払うと、なでたのに加えてジェスチャー（SSP のタッチパネルの OnMouseGesture）として知らせる
     */
    val touchHandler = object : SurfaceTouchHandler {
        private val config = ViewConfiguration.get(context)
        private val slop = config.scaledTouchSlop
        private val multiTapSlop = config.scaledDoubleTapSlop
        private val multiTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private val gestureDistance = GESTURE_MIN_DP * context.resources.displayMetrics.density
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false

        /** 当たり判定の上で触れたか。そこから指を動かすと、ウィンドウを動かさずになでる */
        private var strokeArea = false
        private var stroking = false
        private var lastStrokeTime = 0L
        private var pendingLongPress: Runnable? = null
        private var tapCount = 0
        private var lastTapTime = 0L
        private var lastTapRawX = 0f
        private var lastTapRawY = 0f

        /** 2本指で触れている。上下になぞるとホイール、動かさずに離すと中クリック */
        private var twoFingers = false
        private var twoFingerTime = 0L
        private var twoFingerY = 0f
        private var wheeled = false
        private val wheelStep = WHEEL_STEP_DP * context.resources.displayMetrics.density

        private fun midY(event: MotionEvent) = (event.getY(0) + event.getY(1)) / 2

        private fun cancelLongPress() {
            pendingLongPress?.let(surfaceView::removeCallbacks)
            pendingLongPress = null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(event: MotionEvent, callbacks: SurfaceTouchCallbacks): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = event.x
                    downY = event.y
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    stroking = false
                    twoFingers = false
                    strokeArea = callbacks.canStroke(event.x, event.y)
                    val x = event.x
                    val y = event.y
                    callbacks.onDown(x, y)
                    cancelLongPress()
                    pendingLongPress = Runnable {
                        longPressed = true
                        tapCount = 0
                        surfaceView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        callbacks.onLongPress(x, y)
                    }.also { surfaceView.postDelayed(it, longPressTimeout) }
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2 && !dragging && !longPressed) {
                    // 2本目の指が来たら、1本指の操作（なでる・長押し）はやめる
                    cancelLongPress()
                    twoFingers = true
                    wheeled = false
                    stroking = false
                    twoFingerTime = event.eventTime
                    twoFingerY = midY(event)
                }
                MotionEvent.ACTION_POINTER_UP -> if (twoFingers) {
                    twoFingers = false
                    // 動かさずにすぐ離したら中クリック。残った指ではもう何もしない
                    if (!wheeled && event.eventTime - twoFingerTime <= ViewConfiguration.getTapTimeout() * 2L) {
                        callbacks.onMiddleClick(event.getX(0), event.getY(0))
                    }
                    longPressed = true
                }
                MotionEvent.ACTION_MOVE -> if (twoFingers && event.pointerCount >= 2) {
                    // 2本指で上下になぞった分だけホイールを回す（上へなら正）
                    val moved = twoFingerY - midY(event)
                    if (abs(moved) >= wheelStep) {
                        wheeled = true
                        twoFingerY = midY(event)
                        callbacks.onWheel(event.getX(0), event.getY(0), (moved / wheelStep).toInt() * WHEEL_DELTA)
                    }
                } else {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && !stroking && !longPressed && (abs(dx) > slop || abs(dy) > slop)) {
                        if (strokeArea) {
                            stroking = true
                            cancelLongPress()
                        } else if (!pinned) {
                            dragging = true
                            cancelLongPress()
                            callbacks.onDragStart(downX, downY)
                        }
                    }
                    if (stroking) {
                        // 指の動きは細かく届くので、なでるのは 30ms に1回まで
                        if (event.eventTime - lastStrokeTime >= STROKE_INTERVAL_MS) {
                            lastStrokeTime = event.eventTime
                            callbacks.onStroke(event.x, event.y)
                        }
                    } else if (dragging) {
                        // END / BOTTOM 基準なので、x と y は右下からの距離。端に付けているときは、その向きには動かさない
                        val x = if (alignment == "left" || alignment == "right") params.x else startX + (if (alignEnd) -dx else dx).toInt()
                        val y = if (alignment == "top" || alignment == "bottom") params.y else startY - dy.toInt()
                        setWindowPosition(x, y, propagate = true)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (longPressed) {
                        // 長押しはもう知らせてある
                    } else if (stroking) {
                        // なでた後はタップにしない。さっと払ったならジェスチャーも知らせる
                        tapCount = 0
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (event.eventTime - event.downTime <= GESTURE_MAX_MS && hypot(dx, dy) >= gestureDistance) {
                            // 画面は下が正なので、上に払ったら正の角度にする
                            val angle = Math.toDegrees(atan2(-dy, dx).toDouble()).let { if (it < 0) it + 360 else it }
                            callbacks.onGesture(event.x, event.y, downX, downY, gestureDirection(angle), angle.roundToInt() % 360)
                        }
                    } else if (dragging) {
                        cancelLongPress()
                        savePosition()
                        tapCount = 0
                        callbacks.onDragEnd(event.x, event.y)
                        onSettled?.invoke()
                    } else {
                        cancelLongPress()
                        val continued = event.eventTime - lastTapTime <= multiTapTimeout &&
                            abs(event.rawX - lastTapRawX) <= multiTapSlop && abs(event.rawY - lastTapRawY) <= multiTapSlop
                        tapCount = if (continued) tapCount + 1 else 1
                        lastTapTime = event.eventTime
                        lastTapRawX = event.rawX
                        lastTapRawY = event.rawY
                        callbacks.onTap(event.x, event.y, tapCount)
                    }
                    callbacks.onRelease(event.x, event.y)
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    tapCount = 0
                    if (dragging) {
                        savePosition()
                        callbacks.onDragEnd(event.x, event.y)
                        onSettled?.invoke()
                    }
                    callbacks.onRelease(event.x, event.y)
                }
            }
            return true
        }
    }
}

/** 払った角度（右が 0 度で反時計回り）を、SSP の8方向の名前にする */
internal fun gestureDirection(angle: Double): String {
    val names = listOf("right", "right_up", "up", "left_up", "left", "left_down", "down", "right_down")
    return names[(((angle + 22.5) % 360) / 45).toInt().coerceIn(0, 7)]
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

/** なでる（OnMouseMove）を送る間隔の下限。指の動きのたびに送ると SHIORI を叩きすぎる */
private const val STROKE_INTERVAL_MS = 30L

/** ジェスチャーにする払い方。これより速く（短い時間で）、これより長く動かしたとき */
private const val GESTURE_MAX_MS = 400L
private const val GESTURE_MIN_DP = 48f

/** 2本指でこれだけなぞるごとに、ホイールを1目盛り（[WHEEL_DELTA]）回したことにする */
private const val WHEEL_STEP_DP = 24f
private const val WHEEL_DELTA = 120

/** \4 / \5 で動くのにかける時間 */
private const val NUDGE_TIME_MS = 200L

interface SurfaceTouchHandler {
    fun onTouch(event: MotionEvent, callbacks: SurfaceTouchCallbacks): Boolean
}

/** サーフェスへの指の操作。座標はウィンドウの中の px */
interface SurfaceTouchCallbacks {
    /** 指が触れた */
    fun onDown(x: Float, y: Float)

    /** 動かさずに離した。[count] は続けて叩いた回数 */
    fun onTap(x: Float, y: Float, count: Int)

    fun onLongPress(x: Float, y: Float)

    /** 触れた場所が当たり判定の上か。そうなら指を動かすとウィンドウは動かさずに [onStroke]（なでる）を呼ぶ */
    fun canStroke(x: Float, y: Float): Boolean

    fun onStroke(x: Float, y: Float)

    /** ウィンドウをドラッグで動かし始めた（[x] / [y] は触れた場所）・動かし終えた */
    fun onDragStart(x: Float, y: Float)

    fun onDragEnd(x: Float, y: Float)

    /** なでる途中でさっと払った。[direction] は SSP の8方向の名前、[angle] は右が 0 度の反時計回り */
    fun onGesture(x: Float, y: Float, startX: Float, startY: Float, direction: String, angle: Int)

    /** 2本指で上下になぞった。[amount] は上へなら正（1目盛り 120） */
    fun onWheel(x: Float, y: Float, amount: Int)

    /** 2本指で叩いた（中クリック） */
    fun onMiddleClick(x: Float, y: Float)

    /** 指が離れた（どの操作の後でも最後に呼ぶ） */
    fun onRelease(x: Float, y: Float)
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
