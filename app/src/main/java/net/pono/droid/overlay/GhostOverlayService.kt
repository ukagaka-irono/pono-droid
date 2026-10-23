package net.pono.droid.overlay

import android.app.ActivityManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.ghost.BatteryInfo
import net.pono.baseware.ghost.DeviceInfo
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.GhostPlatform
import net.pono.baseware.shell.Shell
import net.pono.droid.GhostStore
import net.pono.droid.MainActivity
import net.pono.droid.R
import net.pono.droid.TextViewerActivity
import java.io.File
import net.pono.shiori.yaya.RemoteYayaShiori

/** ゴーストを常駐させる。キャラクターごとのオーバーレイウィンドウを持つ。 */
class GhostOverlayService : LifecycleService() {
    private var controller: GhostController? = null
    private val debuggable by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private var touchJob: Job? = null
    private var inputJob: Job? = null
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

    private fun startGhost(ghost: Ghost) {
        teardown()
        val shellDir = ghost.shellDir() ?: run {
            toast("${ghost.name}: シェルがありません")
            stopSelf()
            return
        }
        val shell = Shell(shellDir)
        val repo = GhostStore.repository(this)
        // 選んだバルーン（無ければゴーストの指定）。どちらも無ければ簡易バルーン
        val balloon = ghost.balloon(repo.balloons)
        val c = GhostController(
            ghost = ghost,
            shell = shell,
            balloon = balloon,
            repository = repo,
            shioriFactory = RemoteYayaShiori.factory(this),
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
        // pono-droid の画面が前にある間と入力の箱を出している間は、キャラクターがボタンやキーボードに重なっても押せるようにタッチを通す
        touchJob = lifecycleScope.launch {
            combine(appInFront, c.state.map { it.input != null }) { inFront, typing -> inFront || typing }
                .distinctUntilChanged()
                .collect { pass ->
                    passThrough = pass
                    windows.forEach { it.setTouchable(!pass) }
                }
        }
        // 入力の箱が開いたら、キーボードを使える画面を出す（開き直されたときも）
        inputJob = lifecycleScope.launch {
            c.state.map { it.input?.serial }.distinctUntilChanged().filterNotNull().collect {
                InputActivity.start(this@GhostOverlayService)
            }
        }
        c.start()
    }

    /** キャラクターのウィンドウを、いまのシェルとバルーンで作る */
    private fun createWindows(c: GhostController) {
        val renderer = SurfaceRenderer(c.shell)
        val balloonSkin = c.balloon?.let(::BalloonSkin)
        val prefs = OverlaySettings.prefs(this)
        for (scope in listOf(1, 0)) {
            windows += CharacterWindow(this, scope, c, renderer, balloonSkin, prefs).also {
                it.show()
                it.setTouchable(!passThrough)
            }
        }
    }

    /** シェルやバルーンが切り替わったら、ウィンドウを作り直す */
    private fun rebuildWindows(c: GhostController) {
        windows.forEach { it.dismiss() }
        windows.clear()
        createWindows(c)
    }

    private fun teardown() {
        sound.stop()
        touchJob?.cancel()
        touchJob = null
        inputJob?.cancel()
        inputJob = null
        windows.forEach { it.dismiss() }
        windows.clear()
        controller = null
        _controller.value = null
        _running.value = null
    }

    /** 画面が消えている間は、ゴーストのタイマーとアニメーションを止める */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller?.setScreenOn(intent.action == Intent.ACTION_SCREEN_ON)
        }
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
            },
        )
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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

        /** 起動中のゴースト。入力の箱の画面（[InputActivity]）が答えを返すのに使う */
        val controller: StateFlow<GhostController?> = _controller.asStateFlow()

        private val _running = MutableStateFlow<String?>(null)

        /** 起動中のゴーストのディレクトリ名（起動していなければ null） */
        val running: StateFlow<String?> = _running.asStateFlow()

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
