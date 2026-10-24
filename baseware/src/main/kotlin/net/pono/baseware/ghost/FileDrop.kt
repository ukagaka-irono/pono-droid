package net.pono.baseware.ghost

import java.io.File

/**
 * Android の「共有」でゴーストに渡すファイル（SSP でキャラクターにファイルを落とすのと同じ、OnFileDrop2）。
 * SHIORI は自分のゴーストのフォルダの中しか読めないので、渡すファイルはゴーストのフォルダの pono/drop/ に写す。
 * 前に渡したファイルは、次に渡すときに消す（溜めない）
 */
object FileDrop {
    /** 一度に渡せるファイルの数と、合計の大きさの上限（大きな動画などで端末の容量を使い切らないように） */
    const val MAX_FILES = 32
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    /** 名前の長さの上限（拡張子を含む） */
    private const val MAX_NAME = 100

    /** 渡すファイルを置くフォルダ。毎回空にしてから写す */
    fun dir(ghost: Ghost): File = File(ghost.dir, "pono/drop")

    /**
     * 共有元のアプリが付けた名前を、フォルダの中に置ける名前にする（区切り文字や制御文字を落とし、長さも抑える）。
     * 名前が無いか使えなければ「file[index]」。[taken] にある名前とぶつかれば「名前 (2).拡張子」のように番号を付ける
     */
    fun safeName(displayName: String?, index: Int, taken: Set<String> = emptySet()): String {
        val cleaned = displayName.orEmpty()
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
            .trim().trim('.')
            .takeIf { it.isNotEmpty() } ?: "file$index"
        val ext = cleaned.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 && it != cleaned }
        val base = (if (ext != null) cleaned.dropLast(ext.length + 1) else cleaned).take(MAX_NAME - 12).ifEmpty { "file$index" }
        fun withSuffix(n: Int) = (if (n == 1) base else "$base ($n)") + (ext?.let { ".$it" } ?: "")
        var n = 1
        while (withSuffix(n).lowercase() in taken.map { it.lowercase() }) n++
        return withSuffix(n)
    }
}
