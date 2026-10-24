package net.pono.droid

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * \![open,dialog,open / save / folder] で選んだものと、ゴーストのフォルダのやりとり。Android ではほかのアプリのファイルのパスを
 * そのまま渡せないので、選んだファイルはゴーストのフォルダの pono/dialog/ に写して、そのパスをゴーストに渡す
 */
object SystemDialogFiles {
    /** 写すフォルダのファイルの数と大きさの上限 */
    private const val MAX_FILES = 500
    private const val MAX_BYTES = 64L * 1024 * 1024

    /** [uri] の表示名（ファイル名）。分からなければ [fallback]。区切りや .. は使わせない */
    fun displayName(context: Context, uri: Uri, fallback: String): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return safeName(name ?: fallback, fallback)
    }

    fun safeName(name: String, fallback: String): String =
        name.replace('/', '_').replace('\\', '_').trim().takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: fallback

    /** open: 選んだファイルを [dir]/open/ に写し、写した先を返す */
    fun copyIn(context: Context, uri: Uri, dir: File): File? = runCatching {
        val target = File(dir, "open/" + displayName(context, uri, "file")).apply { parentFile?.mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { limitedCopy(input, it) } } ?: return null
        target
    }.getOrNull()

    /** folder: 選んだフォルダの中身を [dir]/folder/名前/ に写す（数と大きさに上限）。写した先のフォルダを返す */
    fun copyTreeIn(context: Context, tree: Uri, dir: File): File? = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = displayName(context, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), "folder")
        val target = File(dir, "folder/$rootName").apply { deleteRecursively(); mkdirs() }
        var files = 0
        var bytes = 0L
        fun walk(docId: String, into: File, depth: Int) {
            if (depth > 8) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext() && files < MAX_FILES) {
                    val id = c.getString(0)
                    val name = safeName(c.getString(1).orEmpty(), "file$files")
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, File(into, name).apply { mkdirs() }, depth + 1)
                        continue
                    }
                    val size = c.getLong(3)
                    if (bytes + size > MAX_BYTES) return
                    context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { input ->
                        File(into, name).outputStream().use { bytes += limitedCopy(input, it) }
                    }
                    files++
                }
            }
        }
        walk(rootId, target, 0)
        target
    }.getOrNull()

    /** save: ゴーストに渡す保存先（[dir]/save/名前）。ゴーストが書いたら [GhostOverlayService] が選ばれた場所に写す */
    fun saveTarget(context: Context, uri: Uri, dir: File, defaultName: String): File =
        File(dir, "save/" + displayName(context, uri, defaultName.ifBlank { "file" })).apply { parentFile?.mkdirs(); delete() }

    private fun limitedCopy(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return total
            total += n
            if (total > MAX_BYTES) throw java.io.IOException("too large")
            output.write(buf, 0, n)
        }
    }
}
