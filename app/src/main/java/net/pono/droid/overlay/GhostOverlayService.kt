package net.pono.droid.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.GhostPlatform
import net.pono.baseware.shell.Shell
import net.pono.droid.GhostStore
import net.pono.droid.MainActivity
import net.pono.droid.R
import net.pono.shiori.yaya.YayaShiori

/** ゴーストを常駐させる。キャラクターごとのオーバーレイウィンドウを持つ。 */
class GhostOverlayService : LifecycleService() {
    private var controller: GhostController? = null
    private val windows = mutableListOf<CharacterWindow>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> controller?.close() ?: stopSelf()
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
        val shellDir = ghost.defaultShellDir() ?: run {
            toast("シェルがありません")
            stopSelf()
            return
        }
        val shell = Shell(shellDir)
        val c = GhostController(
            ghost = ghost,
            shell = shell,
            shioriFactory = YayaShiori.factory,
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
            },
        )
        controller = c
        val renderer = SurfaceRenderer(shell)
        val prefs = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        for (scope in listOf(1, 0)) {
            windows += CharacterWindow(this, scope, c, renderer, prefs).also { it.show() }
        }
        c.start()
    }

    private fun teardown() {
        windows.forEach { it.dismiss() }
        windows.clear()
        controller = null
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "常駐中のゴースト", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, GhostOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ゴーストが起動しています")
            .setContentIntent(open)
            .addAction(0, "終了", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "pono"
        private const val CHANNEL = "ghost"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "net.pono.droid.START"
        private const val ACTION_STOP = "net.pono.droid.STOP"
        private const val EXTRA_GHOST = "ghost"

        fun start(context: Context, ghostDirName: String) {
            context.startForegroundService(
                Intent(context, GhostOverlayService::class.java).setAction(ACTION_START).putExtra(EXTRA_GHOST, ghostDirName),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GhostOverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
