package net.pono.baseware.update

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * x-ukagaka-link:type=homeurl と \![execute,install,url,URL,homeurl]。URL を更新先とみなし、ネットワーク更新と同じ方法
 * （updates2.dau / updates.txt と MD5）でファイルを全部取ってきて、.nar にまとめる（入れるのは NarInstaller）
 */
object HomeUrlInstaller {
    /** 取ってきたもの（[nar]）か、取れなかった理由（OnInstallFailure などに使う） */
    sealed interface Outcome {
        data class Fetched(val nar: File) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** [work] は作業用のフォルダ（中身は消してから使い、終わったら .nar だけ残す） */
    suspend fun fetch(homeUrl: String, work: File, fetcher: HttpFetcher): Outcome {
        work.deleteRecursively()
        val files = File(work, "files").apply { mkdirs() }
        val result = NetworkUpdater(files, fetcher).update(homeUrl)
        if (result is UpdateResult.Failure) return Outcome.Failed(result.reason)
        if (!File(files, "install.txt").isFile) {
            files.deleteRecursively()
            return Outcome.Failed("invalid type")
        }
        val nar = File(work, "homeurl.nar")
        // 作業用の pono/ は入れない
        val root = files.canonicalFile
        ZipOutputStream(nar.outputStream()).use { zip ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (rel == NetworkUpdater.PONO_DIR || rel.startsWith(NetworkUpdater.PONO_DIR + "/")) return@forEach
                zip.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        files.deleteRecursively()
        return Outcome.Fetched(nar)
    }
}
