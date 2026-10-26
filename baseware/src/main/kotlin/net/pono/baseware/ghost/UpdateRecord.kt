package net.pono.baseware.ghost

import java.io.File
import java.util.Calendar
import java.util.Properties

/**
 * ゴースト・シェル・バルーンの、最後のネットワーク更新の結果と時刻（プロパティの update_result / update_time）。
 * そのフォルダの pono/update.properties に残す。[result] は OK か NG とファイル数か理由（OK,3 / NG,none）、
 * [time] は 年,月,日,時,分,秒
 */
data class UpdateRecord(val result: String, val time: String) {
    companion object {
        private const val FILE = "pono/update.properties"

        fun load(dir: File): UpdateRecord? {
            val f = File(dir, FILE).takeIf { it.isFile } ?: return null
            val p = runCatching { Properties().apply { f.inputStream().use { load(it) } } }.getOrNull() ?: return null
            return UpdateRecord(p.getProperty("result").orEmpty(), p.getProperty("time").orEmpty())
        }

        fun save(dir: File, result: String, now: Calendar = Calendar.getInstance()) {
            val time = "%d,%d,%d,%d,%d,%d".format(
                now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND),
            )
            runCatching {
                val f = File(dir, FILE)
                f.parentFile?.mkdirs()
                f.outputStream().use { Properties().apply { setProperty("result", result); setProperty("time", time) }.store(it, null) }
            }
        }
    }
}
