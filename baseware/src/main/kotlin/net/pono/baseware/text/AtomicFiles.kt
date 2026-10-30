package net.pono.baseware.text

import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 設定や予定表のように、書いている途中で止まると読めなくなるファイルの書き方。隣の一時ファイルに書き終えてから置き換えるので、
 * 読む側には前のものか新しいもののどちらかが見える
 */
object AtomicFiles {
    fun write(file: File, block: (OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "." + file.name + ".tmp")
        try {
            tmp.outputStream().use(block)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    fun writeText(file: File, text: String) = write(file) { it.write(text.toByteArray(Charsets.UTF_8)) }
}
