package net.pono.droid

import android.content.Context
import android.net.Uri
import net.pono.baseware.ghost.GhostRepository
import java.io.File

/** アプリ内のゴースト置き場（filesDir/ghost）。 */
object GhostStore {
    fun repository(context: Context) = GhostRepository(File(context.filesDir, "ghost"))

    /** SAF で選んだ .nar を一時ファイルに写してから展開する。 */
    fun install(context: Context, uri: Uri): File {
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
