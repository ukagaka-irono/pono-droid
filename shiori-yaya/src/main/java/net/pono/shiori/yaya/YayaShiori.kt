package net.pono.shiori.yaya

import net.pono.baseware.shiori.Shiori
import java.io.File

internal object YayaNative {
    init {
        System.loadLibrary("yaya")
    }

    /** @return VM の ID（0 以下なら失敗） */
    @JvmStatic external fun load(dir: ByteArray): Long

    @JvmStatic external fun request(id: Long, request: ByteArray): ByteArray?

    @JvmStatic external fun unload(id: Long): Boolean
}

/**
 * NDK でビルドした YAYA を、呼び出したプロセスの中で動かす。1インスタンスが YAYA の VM 1つに対応する。
 * アプリからは [RemoteYayaShiori]（別プロセス）を使う。これは [YayaService] の中とテストで使う。
 */
class YayaShiori : Shiori {
    private var id = 0L

    override fun load(dir: File): Boolean {
        check(id == 0L) { "already loaded" }
        // POSIX 版 YAYA のパスは UTF-8 で、末尾の / が要る
        id = YayaNative.load((dir.absolutePath.trimEnd('/') + "/").toByteArray(Charsets.UTF_8))
        return id > 0
    }

    override fun request(request: ByteArray): ByteArray? =
        if (id > 0) YayaNative.request(id, request) else null

    override fun unload() {
        if (id > 0) {
            YayaNative.unload(id)
            id = 0
        }
    }
}
