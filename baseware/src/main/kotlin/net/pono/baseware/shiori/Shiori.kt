package net.pono.baseware.shiori

import java.io.File

/**
 * SHIORI モジュール（ゴーストの頭脳）。DLL の load / request / unload に相当する。
 * 実装はスレッドセーフでなくてよい。呼び出し側（[ShioriSession]）が1スレッドに固定する。
 */
interface Shiori {
    /** @param dir ghost/master ディレクトリ */
    fun load(dir: File): Boolean

    /** 生の SHIORI リクエストを渡し、生のレスポンスを返す。応答なしは null。 */
    fun request(request: ByteArray): ByteArray?

    fun unload()
}

/** descript.txt の `shiori` 名から実装を選ぶ。対応していなければ null を返す。 */
fun interface ShioriFactory {
    fun create(shioriName: String, ghostMasterDir: File): Shiori?
}
