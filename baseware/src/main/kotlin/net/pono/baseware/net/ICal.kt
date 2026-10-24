package net.pono.baseware.net

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** iCalendar（.ics）の予定1件 */
data class ICalEvent(
    val uid: String,
    val start: LocalDateTime,
    /** 終わり（その予定の最後の瞬間。終日なら最終日の 0 時） */
    val end: LocalDateTime,
    val allDay: Boolean,
    val summary: String,
    val description: String,
    val location: String,
    val url: String,
    val status: String,
    val categories: String,
    val rrule: String,
    val exdates: Set<LocalDate>,
)

data class ICalendar(val name: String, val timezone: String, val calscale: String, val events: List<ICalEvent>)

/**
 * \![execute,ical-get] で取ってきた iCalendar を読み、--from / --to / --expand / --limit で絞って
 * OnExecuteICalComplete の Reference にする（SSP 2.8.90 と同じ決まり）
 */
object ICal {
    /** 期間の長さの上限（日） */
    private const val MAX_SPAN_DAYS = 1830L

    /** 読む予定の数の上限（細工したファイルで止まらないよう） */
    private const val MAX_EVENTS = 10_000

    fun parse(text: String): ICalendar? {
        // 折り返された行（空白で始まる行）をつなぐ
        val lines = mutableListOf<String>()
        for (raw in text.split("\r\n", "\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] += raw.substring(1) else lines += raw
        }
        if (lines.none { it.trim().equals("BEGIN:VCALENDAR", ignoreCase = true) }) return null
        var name = ""
        var tz = ""
        var calscale = ""
        val events = mutableListOf<ICalEvent>()
        var props: MutableList<Triple<String, Map<String, String>, String>>? = null
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val head = line.substring(0, colon)
            val value = line.substring(colon + 1)
            val parts = head.split(';')
            val key = parts[0].uppercase()
            val params = parts.drop(1).associate { p -> p.substringBefore('=').uppercase() to p.substringAfter('=', "").trim('"') }
            when {
                key == "BEGIN" && value.equals("VEVENT", true) -> props = mutableListOf()
                key == "END" && value.equals("VEVENT", true) -> {
                    props?.let { p -> event(p)?.let { if (events.size < MAX_EVENTS) events += it } }
                    props = null
                }
                props != null -> props.add(Triple(key, params, value))
                key == "X-WR-CALNAME" -> name = unescape(value)
                key == "X-WR-TIMEZONE" -> tz = value
                key == "CALSCALE" -> calscale = value
            }
        }
        return ICalendar(name, tz, calscale, events)
    }

    private fun event(props: List<Triple<String, Map<String, String>, String>>): ICalEvent? {
        fun prop(k: String) = props.firstOrNull { it.first == k }
        val (_, startParams, startValue) = prop("DTSTART") ?: return null
        val (start, allDay) = dateTime(startValue, startParams) ?: return null
        val endProp = prop("DTEND")
        val end = endProp?.let { dateTime(it.third, it.second)?.first }
            ?: prop("DURATION")?.let { duration(it.third) }?.let { start.plus(it) }
            ?: start.plusDays(if (allDay) 1 else 0)
        // DTEND は「その日を含まない」ので、終日なら1日戻す
        val last = if (allDay) end.minusDays(1).let { if (it.isBefore(start)) start else it } else end
        val exdates = props.filter { it.first == "EXDATE" }.flatMap { (_, p, v) -> v.split(',').mapNotNull { dateTime(it, p)?.first?.toLocalDate() } }.toSet()
        fun text(k: String) = prop(k)?.third?.let(::unescape).orEmpty()
        return ICalEvent(
            uid = text("UID"), start = start, end = last, allDay = allDay, summary = text("SUMMARY"), description = text("DESCRIPTION"),
            location = text("LOCATION"), url = prop("URL")?.third.orEmpty(), status = text("STATUS"), categories = text("CATEGORIES"),
            rrule = prop("RRULE")?.third.orEmpty(), exdates = exdates,
        )
    }

    /** 20260910 は終日、20260910T090000 は端末の時刻、末尾 Z は UTC、TZID があればその地域の時刻 */
    private fun dateTime(value: String, params: Map<String, String>): Pair<LocalDateTime, Boolean>? {
        val v = value.trim()
        if (params["VALUE"].equals("DATE", true) || (v.length == 8 && v.all { it.isDigit() })) {
            return runCatching { LocalDate.parse(v.take(8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay() to true }.getOrNull()
        }
        val local = runCatching { LocalDateTime.parse(v.removeSuffix("Z").take(15), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")) }.getOrNull()
            ?: return null
        val zone = when {
            v.endsWith("Z") -> ZoneOffset.UTC
            params["TZID"] != null -> runCatching { ZoneId.of(params["TZID"]) }.getOrNull()
            else -> null
        } ?: return local to false
        return local.atZone(zone).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() to false
    }

    /** P1D / PT1H30M / P1W など */
    private fun duration(s: String): Duration? = runCatching {
        val t = s.trim().uppercase()
        val weeks = Regex("""P(\d+)W""").matchEntire(t)?.groupValues?.get(1)?.toLong()
        if (weeks != null) Duration.ofDays(weeks * 7) else Duration.parse(t)
    }.getOrNull()

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** 期間の決め方（--from・--to・--expand・--limit）。[mode] が false なら絞らない */
    data class Range(val mode: Boolean, val from: LocalDate, val to: LocalDate, val expand: Boolean, val limit: Int)

    fun range(from: LocalDate?, to: LocalDate?, expandDays: Int?, expand: Boolean, limit: Int?, today: LocalDate = LocalDate.now()): Range {
        val mode = from != null || to != null || expand || limit != null
        val start = from ?: today
        val span = when {
            to != null -> ChronoUnit.DAYS.between(start, to)
            expand -> (expandDays ?: 30).toLong() - 1
            else -> MAX_SPAN_DAYS - 1
        }.coerceAtMost(MAX_SPAN_DAYS - 1)
        val end = start.plusDays(span)
        return Range(mode, start, end, expand, if (!mode) 0 else limit ?: 200)
    }

    /**
     * OnExecuteICalComplete の Reference。0 番はカレンダーの情報、1 番から予定（UID[1]開始[1]終了[1]題[1]説明[1]場所[1]URL[1]状態[1]分類[1]
     * 繰り返し[1]終日[1]発生日）
     */
    fun references(cal: ICalendar, range: Range): List<String> {
        val rows = mutableListOf<Pair<ICalEvent, LocalDate>>()
        if (!range.mode) {
            cal.events.forEach { rows += it to it.start.toLocalDate() }
        } else if (range.to.isBefore(range.from)) {
            // 期間が空
        } else if (range.expand) {
            cal.events.forEach { e -> occurrences(e, range.from, range.to).forEach { rows += e to it } }
            rows.sortWith(compareBy({ it.second }, { it.first.start.toLocalTime() }))
        } else {
            cal.events.forEach { e -> occurrences(e, range.from, range.to).firstOrNull()?.let { rows += e to it } }
            rows.sortWith(compareBy({ it.second }, { it.first.start.toLocalTime() }))
        }
        val limited = if (range.limit > 0) rows.take(range.limit) else rows
        fun date(d: LocalDate) = "${d.year},${d.monthValue},${d.dayOfMonth},,,"
        fun time(t: LocalDateTime, allDay: Boolean) = if (allDay) date(t.toLocalDate()) else "%d,%d,%d,%d,%d,%d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
        val head = listOf(
            cal.name, cal.timezone, cal.calscale.ifEmpty { "GREGORIAN" }, limited.size.toString(), cal.events.size.toString(),
            if (range.mode) date(range.from) else "", if (range.mode) date(range.to) else "",
        ).joinToString("\u0001")
        return listOf(head) + limited.map { (e, day) ->
            listOf(
                e.uid, time(e.start, e.allDay), time(e.end, e.allDay), e.summary, e.description, e.location, e.url, e.status, e.categories,
                e.rrule, if (e.allDay) "1" else "0", date(day),
            ).joinToString("\u0001") { it.replace('\u0001', ' ').replace('\r', ' ').replace('\n', ' ') }
        }
    }

    /** [from] から [to] までに [e] が「起きている」日（複数日にわたる予定は、その全ての日） */
    fun occurrences(e: ICalEvent, from: LocalDate, to: LocalDate): List<LocalDate> {
        val length = ChronoUnit.DAYS.between(e.start.toLocalDate(), e.end.toLocalDate()).coerceAtLeast(0)
        val out = sortedSetOf<LocalDate>()
        for (startDay in starts(e, to)) {
            if (startDay in e.exdates) continue
            for (i in 0..length) {
                val d = startDay.plusDays(i)
                if (!d.isBefore(from) && !d.isAfter(to)) out += d
            }
        }
        return out.toList()
    }

    /** 繰り返しの始まりの日（[until] まで）。RRULE の FREQ・INTERVAL・COUNT・UNTIL・BYDAY・BYMONTHDAY・BYMONTH を読む */
    private fun starts(e: ICalEvent, until: LocalDate): Sequence<LocalDate> {
        val first = e.start.toLocalDate()
        if (e.rrule.isBlank()) return sequenceOf(first)
        val rule = e.rrule.split(';').associate { it.substringBefore('=').uppercase() to it.substringAfter('=', "") }
        val freq = rule["FREQ"]?.uppercase() ?: return sequenceOf(first)
        val interval = rule["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = rule["COUNT"]?.toIntOrNull()
        val ruleUntil = rule["UNTIL"]?.let { dateTime(it, emptyMap())?.first?.toLocalDate() }
        val byMonth = rule["BYMONTH"]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet()
        val byMonthDay = rule["BYMONTHDAY"]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet()
        val byDay = rule["BYDAY"]?.split(',')?.mapNotNull { d ->
            val m = Regex("""^([+-]?\d+)?(MO|TU|WE|TH|FR|SA|SU)$""").matchEntire(d.trim().uppercase()) ?: return@mapNotNull null
            m.groupValues[1].toIntOrNull() to dayOf(m.groupValues[2])
        }
        val last = listOfNotNull(until, ruleUntil).min()
        return sequence {
            var produced = 0
            var day = first
            while (!day.isAfter(last)) {
                if (count != null && produced >= count) break
                if (matches(day, first, freq, interval, byMonth, byMonthDay, byDay)) {
                    yield(day)
                    produced++
                }
                day = day.plusDays(1)
            }
        }
    }

    private fun matches(
        day: LocalDate,
        first: LocalDate,
        freq: String,
        interval: Int,
        byMonth: Set<Int>?,
        byMonthDay: Set<Int>?,
        byDay: List<Pair<Int?, DayOfWeek>>?,
    ): Boolean {
        if (byMonth != null && day.monthValue !in byMonth) return false
        if (byMonthDay != null) {
            val len = day.lengthOfMonth()
            if (byMonthDay.none { it == day.dayOfMonth || (it < 0 && len + it + 1 == day.dayOfMonth) }) return false
        }
        if (byDay != null) {
            val ok = byDay.any { (n, dow) ->
                if (day.dayOfWeek != dow) return@any false
                when {
                    n == null -> true
                    // 月ごと・年ごとの「第 n」と「最後から n 番目」
                    n > 0 -> day == day.with(TemporalAdjusters.dayOfWeekInMonth(n, dow))
                    else -> day == day.with(TemporalAdjusters.lastInMonth(dow)).minusWeeks((-n - 1).toLong())
                }
            }
            if (!ok) return false
        }
        return when (freq) {
            "DAILY" -> ChronoUnit.DAYS.between(first, day) % interval == 0L
            "WEEKLY" -> {
                val weeks = ChronoUnit.WEEKS.between(first.with(DayOfWeek.MONDAY), day.with(DayOfWeek.MONDAY))
                weeks % interval == 0L && (byDay != null || day.dayOfWeek == first.dayOfWeek)
            }
            "MONTHLY" -> {
                val months = ChronoUnit.MONTHS.between(first.withDayOfMonth(1), day.withDayOfMonth(1))
                months % interval == 0L && (byDay != null || byMonthDay != null || day.dayOfMonth == first.dayOfMonth)
            }
            "YEARLY" -> {
                val years = (day.year - first.year).toLong()
                years % interval == 0L && (byMonth != null || day.monthValue == first.monthValue) &&
                    (byDay != null || byMonthDay != null || day.dayOfMonth == first.dayOfMonth)
            }
            else -> day == first
        }
    }

    private fun dayOf(s: String) = when (s) {
        "MO" -> DayOfWeek.MONDAY
        "TU" -> DayOfWeek.TUESDAY
        "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY
        "FR" -> DayOfWeek.FRIDAY
        "SA" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }
}
