package net.pono.droid

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.ghost.FileDrop
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.NarInstaller
import net.pono.droid.overlay.GhostOverlayService
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Android の「共有」の受け口。SSP でキャラクターにファイルを落とすのと同じく、.nar ならインストールし、
 * それ以外のファイルは起動中のゴーストに渡す（OnFileDrop2）。画面は出さず、済んだら閉じる。
 *
 * どのアプリからでも呼べるので、受け取るのは content:// だけにする（file:// を許すと、pono-droid の中のファイルを
 * 名指しで写させて、ゴーストに読ませることができてしまう）
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent).filter { it.scheme == "content" }
        if (uris.isEmpty()) {
            toast("ファイルを選んで共有してください")
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                handle(uris)
            } finally {
                finish()
            }
        }
    }

    private suspend fun handle(uris: List<Uri>) {
        // .nar は SSP と同じくインストールする
        if (uris.size == 1 && displayName(uris[0])?.lowercase()?.endsWith(".nar") == true) {
            val result = withContext(Dispatchers.IO) { runCatching { GhostStore.install(this@ShareActivity, uris[0]) } }
            toast(
                result.fold(
                    { r ->
                        when (r.type) {
                            NarInstaller.Type.GHOST -> "ゴーストをインストールしました: ${Ghost(r.dir).name}"
                            NarInstaller.Type.BALLOON -> "バルーンをインストールしました: ${Balloon(r.dir).name}"
                        }
                    },
                    { "インストールできませんでした: ${it.message}" },
                ),
            )
            return
        }
        val controller = GhostOverlayService.controller.value ?: return toast("ゴーストが起動していません")
        val dropped = withContext(Dispatchers.IO) { copyIn(FileDrop.dir(controller.ghost), uris) }
        if (dropped.files.isEmpty()) return toast(if (dropped.tooLarge) "ファイルが大きすぎます（合計 64MB まで）" else "ファイルを読めませんでした")
        if (dropped.tooLarge) toast("大きすぎるファイルは渡しませんでした（合計 64MB まで）")
        controller.onFileDrop(dropped.files.map { it.first.path }, dropped.files.map { it.second })
    }

    private class Dropped(val files: List<Pair<File, String>>, val tooLarge: Boolean)

    /** 前に渡した分を消してから、ゴーストのフォルダに写す。数と合計の大きさに上限を置く */
    private fun copyIn(dir: File, uris: List<Uri>): Dropped {
        dir.deleteRecursively()
        dir.mkdirs()
        val files = mutableListOf<Pair<File, String>>()
        val taken = mutableSetOf<String>()
        var total = 0L
        var tooLarge = false
        for ((i, uri) in uris.take(FileDrop.MAX_FILES).withIndex()) {
            val name = FileDrop.safeName(displayName(uri), i + 1, taken)
            val file = File(dir, name)
            val copied = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { copyAtMost(input, it, FileDrop.MAX_TOTAL_BYTES - total) }
                }
            }.getOrNull()
            if (copied == null || copied < 0) {
                file.delete()
                if (copied != null) tooLarge = true
                continue
            }
            taken += name
            total += copied
            files += file to (contentResolver.getType(uri) ?: "application/octet-stream")
        }
        return Dropped(files, tooLarge)
    }

    /** [limit] バイトまで写す。超えたら -1 */
    private fun copyAtMost(input: InputStream, output: OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return total
            total += n
            if (total > limit) return -1
            output.write(buf, 0, n)
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            },
        )
        Intent.ACTION_SEND_MULTIPLE -> if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
        else -> emptyList()
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
