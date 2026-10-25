package net.pono.droid.overlay

import android.graphics.BitmapFactory
import java.io.File

/** バルーンの画像の代表の色（currentghost.balloon.scope(ID).background.color） */
object BalloonColors {
    /** 縮めて読む大きさ（色を数えるだけなので小さくてよい） */
    private const val SAMPLE_SIDE = 64

    /** いちばん多く使われている色（透明なところは数えない）。色は 1 チャンネル 16 段に丸めて数え、その段の真ん中の色を返す */
    fun mainColor(file: File): Int? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SAMPLE_SIDE && bounds.outHeight / (sample * 2) >= SAMPLE_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        val counts = HashMap<Int, Int>()
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val key = ((p shr 20) and 0xf shl 8) or ((p shr 12) and 0xf shl 4) or ((p shr 4) and 0xf)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val top = counts.maxByOrNull { it.value }?.key ?: return null
        fun channel(v: Int) = (v shl 4) or 0x8
        (channel(top shr 8 and 0xf) shl 16) or (channel(top shr 4 and 0xf) shl 8) or channel(top and 0xf)
    }.getOrNull()
}
