package net.pono.droid.overlay

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.ghost.BatteryInfo
import net.pono.baseware.ghost.DeviceInfo
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.GhostPlatform
import net.pono.baseware.ghost.GhostRepository
import net.pono.baseware.ghost.InputRequest
import net.pono.baseware.ghost.NetworkStatus
import net.pono.baseware.ghost.PreviousGhost
import net.pono.baseware.ghost.WindowState
import net.pono.baseware.shell.Shell
import net.pono.droid.AppVersion
import net.pono.droid.GhostStore
import net.pono.droid.MainActivity
import net.pono.droid.R
import net.pono.droid.TextViewerActivity
import java.io.File
import kotlin.math.roundToInt
import net.pono.shiori.yaya.RemoteShiori

/** ゴーストを常駐させる。キャラクターごとのオーバーレイウィンドウを持つ。 */
class GhostOverlayService : LifecycleService() {
    private var controller: GhostController? = null
    private val debuggable by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private var touchJob: Job? = null
    private var inputJob: Job? = null
    private var startJob: Job? = null
    private var loadingJob: Job? = null
    private var scopesJob: Job? = null
    private var positionsJob: Job? = null
    private var windowJob: Job? = null

    /** 動かし終えた \![move] と \4 / \5 の通し番号（キャラクターごと） */
    private val handledMoves = HashMap<Int, Int>()
    private val handledNudges = HashMap<Int, Int>()
    private var passThrough = false
    private val sound = SoundPlayer()
    private val windows = mutableListOf<CharacterWindow>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> controller?.close() ?: stopSelf()
            ACTION_UPDATE -> controller?.networkUpdate() ?: toast("ゴーストが起動していません")
            ACTION_SHOW -> controller?.restoreWindows("user")
            ACTION_START -> {
                startInForeground()
                val dirName = intent.getStringExtra(EXTRA_GHOST)
                val ghost = dirName?.let { GhostStore.repository(this).find(it) }
                if (ghost == null || !Settings.canDrawOverlays(this)) {
                    stopSelf()
                } else {
                    startGhost(ghost)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** @param previous 切り替える前のゴースト（OnGhostChanged で渡す）。ふつうの起動なら null */
    private fun startGhost(ghost: Ghost, previous: PreviousGhost? = null) {
        teardown()
        _loading.value = ghost.dir.name
        startJob = lifecycleScope.launch {
            // シェルの定義（surfaces*.txt）は大きいこともあるので、画面を描くスレッドの外で読む
            val loaded = withContext(Dispatchers.Default) {
                val shellDir = ghost.shellDir() ?: return@withContext null
                val repo = GhostStore.repository(this@GhostOverlayService)
                // 選んだバルーン（無ければゴーストの指定）。どちらも無ければ簡易バルーン
                Triple(Shell(shellDir), repo, ghost.balloon(repo.balloons))
            }
            if (loaded == null) {
                _loading.value = null
                toast("${ghost.name}: シェルがありません")
                stopSelf()
                return@launch
            }
            val (shell, repo, balloon) = loaded
            runGhost(ghost, previous, shell, repo, balloon)
        }
    }

    private fun runGhost(ghost: Ghost, previous: PreviousGhost?, shell: Shell, repo: GhostRepository, balloon: Balloon?) {
        val c = GhostController(
            ghost = ghost,
            shell = shell,
            balloon = balloon,
            repository = repo,
            basewareVersion = AppVersion.of(this).name,
            previous = previous,
            shioriFactory = RemoteShiori.factory(this),
            scope = lifecycleScope,
            platform = object : GhostPlatform {
                override fun deviceInfo(): DeviceInfo {
                    val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                    val metrics = resources.displayMetrics
                    return DeviceInfo(
                        osName = "Android",
                        osVersion = Build.VERSION.RELEASE.orEmpty(),
                        osBuild = Build.VERSION.SDK_INT.toString(),
                        arch = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                        cpuCount = Runtime.getRuntime().availableProcessors(),
                        memoryTotalMb = memory.totalMem / (1024 * 1024),
                        memoryFreeMb = memory.availMem / (1024 * 1024),
                        uptimeSeconds = SystemClock.elapsedRealtime() / 1000,
                        screenWidth = metrics.widthPixels,
                        screenHeight = metrics.heightPixels,
                        dpi = metrics.densityDpi,
                    )
                }

                override fun batteryInfo(): BatteryInfo? =
                    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::batteryInfo)

                override fun networkStatus(): NetworkStatus {
                    val cm = getSystemService(ConnectivityManager::class.java)
                    val network = cm.activeNetwork
                    return networkStatus(network?.let(cm::getNetworkCapabilities), network?.let(cm::getLinkProperties))
                }

                override fun showText(title: String, text: String) = TextViewerActivity.start(this@GhostOverlayService, title, text)

                override fun openSettings() {
                    startActivity(Intent(this@GhostOverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                override fun playSound(file: File, loop: Boolean, volume: Int?) = sound.play(file, loop, volume)

                override fun stopSound() = sound.stop()

                override suspend fun waitSound() = sound.await()

                override fun onShellChanged(shell: Shell) {
                    controller?.let { rebuildWindows(it) }
                }

                override fun onBalloonChanged(balloon: Balloon?) {
                    controller?.let { rebuildWindows(it) }
                }

                /** ウェブのページだけ開く。ゴーストのスクリプトに他のアプリ（電話や決済など）を起動させない */
                override fun openUrl(url: String) {
                    val uri = Uri.parse(url)
                    if (uri.scheme?.lowercase() !in setOf("http", "https")) {
                        Log.w(TAG, "unsupported url scheme: $url")
                        return
                    }
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: ActivityNotFoundException) {
                        toast("ブラウザが見つかりません")
                    }
                }

                override fun onQuit() {
                    teardown()
                    stopSelf()
                }

                /** いまのゴーストを閉じて、サービスは止めずに次のゴーストを起こす */
                override fun switchGhost(dirName: String, previous: PreviousGhost) {
                    val next = GhostStore.repository(this@GhostOverlayService).find(dirName)
                    if (next == null) {
                        onQuit()
                        return
                    }
                    // 閉じる処理の途中で呼ばれるので、落ち着いてから入れ替える
                    lifecycleScope.launch { startGhost(next, previous) }
                }

                // ゴーストとのやりとり（ユーザーの操作も含む）はデバッグビルドでだけ残す
                override fun log(message: String) {
                    if (debuggable) Log.i(TAG, message)
                }

                override fun onError(message: String) {
                    Log.w(TAG, message)
                    toast(message)
                }
            },
        )
        controller = c
        _controller.value = c
        _running.value = ghost.dir.name
        c.setScreenOn(getSystemService(PowerManager::class.java).isInteractive)
        createWindows(c)
        watchPositions(c)
        watchWindowState(c)
        // pono-droid の画面が前にある間は、キャラクターがボタンに重なっても押せるようにタッチを通す。
        // ただし入力の箱を出している間は、箱に触れられるように通さない
        touchJob = lifecycleScope.launch {
            combine(appInFront, c.state.map { it.input != null }) { inFront, typing -> inFront && !typing }
                .distinctUntilChanged()
                .collect { pass ->
                    passThrough = pass
                    windows.forEach { it.setTouchable(!pass) }
                }
        }
        // 入力の箱は \0 のバルーンに出す。開いている間だけ、そのウィンドウがキーボードの入力を受ける
        inputJob = lifecycleScope.launch {
            c.state.map { it.input?.kind }.distinctUntilChanged().collect { applyInput(c) }
        }
        c.start()
        // SHIORI を読み終わるまで、アプリの一覧にも読み込み中と出す（start で読み込み中になる）
        loadingJob = lifecycleScope.launch {
            c.state.first { it.loading == null }
            _loading.value = null
        }
    }

    /** いまのシェルとバルーン（キャラクターのウィンドウで共有する） */
    private var renderer: SurfaceRenderer? = null
    private var balloonSkin: BalloonSkin? = null

    /**
     * キャラクターのウィンドウを、いまのシェルとバルーンで作る。\0 と \1 のほか、3人目以降のキャラクター（\p[2] など）にも作り、
     * スクリプトが新しい \p[N] に話しかけたら、そのときに足す
     */
    private fun createWindows(c: GhostController) {
        renderer = SurfaceRenderer(c.shell)
        balloonSkin = c.balloon?.let(::BalloonSkin)
        scopesJob?.cancel()
        scopesJob = lifecycleScope.launch {
            c.state.map { it.characters.keys }.distinctUntilChanged().collect { scopes -> ensureWindows(c, scopes) }
        }
    }

    /** \![move]・\![set,position]・\4 / \5・\![set,alignmenttodesktop] を、キャラクターのウィンドウに伝える */
    private fun watchPositions(c: GhostController) {
        handledMoves.clear()
        handledNudges.clear()
        positionsJob?.cancel()
        positionsJob = lifecycleScope.launch {
            c.state.map { s ->
                s.characters.mapValues { (_, ch) -> listOf(ch.move?.first, ch.pinned, ch.nudge?.first, ch.alignment, ch.balloonAlign, ch.balloonOffset) }
            }
                .distinctUntilChanged()
                .collect { applyPositions(c) }
        }
    }

    private fun applyPositions(c: GhostController) {
        for ((scope, ch) in c.state.value.characters) {
            val window = windows.firstOrNull { it.scopeId == scope } ?: continue
            window.setPinned(ch.pinned)
            window.setAlignment(ch.alignment)
            window.refreshBalloon()
            ch.nudge?.let { (serial, away) ->
                if (handledNudges[scope] == serial) return@let
                handledNudges[scope] = serial
                // 相手は、\0 なら \1、ほかのキャラクターなら \0
                val partner = windows.firstOrNull { it.scopeId == if (scope == 0) 1 else 0 } ?: return@let
                window.nudge(away, partner.screenRect())
            }
            val (serial, move) = ch.move ?: continue
            if (handledMoves[scope] == serial) continue
            handledMoves[scope] = serial
            window.move(move) { id -> windows.firstOrNull { it.scopeId == id }?.screenRect() }
        }
    }

    /** 最小化・重なり順・位置の初期化・最初の位置を、キャラクターのウィンドウに伝える */
    private fun watchWindowState(c: GhostController) {
        windowJob?.cancel()
        windowJob = lifecycleScope.launch {
            var previous: WindowState? = null
            c.state.map { it.window }.distinctUntilChanged().collect { w ->
                val before = previous
                previous = w
                if (w.minimized != before?.minimized) {
                    windows.forEach { it.setHidden(w.minimized) }
                    // 隠している間は、通知から出し直せるようにする
                    if (before != null) startInForeground(minimized = w.minimized)
                }
                if (before != null && (w.zorder != before.zorder || w.frontRequests != before.frontRequests)) restack(w)
                if (before != null && w.resetRequests != before.resetRequests) {
                    windows.forEach { it.resetPosition(w.defaultPositions[it.scopeId]) }
                }
                if (w.defaultPositions != before?.defaultPositions) {
                    windows.forEach { it.applyDefault(w.defaultPositions[it.scopeId]) }
                }
            }
        }
    }

    /**
     * ウィンドウを付け直して重なり順を決める（後から付けたものほど上）。いつもの順は \1 → 3人目以降 → \0 で、
     * それぞれサーフェスの上にバルーン。\![set,zorder] の組があれば、組の中だけその順に並べ替える
     */
    private fun restack(w: WindowState) {
        val order = windows.map { it.scopeId }.sortedBy { if (it == 0) Int.MAX_VALUE else it }
            .flatMap { listOf("s$it", "b$it") }.toMutableList()
        for (group in w.zorder) {
            val slots = order.indices.filter { order[it] in group }
            // 組は手前から並べてあるので、下（先に付ける方）から埋める
            val members = group.filter { it in order }.reversed()
            slots.zip(members).forEach { (i, token) -> order[i] = token }
        }
        windows.forEach { it.detach() }
        for (token in order) {
            windows.firstOrNull { it.scopeId == token.drop(1).toInt() }?.attach(balloon = token[0] == 'b')
        }
        applyInput(controller ?: return)
    }

    /** sticky-window の組で、[scope] と一緒に動くキャラクターのウィンドウ */
    private fun stickyPartners(scope: Int): List<CharacterWindow> {
        val group = controller?.state?.value?.window?.stickyGroups?.firstOrNull { scope in it } ?: return emptyList()
        return windows.filter { it.scopeId != scope && it.scopeId in group }
    }

    /** キャラクターの重なりと見切れを調べて、変わっていればゴーストに伝える（OnOverlap / OnOffscreen） */
    private fun reportLayout() {
        val c = controller ?: return
        val shown = windows.filter { (c.state.value.characters[it.scopeId]?.surface ?: -1) >= 0 }
            .map { it.scopeId to it.screenRect() }
            .filter { (_, r) -> !r.isEmpty }
        val screen = windows.firstOrNull()?.screenBounds() ?: return
        val overlaps = shown.flatMapIndexed { i, (a, ra) ->
            shown.drop(i + 1).filter { (_, rb) -> Rect.intersects(ra, rb) }.map { (b, _) -> a to b }
        }
        val offscreen = shown.filter { (_, r) -> !screen.contains(r) }.map { it.first }
        c.onWindowLayout(overlaps, offscreen)
    }

    /** 入力の箱を開いているかを \0 のウィンドウに伝える（作り直したウィンドウにも） */
    private fun applyInput(c: GhostController) {
        val kind = c.state.value.input?.kind
        windows.firstOrNull { it.scopeId == 0 }?.setInputOpen(kind != null, secure = kind == InputRequest.Kind.PASSWORD)
    }

    private fun ensureWindows(c: GhostController, scopes: Set<Int>) {
        val shown = windows.map { it.scopeId }.toSet()
        // 後から足したウィンドウほど上に重なるので、\1 → 3人目以降 → \0 の順に作る（\0 がいちばん上）
        val order = scopes.filter { it !in shown }.sortedBy { if (it == 0) Int.MAX_VALUE else it }
        val prefs = OverlaySettings.prefs(this)
        val w = c.state.value.window
        for (scope in order) {
            windows += CharacterWindow(this, scope, c, renderer ?: return, balloonSkin, prefs).also {
                it.onShift = { dx, dy -> stickyPartners(scope).forEach { p -> p.shiftBy(dx, dy) } }
                it.onSettled = {
                    stickyPartners(scope).forEach(CharacterWindow::savePosition)
                    reportLayout()
                }
                it.show()
                it.setTouchable(!passThrough)
                it.setHidden(w.minimized)
                it.applyDefault(w.defaultPositions[scope])
            }
        }
        if (order.isEmpty()) return
        if (w.zorder.isNotEmpty()) restack(w)
        // 後から出てきたキャラクターへの \![move] も、ウィンドウができてから動かす
        applyPositions(c)
    }

    /** シェルやバルーンが切り替わったら、ウィンドウを作り直す */
    private fun rebuildWindows(c: GhostController) {
        windows.forEach { it.dismiss() }
        windows.clear()
        createWindows(c)
    }

    private fun teardown() {
        startJob?.cancel()
        startJob = null
        loadingJob?.cancel()
        loadingJob = null
        _loading.value = null
        sound.stop()
        touchJob?.cancel()
        touchJob = null
        inputJob?.cancel()
        inputJob = null
        scopesJob?.cancel()
        scopesJob = null
        positionsJob?.cancel()
        positionsJob = null
        windowJob?.cancel()
        windowJob = null
        windows.forEach { it.dismiss() }
        windows.clear()
        controller = null
        _controller.value = null
        _running.value = null
    }

    /** 画面が消えている間は、ゴーストのタイマーとアニメーションを止める */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val c = controller ?: return
            when (intent.action) {
                // Android にはパソコンのような「休止」が無いので、画面が消えたら休んだことにする（OnSysSuspend）
                Intent.ACTION_SCREEN_OFF -> {
                    c.setScreenOn(false)
                    c.onSystemSuspend()
                }
                Intent.ACTION_SCREEN_ON -> {
                    c.setScreenOn(true)
                    // ロック画面の上にはキャラクターが出ないので、ロックがあれば解いてから OnSysResume
                    if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) c.onSystemResume()
                }
                Intent.ACTION_USER_PRESENT -> c.onSystemResume()
            }
        }
    }

    /** 既定のネットワークの今の様子。コールバックで届いたものを覚えておく */
    private var networkCaps: NetworkCapabilities? = null
    private var networkLink: LinkProperties? = null

    /** ネットワークが変わったらゴーストに伝える（OnNetworkStatusChange。送るかどうかは controller が見る） */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networkCaps = caps
            pushNetwork()
        }

        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
            networkLink = link
            pushNetwork()
        }

        override fun onLost(network: Network) {
            networkCaps = null
            networkLink = null
            pushNetwork()
        }

        // コールバックは別のスレッドで来るので、ゴーストには画面のスレッドから伝える
        private fun pushNetwork() {
            val status = networkStatus(networkCaps, networkLink)
            lifecycleScope.launch { controller?.onNetworkChanged(status) }
        }
    }

    /** SSP の OnNetworkStatusChange の形にする。従量制かどうかしか分からないので、従量制なら fixed（上限のある定額）とする */
    private fun networkStatus(caps: NetworkCapabilities?, link: LinkProperties?): NetworkStatus {
        if (caps == null) return NetworkStatus(online = false, type = "none", metering = "unknown")
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        // IPv6 の「%wlan0」のようなインターフェース名は落とす
        val addresses = link?.linkAddresses.orEmpty()
            .mapNotNull { it.address?.takeIf { a -> !a.isLoopbackAddress }?.hostAddress?.substringBefore('%') }
        val mbps = caps.linkDownstreamBandwidthKbps / 8000.0
        return NetworkStatus(
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            addresses = addresses,
            type = type,
            maxMbps = mbps,
            effectiveMbps = mbps,
            metering = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "unrestricted" else "fixed",
        )
    }

    /** 電池の状態はゴーストに伝える（OnBatteryNotify。変わったときだけ送るかは controller が見る） */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller?.onBatteryChanged(batteryInfo(intent))
        }
    }

    private fun batteryInfo(intent: Intent): BatteryInfo {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return BatteryInfo(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
    }

    /** アプリの画面で変えたキャラクターの大きさ。変わったらゴーストに伝える（OnShellScaling） */
    private var characterScale = 1f
    /** バルーンの大きさ（OnBalloonScaling） */
    private var balloonScale = 1f
    private val scaleListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            OverlaySettings.KEY_CHARACTER_SCALE -> {
                val old = characterScale
                characterScale = OverlaySettings.characterScale(p)
                controller?.onShellScaling((characterScale * 100).roundToInt(), (old * 100).roundToInt())
            }
            OverlaySettings.KEY_BALLOON_SCALE -> {
                val old = balloonScale
                balloonScale = OverlaySettings.balloonScale(p)
                controller?.onBalloonScaling((balloonScale * 100).roundToInt(), (old * 100).roundToInt())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        OverlaySettings.prefs(this).let {
            characterScale = OverlaySettings.characterScale(it)
            balloonScale = OverlaySettings.balloonScale(it)
            it.registerOnSharedPreferenceChangeListener(scaleListener)
        }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    /** 画面の向きが変わった（OnDisplayChange） */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        controller?.onDisplayChanged(metrics.widthPixels, metrics.heightPixels)
    }

    override fun onDestroy() {
        OverlaySettings.prefs(this).unregisterOnSharedPreferenceChangeListener(scaleListener)
        unregisterReceiver(screenReceiver)
        unregisterReceiver(batteryReceiver)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        teardown()
        super.onDestroy()
    }

    /** 常駐の通知を出す（出し直す）。[minimized] ならキャラクターを隠しているので、「表示」で出し直せるようにする */
    private fun startInForeground(minimized: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        // 重要度が「低」だと「サイレント」欄に折りたたまれて、終了などのボタンが見えない。
        // 「標準」にして、音とバイブレーションだけ切る（チャンネルの重要度は後から変えられないので作り直す）
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "常駐中のゴースト", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, GhostOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val update = PendingIntent.getService(
            this, 1, Intent(this, GhostOverlayService::class.java).setAction(ACTION_UPDATE), PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getService(
            this, 2, Intent(this, GhostOverlayService::class.java).setAction(ACTION_SHOW), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = if (minimized) "ゴーストを隠しています" else "ゴーストが起動しています"
        // 通知は先頭のもの以外は折りたたまれ、標準のボタン（addAction）は隠れてしまう。
        // 折りたたまれた1行の中にもボタンを置いておく
        val collapsed = RemoteViews(packageName, R.layout.notification_ghost).apply {
            setTextViewText(R.id.notification_title, title)
            setViewVisibility(R.id.notification_show, if (minimized) android.view.View.VISIBLE else android.view.View.GONE)
            setOnClickPendingIntent(R.id.notification_show, show)
            setOnClickPendingIntent(R.id.notification_update, update)
            setOnClickPendingIntent(R.id.notification_stop, stop)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            // 隠している間は、通知に触れたら出し直す
            .setContentIntent(if (minimized) show else open)
        if (minimized) builder.addAction(0, "表示", show)
        val notification: Notification = builder
            .addAction(0, "ネットワーク更新", update)
            .addAction(0, "終了", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "pono"
        private const val CHANNEL = "ghost_running"
        private const val OLD_CHANNEL = "ghost"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "net.pono.droid.START"
        private const val ACTION_STOP = "net.pono.droid.STOP"
        private const val ACTION_UPDATE = "net.pono.droid.UPDATE"
        private const val ACTION_SHOW = "net.pono.droid.SHOW"
        private const val EXTRA_GHOST = "ghost"

        private val _appInFront = MutableStateFlow(false)

        /**
         * pono-droid の画面が前にあるか。その間はゴーストのウィンドウがタッチを奪わないようにする
         * （キャラクターがアプリのボタンに重なって押せなくなるため）。
         */
        val appInFront: StateFlow<Boolean> = _appInFront.asStateFlow()

        fun setAppInFront(inFront: Boolean) {
            _appInFront.value = inFront
        }

        private val _controller = MutableStateFlow<GhostController?>(null)

        /** 起動中のゴースト */
        val controller: StateFlow<GhostController?> = _controller.asStateFlow()

        private val _running = MutableStateFlow<String?>(null)

        /** 起動中のゴーストのディレクトリ名（起動していなければ null） */
        val running: StateFlow<String?> = _running.asStateFlow()

        private val _loading = MutableStateFlow<String?>(null)

        /** 起動しようとして、シェルや SHIORI を読み込んでいる最中のゴースト（ディレクトリ名） */
        val loading: StateFlow<String?> = _loading.asStateFlow()

        fun start(context: Context, ghostDirName: String) {
            context.startForegroundService(
                Intent(context, GhostOverlayService::class.java).setAction(ACTION_START).putExtra(EXTRA_GHOST, ghostDirName),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GhostOverlayService::class.java).setAction(ACTION_STOP))
        }

        /** 起動中のゴーストのネットワーク更新 */
        fun update(context: Context) {
            context.startService(Intent(context, GhostOverlayService::class.java).setAction(ACTION_UPDATE))
        }
    }
}
