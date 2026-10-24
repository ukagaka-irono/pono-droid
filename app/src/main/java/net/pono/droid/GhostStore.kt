package net.pono.droid

import android.content.Context
import android.net.Uri
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.GhostRepository
import net.pono.baseware.ghost.NarInstaller
import net.pono.baseware.balloon.Balloon
import net.pono.droid.overlay.GhostOverlayService
import java.io.File

/** アプリ内のゴースト置き場（filesDir/ghost）とバルーン置き場（filesDir/balloon）。 */
object GhostStore {
    fun repository(context: Context) = GhostRepository(File(context.filesDir, "ghost"))

    /**
     * SAF で選んだ .nar を一時ファイルに写してから展開する。ゴーストかバルーンかは .nar の install.txt で決まる。
     * ゴーストが起動していれば、そのゴーストに入れてもらう（OnInstallBegin などのイベントを送り、シェルや追加ファイルはそのゴーストに入る）
     */
    suspend fun install(context: Context, uri: Uri): NarInstaller.Result {
        val tmp = File.createTempFile("import", ".nar", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: error("ファイルを開けません")
            val controller = GhostOverlayService.controller.value
            return controller?.installNar(tmp)?.getOrThrow() ?: repository(context).installer().install(tmp)
        } finally {
            tmp.delete()
        }
    }

    /** インストールしたことを伝える文。同梱のバルーンなどがあれば、それも並べる */
    fun describe(result: NarInstaller.Result): String = result.installed.joinToString("、") { item ->
        when (item.type) {
            NarInstaller.Type.GHOST -> "ゴースト: ${Ghost(item.dir).name}"
            NarInstaller.Type.BALLOON -> "バルーン: ${Balloon(item.dir).name}"
            NarInstaller.Type.SHELL -> "シェル: ${item.name}"
            NarInstaller.Type.SUPPLEMENT -> "追加ファイル: ${item.name}"
        }
    } + " をインストールしました"
}
