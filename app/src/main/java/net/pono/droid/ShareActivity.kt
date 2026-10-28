package net.pono.droid

import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.activity.compose.setContent
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
        // x-ukagaka-link: のリンクを開いた（SSP の Web 関連付け）
        if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "x-ukagaka-link") {
            val link = intent.dataString.orEmpty()
            // type=install / homeurl は Web のページから来るので、入れる前に必ず確かめる
            linkInstall(link)?.let { (type, url) ->
                confirmLinkInstall(type, url)
                return
            }
            val controller = GhostOverlayService.controller.value
            if (controller == null || !controller.onUkagakaLink(link)) toast("そのゴーストは起動していません")
            finish()
            return
        }
        // ブラウザなどで落とした .nar を開いた（VIEW。application/x-nar か zip）
        val viewed = intent.action == Intent.ACTION_VIEW
        val uris = (if (viewed) listOfNotNull(intent.data) else sharedUris(intent)).filter { it.scheme == "content" }
        // ファイルでなく文字（URL も）を共有された
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { uris.isEmpty() && it.isNotBlank() }
        if (text != null) {
            GhostOverlayService.controller.value?.onTextShared(text) ?: toast("ゴーストが起動していません")
            finish()
            return
        }
        if (uris.isEmpty()) {
            toast("ファイルを選んで共有してください")
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                handle(uris, viewed)
            } finally {
                finish()
            }
        }
    }

    private suspend fun handle(uris: List<Uri>, viewed: Boolean = false) {
        // .nar は SSP と同じくインストールする（開いたもの（VIEW）は、名前が .nar でなくても .nar として入れてみる）
        if (uris.size == 1 && (viewed || displayName(uris[0])?.lowercase()?.endsWith(".nar") == true)) {
            val result = withContext(Dispatchers.IO) { runCatching { GhostStore.install(this@ShareActivity, uris[0]) } }
            toast(result.fold({ GhostStore.describe(it) }, { "インストールできませんでした: ${it.message}" }))
            result.onSuccess { GhostStore.afterInstall(this@ShareActivity, it) }
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

    /** x-ukagaka-link:type=install&url=… / type=homeurl&url=… の種類と URL（http / https のときだけ）。ほかは null */
    private fun linkInstall(link: String): Pair<String, String>? {
        val params = link.substringAfter(':', "").substringAfter('?').split('&').associate { p ->
            p.substringBefore('=').lowercase() to runCatching { java.net.URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }
        val type = params["type"]?.lowercase()?.takeIf { it == "install" || it == "homeurl" } ?: return null
        val url = params["url"]?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        return type to url
    }

    /** リンクから入れてよいか聞く。「インストール」なら、ゴーストが動いていればそのゴーストに、いなければアプリが入れる */
    private fun confirmLinkInstall(type: String, url: String) {
        setContent {
            MaterialTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("インストール") },
                    text = {
                        Text(
                            if (type == "homeurl") "「$url」から、ネットワーク更新と同じ方法でファイルを取ってきてインストールしますか？"
                            else "「$url」の .nar をインストールしますか？",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            // 先に閉じる（取ってくる間にもう一度押されて、二重に入れないように）
                            setContent {}
                            lifecycleScope.launch {
                                try {
                                    installFromLink(type, url)
                                } finally {
                                    finish()
                                }
                            }
                        }) { Text("インストール") }
                    },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("やめる") } },
                )
            }
        }
    }

    private suspend fun installFromLink(type: String, url: String) {
        GhostOverlayService.controller.value?.let {
            it.installFromLink(type, url)
            return
        }
        // ゴーストが動いていなければ、アプリが取ってきて入れる
        val fetcher = net.pono.baseware.update.UrlConnectionFetcher("pono-droid")
        val work = File(cacheDir, "link-install")
        val nar = withContext(Dispatchers.IO) {
            if (type == "homeurl") {
                (net.pono.baseware.update.HomeUrlInstaller.fetch(url, work, fetcher) as? net.pono.baseware.update.HomeUrlInstaller.Outcome.Fetched)?.nar
            } else {
                work.deleteRecursively()
                work.mkdirs()
                File(work, "link.nar").takeIf { f ->
                    f.outputStream().use { fetcher.fetch(url, it, FileDrop.MAX_TOTAL_BYTES) } == net.pono.baseware.update.FetchResult.Ok
                }
            }
        }
        if (nar == null) {
            work.deleteRecursively()
            toast("取ってこられませんでした")
            return
        }
        val result = withContext(Dispatchers.IO) { runCatching { GhostStore.repository(this@ShareActivity).installer().install(nar) } }
        work.deleteRecursively()
        toast(result.fold({ GhostStore.describe(it) }, { "インストールできませんでした: ${it.message}" }))
        result.onSuccess { GhostStore.afterInstall(this@ShareActivity, it) }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
