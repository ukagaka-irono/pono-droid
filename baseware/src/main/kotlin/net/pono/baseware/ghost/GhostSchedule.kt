package net.pono.baseware.ghost

import net.pono.baseware.net.ICal
import net.pono.baseware.net.ICalEvent
import net.pono.baseware.net.ICalendar
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * ゴースト用の予定表（\![execute,schedule-add / delete / get]）。ユーザーの予定とは別に、ゴーストのフォルダの pono/schedule.ics に置く。
 * 予定には、始まる何分前に知らせるか（--notify。OnSchedule5MinutesToGo）を付けられる
 */
class GhostSchedule(private val file: File, private val name: String) {
    /** schedule-add の中身。[start] / [end] は 20261224（終日）か 20261001T1000（時刻つき） */
    data class Entry(
        val uid: String,
        val summary: String,
        val start: String,
        val end: String = "",
        val rrule: String = "",
        val description: String = "",
        val location: String = "",
        /** 始まる何分前に知らせるか（null なら SSP と同じ 5 分前） */
        val notifyMinutes: Int? = null,
    )

    @Synchronized
    fun calendar(): ICalendar = runCatching { file.takeIf { it.isFile }?.readText()?.let(ICal::parse) }.getOrNull()
        ?: ICalendar(name, "", "GREGORIAN", emptyList())

    /** 知らせる分（UID → 分）。ICS の X-PONO-NOTIFY に書いておく */
    @Synchronized
    fun notifyMinutes(): Map<String, Int> {
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Int>()
        for (block in text.split("BEGIN:VEVENT").drop(1)) {
            val uid = Regex("""(?m)^UID:(.*)$""").find(block)?.groupValues?.get(1)?.trim() ?: continue
            Regex("""(?m)^X-PONO-NOTIFY:(\d+)""").find(block)?.groupValues?.get(1)?.toIntOrNull()?.let { out[uid] = it }
        }
        return out
    }

    /** 予定を足す（同じ UID があれば置き換える）。書き込めなければ例外 */
    @Synchronized
    fun add(e: Entry) {
        val entries = entries().filter { it.uid != e.uid } + e
        if (entries.size > MAX_ENTRIES) throw IllegalStateException("too many")
        write(entries)
    }

    /** 予定を消す。無ければ false */
    @Synchronized
    fun delete(uid: String): Boolean {
        val entries = entries()
        if (entries.none { it.uid == uid }) return false
        write(entries.filter { it.uid != uid })
        return true
    }

    /** ファイルの予定を Entry に戻す（書き直すため） */
    private fun entries(): List<Entry> {
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return emptyList()
        return text.split("BEGIN:VEVENT").drop(1).mapNotNull { block ->
            fun v(k: String) = Regex("""(?m)^$k(?:;[^:\r\n]*)?:(.*)$""").find(block)?.groupValues?.get(1)?.trim()
            val uid = v("UID") ?: return@mapNotNull null
            Entry(
                uid = uid, summary = unescape(v("SUMMARY").orEmpty()), start = v("DTSTART").orEmpty(), end = v("DTEND").orEmpty(),
                rrule = v("RRULE").orEmpty(), description = unescape(v("DESCRIPTION").orEmpty()), location = unescape(v("LOCATION").orEmpty()),
                notifyMinutes = v("X-PONO-NOTIFY")?.toIntOrNull(),
            )
        }
    }

    private fun write(entries: List<Entry>) {
        val sb = StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//pono-droid//ghost schedule//JA\r\nX-WR-CALNAME:${escape(name)}\r\n")
        for (e in entries) {
            sb.append("BEGIN:VEVENT\r\n")
            sb.append("UID:${e.uid}\r\n")
            sb.append(dateLine("DTSTART", e.start))
            if (e.end.isNotEmpty()) sb.append(dateLine("DTEND", e.end))
            sb.append("SUMMARY:${escape(e.summary)}\r\n")
            if (e.description.isNotEmpty()) sb.append("DESCRIPTION:${escape(e.description)}\r\n")
            if (e.location.isNotEmpty()) sb.append("LOCATION:${escape(e.location)}\r\n")
            if (e.rrule.isNotEmpty()) sb.append("RRULE:${e.rrule}\r\n")
            e.notifyMinutes?.let { sb.append("X-PONO-NOTIFY:$it\r\n") }
            sb.append("END:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    private fun dateLine(key: String, value: String) = if (value.length == 8) "$key;VALUE=DATE:$value\r\n" else "$key:$value\r\n"

    private fun escape(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\r", "").replace("\n", "\\n")

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** 今日の予定（OnScheduleTodayNotify の1件ずつ: 種類[1]題[1]説明[1]スクリプト[1]開始[1]終了[1]） */
    fun today(day: LocalDate = LocalDate.now()): List<String> = calendar().events
        .filter { ICal.occurrences(it, day, day).isNotEmpty() }
        .sortedWith(compareBy({ it.allDay.not() }, { it.start.toLocalTime() }))
        .map { e -> todayReference("ghost", e.summary, e.description, "", e, day) }

    /** これから知らせる予定（[now] から [withinMinutes] 分のうちに「知らせる時刻」になるもの）。発生の開始時刻と一緒に */
    fun dueNotifications(now: LocalDateTime, withinMinutes: Long = 1): List<Pair<ICalEvent, LocalDateTime>> {
        val minutes = notifyMinutes()
        val out = mutableListOf<Pair<ICalEvent, LocalDateTime>>()
        for (e in calendar().events) {
            if (e.allDay) continue
            val before = (minutes[e.uid] ?: DEFAULT_NOTIFY_MINUTES).toLong()
            for (day in ICal.occurrences(e, now.toLocalDate(), now.toLocalDate().plusDays(1))) {
                val start = day.atTime(e.start.toLocalTime())
                val notifyAt = start.minusMinutes(before)
                if (!notifyAt.isBefore(now) && notifyAt.isBefore(now.plusMinutes(withinMinutes))) out += e to start
            }
        }
        return out
    }

    companion object {
        const val DEFAULT_NOTIFY_MINUTES = 5
        private const val MAX_ENTRIES = 1000

        private val DATE = DateTimeFormatter.BASIC_ISO_DATE

        /** --start / --end の書式（20261224 / 20261001T1000 / 20261001T100000）。読めれば ICS に書く形にする */
        fun normalizeDate(value: String): String? {
            val v = value.trim()
            return when {
                Regex("""\d{8}""").matches(v) -> runCatching { LocalDate.parse(v, DATE); v }.getOrNull()
                Regex("""\d{8}T\d{4}""").matches(v) -> normalizeDate(v + "00")
                Regex("""\d{8}T\d{6}""").matches(v) -> runCatching { LocalDateTime.parse(v, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")); v }.getOrNull()
                else -> null
            }
        }

        /** UID に使える文字（ICS の行を壊さないもの） */
        fun validUid(uid: String) = uid.isNotEmpty() && uid.length <= 200 && uid.all { it.isLetterOrDigit() || it in "-_.@" }

        /** OnScheduleTodayNotify の1件。開始・終了は 年,月,日,時,分,秒（時刻の無いものは時分秒が空）。末尾にも区切りが付く */
        fun todayReference(type: String, caption: String, subtitle: String, script: String, e: ICalEvent?, day: LocalDate): String {
            fun date(d: LocalDate) = "${d.year},${d.monthValue},${d.dayOfMonth},,,"
            fun time(t: LocalDateTime) = "${t.year},${t.monthValue},${t.dayOfMonth},${t.hour},${t.minute},${t.second}"
            val begin = e?.takeIf { !it.allDay }?.let { day.atTime(it.start.toLocalTime()) }
            val start = begin?.let(::time) ?: date(day)
            // 繰り返しの予定は、その日の発生の終わり（最初の発生と同じ長さ）
            val end = if (e != null && begin != null && e.end != e.start) time(begin.plus(Duration.between(e.start, e.end))) else ""
            return listOf(type, caption, subtitle, script, start, end).joinToString("") { it.replace('\u0001', ' ').replace('\r', ' ').replace('\n', ' ') + "\u0001" }
        }
    }
}
