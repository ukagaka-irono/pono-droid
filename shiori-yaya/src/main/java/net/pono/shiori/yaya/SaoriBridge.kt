package net.pono.shiori.yaya

/**
 * ネイティブの SHIORI が内蔵 SAORI（SAORI over DirectSSTP の internal_*）を呼んだときの入口（shim/pono_saori.cpp から）。
 * 呼ばれるのは SHIORI の load / request の途中で、そのスレッドで [with] した先（そのゴーストの本体）に渡す
 */
object SaoriBridge {
    private val current = ThreadLocal<((ByteArray, String?) -> ByteArray?)?>()

    /** [block]（SHIORI の呼び出し）の間、内蔵 SAORI の要求を [handler] に渡す */
    fun <T> with(handler: ((ByteArray, String?) -> ByteArray?)?, block: () -> T): T {
        val previous = current.get()
        current.set(handler)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    /** SAORI/1.0 の要求に答える。渡す先が無い・答えられなければ null（ネイティブ側が 500 にする） */
    @JvmStatic
    fun request(request: ByteArray, module: String?): ByteArray? =
        runCatching { current.get()?.invoke(request, module) }.getOrNull()
}
