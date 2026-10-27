package net.pono.baseware.saori

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/** internal_time: 日時の整形と計算。日時はエポック秒で扱う */
object TimeModule {
    val module = SaoriModule(
        "internal_time",
        listOf(
            SaoriFunction("Format", 1, 2, listOf("tz")) { c ->
                val zone = zone(c)
                SaoriResult(format(parse(c.arg(0), zone, c), c.arg(1), zone))
            },
            SaoriFunction("Add", 3, 3, listOf("tz")) { c ->
                val zone = zone(c)
                val t = parse(c.arg(0), zone, c)
                val amount = c.arg(1).trim().toLongOrNull() ?: throw SaoriException.bad("invalid parameter")
                SaoriResult(checkRange(add(t, amount, unit(c.arg(2)), zone), zone).toString())
            },
            SaoriFunction("Diff", 3, 3, listOf("tz")) { c ->
                val zone = zone(c)
                SaoriResult(diff(parse(c.arg(0), zone, c), parse(c.arg(1), zone, c), unit(c.arg(2)), zone).toString())
            },
        ),
    )

    /** 扱える範囲（UTC で 0001-01-01 ～ 9999-12-31） */
    private val MIN = LocalDateTime.of(1, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC)
    private val MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59).toEpochSecond(ZoneOffset.UTC)

    /** --tz=local のときの下限（1601 年） */
    private val MIN_LOCAL = LocalDateTime.of(1601, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC)

    /** ゾーン。[local] は端末のタイムゾーン */
    class Zone(val id: ZoneId, val local: Boolean)

    fun zone(c: SaoriCall): Zone = zone(c.options["tz"], c.env)

    fun zone(raw: String?, env: SaoriEnv): Zone {
        val s = raw?.trim() ?: return Zone(env.localZone, true)
        if (s.equals("local", ignoreCase = true)) return Zone(env.localZone, true)
        if (s.equals("UTC", ignoreCase = true) || s.equals("Z", ignoreCase = true)) return Zone(ZoneOffset.UTC, false)
        OFFSET.matchEntire(s)?.let { m ->
            val h = m.groupValues[2].toInt()
            val mi = m.groupValues[3].ifEmpty { "0" }.toInt()
            if (h > 18 || mi > 59 || (h == 18 && mi > 0)) throw SaoriException.bad("invalid parameter")
            val sign = if (m.groupValues[1] == "-") -1 else 1
            return Zone(ZoneOffset.ofHoursMinutes(sign * h, sign * mi), false)
        }
        val iana = ZoneId.getAvailableZoneIds().firstOrNull { it.equals(s, ignoreCase = true) }
            ?: env.windowsZone(s)
            ?: throw SaoriException.bad("invalid parameter")
        return try {
            Zone(ZoneId.of(iana), false)
        } catch (e: DateTimeException) {
            throw SaoriException.bad("invalid parameter")
        }
    }

    private val OFFSET = Regex("""([+-])(\d{2})(?::?(\d{2}))?""")
    private val ISO = Regex(
        """(\d{4})-?(\d{2})-?(\d{2})(?:[Tt ](\d{2})(?::?(\d{2})(?::?(\d{2})(?:[.,]\d+)?)?)?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?""",
    )

    /** 日時の引数を読む（エポック秒・now・ISO 8601・HTTP の日付）。[c] は今の時刻のため */
    fun parse(raw: String, zone: Zone, c: SaoriCall): Long = parse(raw, zone, c.env)

    fun parse(raw: String, zone: Zone, env: SaoriEnv): Long {
        val s = raw.trim()
        val epoch = when {
            s.equals("now", ignoreCase = true) -> Math.floorDiv(env.now(), 1000L)
            s.matches(Regex("""[+-]?\d+""")) -> s.toBigInteger().let {
                if (it < MIN.toBigInteger() || it > MAX.toBigInteger()) throw SaoriException.bad("out of range")
                it.toLong()
            }
            else -> parseText(s, zone)
        }
        return checkRange(epoch, zone)
    }

    private fun parseText(s: String, zone: Zone): Long {
        ISO.matchEntire(s)?.let { m ->
            val g = m.groupValues
            val year = g[1].toInt()
            if (year < 1601) throw SaoriException.bad("invalid parameter")
            val dt = try {
                LocalDateTime.of(
                    LocalDate.of(year, g[2].toInt(), g[3].toInt()),
                    LocalTime.of(g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt()),
                )
            } catch (e: DateTimeException) {
                throw SaoriException.bad("invalid parameter")
            }
            val off = g[7]
            return when {
                off.isEmpty() -> dt.atZone(zone.id).toEpochSecond()
                off.equals("Z", ignoreCase = true) -> dt.toEpochSecond(ZoneOffset.UTC)
                else -> {
                    val om = OFFSET.matchEntire(off) ?: throw SaoriException.bad("invalid parameter")
                    val h = om.groupValues[2].toInt()
                    val mi = om.groupValues[3].ifEmpty { "0" }.toInt()
                    if (h > 18 || mi > 59) throw SaoriException.bad("invalid parameter")
                    val sign = if (om.groupValues[1] == "-") -1 else 1
                    dt.toEpochSecond(ZoneOffset.ofHoursMinutes(sign * h, sign * mi))
                }
            }
        }
        // HTTP の日付（Sun, 11 Oct 2026 12:00:00 GMT）
        return try {
            val t = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)
            if (t.year < 1601) throw SaoriException.bad("invalid parameter")
            t.toEpochSecond()
        } catch (e: DateTimeParseException) {
            throw SaoriException.bad("invalid parameter")
        }
    }

    private fun checkRange(epoch: Long, zone: Zone): Long {
        if (epoch < (if (zone.local) MIN_LOCAL else MIN) || epoch > MAX) throw SaoriException.bad("out of range")
        return epoch
    }

    enum class Unit(val seconds: Long) { SECOND(1), MINUTE(60), HOUR(3600), DAY(0), WEEK(0), MONTH(0), YEAR(0) }

    fun unit(raw: String): Unit = when (raw.trim().lowercase().removeSuffix("s")) {
        "second" -> Unit.SECOND
        "minute" -> Unit.MINUTE
        "hour" -> Unit.HOUR
        "day" -> Unit.DAY
        "week" -> Unit.WEEK
        "month" -> Unit.MONTH
        "year" -> Unit.YEAR
        else -> throw SaoriException.bad("invalid parameter")
    }

    /** 秒・分・時は経過時間で、日以上は [zone] の暦で足す（月末は丸める） */
    fun add(epoch: Long, amount: Long, unit: Unit, zone: Zone): Long = try {
        if (unit.seconds > 0) {
            Math.addExact(epoch, Math.multiplyExact(amount, unit.seconds))
        } else {
            val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone.id)
            when (unit) {
                Unit.DAY -> t.plusDays(amount)
                Unit.WEEK -> t.plusWeeks(amount)
                Unit.MONTH -> t.plusMonths(amount)
                else -> t.plusYears(amount)
            }.toEpochSecond()
        }
    } catch (e: ArithmeticException) {
        throw SaoriException.bad("out of range")
    } catch (e: DateTimeException) {
        throw SaoriException.bad("out of range")
    }

    /** [b] - [a] を [unit] で数えた整数（端数は 0 の方向へ）。日以上は [add] で足して越えない最大の数 */
    fun diff(a: Long, b: Long, unit: Unit, zone: Zone): Long {
        if (unit.seconds > 0) return (b - a) / unit.seconds
        val ta = ZonedDateTime.ofInstant(Instant.ofEpochSecond(a), zone.id)
        val tb = ZonedDateTime.ofInstant(Instant.ofEpochSecond(b), zone.id)
        // 目安から始めて、足しても越えない最大の数に合わせる
        var n = when (unit) {
            Unit.DAY -> ChronoUnit.DAYS.between(ta.toLocalDate(), tb.toLocalDate())
            Unit.WEEK -> ChronoUnit.DAYS.between(ta.toLocalDate(), tb.toLocalDate()) / 7
            Unit.MONTH -> ChronoUnit.MONTHS.between(ta.toLocalDate().withDayOfMonth(1), tb.toLocalDate().withDayOfMonth(1))
            else -> (tb.year - ta.year).toLong()
        }
        if (b >= a) {
            n = maxOf(n + 1, 0)
            while (n > 0 && add(a, n, unit, zone) > b) n--
        } else {
            n = minOf(n - 1, 0)
            while (n < 0 && add(a, n, unit, zone) < b) n++
        }
        return n
    }

    private val DAYS_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val DAYS_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val MONTHS_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val MONTHS_LONG = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December",
    )

    /** [zone] の日時として整形する。曜日・月名は英語 */
    fun format(epoch: Long, pattern: String, zone: Zone): String {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epoch), zone.id)
        val name = pattern.trim().lowercase()
        if (pattern.isBlank() || name == "iso8601" || name == "rfc3339") {
            val off = t.offset.totalSeconds
            return "%04d-%02d-%02dT%02d:%02d:%02d".format(Locale.ROOT, t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second) +
                if (off == 0) "Z" else offset(off, colon = true)
        }
        when (name) {
            "http" -> {
                val u = t.withZoneSameInstant(ZoneOffset.UTC)
                return "%s, %02d %s %04d %02d:%02d:%02d GMT".format(
                    Locale.ROOT, DAYS_SHORT[u.dayOfWeek.value - 1], u.dayOfMonth, MONTHS_SHORT[u.monthValue - 1], u.year, u.hour, u.minute, u.second,
                )
            }
            "epoch" -> return epoch.toString()
            "epochms" -> return (epoch * 1000).toString()
        }
        if ('%' !in pattern) throw SaoriException.bad("invalid parameter")
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            if (ch != '%') {
                sb.append(ch)
                i++
                continue
            }
            val code = pattern.getOrNull(i + 1) ?: throw SaoriException.bad("invalid parameter")
            i += 2
            when (code) {
                'Y' -> sb.append("%04d".format(Locale.ROOT, t.year))
                'y' -> sb.append("%02d".format(Locale.ROOT, Math.floorMod(t.year, 100)))
                'm' -> sb.append("%02d".format(Locale.ROOT, t.monthValue))
                'd' -> sb.append("%02d".format(Locale.ROOT, t.dayOfMonth))
                'H' -> sb.append("%02d".format(Locale.ROOT, t.hour))
                'M' -> sb.append("%02d".format(Locale.ROOT, t.minute))
                'S' -> sb.append("%02d".format(Locale.ROOT, t.second))
                'j' -> sb.append("%03d".format(Locale.ROOT, t.dayOfYear))
                'u' -> sb.append(t.dayOfWeek.value)
                'w' -> sb.append(t.dayOfWeek.value % 7)
                'a' -> sb.append(DAYS_SHORT[t.dayOfWeek.value - 1])
                'A' -> sb.append(DAYS_LONG[t.dayOfWeek.value - 1])
                'b' -> sb.append(MONTHS_SHORT[t.monthValue - 1])
                'B' -> sb.append(MONTHS_LONG[t.monthValue - 1])
                'z' -> sb.append(offset(t.offset.totalSeconds, colon = false))
                ':' -> {
                    if (pattern.getOrNull(i) != 'z') throw SaoriException.bad("invalid parameter")
                    i++
                    sb.append(offset(t.offset.totalSeconds, colon = true))
                }
                's' -> sb.append(epoch)
                '%' -> sb.append('%')
                else -> throw SaoriException.bad("invalid parameter")
            }
        }
        return sb.toString()
    }

    private fun offset(seconds: Int, colon: Boolean): String {
        val sign = if (seconds < 0) "-" else "+"
        val abs = Math.abs(seconds) / 60
        return "%s%02d%s%02d".format(Locale.ROOT, sign, abs / 60, if (colon) ":" else "", abs % 60)
    }
}
