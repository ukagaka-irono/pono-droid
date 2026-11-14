package net.pono.shiori.yaya

/**
 * ネイティブの SHIORI が内蔵 SAORI（SAORI over DirectSSTP の internal_*）を呼んだときの入口（shim/pono_saori.cpp から）。
 * 呼ばれるのは SHIORI の load / request の途中で、そのスレッドで [with] した先（そのゴーストの本体）に渡す。
 * SHIORI が自分で作ったスレッドから呼んだとき（湊は SAORI をタイムアウトつきで別のスレッドから呼ぶ）は、
 * そのとき動いている呼び出しの先に渡す（ShioriService は呼び出しを 1 つずつ処理する）
 */
object SaoriBridge {
    private val current = ThreadLocal<((ByteArray, String?) -> ByteArray?)?>()
    private val inherited = ThreadLocal<SaoriContext?>()

    @Volatile
    private var active: ((ByteArray, String?) -> ByteArray?)? = null

    /** [block]（SHIORI の呼び出し）の間、内蔵 SAORI の要求を [handler] に渡す */
    fun <T> with(handler: ((ByteArray, String?) -> ByteArray?)?, block: () -> T): T {
        val previous = current.get()
        val previousActive = active
        current.set(handler)
        active = handler
        try {
            return block()
        } finally {
            current.set(previous)
            active = previousActive
        }
    }

    /** SAORI/1.0 の要求に答える。渡す先が無い・答えられなければ null（ネイティブ側が 500 にする） */
    @JvmStatic
    fun request(request: ByteArray, module: String?): ByteArray? {
        inherited.get()?.let { return it.request(request, module) }
        return runCatching { (current.get() ?: active)?.invoke(request, module) }.getOrNull()
    }

    /** 非同期 SAORI が request の終了後も、呼び出し元のゴーストへ返せるように保持する。 */
    @JvmStatic
    fun capture(): SaoriContext = inherited.get() ?: SaoriContext(current.get() ?: active)

    /** 新しく作ったネイティブ worker の間だけ設定し、終了時に null で外す。 */
    @JvmStatic
    fun setContext(context: SaoriContext?) {
        if (context == null) inherited.remove() else inherited.set(context)
    }
}

class SaoriContext internal constructor(private val handler: ((ByteArray, String?) -> ByteArray?)?) {
    // JNI から呼ぶ。別のゴーストの active にフォールバックしない。
    fun request(request: ByteArray, module: String?): ByteArray? = runCatching { handler?.invoke(request, module) }.getOrNull()
}
