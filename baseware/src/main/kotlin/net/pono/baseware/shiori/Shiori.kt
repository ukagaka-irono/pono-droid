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

    /**
     * SHIORI が異常終了したときに呼んでもらう（別プロセスで動かす実装のため）。どのスレッドから呼ばれてもよいようにすること。
     * 同じプロセスで動く実装は、落ちればアプリごと落ちるので呼ばない。
     */
    fun setOnCrash(listener: () -> Unit) {}

    /**
     * 呼び出しが戻ってこない（辞書が無限ループしている）ので、強制的に止める。
     * 別プロセスで動く実装はプロセスを止めて [setOnCrash] の流れに乗せる。同じプロセスの実装は何もできない。
     */
    fun abort() {}

    /**
     * SAORI over DirectSSTP の要求（ゴーストが内蔵モジュール internal_* を呼んだ）を渡す先。SHIORI の load / request の途中で呼ぶ。
     * [handler] は SAORI/1.0 の要求と、Module ヘッダが無いときのモジュール名を受けて、応答を返す
     */
    fun setSaoriHandler(handler: (request: ByteArray, module: String?) -> ByteArray) {}
}

/** descript.txt の `shiori` 名から実装を選ぶ。対応していなければ null を返す。 */
fun interface ShioriFactory {
    fun create(shioriName: String, ghostMasterDir: File): Shiori?
}
