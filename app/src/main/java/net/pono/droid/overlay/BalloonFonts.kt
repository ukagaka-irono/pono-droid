package net.pono.droid.overlay

import android.graphics.Typeface
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * \f[name,...] とバルーンの font.name のフォント。ゴーストやバルーンのフォルダのフォントファイルは読み込んで覚えておき、
 * 名前は Android にある系統（明朝・ゴシック・等幅など）に読み替える。どれも使えなければ null（既定のフォント）
 */
object BalloonFonts {
    /** フォントファイルの大きさの上限。ゴーストのファイルは信用しないので、巨大なファイルで読み込みを止めさせない */
    private const val MAX_FONT_BYTES = 32L * 1024 * 1024

    private val cache = HashMap<String, FontFamily?>()

    @Synchronized
    fun family(candidates: List<String>): FontFamily? {
        for (c in candidates) {
            val family = if (c.startsWith("/")) fromFile(c) else byName(c)
            if (family != null) return family
        }
        return null
    }

    private fun fromFile(path: String): FontFamily? = cache.getOrPut(path) {
        val file = File(path)
        if (!file.isFile || file.length() !in 12..MAX_FONT_BYTES || !looksLikeFont(file)) {
            Log.w("pono", "font skipped (missing, too large or not a font): ${file.name}")
            return@getOrPut null
        }
        runCatching { FontFamily(Typeface.createFromFile(file)) }
            .onFailure { Log.w("pono", "font unreadable: ${file.name}: $it") }
            .getOrNull()
    }

    /** TrueType / OpenType / TrueType Collection の頭の印があるか */
    private fun looksLikeFont(file: File): Boolean {
        val head = ByteArray(4)
        val n = file.inputStream().use { it.read(head) }
        if (n < 4) return false
        val tag = String(head, Charsets.ISO_8859_1)
        return tag == "\u0000\u0001\u0000\u0000" || tag == "OTTO" || tag == "true" || tag == "ttcf"
    }

    /** Windows のフォント名などを、Android にある系統に読み替える。知らない名前は null（次の候補へ） */
    private fun byName(name: String): FontFamily? {
        val n = name.lowercase()
        return when {
            n.isEmpty() -> null
            n == "serif" || "明朝" in n || "mincho" in n || "times" in n || "serif" in n && "sans" !in n -> FontFamily.Serif
            n == "monospace" || "等幅" in n || "courier" in n || "consol" in n || "mono" in n -> FontFamily.Monospace
            n == "cursive" || "script" in n || "教科書" in n -> FontFamily.Cursive
            n == "sans-serif" || "ゴシック" in n || "gothic" in n || "sans" in n || "meiryo" in n || "メイリオ" in n ||
                "arial" in n || "segoe" in n || "yu " in n || "游" in n -> FontFamily.SansSerif
            else -> null
        }
    }
}
