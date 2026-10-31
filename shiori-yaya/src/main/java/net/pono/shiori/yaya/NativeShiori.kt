package net.pono.shiori.yaya

import android.content.Context
import net.pono.baseware.shiori.Shiori
import java.io.File

/** NDK でビルドした SHIORI のライブラリ1つ分の入口（jni_common.h の PONO_DEFINE_SHIORI_JNI と対になる） */
internal interface NativeBinding {
    /**
     * @param allowedDir 辞書が読み書きしてよいディレクトリ（UTF-8）
     * @param protectedDir 辞書に読ませないディレクトリ（UTF-8。空なら無し）
     * @param readableDirs [protectedDir] の中でも読むだけならよいディレクトリ（UTF-8、改行区切り。空なら無し）
     * @return VM の ID（0 以下なら失敗）
     */
    fun load(dir: ByteArray, allowedDir: ByteArray, protectedDir: ByteArray, readableDirs: ByteArray): Long

    fun request(id: Long, request: ByteArray): ByteArray?

    fun unload(id: Long): Boolean
}

internal object YayaNative : NativeBinding {
    init {
        System.loadLibrary("yaya")
    }

    external override fun load(dir: ByteArray, allowedDir: ByteArray, protectedDir: ByteArray, readableDirs: ByteArray): Long

    external override fun request(id: Long, request: ByteArray): ByteArray?

    external override fun unload(id: Long): Boolean
}

internal object SatoriNative : NativeBinding {
    init {
        System.loadLibrary("satori")
    }

    external override fun load(dir: ByteArray, allowedDir: ByteArray, protectedDir: ByteArray, readableDirs: ByteArray): Long

    external override fun request(id: Long, request: ByteArray): ByteArray?

    external override fun unload(id: Long): Boolean
}

internal object KawariNative : NativeBinding {
    init {
        System.loadLibrary("kawari")
    }

    external override fun load(dir: ByteArray, allowedDir: ByteArray, protectedDir: ByteArray, readableDirs: ByteArray): Long

    external override fun request(id: Long, request: ByteArray): ByteArray?

    external override fun unload(id: Long): Boolean
}

internal object KagariNative : NativeBinding {
    init {
        System.loadLibrary("kagari")
    }

    external override fun load(dir: ByteArray, allowedDir: ByteArray, protectedDir: ByteArray, readableDirs: ByteArray): Long

    external override fun request(id: Long, request: ByteArray): ByteArray?

    external override fun unload(id: Long): Boolean
}

internal object MinatoNative : NativeBinding {
    init {
        System.loadLibrary("minato")
    }

    external override fun load(dir: ByteArray, allowedDir: ByteArray, protectedDir: ByteArray, readableDirs: ByteArray): Long

    external override fun request(id: Long, request: ByteArray): ByteArray?

    external override fun unload(id: Long): Boolean
}

internal object PastaNative : NativeBinding {
    init {
        System.loadLibrary("pasta")
    }

    external override fun load(dir: ByteArray, allowedDir: ByteArray, protectedDir: ByteArray, readableDirs: ByteArray): Long

    external override fun request(id: Long, request: ByteArray): ByteArray?

    external override fun unload(id: Long): Boolean
}

internal object QuickShioriNative : NativeBinding {
    init {
        System.loadLibrary("quickshiori")
    }

    external override fun load(dir: ByteArray, allowedDir: ByteArray, protectedDir: ByteArray, readableDirs: ByteArray): Long

    external override fun request(id: Long, request: ByteArray): ByteArray?

    external override fun unload(id: Long): Boolean
}

/** pono-droid で動かせる SHIORI */
enum class ShioriEngine(internal val binding: () -> NativeBinding) {
    YAYA({ YayaNative }),
    SATORI({ SatoriNative }),
    KAWARI({ KawariNative }),
    KAGARI({ KagariNative }),
    MINATO({ MinatoNative }),
    PASTA({ PastaNative }),
    QUICKSHIORI({ QuickShioriNative }),
    ;

    companion object {
        /** descript.txt の `shiori` で名前が分かるもの */
        private val byDllName = mapOf(
            "yaya.dll" to YAYA, "aya.dll" to YAYA, "aya5.dll" to YAYA,
            "satori.dll" to SATORI,
            "kawari.dll" to KAWARI, "kawari8.dll" to KAWARI,
            "kagari.dll" to KAGARI,
            "minato.dll" to MINATO,
            "pasta.dll" to PASTA,
            "quickshiori.dll" to QUICKSHIORI,
        )

        /**
         * ゴーストの SHIORI を選ぶ。descript.txt の `shiori` の名前で決まらなければ（shiori.dll など）、
         * ghost/master にある辞書の設定ファイルで見分ける。どれでもなければ null（未対応）
         */
        fun forGhost(shioriName: String, masterDir: File): ShioriEngine? {
            byDllName[shioriName.trim().lowercase()]?.let { return it }
            fun has(name: String) = File(masterDir, name).isFile
            return when {
                has("yaya.txt") || has("aya.txt") -> YAYA
                has("satori_conf.txt") || masterDir.list()?.any { it.startsWith("dic") && it.endsWith(".txt") && has("satori_savedata.txt") } == true -> SATORI
                has("kawarirc.kis") || has("kawari.ini") -> KAWARI
                has("index.lua") -> KAGARI
                has("config.toml") && has("talks/main.mnt") -> MINATO
                has("pasta.toml") -> PASTA
                has("index.js") -> QUICKSHIORI
                else -> null
            }
        }
    }
}

/**
 * NDK でビルドした SHIORI（[engine]）を、呼び出したプロセスの中で動かす。1インスタンスが SHIORI の VM 1つに対応する。
 * アプリからは [RemoteShiori]（別プロセス）を使う。これは [ShioriService] の中とテストで使う。
 */
class NativeShiori(private val engine: ShioriEngine, private val sandbox: Sandbox) : Shiori {
    /**
     * ゴーストの辞書（信頼できないコード）にファイルを触らせてよい範囲。
     * [allowedDir] の下は読み書きできる。[protectedDir]（アプリの領域）はそれ以外読めない。
     * ほかのファイルは読むだけなら許す（タイムゾーンなどシステムのもの）。
     * ネイティブコードの読み込み（SAORI など）とプロセスの起動（EXECUTE など）は範囲によらず止める。
     */
    class Sandbox(val allowedDir: File, val protectedDir: File?, val readableDirs: List<File> = emptyList()) {
        companion object {
            /** [masterDir] が .../ghost/master なら、そのゴースト全体（シェルも）を触れるようにする */
            fun forGhost(context: Context, masterDir: File): Sandbox {
                val ghostDir = masterDir.parentFile
                    ?.takeIf { masterDir.name == "master" && it.name == "ghost" }
                    ?.parentFile
                    ?: masterDir
                return Sandbox(ghostDir, context.dataDir)
            }

            /**
             * プラグイン（files/plugin/名前/ の中の [dllDir]）。そのプラグインのフォルダは読み書きでき、ゴーストとバルーンの置き場は
             * 読むだけならよい（SSP と同じく ghostpathlist・balloonpathlist を伝えるので、バルーンを選ぶプラグインなどが読む）
             */
            fun forPlugin(context: Context, dllDir: File): Sandbox {
                val root = File(context.filesDir, "plugin").canonicalFile
                var dir = dllDir.canonicalFile
                while (dir.parentFile != null && dir.parentFile != root) dir = dir.parentFile!!
                if (dir.parentFile != root) dir = dllDir
                return Sandbox(dir, context.dataDir, listOf(File(context.filesDir, "ghost"), File(context.filesDir, "balloon")))
            }
        }
    }

    private val native: NativeBinding by lazy { engine.binding() }
    private var id = 0L
    private var saori: ((ByteArray, String?) -> ByteArray?)? = null

    override fun setSaoriHandler(handler: (request: ByteArray, module: String?) -> ByteArray) {
        saori = handler
    }

    override fun load(dir: File): Boolean {
        check(id == 0L) { "already loaded" }
        // POSIX 版の SHIORI のパスは UTF-8 で、末尾の / が要る
        id = SaoriBridge.with(saori) {
            native.load(
                (dir.absolutePath.trimEnd('/') + "/").toByteArray(Charsets.UTF_8),
                sandbox.allowedDir.absolutePath.toByteArray(Charsets.UTF_8),
                sandbox.protectedDir?.absolutePath?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
                sandbox.readableDirs.joinToString("\n") { it.absolutePath }.toByteArray(Charsets.UTF_8),
            )
        }
        return id > 0
    }

    override fun request(request: ByteArray): ByteArray? =
        if (id > 0) SaoriBridge.with(saori) { native.request(id, request) } else null

    override fun unload() {
        if (id > 0) {
            native.unload(id)
            id = 0
        }
    }
}
