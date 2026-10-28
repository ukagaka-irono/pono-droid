package net.pono.baseware.shell

import net.pono.baseware.text.TextCharsets
import java.io.File

/**
 * surfacetable.txt（サーフェステストで使う、サーフェスの名前とグループ分け）。
 * charset 行（無ければ Shift_JIS か中身から決める）・version・option・group,名前 { scope,番号 / ID,名前 } を読む。// から後はコメント
 */
data class SurfaceNameTable(val version: Int, val disableNoDefineSurfaces: Boolean, val groups: List<Group>) {
    /** group,名前 の { } の中。[scope] は scope,数値（無ければ null） */
    data class Group(val name: String, val scope: Int?, val entries: List<Entry>) {
        /** __disabled のグループは使っていないサーフェス（サーフェステストに出さない） */
        val disabled: Boolean get() = name == "__disabled"
    }

    /** サーフェスID,名前。[name] が null なら名前を省いて書いた（標準の名前も付けない）。__parts は名前なしと同じに扱う */
    data class Entry(val id: Int, val name: String?)

    companion object {
        fun read(file: File): SurfaceNameTable? = runCatching { parse(TextCharsets.decode(file.readBytes())) }.getOrNull()

        fun parse(text: String): SurfaceNameTable {
            var version = 1
            var disable = false
            val groups = ArrayList<Group>()
            // グループの外に書いたもの（ふつうはグループの中に書く）
            val loose = ArrayList<Entry>()
            var pendingName: String? = null
            var current: Triple<String, Array<Int?>, ArrayList<Entry>>? = null
            for (raw in text.lines()) {
                val line = raw.trim().trimStart('﻿')
                if (line.isEmpty() || line.startsWith("//")) continue
                when {
                    line == "{" -> if (pendingName != null && current == null) {
                        current = Triple(pendingName, arrayOfNulls(1), ArrayList())
                        pendingName = null
                    }
                    line == "}" -> current?.let { (name, scope, entries) -> groups += Group(name, scope[0], entries) }.also { current = null }
                    else -> {
                        val key = line.substringBefore(',').trim()
                        val value = line.substringAfter(',', "").trim()
                        val id = key.toIntOrNull()
                        when {
                            id != null -> (current?.third ?: loose) += Entry(id, value.takeIf { it.isNotEmpty() })
                            key.equals("group", ignoreCase = true) && current == null -> pendingName = value
                            key.equals("scope", ignoreCase = true) -> current?.second?.set(0, value.toIntOrNull())
                            key.equals("version", ignoreCase = true) -> version = value.toIntOrNull() ?: version
                            key.equals("option", ignoreCase = true) ->
                                if (value.split(',').any { it.trim().equals("DisableNoDefineSurfaces", ignoreCase = true) }) disable = true
                        }
                    }
                }
            }
            // 閉じていないグループも捨てない
            current?.let { (name, scope, entries) -> groups += Group(name, scope[0], entries) }
            if (loose.isNotEmpty()) groups.add(0, Group("", null, loose))
            return SurfaceNameTable(version, disable, groups)
        }

        /** surfacetable.txt に名前が無いときの、事実上の標準の名前（SSP のサーフェステストと同じく、これだけは書かなくても付く） */
        val STANDARD_NAMES = mapOf(
            0 to "素", 1 to "照れ", 2 to "驚き", 3 to "不安", 4 to "落ち込み", 5 to "微笑み", 6 to "目閉じ", 7 to "怒り", 8 to "冷笑",
            9 to "照れ怒り", 25 to "歌", 10 to "素", 11 to "刮目", 19 to "歌",
        )
    }
}

/** サーフェステストの一覧の1グループ。[name] が null ならグループ分けしていない（見出しを出さない） */
data class SurfaceTestGroup(val name: String?, val items: List<SurfaceTestItem>)

/** サーフェステストの一覧の1行。[name] は surfacetable.txt の名前か標準の名前（無ければ null） */
data class SurfaceTestItem(val id: Int, val name: String?)

/**
 * サーフェステストの一覧。surfacetable.txt があればそのグループに分け、[scope] のキャラクターのグループを先に出す。
 * __disabled のグループのサーフェスは出さず、どのグループにも無いものは最後の「そのほか」にまとめる。
 * DisableNoDefineSurfaces なら、surfaces.txt に定義が無く surfacetable.txt にも無いサーフェスは出さない
 */
fun Shell.surfaceTestGroups(scope: Int): List<SurfaceTestGroup> {
    val ids = surfaceIds()
    val existing = ids.toSet()
    val table = surfaceNames
    fun nameOf(e: SurfaceNameTable.Entry?, id: Int): String? = when {
        e == null -> SurfaceNameTable.STANDARD_NAMES[id]
        e.name == "__parts" -> null
        else -> e.name
    }
    if (table == null) return listOf(SurfaceTestGroup(null, ids.map { SurfaceTestItem(it, nameOf(null, it)) }))
    val listed = table.groups.flatMap { g -> g.entries.map { it.id } }.toSet()
    val hidden = table.groups.filter { it.disabled }.flatMap { g -> g.entries.map { it.id } }.toSet()
    val shown = HashSet<Int>()
    val groups = table.groups.filterNot { it.disabled }
        // 選んだキャラクターのグループを先に（並びはファイルの順のまま）
        .sortedBy { if (it.scope == scope) 0 else 1 }
        .mapNotNull { g ->
            val items = g.entries.filter { it.id in existing && it.id !in hidden && shown.add(it.id) }.map { SurfaceTestItem(it.id, nameOf(it, it.id)) }
            // 名前の無いグループ（グループの外に書いたものも）にも見出しを付け、前のグループと続いて見えないようにする
            items.takeIf { it.isNotEmpty() }?.let { SurfaceTestGroup(g.name.ifEmpty { "（グループなし）" }, it) }
        }
    val rest = ids.filter { it !in shown && it !in hidden }
        .filter { !table.disableNoDefineSurfaces || it in listed || surfaces[it] != null }
        .map { SurfaceTestItem(it, nameOf(null, it)) }
    return if (rest.isEmpty()) groups else groups + SurfaceTestGroup(if (groups.isEmpty()) null else "そのほか", rest)
}
