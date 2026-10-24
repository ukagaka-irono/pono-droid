package net.pono.droid.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import net.pono.baseware.ghost.WallpaperRequest

/** \![set,wallpaper] の壁紙を、画面の大きさの絵にする */
object Wallpapers {
    /** 大きすぎる画像は縮めて読む（メモリを使い切らないように） */
    private const val MAX_SIDE = 4096

    fun compose(request: WallpaperRequest, width: Int, height: Int): Bitmap? {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(request.color?.let { Color.rgb(it shr 16 and 0xff, it shr 8 and 0xff, it and 0xff) } ?: Color.BLACK)
        val file = request.file ?: return out
        val image = decode(file.path) ?: return null
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        when (request.position) {
            "tile" -> canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { shader = BitmapShader(image, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
            "stretch" -> canvas.drawBitmap(image, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
            "center" -> canvas.drawBitmap(image, (width - w) / 2, (height - h) / 2, paint)
            else -> {
                // fit は全体が収まるように（余白は色）、fill は画面を覆うように（はみ出しは切る）
                val scale = if (request.position == "fit") minOf(width / w, height / h) else maxOf(width / w, height / h)
                val dw = w * scale
                val dh = h * scale
                canvas.drawBitmap(image, null, RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2), paint)
            }
        }
        return out
    }

    private fun decode(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
