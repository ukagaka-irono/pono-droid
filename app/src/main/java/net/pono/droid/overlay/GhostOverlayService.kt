package net.pono.droid.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.PowerManager
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
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.GhostPlatform
import net.pono.baseware.shell.Shell
import net.pono.droid.GhostStore
import net.pono.droid.MainActivity
import net.pono.droid.R
import net.pono.shiori.yaya.RemoteYayaShiori

/** ゴーストを常駐させる。キャラクターごとのオーバーレイウィンドウを持つ。 */
class GhostOverlayService : LifecycleService() {
    private var controller: GhostController? = null
    private var touchJob: Job? = null
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
        val c = GhostController(
            ghost = ghost,
            shell = shell,
            shioriFactory = RemoteYayaShiori.factory(this),
            scope = lifecycleScope,
            platform = object : GhostPlatform {
                override fun openUrl(url: String) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }

                override fun onQuit() {
                    teardown()
                    stopSelf()
                }

                override fun log(message: String) {
                    Log.i(TAG, message)
                }

                override fun onError(message: String) {
                    Log.w(TAG, message)
                    toast(message)
                }
            },
        )
        controller = c
        _running.value = ghost.dir.name
        c.setScreenOn(getSystemService(PowerManager::class.java).isInteractive)
        val renderer = SurfaceRenderer(shell)
        // 選んだバルーン（無ければゴーストの指定）。どちらも無ければ簡易バルーン
        val balloonSkin = ghost.balloon(GhostStore.repository(this).balloons)?.let(::BalloonSkin)
        val prefs = OverlaySettings.prefs(this)
        for (scope in listOf(1, 0)) {
            windows += CharacterWindow(this, scope, c, renderer, balloonSkin, prefs).also { it.show() }
        }
        // pono-droid の画面が前にある間は、キャラクターがボタンに重なっても押せるようにタッチを通す
        touchJob = lifecycleScope.launch {
            appInFront.collect { inFront -> windows.forEach { it.setTouchable(!inFront) } }
        }
        c.start()
    }

    private fun teardown() {
        touchJob?.cancel()
        touchJob = null
        windows.forEach { it.dismiss() }
        windows.clear()
        controller = null
        _running.value = null
    }

    /** 画面が消えている間は、ゴーストのタイマーとアニメーションを止める */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller?.setScreenOn(intent.action == Intent.ACTION_SCREEN_ON)
        }
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
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
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
