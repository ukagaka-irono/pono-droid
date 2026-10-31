package net.pono.droid.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 動く画像（GIF / APNG / WebP）を流す元。[waitMs] 待ってから動かす（surfaces.txt の import の待ち時間） */
data class LiveSource(val file: File, val waitMs: Int = 0)

/**
 * 動く画像の、いま見せるコマ（ファイル → そのコマの絵）。SurfaceRenderer.render に渡すと、その画像をサーフェスの重なりの中の
 * 決まった位置（surface*.png・element・import のコマ）に重ねる。SERIKO のコマより上に別に出すのではないので、
 * 目パチなどの overlay-fast も、\![set,scaling] の裏返しも、ほかと同じに当たる。
 * Android 9 より前は動かさない（render が最初のコマを重ねる）
 */
@Composable
fun rememberLiveFrames(sources: List<LiveSource>): Map<File, Bitmap> {
    val frames = remember { mutableStateMapOf<File, Bitmap>() }
    if (Build.VERSION.SDK_INT >= 28) {
        for (s in sources.distinctBy { it.file }) key(s) { LiveFrame(s, frames) }
    }
    return frames
}

@Composable
private fun LiveFrame(source: LiveSource, frames: SnapshotStateMap<File, Bitmap>) {
    if (Build.VERSION.SDK_INT < 28) return
    val drawable by produceState<Drawable?>(null, source.file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(source.file)) { decoder, info, _ ->
                    // 細工された巨大な画像でメモリを使い切らせない
                    val w = info.size.width
                    val h = info.size.height
                    if (w > ImageTransparency.MAX_SIDE || h > ImageTransparency.MAX_SIDE) {
                        decoder.setTargetSize(minOf(w, ImageTransparency.MAX_SIDE), minOf(h, ImageTransparency.MAX_SIDE))
                    }
                    // 1コマずつ絵に描き写すので、ソフトウェアで描ける形にする
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
    }
    val d = drawable as? AnimatedImageDrawable ?: return
    val handler = remember { Handler(Looper.getMainLooper()) }
    /** いまのコマを新しい絵に描き写す（前の絵は render がほかのスレッドで使っているかもしれないので描き替えない） */
    fun capture() {
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(out))
        frames[source.file] = out
    }
    // コマが進むたびに、Drawable が invalidateSelf で知らせてくる
    val callback = remember(d) {
        object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = capture()

            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                handler.postAtTime(what, who, `when`)
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) = handler.removeCallbacks(what, who)
        }
    }
    LaunchedEffect(d) {
        d.callback = callback
        d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        capture()
        delay(source.waitMs.toLong())
        d.start()
        capture()
    }
    DisposableEffect(d) {
        onDispose {
            d.stop()
            d.callback = null
            handler.removeCallbacksAndMessages(d)
            frames.remove(source.file)
        }
    }
}
