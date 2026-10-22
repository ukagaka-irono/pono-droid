package net.pono.shiori.yaya

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/**
 * 別プロセス（:yaya）で YAYA を動かすサービス。[RemoteYayaShiori] が bind して使う。
 * YAYA はスレッドセーフではないので、Binder のスレッドから来る呼び出しを1つずつ処理する。
 */
class YayaService : Service() {
    private val lock = Any()
    private val shioris = HashMap<Long, YayaShiori>()
    private var nextHandle = 1L

    private val binder = object : IYayaService.Stub() {
        override fun load(dir: String): Long = synchronized(lock) {
            val shiori = YayaShiori()
            if (!shiori.load(File(dir))) return 0
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
