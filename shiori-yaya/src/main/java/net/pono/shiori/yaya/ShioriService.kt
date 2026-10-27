package net.pono.shiori.yaya

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import java.io.File

/**
 * 別プロセス（:shiori）で SHIORI を動かすサービス。[RemoteShiori] が bind して使う。
 * SHIORI はスレッドセーフではないので、Binder のスレッドから来る呼び出しを1つずつ処理する。
 * 同時に動かす2体目・3体目のゴーストは [ShioriService2] / [ShioriService3]（:shiori2 / :shiori3）を使う
 */
open class ShioriService : Service() {
    private val lock = Any()
    private val shioris = HashMap<Long, NativeShiori>()
    private var nextHandle = 1L

    private val binder = object : IShioriService.Stub() {
        override fun load(engine: String, dir: String, host: IShioriHost?): Long = synchronized(lock) {
            val kind = runCatching { ShioriEngine.valueOf(engine) }.getOrNull() ?: return 0
            val masterDir = File(dir)
            val shiori = NativeShiori(kind, NativeShiori.Sandbox.forGhost(this@ShioriService, masterDir))
            // 内蔵 SAORI の要求は、そのゴーストの本体へ（本体のプロセスは、この呼び出しを待っているスレッドで答える）
            if (host != null) shiori.setSaoriHandler { request, module -> host.saori(request, module) ?: ByteArray(0) }
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

    companion object {
        /** 同時に動かせるゴーストの数（SHIORI のプロセスの数） */
        const val SLOTS = 3

        /** [slot] 番目（0 から）のプロセスのサービス */
        fun forSlot(slot: Int): Class<out ShioriService> = when (slot) {
            1 -> ShioriService2::class.java
            2 -> ShioriService3::class.java
            else -> ShioriService::class.java
        }
    }
}

/** 2体目のゴーストの SHIORI（:shiori2） */
class ShioriService2 : ShioriService()

/** 3体目のゴーストの SHIORI（:shiori3） */
class ShioriService3 : ShioriService()
