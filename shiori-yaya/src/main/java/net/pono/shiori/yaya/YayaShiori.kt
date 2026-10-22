package net.pono.shiori.yaya

import android.content.Context
import net.pono.baseware.shiori.Shiori
import java.io.File

internal object YayaNative {
    init {
        System.loadLibrary("yaya")
    }

    /**
     * @param allowedDir 辞書が読み書きしてよいディレクトリ（UTF-8）
     * @param protectedDir 辞書に読ませないディレクトリ（UTF-8。空なら無し）
     * @return VM の ID（0 以下なら失敗）
     */
    @JvmStatic external fun load(dir: ByteArray, allowedDir: ByteArray, protectedDir: ByteArray): Long

    @JvmStatic external fun request(id: Long, request: ByteArray): ByteArray?

    @JvmStatic external fun unload(id: Long): Boolean
}

/**
 * NDK でビルドした YAYA を、呼び出したプロセスの中で動かす。1インスタンスが YAYA の VM 1つに対応する。
 * アプリからは [RemoteYayaShiori]（別プロセス）を使う。これは [YayaService] の中とテストで使う。
 */
class YayaShiori(private val sandbox: Sandbox) : Shiori {
    /**
     * ゴーストの辞書（信頼できないコード）にファイルを触らせてよい範囲。
     * [allowedDir] の下は読み書きできる。[protectedDir]（アプリの領域）はそれ以外読めない。
     * ほかのファイルは読むだけなら許す（タイムゾーンなどシステムのもの）。
     * ネイティブコードの読み込み（LOADLIB）とプロセスの起動（EXECUTE）は範囲によらず止める。
     */
    class Sandbox(val allowedDir: File, val protectedDir: File?) {
        companion object {
            /** [masterDir] が .../ghost/master なら、そのゴースト全体（シェルも）を触れるようにする */
            fun forGhost(context: Context, masterDir: File): Sandbox {
                val ghostDir = masterDir.parentFile
                    ?.takeIf { masterDir.name == "master" && it.name == "ghost" }
                    ?.parentFile
                    ?: masterDir
                return Sandbox(ghostDir, context.dataDir)
            }
        }
    }

    private var id = 0L

    override fun load(dir: File): Boolean {
        check(id == 0L) { "already loaded" }
        // POSIX 版 YAYA のパスは UTF-8 で、末尾の / が要る
        id = YayaNative.load(
            (dir.absolutePath.trimEnd('/') + "/").toByteArray(Charsets.UTF_8),
            sandbox.allowedDir.absolutePath.toByteArray(Charsets.UTF_8),
            sandbox.protectedDir?.absolutePath?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
        )
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
