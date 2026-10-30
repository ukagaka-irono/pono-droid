package net.pono.baseware.ghost

import net.pono.baseware.net.ICal
import net.pono.baseware.net.ICalEvent
import net.pono.baseware.net.ICalendar
import net.pono.baseware.text.AtomicFiles
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
        val url: String = "",
        /** CATEGORIES の値そのまま（カンマ区切り） */
        val categories: String = "",
        /** CONFIRMED / TENTATIVE / CANCELLED か空 */
        val status: String = "",
        /** 繰り返しから除く日（yyyymmdd） */
        val exdates: List<String> = emptyList(),
        /** 予定の種類（[TYPES] のどれか。OnSchedule5MinutesToGo などの Reference0） */
        val type: String = DEFAULT_TYPE,
        /** 予定に添えるさくらスクリプト */
        val script: String = "",
    )

    /** ICS の X- の欄に書いておく、pono-droid だけが使うもの */
    data class Extra(val notifyMinutes: Int?, val type: String, val script: String)

    @Synchronized
    fun calendar(): ICalendar = runCatching { file.takeIf { it.isFile }?.readText()?.let(ICal::parse) }.getOrNull()
        ?: ICalendar(name, "", "GREGORIAN", emptyList())

    /** 知らせる分・種類・スクリプト（UID ごと）。ICS の X-PONO-NOTIFY / X-PONO-TYPE / X-PONO-SCRIPT に書いておく */
    @Synchronized
    fun extras(): Map<String, Extra> = entries().associate { it.uid to Extra(it.notifyMinutes, it.type, it.script) }

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
                url = v("URL").orEmpty(), categories = v("CATEGORIES").orEmpty(), status = v("STATUS").orEmpty(),
                exdates = v("EXDATE")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                type = scheduleType(v("X-PONO-TYPE")), script = unescape(v("X-PONO-SCRIPT").orEmpty()),
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
            if (e.exdates.isNotEmpty()) sb.append("EXDATE;VALUE=DATE:${e.exdates.joinToString(",")}\r\n")
            if (e.url.isNotEmpty()) sb.append("URL:${e.url.replace("\r", "").replace("\n", "")}\r\n")
            if (e.categories.isNotEmpty()) sb.append("CATEGORIES:${e.categories.replace("\r", "").replace("\n", "")}\r\n")
            if (e.status.isNotEmpty()) sb.append("STATUS:${e.status}\r\n")
            e.notifyMinutes?.let { sb.append("X-PONO-NOTIFY:$it\r\n") }
            if (e.type != DEFAULT_TYPE) sb.append("X-PONO-TYPE:${e.type}\r\n")
            if (e.script.isNotEmpty()) sb.append("X-PONO-SCRIPT:${escape(e.script)}\r\n")
            sb.append("END:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        // 書いている途中で止まっても壊れないよう、書き終えてから置き換える
        AtomicFiles.writeText(file, sb.toString())
    }

    private fun dateLine(key: String, value: String) = if (value.length == 8) "$key;VALUE=DATE:$value\r\n" else "$key:$value\r\n"

    private fun escape(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\r", "").replace("\n", "\\n")

    /** [escape] の逆。前から 1 文字ずつ読む（\\n を先に改行へ戻すと、スクリプトの \n タグや C:\new が化ける） */
    private fun unescape(s: String) = ICal.unescape(s, newline = "\n")

    /** 今日の予定（OnScheduleTodayNotify の1件ずつ: 種類[1]題[1]説明[1]スクリプト[1]開始[1]終了[1]） */
    fun today(day: LocalDate = LocalDate.now()): List<String> {
        val extras = extras()
        return calendar().events
            .mapNotNull { e -> ICal.occurrenceStartFor(e, day)?.let { e to it } }
            .sortedWith(compareBy({ it.first.allDay.not() }, { it.first.start.toLocalTime() }))
            .map { (e, startDay) ->
                todayReference(extras[e.uid]?.type ?: DEFAULT_TYPE, e.summary, e.description, extras[e.uid]?.script.orEmpty(), e, day, startDay)
            }
    }

    /** これから知らせる予定（[now] から [withinMinutes] 分のうちに「知らせる時刻」になるもの）。発生の開始時刻と一緒に */
    fun dueNotifications(now: LocalDateTime, withinMinutes: Long = 1): List<Pair<ICalEvent, LocalDateTime>> {
        val minutes = extras().mapNotNull { (uid, x) -> x.notifyMinutes?.let { uid to it } }.toMap()
        val out = mutableListOf<Pair<ICalEvent, LocalDateTime>>()
        for (e in calendar().events) {
            if (e.allDay) continue
            val before = (minutes[e.uid] ?: DEFAULT_NOTIFY_MINUTES).toLong()
            // 知らせる時刻が今なら、発生はその分だけ先（--notify は 7 日前まで）。日をまたぐ予定は始まる日にだけ知らせる
            val last = now.plusMinutes(before + withinMinutes).toLocalDate()
            for (day in ICal.occurrenceStarts(e, now.toLocalDate(), last)) {
                val start = day.atTime(e.start.toLocalTime())
                val notifyAt = start.minusMinutes(before)
                if (!notifyAt.isBefore(now) && notifyAt.isBefore(now.plusMinutes(withinMinutes))) out += e to start
            }
        }
        return out
    }

    companion object {
        const val DEFAULT_NOTIFY_MINUTES = 5

        /** 予定の種類（SSP のスケジュールタイプ）。知らない種類は default */
        val TYPES = setOf(
            "default", "event", "meeting", "appointment", "reservation", "duedate", "party", "date", "work", "go", "tv", "recording",
            "cd", "game", "video", "book", "release",
        )
        const val DEFAULT_TYPE = "default"

        fun scheduleType(value: String?): String = value?.trim()?.lowercase()?.takeIf { it in TYPES } ?: DEFAULT_TYPE
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

        /**
         * OnScheduleTodayNotify の1件。開始・終了は 年,月,日,時,分,秒（時刻の無いものは時分秒が空）。末尾にも区切りが付く。
         * [startDay] はその日にかかっている発生の始まりの日（日をまたぐ予定では [day] より前のことがある）
         */
        fun todayReference(
            type: String,
            caption: String,
            subtitle: String,
            script: String,
            e: ICalEvent?,
            day: LocalDate,
            startDay: LocalDate = day,
        ): String {
            fun date(d: LocalDate) = "${d.year},${d.monthValue},${d.dayOfMonth},,,"
            fun time(t: LocalDateTime) = "${t.year},${t.monthValue},${t.dayOfMonth},${t.hour},${t.minute},${t.second}"
            val begin = e?.takeIf { !it.allDay }?.let { startDay.atTime(it.start.toLocalTime()) }
            val start = begin?.let(::time) ?: date(day)
            // 繰り返しの予定は、その日の発生の終わり（最初の発生と同じ長さ）
            val end = if (e != null && begin != null && e.end != e.start) time(begin.plus(Duration.between(e.start, e.end))) else ""
            return listOf(type, caption, subtitle, script, start, end).joinToString("") { it.replace('\u0001', ' ').replace('\r', ' ').replace('\n', ' ') + "\u0001" }
        }
    }
}
