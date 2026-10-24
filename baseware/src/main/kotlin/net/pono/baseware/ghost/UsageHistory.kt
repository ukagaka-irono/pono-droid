package net.pono.baseware.ghost

import java.io.File
import java.time.LocalDate
import java.util.Properties

/**
 * 使ったゴーストとバルーンの履歴（プロパティシステムの history.ghost / history.balloon）と、ゴーストごとの起動回数と
 * 起動していた時間（rateofuselist）。[file] に保存する。日ごとの数は [KEEP_DAYS] 日ぶんだけ残す
 */
class UsageHistory(private val file: File, private val clock: () -> LocalDate = LocalDate::now) {
    /** 1体ぶんの使った記録。[days] は日付（yyyy-MM-dd）ごとの起動回数と分 */
    data class Usage(val dirName: String, val boots: Int, val minutes: Int, val days: Map<String, Pair<Int, Int>>) {
        fun boots(lastDays: Int, today: LocalDate) = within(lastDays, today).sumOf { it.first }
        fun minutes(lastDays: Int, today: LocalDate) = within(lastDays, today).sumOf { it.second }

        private fun within(lastDays: Int, today: LocalDate): List<Pair<Int, Int>> {
            val from = today.minusDays(lastDays.toLong())
            return days.filterKeys { runCatching { !LocalDate.parse(it).isBefore(from) }.getOrDefault(false) }.values.toList()
        }
    }

    private val props = Properties().apply {
        if (file.isFile) runCatching { file.inputStream().use(::load) }
    }

    /** 新しく使ったものから順のディレクトリ名 */
    @Synchronized
    fun recentGhosts(): List<String> = list(KEY_GHOSTS)

    @Synchronized
    fun recentBalloons(): List<String> = list(KEY_BALLOONS)

    /** ゴーストを起動した（履歴の先頭に置き、起動回数を数える） */
    @Synchronized
    fun ghostBooted(dirName: String) {
        push(KEY_GHOSTS, dirName)
        val day = today().toString()
        add("usage.$dirName.boots", 1)
        add("usage.$dirName.day.$day.boots", 1)
        prune(dirName)
        save()
    }

    /** ゴーストが1分起動していた */
    @Synchronized
    fun ghostMinute(dirName: String) {
        add("usage.$dirName.minutes", 1)
        add("usage.$dirName.day.${today()}.minutes", 1)
        save()
    }

    @Synchronized
    fun balloonUsed(dirName: String) {
        push(KEY_BALLOONS, dirName)
        save()
    }

    /** 起動していた時間の長い順 */
    @Synchronized
    fun usage(): List<Usage> {
        val names = props.stringPropertyNames().filter { it.startsWith("usage.") && it.endsWith(".boots") && ".day." !in it }
            .map { it.removePrefix("usage.").removeSuffix(".boots") }
        return names.map { name ->
            val dayPrefix = "usage.$name.day."
            val days = props.stringPropertyNames().filter { it.startsWith(dayPrefix) }
                .groupBy { it.removePrefix(dayPrefix).substringBefore('.') }
                .mapValues { (day, _) -> int("$dayPrefix$day.boots") to int("$dayPrefix$day.minutes") }
            Usage(name, int("usage.$name.boots"), int("usage.$name.minutes"), days)
        }.sortedWith(compareByDescending<Usage> { it.minutes }.thenByDescending { it.boots }.thenBy { it.dirName })
    }

    fun today(): LocalDate = clock()

    private fun list(key: String) = props.getProperty(key).orEmpty().split('/').filter { it.isNotEmpty() }

    private fun push(key: String, dirName: String) {
        if ('/' in dirName) return
        props.setProperty(key, (listOf(dirName) + list(key).filter { it != dirName }).take(MAX_RECENT).joinToString("/"))
    }

    private fun int(key: String) = props.getProperty(key)?.toIntOrNull() ?: 0

    private fun add(key: String, n: Int) = props.setProperty(key, (int(key) + n).toString())

    /** 古い日の数は消す */
    private fun prune(dirName: String) {
        val from = today().minusDays(KEEP_DAYS.toLong())
        val prefix = "usage.$dirName.day."
        for (k in props.stringPropertyNames().filter { it.startsWith(prefix) }) {
            val day = runCatching { LocalDate.parse(k.removePrefix(prefix).substringBefore('.')) }.getOrNull()
            if (day == null || day.isBefore(from)) props.remove(k)
        }
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, null) }
        }
    }

    private companion object {
        const val KEY_GHOSTS = "recent.ghost"
        const val KEY_BALLOONS = "recent.balloon"
        const val MAX_RECENT = 20
        const val KEEP_DAYS = 31
    }
}

/**
 * 開発のための記録（developer.log.script / error / network / update）。それぞれ新しいものから [MAX] 件まで。
 * [type] は script なら Ghost、ほかは Info / Warning / Error など
 */
class DeveloperLog {
    data class Entry(val type: String, val name: String, val value: String, val time: Long)

    private val logs = HashMap<String, ArrayDeque<Entry>>()

    @Synchronized
    fun add(kind: String, type: String, name: String, value: String) {
        val q = logs.getOrPut(kind) { ArrayDeque() }
        q.addFirst(Entry(type, name, value.take(MAX_VALUE), System.currentTimeMillis()))
        while (q.size > MAX) q.removeLast()
    }

    /** 新しいものから順 */
    @Synchronized
    fun entries(kind: String): List<Entry> = logs[kind]?.toList().orEmpty()

    companion object {
        val KINDS = listOf("script", "error", "network", "update")
        const val MAX = 50
        private const val MAX_VALUE = 64 * 1024
    }
}
