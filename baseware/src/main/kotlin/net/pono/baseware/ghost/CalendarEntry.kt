package net.pono.baseware.ghost

import java.time.LocalDate
import java.time.LocalTime

/** 本体カレンダーに表示する予定。端末・購読・ゴーストの予定を同じ形で扱う。 */
data class CalendarEntry(
    val type: String,
    val caption: String,
    val subtitle: String,
    val script: String,
    val start: Moment,
    val end: Moment?,
) {
    data class Moment(val date: LocalDate, val time: LocalTime?)
    val references: List<String> get() = listOf(type, caption, subtitle, script)

    companion object {
        /** OnScheduleTodayNotify の形式を読む。日時の無い予定や不正な日付は表示しない。 */
        fun parse(reference: String): CalendarEntry? {
            val fields = reference.split('\u0001')
            if (fields.size < 6) return null
            val start = moment(fields[4]) ?: return null
            return CalendarEntry(fields[0], fields[1], fields[2], fields[3], start, moment(fields[5]))
        }

        private fun moment(value: String): Moment? = runCatching {
            val parts = value.split(',')
            if (parts.size != 6) return null
            val date = LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            val time = if (parts.drop(3).all { it.isEmpty() }) null else LocalTime.of(parts[3].toInt(), parts[4].toInt(), parts[5].toInt())
            Moment(date, time)
        }.getOrNull()
    }
}
