package net.pono.shiori.yaya

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import net.pono.baseware.shiori.Shiori
import net.pono.baseware.shiori.ShioriFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 別プロセス（:yaya）の [YayaService] で動く YAYA。YAYA の中で落ちてもアプリ本体は落ちず、[setOnCrash] で知らせる。
 * bind の完了はメインスレッドで届くので、[load] はメインスレッド以外から呼ぶこと（ShioriSession は専用のスレッドで呼ぶ）。
 */
class RemoteYayaShiori(context: Context) : Shiori {
    private val context = context.applicationContext
    @Volatile private var service: IYayaService? = null
    private var binder: IBinder? = null
    private var handle = 0L
    @Volatile private var pid = 0
    @Volatile private var onCrash: (() -> Unit)? = null
    @Volatile private var unloading = false

    private val deathRecipient = IBinder.DeathRecipient {
        service = null
        if (!unloading) {
            Log.w(TAG, "YAYA process died")
            onCrash?.invoke()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            this@RemoteYayaShiori.binder = binder
            service = IYayaService.Stub.asInterface(binder)
            runCatching { binder.linkToDeath(deathRecipient, 0) }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }
    private val connected = CountDownLatch(1)

    override fun load(dir: File): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "load() must not be called on the main thread" }
        if (!context.bindService(Intent(context, YayaService::class.java), connection, Context.BIND_AUTO_CREATE)) return false
        if (!connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return false
        pid = call { it.pid } ?: 0
        handle = call { it.load(dir.absolutePath) } ?: 0
        return handle > 0
    }

    /** 辞書が固まって戻ってこない。:yaya のプロセスを止める（落ちた扱いになり、[setOnCrash] に知らせが届く） */
    override fun abort() {
        val p = pid
        if (p > 0 && p != android.os.Process.myPid()) {
            Log.w(TAG, "YAYA is not responding; killing process $p")
            android.os.Process.killProcess(p)
        }
    }

    override fun request(request: ByteArray): ByteArray? =
        if (handle > 0) call { it.request(handle, request) } else null

    override fun unload() {
        unloading = true
        if (handle > 0) call { it.unload(handle) }
        handle = 0
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        runCatching { context.unbindService(connection) }
        service = null
    }

    override fun setOnCrash(listener: () -> Unit) {
        onCrash = listener
    }

    /** プロセスが落ちていれば null（落ちたことは deathRecipient が知らせる） */
    private fun <T> call(block: (IYayaService) -> T): T? {
        val s = service ?: return null
        return try {
            block(s)
        } catch (e: RemoteException) {
            Log.w(TAG, "YAYA call failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "pono"
        private const val BIND_TIMEOUT_SECONDS = 10L

        /** YAYA と、その元になった AYA5 系の DLL 名。 */
        private val names = setOf("yaya.dll", "aya.dll", "aya5.dll")

        fun factory(context: Context) = ShioriFactory { shioriName, _ ->
            if (shioriName.lowercase() in names) RemoteYayaShiori(context) else null
        }
    }
}
