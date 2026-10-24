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
import net.pono.baseware.shell.Shell
import net.pono.droid.AppVersion
import net.pono.droid.GhostStore
import net.pono.droid.MainActivity
import net.pono.droid.R
import net.pono.droid.TextViewerActivity
import java.io.File
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

    /** 動かし終えた \![move] の通し番号（キャラクターごと） */
    private val handledMoves = HashMap<Int, Int>()
    private var passThrough = false
    private val sound = SoundPlayer()
    private val windows = mutableListOf<CharacterWindow>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> controller?.close() ?: stopSelf()
            ACTION_UPDATE -> controller?.networkUpdate() ?: toast("ゴーストが起動していません")
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

    /** \![move] と \![set,position] を、キャラクターのウィンドウに伝える */
    private fun watchPositions(c: GhostController) {
        handledMoves.clear()
        positionsJob?.cancel()
        positionsJob = lifecycleScope.launch {
            c.state.map { s -> s.characters.mapValues { (_, ch) -> ch.move?.first to ch.pinned } }
                .distinctUntilChanged()
                .collect { applyPositions(c) }
        }
    }

    private fun applyPositions(c: GhostController) {
        for ((scope, ch) in c.state.value.characters) {
            val window = windows.firstOrNull { it.scopeId == scope } ?: continue
            window.setPinned(ch.pinned)
            val (serial, move) = ch.move ?: continue
            if (handledMoves[scope] == serial) continue
            handledMoves[scope] = serial
            window.move(move) { id -> windows.firstOrNull { it.scopeId == id }?.screenRect() }
        }
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
        for (scope in order) {
            windows += CharacterWindow(this, scope, c, renderer ?: return, balloonSkin, prefs).also {
                it.show()
                it.setTouchable(!passThrough)
            }
        }
        // 後から出てきたキャラクターへの \![move] も、ウィンドウができてから動かす
        if (order.isNotEmpty()) applyPositions(c)
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

    override fun onCreate() {
        super.onCreate()
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
        unregisterReceiver(screenReceiver)
        unregisterReceiver(batteryReceiver)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        teardown()
        super.onDestroy()
    }

    private fun startInForeground() {
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
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // 通知は先頭のもの以外は折りたたまれ、標準のボタン（addAction）は隠れてしまう。
        // 折りたたまれた1行の中にもボタンを置いておく
        val collapsed = RemoteViews(packageName, R.layout.notification_ghost).apply {
            setTextViewText(R.id.notification_title, "ゴーストが起動しています")
            setOnClickPendingIntent(R.id.notification_update, update)
            setOnClickPendingIntent(R.id.notification_stop, stop)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ゴーストが起動しています")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setContentIntent(open)
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
