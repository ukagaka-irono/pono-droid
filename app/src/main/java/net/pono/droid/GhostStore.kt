package net.pono.droid

import android.content.Context
import android.net.Uri
import net.pono.baseware.ghost.GhostRepository
import net.pono.baseware.ghost.NarInstaller
import java.io.File

/** アプリ内のゴースト置き場（filesDir/ghost）とバルーン置き場（filesDir/balloon）。 */
object GhostStore {
    fun repository(context: Context) = GhostRepository(File(context.filesDir, "ghost"))

    /** SAF で選んだ .nar を一時ファイルに写してから展開する。ゴーストかバルーンかは .nar の install.txt で決まる */
    fun install(context: Context, uri: Uri): NarInstaller.Result {
        val tmp = File.createTempFile("import", ".nar", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: error("ファイルを開けません")
            return repository(context).installer().install(tmp)
        } finally {
            tmp.delete()
        }
    }
}
