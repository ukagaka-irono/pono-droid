package net.pono.droid.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalContext
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.shell.CursorChoice
import net.pono.baseware.shell.Cursors
import net.pono.baseware.text.TextCharsets
import java.io.File

/**
 * ゴーストやバルーンのマウスカーソル（[CursorChoice]）を Compose の [PointerIcon] にする。マウスやペンを使うときだけ見える。
 * system:名前 は Android の同じ形のカーソル、ファイル（.cur / .ani / .ico）は読んだ絵で、読んだものは覚えておく
 */
object CursorIcons {
    private val cache = LruCache<String, android.view.PointerIcon>(MAX_CACHED)

    fun icon(context: Context, choice: CursorChoice): PointerIcon = PointerIcon(androidIcon(context, choice))

    private fun androidIcon(context: Context, choice: CursorChoice): android.view.PointerIcon = when (choice) {
        is CursorChoice.System -> android.view.PointerIcon.getSystemIcon(context, systemType(choice.name))
        is CursorChoice.FromFile -> fromFile(choice.file) ?: android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_ARROW)
    }

    private fun fromFile(file: File): android.view.PointerIcon? {
        val key = file.path + ":" + file.lastModified()
        cache.get(key)?.let { return it }
        if (!file.isFile || file.length() > MAX_FILE) return null
        val image = runCatching { Cursors.decode(file.readBytes()) }.getOrNull() ?: return null
        val bitmap = image.png?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            ?: image.argb?.let { Bitmap.createBitmap(it, image.width, image.height, Bitmap.Config.ARGB_8888) }
            ?: return null
        val icon = runCatching {
            android.view.PointerIcon.create(bitmap, image.hotX.toFloat().coerceIn(0f, bitmap.width - 1f), image.hotY.toFloat().coerceIn(0f, bitmap.height - 1f))
        }.getOrNull() ?: return null
        cache.put(key, icon)
        return icon
    }

    /** SSP の system:名前 を、Android の近い形のカーソルにする（finger は指さし、hand は開いた手、grip は握った手） */
    private fun systemType(name: String): Int = when (name) {
        "cross" -> android.view.PointerIcon.TYPE_CROSSHAIR
        "no" -> android.view.PointerIcon.TYPE_NO_DROP
        "finger" -> android.view.PointerIcon.TYPE_HAND
        "hand" -> android.view.PointerIcon.TYPE_GRAB
        "grip" -> android.view.PointerIcon.TYPE_GRABBING
        "text" -> android.view.PointerIcon.TYPE_TEXT
        "wait" -> android.view.PointerIcon.TYPE_WAIT
        "help" -> android.view.PointerIcon.TYPE_HELP
        "move" -> android.view.PointerIcon.TYPE_ALL_SCROLL
        else -> android.view.PointerIcon.TYPE_ARROW
    }

    /** カーソルファイルの大きさの上限 */
    private const val MAX_FILE = 1L shl 20

    private const val MAX_CACHED = 32
}

/** バルーンの上のカーソル。[arrow] はふだん、[choice] は選択肢とリンク、[text] は入力の箱の文字の欄 */
data class BalloonCursors(val arrow: PointerIcon, val choice: PointerIcon, val text: PointerIcon)

/** そのキャラクターのバルーンで使うカーソル（無ければカーソルを替えない） */
val LocalBalloonCursors = staticCompositionLocalOf<BalloonCursors?> { null }

/**
 * バルーンのカーソル。バルーンの descript.txt（mousecursor.arrow / cursor / mousecursor.text）、次にゴーストのものを使う。
 * [loading]（SHIORI を読み込んでいる）の間は mousecursor.wait
 */
@Composable
fun rememberBalloonCursors(controller: GhostController, skin: BalloonSkin?, loading: Boolean): BalloonCursors {
    val context = LocalContext.current
    val balloon = skin?.balloon
    return remember(controller, balloon?.dir, loading) {
        val ghost = controller.ghost
        val sources = listOfNotNull(
            balloon?.let { b -> b.descript to { name: String -> TextCharsets.fileIn(b.dir, name) } },
            ghost.descript to { name: String -> TextCharsets.fileIn(ghost.masterDir, name) },
        )
        if (loading) {
            val wait = CursorIcons.icon(context, Cursors.fromDescripts("wait", sources))
            BalloonCursors(wait, wait, wait)
        } else {
            BalloonCursors(
                arrow = CursorIcons.icon(context, Cursors.fromDescripts("arrow", sources)),
                choice = CursorIcons.icon(context, Cursors.forBalloonChoice(sources)),
                text = CursorIcons.icon(context, Cursors.fromDescripts("text", sources)),
            )
        }
    }
}
