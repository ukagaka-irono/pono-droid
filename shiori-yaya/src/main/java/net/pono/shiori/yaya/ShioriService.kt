package net.pono.shiori.yaya

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.io.File

/**
 * 別プロセス（:shiori）で SHIORI を動かすサービス。[RemoteShiori] が bind して使う。
 * SHIORI はスレッドセーフではないので、Binder のスレッドから来る呼び出しを1つずつ処理する。
 */
class ShioriService : Service() {
    private val lock = Any()
    private val shioris = HashMap<Long, NativeShiori>()
    private var nextHandle = 1L

    private val binder = object : IShioriService.Stub() {
        override fun load(engine: String, dir: String): Long = synchronized(lock) {
            val kind = runCatching { ShioriEngine.valueOf(engine) }.getOrNull() ?: return 0
            val masterDir = File(dir)
            val shiori = NativeShiori(kind, NativeShiori.Sandbox.forGhost(this@ShioriService, masterDir))
            if (!shiori.load(masterDir)) return 0
            val handle = nextHandle++
            shioris[handle] = shiori
            handle
        }

        override fun request(handle: Long, request: ByteArray): ByteArray? = synchronized(lock) {
            shioris[handle]?.request(request)
        }

        override fun unload(handle: Long) = synchronized(lock) {
            shioris.remove(handle)?.unload()
            Unit
        }

        // lock は取らない。request で固まっているときに呼ばれても答えられるように
        override fun getPid(): Int = Process.myPid()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        synchronized(lock) {
            shioris.values.forEach { it.unload() }
            shioris.clear()
        }
        super.onDestroy()
    }
}
