package net.pono.baseware.ghost

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.floor

/**
 * 日本の祝日（OnNotifyHolidayInfo）。SSP は祝日表のファイルを読むが、pono-droid は「国民の祝日に関する法律」の決まりから求める。
 * 振替休日と国民の休日も出す。2007 年以降の決まり（2019〜2021 年の特別な日を含む）に従う
 */
object JapaneseHolidays {
    /** [date] の祝日名。祝日でなければ null */
    fun name(date: LocalDate): String? {
        base(date)?.let { return it }
        // 祝日が日曜なら、その後の最初の祝日でない日が振替休日
        var d = date.minusDays(1)
        while (base(d) != null) {
            if (d.dayOfWeek == DayOfWeek.SUNDAY) return "振替休日"
            d = d.minusDays(1)
        }
        // 祝日にはさまれた平日は国民の休日
        if (date.dayOfWeek != DayOfWeek.SUNDAY && base(date.minusDays(1)) != null && base(date.plusDays(1)) != null) return "国民の休日"
        return null
    }

    /** 振替休日・国民の休日を除いた祝日 */
    private fun base(date: LocalDate): String? {
        val y = date.year
        val m = date.monthValue
        val d = date.dayOfMonth
        fun nthMonday(month: Int, n: Int) = LocalDate.of(y, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, DayOfWeek.MONDAY))
        val spring = floor(20.8431 + 0.242194 * (y - 1980) - floor((y - 1980) / 4.0)).toInt()
        val autumn = floor(23.2488 + 0.242194 * (y - 1980) - floor((y - 1980) / 4.0)).toInt()
        // 東京オリンピックの年は海の日・スポーツの日・山の日が動いた
        val marine = when (y) {
            2020 -> LocalDate.of(2020, 7, 23)
            2021 -> LocalDate.of(2021, 7, 22)
            else -> nthMonday(7, 3)
        }
        val sports = when (y) {
            2020 -> LocalDate.of(2020, 7, 24)
            2021 -> LocalDate.of(2021, 7, 23)
            else -> nthMonday(10, 2)
        }
        val mountain = when (y) {
            2020 -> LocalDate.of(2020, 8, 10)
            2021 -> LocalDate.of(2021, 8, 8)
            else -> LocalDate.of(y, 8, 11)
        }
        return when {
            m == 1 && d == 1 -> "元日"
            date == nthMonday(1, 2) -> "成人の日"
            m == 2 && d == 11 -> "建国記念の日"
            m == 2 && d == 23 && y >= 2020 -> "天皇誕生日"
            m == 3 && d == spring -> "春分の日"
            m == 4 && d == 29 -> "昭和の日"
            m == 5 && d == 3 -> "憲法記念日"
            m == 5 && d == 4 -> "みどりの日"
            m == 5 && d == 5 -> "こどもの日"
            date == marine -> "海の日"
            date == mountain && y >= 2016 -> "山の日"
            date == nthMonday(9, 3) -> "敬老の日"
            m == 9 && d == autumn -> "秋分の日"
            date == sports -> if (y >= 2020) "スポーツの日" else "体育の日"
            m == 11 && d == 3 -> "文化の日"
            m == 11 && d == 23 -> "勤労感謝の日"
            m == 12 && d == 23 && y <= 2018 -> "天皇誕生日"
            y == 2019 && m == 5 && d == 1 -> "天皇の即位の日"
            y == 2019 && m == 10 && d == 22 -> "即位礼正殿の儀の行われる日"
            else -> null
        }
    }
}
