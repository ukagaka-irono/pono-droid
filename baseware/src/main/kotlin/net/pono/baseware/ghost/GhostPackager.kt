package net.pono.baseware.ghost

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * \![execute,createnar] / \![execute,createupdatedata]。ゴーストのフォルダから .nar と更新の一覧（updates2.dau / updates.txt）を作る。
 * 入れるファイルは SSP と同じ決まりで選ぶ: いつも外すもの（profile/ や var/、desktop.ini など）、developer_options.txt の
 * nonar / noupdate、ゴーストのフォルダの直下の .narignore / .updateignore（.gitignore の書き方）と .narinclude / .updateinclude（書いたものだけ）。
 * pono-droid が使う pono/ も入れない
 */
class GhostPackager(private val root: File) {
    enum class Purpose(val option: String, val ignoreFile: String, val includeFile: String) {
        NAR("nonar", ".narignore", ".narinclude"),
        UPDATE("noupdate", ".updateignore", ".updateinclude"),
    }

    /** 入れるファイル（ゴーストのフォルダからの相対パス、区切りは /）。[exclude] は出力先など、入れないファイル */
    fun files(purpose: Purpose, exclude: Set<File> = emptySet()): List<Pair<String, File>> {
        val base = root.canonicalFile
        val skip = exclude.map { it.canonicalFile }.toSet()
        val options = developerOptions()
        val ignore = readPatterns(File(base, purpose.ignoreFile))
        val include = File(base, purpose.includeFile).takeIf { it.isFile }?.let(::readPatterns)
        val out = mutableListOf<Pair<String, File>>()
        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles()?.sortedBy { it.name } ?: return
            for (f in children) {
                // フォルダの外を指すリンクは辿らない
                val canonical = runCatching { f.canonicalFile }.getOrNull() ?: continue
                if (!canonical.path.startsWith(base.path + File.separator)) continue
                val isDir = f.isDirectory
                val rel = prefix + f.name
                if (isDir) {
                    if (f.name.lowercase() in AUTO_EXCLUDED_DIRS || (prefix.isEmpty() && f.name == PONO_DIR)) continue
                    if (options.excluded(rel + "/", purpose) || ignore.ignored(rel, true)) continue
                    walk(f, "$rel/")
                } else {
                    if (canonical in skip || f.name.lowercase() in AUTO_EXCLUDED_FILES) continue
                    if (options.excluded(rel, purpose) || ignore.ignored(rel, false)) continue
                    if (include != null && !include.included(rel)) continue
                    out += rel to f
                    if (out.size > MAX_FILES) throw IllegalStateException("ファイルが多すぎます")
                }
            }
        }
        walk(base, "")
        return out
    }

    /** .nar（ZIP）を [out] に作る。入れたファイルの数を返す */
    fun createNar(out: File): Int {
        val entries = files(Purpose.NAR, setOf(out))
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".part")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                for ((rel, file) in entries) {
                    zip.putNextEntry(ZipEntry(rel).apply { time = file.lastModified() })
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
        } finally {
            tmp.delete()
        }
        return entries.size
    }

    /**
     * 更新の一覧を [outputs] に書く。.dau で終わるものは updates2.dau の形、ほかは updates.txt の形（行頭に file,）。
     * いつもの出力先（ゴーストのフォルダと ghost/master の updates2.dau・updates.txt）は一覧に入れない
     */
    fun createUpdateData(outputs: List<File>): Int {
        val standard = listOf("", "ghost/master/").flatMap { listOf(File(root, it + "updates2.dau"), File(root, it + "updates.txt")) }
        val entries = files(Purpose.UPDATE, (standard + outputs).toSet())
        // 一覧を作る間に消えたファイル（ほかで作っている途中のものなど）は入れない
        val lines = entries.mapNotNull { (rel, file) ->
            val sum = runCatching { md5(file) }.getOrNull() ?: return@mapNotNull null
            "$rel\u0001$sum\u0001size=${file.length()}\u0001"
        }
        for (out in outputs) {
            out.parentFile?.mkdirs()
            val dau = out.name.lowercase().endsWith(".dau")
            out.writeText(lines.joinToString("") { (if (dau) it else "file,$it") + "\r\n" }, Charsets.UTF_8)
        }
        return entries.size
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- developer_options.txt ----

    private class DeveloperOptions(private val rules: List<Pair<Regex, Set<String>>>) {
        /** 同じ対象に何行あっても最後の行だけが効く（SSP と同じ）。フォルダの指定はその中のすべてにかかる */
        fun excluded(rel: String, purpose: Purpose): Boolean =
            rules.lastOrNull { (pattern, _) -> pattern.matches(rel) }?.second?.contains(purpose.option) == true
    }

    private fun developerOptions(): DeveloperOptions {
        val file = File(root, "developer_options.txt").takeIf { it.isFile } ?: return DeveloperOptions(emptyList())
        val rules = runCatching { file.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val parts = line.split(',').map { it.trim() }
            val path = parts.firstOrNull()?.replace('\\', '/')?.takeIf { it.isNotEmpty() && !it.startsWith("//") } ?: return@mapNotNull null
            // フォルダ（/ で終わる）はその下のすべて
            val regex = wildcard(path) + if (path.endsWith("/")) ".*" else ""
            Regex(regex, RegexOption.IGNORE_CASE) to parts.drop(1).map { it.lowercase() }.toSet()
        }
        return DeveloperOptions(rules)
    }

    /** developer_options.txt の * と ? */
    private fun wildcard(path: String): String = path.map { c ->
        when (c) {
            '*' -> "[^/]*"
            '?' -> "[^/]"
            else -> Regex.escape(c.toString())
        }
    }.joinToString("")

    // ---- .narignore / .updateignore / .narinclude / .updateinclude（.gitignore の書き方） ----

    private class Rule(val regex: Regex, val negated: Boolean, val dirOnly: Boolean)

    private class Patterns(private val rules: List<Rule>) {
        /** 最後に当てはまった行で決まる。! で始まる行は外したものを戻す */
        fun ignored(rel: String, isDir: Boolean): Boolean {
            var result = false
            for (r in rules) {
                if (r.dirOnly && !isDir) continue
                if (r.regex.matches(rel)) result = !r.negated
            }
            return result
        }

        /** 書いたものと、その下のものだけ。親のフォルダを書いていなくても、子を書けば入る */
        fun included(rel: String): Boolean {
            var result = false
            val parents = rel.split('/').runningReduce { a, b -> "$a/$b" }
            for (r in rules) {
                if (parents.any { p -> r.regex.matches(p) && (!r.dirOnly || p != rel) }) result = !r.negated
            }
            return result
        }
    }

    private fun readPatterns(file: File): Patterns {
        val rules = mutableListOf<Rule>()
        fun read(f: File, depth: Int) {
            if (!f.isFile || rules.size > MAX_RULES) return
            for (raw in runCatching { f.readLines() }.getOrDefault(emptyList())) {
                val line = raw.trimEnd()
                if (line.isBlank() || line.startsWith("#")) continue
                if (line.startsWith("include:")) {
                    // include: はその行のファイルのあるフォルダから。辿るのは3階層まで、ゴーストのフォルダの外は読まない
                    if (depth >= MAX_INCLUDE_DEPTH) continue
                    val next = runCatching { File(f.parentFile, line.removePrefix("include:").trim().replace('\\', '/')).canonicalFile }.getOrNull() ?: continue
                    if (next.path.startsWith(root.canonicalPath + File.separator)) read(next, depth + 1)
                    continue
                }
                rules += parseRule(line) ?: continue
            }
        }
        read(file, 0)
        return Patterns(rules)
    }

    private fun parseRule(line: String): Rule? {
        var p = line
        val negated = p.startsWith("!")
        if (negated) p = p.substring(1)
        if (p.startsWith("\\")) p = p.substring(1)
        val dirOnly = p.endsWith("/")
        p = p.trimEnd('/')
        if (p.isEmpty()) return null
        // 途中に / があれば、ゴーストのフォルダからのパス。無ければどの階層の名前にも当てはまる
        val anchored = p.contains('/')
        p = p.trimStart('/')
        val sb = StringBuilder(if (anchored) "" else "(?:.*/)?")
        var i = 0
        while (i < p.length) {
            val c = p[i]
            when {
                p.startsWith("**/", i) -> {
                    sb.append("(?:.*/)?")
                    i += 3
                    continue
                }
                p.startsWith("/**", i) && i + 3 == p.length -> {
                    sb.append("/.*")
                    i += 3
                    continue
                }
                c == '*' -> sb.append("[^/]*")
                c == '?' -> sb.append("[^/]")
                c == '[' -> {
                    val end = p.indexOf(']', i + 1)
                    if (end > i + 1) {
                        sb.append('[').append(p.substring(i + 1, end).replace("\\", "\\\\").replaceFirst("!", "^")).append(']')
                        i = end + 1
                        continue
                    }
                    sb.append("\\[")
                }
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return runCatching { Rule(Regex(sb.toString(), RegexOption.IGNORE_CASE), negated, dirOnly) }.getOrNull()
    }

    companion object {
        /** SSP がいつも外すもの */
        private val AUTO_EXCLUDED_FILES = setOf("desktop.ini", "thumbs.db", "folder.htt", "mscreate.dir", ".ds_store", "_catalog.vix")
        private val AUTO_EXCLUDED_DIRS = setOf("profile", "var", "__macosx", "xtrastuf.mac")

        /** pono-droid がゴーストごとに覚えておくことを置くフォルダ（[Ghost] の profile、渡したファイル） */
        const val PONO_DIR = "pono"

        private const val MAX_FILES = 100_000
        private const val MAX_RULES = 10_000
        private const val MAX_INCLUDE_DEPTH = 3
    }
}
