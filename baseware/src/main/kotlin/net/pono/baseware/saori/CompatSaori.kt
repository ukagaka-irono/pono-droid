package net.pono.baseware.saori

import net.pono.baseware.text.TextCharsets
import java.io.File
import java.nio.charset.Charset
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 互換モジュール（spec/saori-ex/compat.md）。よく使われていた SAORI DLL（textcopy・clipcopy・saori_ip・saori_cpuid・cpuusage・
 * string・httpc・mciaudio・houchi・hanayu）をゴーストが読もうとしたら、DLL の代わりにこれが答える。引数と戻り値は原作の SAORI のまま。
 * 状態を持つもの（string・mciaudio・httpc・hanayu）は、呼び出し元のゴーストごとの [InternalSaori] に1つずつ
 */
class CompatSaori(private val env: SaoriEnv) {
    fun answer(module: String, req: SaoriRequest): SaoriResponse {
        if (req.method == SaoriRequest.GET_VERSION) {
            // houchi は読み込んだだけで働く（YAYA などは読み込んだ直後に GET Version を送る）
            if (module == "houchi") env.houchi()
            return SaoriResponse(200, InternalSaori.VERSION_RESULT)
        }
        val args = req.arguments
        return try {
            when (module) {
                "textcopy" -> textcopy(args)
                "clipcopy" -> clipcopy(args)
                "saori_ip" -> saoriIp(args)
                "saori_cpuid" -> cpuid(args)
                "cpuusage" -> cpuusage(args)
                "string" -> string.answer(args)
                "httpc" -> httpc(args)
                "mciaudio" -> mciaudio(args)
                "houchi" -> {
                    env.houchi()
                    SaoriResponse(204)
                }
                "hanayu" -> hanayu(args)
                else -> SaoriResponse.error(400, "unknown module")
            }
        } catch (e: SaoriException) {
            SaoriResponse.error(e.code, e.description)
        } catch (e: Exception) {
            SaoriResponse.error(500, "internal error")
        }
    }

    // ---- textcopy / clipcopy ----

    /** Argument0 をクリップボードに写す。Argument1 が 0 でなければ写した文字を Result に返す。Argument2 があれば、その文字を改行にする */
    private fun textcopy(args: List<String>): SaoriResponse {
        val text = args.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return SaoriResponse.error(400, "invalid parameter")
        val copied = args.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { text.replace(it, "\r\n") } ?: text
        if (!env.copyText(copied)) return SaoriResponse.error(400, "clipboard error")
        val back = args.getOrNull(1)?.trim()?.toLongOrNull()?.let { it != 0L } ?: false
        return if (back) SaoriResponse(200, text) else SaoriResponse(204)
    }

    /** Argument0 をクリップボードに写す。\n は改行、\\n は \n のまま */
    private fun clipcopy(args: List<String>): SaoriResponse {
        val text = args.getOrNull(0) ?: return SaoriResponse.error(400, "invalid parameter")
        val copied = text.replace("\\\\n", "\u0000").replace("\\n", "\r\n").replace("\u0000", "\\n")
        if (!env.copyText(copied)) return SaoriResponse.error(400, "clipboard error")
        return SaoriResponse(204)
    }

    // ---- saori_ip / saori_cpuid / cpuusage ----

    /** GetLocalIP（端末の IPv4）・GetLocalHostname（端末の名前）。GetInternetIP は外のサービスに問い合わせるので答えない */
    private fun saoriIp(args: List<String>): SaoriResponse {
        val command = args.getOrNull(0)?.trim()?.lowercase().orEmpty()
        fun named(name: String) = command.isNotEmpty() && name.startsWith(command)
        return when {
            named("getlocalip") -> env.localAddress()?.let { SaoriResponse(200, it) } ?: SaoriResponse.error(500, "no address")
            named("getlocalhostname") -> env.hostName()?.let { SaoriResponse(200, it) } ?: SaoriResponse.error(500, "no hostname")
            named("getinternetip") -> SaoriResponse.error(500, "not supported")
            else -> SaoriResponse.error(400, "unknown function")
        }
    }

    /** 引数ひとつずつを os.name などのキーとして読み、Value0 から順に答える（Result は Value0）。どれも知らなければ 400 */
    private fun cpuid(args: List<String>): SaoriResponse {
        if (args.isEmpty()) return SaoriResponse(204)
        val values = args.map { cpuidValue(it.trim().removePrefix("!").lowercase()) }
        if (values.all { it == null }) return SaoriResponse.error(400, "param error")
        val out = values.map { it.orEmpty() }
        return SaoriResponse(200, out.first(), out)
    }

    private fun cpuidValue(key: String): String? {
        val d = env.device()
        val runtime = Runtime.getRuntime()
        val mb = 1024L * 1024L
        fun feature() = if (d?.cpuFeatures?.split(' ', ',')?.any { it.equals(CPU_FEATURES[key], ignoreCase = true) } == true) "Ready" else "Not Ready"
        return when (key) {
            "os.name" -> d?.let { "${it.osName} ${it.osVersion}".trim() }
            "os.version" -> d?.osVersion
            "os.build" -> d?.osBuild
            "cpu.num", "cpu.logicalprocessors" -> (d?.cpuCount?.takeIf { it > 0 } ?: runtime.availableProcessors()).toString()
            "cpu.vender", "cpu.vendor" -> d?.cpuVendor.orEmpty()
            "cpu.name" -> d?.cpuName.orEmpty()
            "cpu.ptype", "cpu.family", "cpu.model", "cpu.stepping" -> "0"
            "cpu.mmx", "cpu.sse", "cpu.sse2", "cpu.tdn", "cpu.mmx+", "cpu.tdn+", "cpu.htt" -> feature()
            "cpu.features" -> d?.cpuFeatures.orEmpty()
            "cpu.clock" -> (d?.cpuClockMhz ?: 0).toString()
            "cpu.clockex" -> "%.3f".format(java.util.Locale.ROOT, (d?.cpuClockMhz ?: 0).toDouble())
            "cpu.usage" -> env.cpuLoad()?.toString().orEmpty()
            "cpu.vmguest" -> "No"
            "mem.os" -> d?.takeIf { it.memoryTotalMb > 0 }?.let { ((it.memoryTotalMb - it.memoryFreeMb) * 100 / it.memoryTotalMb).toString() }.orEmpty()
            "mem.phyt" -> d?.memoryTotalMb?.toString().orEmpty()
            "mem.phya" -> d?.memoryFreeMb?.toString().orEmpty()
            // ページファイルは Android に無い
            "mem.pagt", "mem.paga" -> "0"
            "mem.virt" -> (runtime.maxMemory() / mb).toString()
            "mem.vira", "mem.viraex" -> ((runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / mb).toString()
            "platform" -> "pono-droid"
            else -> null
        }
    }

    /** get で、この1秒の CPU の負荷（%） */
    private fun cpuusage(args: List<String>): SaoriResponse {
        if (args.getOrNull(0)?.trim()?.lowercase() != "get") return SaoriResponse.error(400, "unknown function")
        return env.cpuLoad()?.let { SaoriResponse(200, it.toString()) } ?: SaoriResponse.error(500, "unknown")
    }

    // ---- string ----

    private val string = StringModule()

    // ---- httpc ----

    /**
     * 0 個なら つながっているか（1 / 0）、URL なら取ってきた文、URL とファイル名なら保存（1 / 0）、URL と始まり・終わりなら挟まれたもの
     * （最初を Result、2つ目からを Value0 から）。前に euc / jis / sjis / utf8 / erase_tag / translate_tag を書ける。
     * bg,識別子,… なら別のスレッドで動かし、終わったら OnHttpcNotify（識別子・空・結果…）を送る。いつも 200
     */
    private fun httpc(raw: List<String>): SaoriResponse {
        if (raw.firstOrNull() == "bg") {
            val id = raw.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return SaoriResponse(200, "bgするならIDを指定していただけませんと。")
            if (env.ghostMaster == null) return SaoriResponse.error(400, "ghost required")
            val rest = raw.drop(2)
            env.async {
                val r = runCatching { httpcRun(rest) }.getOrElse { SaoriResult("") }
                "OnHttpcNotify" to listOf(id, "", r.result) + r.values
            }
            return SaoriResponse(200, "")
        }
        val r = httpcRun(raw)
        return SaoriResponse(200, r.result, r.values)
    }

    private fun httpcRun(raw: List<String>): SaoriResult {
        val options = raw.takeWhile { it in HTTPC_OPTIONS }.toSet()
        val args = raw.drop(options.size)
        if (args.isEmpty()) return SaoriResult(if (env.online()) "1" else "0")
        if (env.ghostMaster == null) throw SaoriException.bad("ghost required")
        val url = args[0].trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return SaoriResult(if (args.size == 2) "0" else "")
        val bytes = env.httpGet(url) ?: return SaoriResult(if (args.size == 2) "0" else "")
        if (args.size == 2) {
            // 保存先はゴーストの ghost/master の中だけ
            val master = env.ghostMaster ?: return SaoriResult("0")
            val file = runCatching { File(master, args[1].replace('\\', '/')).canonicalFile }.getOrNull()
            val root = env.ghostRoot?.canonicalFile ?: master.canonicalFile
            if (file == null || !file.path.startsWith(root.path + File.separator)) return SaoriResult("0")
            return SaoriResult(if (runCatching { file.parentFile?.mkdirs(); file.writeBytes(bytes) }.isSuccess) "1" else "0")
        }
        var text = Httpc.decode(bytes, options)
        text = when {
            "translate_tag" in options -> Httpc.translateTags(text)
            "erase_tag" in options -> text.replace(Regex("<[^>]*>"), "")
            else -> text
        }
        if (args.size >= 3) {
            val hits = Httpc.extract(text, args[1], args[2])
            return SaoriResult(hits.firstOrNull().orEmpty(), hits.drop(1))
        }
        return SaoriResult(text.replace("\r", "").replace("\n", "").replace("\u0000", ""))
    }

    // ---- mciaudio ----

    private var audioFile: File? = null

    /** load,ファイル（ghost/master から）・play（鳴っていれば一時停止の切り替え）・loop・stop */
    private fun mciaudio(args: List<String>): SaoriResponse {
        if (env.ghostMaster == null) return SaoriResponse.error(400, "ghost required")
        when (args.getOrNull(0)?.trim()?.lowercase()) {
            "load" -> {
                val name = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return SaoriResponse.error(400, "invalid parameter")
                val file = TextCharsets.fileIn(env.ghostMaster!!, name) ?: return SaoriResponse.error(400, "file not found")
                audioFile?.let { env.audio("stop", it) }
                audioFile = file
            }
            "play" -> audioFile?.let { env.audio("play", it) } ?: return SaoriResponse.error(400, "not loaded")
            "loop" -> audioFile?.let { env.audio("loop", it) } ?: return SaoriResponse.error(400, "not loaded")
            "stop" -> audioFile?.let { env.audio("stop", it) }
            else -> return SaoriResponse.error(400, "unknown function")
        }
        return SaoriResponse(204)
    }

    // ---- hanayu ----

    /** show,スタイル名,ラベル1,値1,… でグラフを出し、hide で閉じる（スタイルは hanayu.txt） */
    private fun hanayu(args: List<String>): SaoriResponse {
        return when (args.getOrNull(0)?.trim()?.lowercase()) {
            "show" -> {
                if (env.ghostMaster == null) return SaoriResponse.error(400, "ghost required")
                val style = args.getOrNull(1)?.trim().orEmpty()
                val pairs = args.drop(2).chunked(2).filter { it.size == 2 }.map { (label, value) -> label to (value.trim().toDoubleOrNull() ?: 0.0) }
                if (env.showHanayu(style, pairs)) SaoriResponse(204) else SaoriResponse.error(500, "cannot show")
            }
            "hide" -> if (env.hideHanayu()) SaoriResponse(204) else SaoriResponse.error(400, "not shown")
            else -> SaoriResponse.error(400, "unknown function")
        }
    }

    companion object {
        /** 互換モジュールの名前（DLL の名前から .dll を除いたもの） */
        val NAMES = setOf("houchi", "clipcopy", "textcopy", "saori_ip", "saori_cpuid", "mciaudio", "cpuusage", "httpc", "string", "hanayu")

        private val HTTPC_OPTIONS = setOf("euc", "jis", "sjis", "utf8", "erase_tag", "translate_tag")

        /** saori_cpuid の機能のキー → cpu.features の名前 */
        private val CPU_FEATURES = mapOf(
            "cpu.mmx" to "mmx", "cpu.sse" to "sse", "cpu.sse2" to "sse2", "cpu.tdn" to "3dnow", "cpu.mmx+" to "mmxext",
            "cpu.tdn+" to "3dnowext", "cpu.htt" to "ht",
        )
    }
}

/** httpc の文字コードの決め方と、タグの扱い・抜き出し */
internal object Httpc {
    private val EUC: Charset = Charset.forName("EUC-JP")
    private val JIS: Charset = Charset.forName("ISO-2022-JP")

    /** オプション（jis > euc > utf8 > sjis）か、BOM・charset= / encoding=・JIS のエスケープ・中身から決めて読む */
    fun decode(bytes: ByteArray, options: Set<String>): String {
        val charset = when {
            "jis" in options -> JIS
            "euc" in options -> EUC
            "utf8" in options -> Charsets.UTF_8
            "sjis" in options -> TextCharsets.CP932
            else -> detect(bytes)
        }
        return String(bytes, charset).removePrefix("\uFEFF")
    }

    private fun detect(b: ByteArray): Charset {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return Charsets.UTF_8
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        val head = String(b, 0, minOf(b.size, 4096), Charsets.ISO_8859_1).lowercase()
        Regex("""(?:charset|encoding)\s*=\s*["']?([a-z0-9_\-]+)""").find(head)?.groupValues?.get(1)?.let { name ->
            when {
                name == "iso-2022-jp" -> return JIS
                name in setOf("shift_jis", "x-sjis", "windows-31j") -> return TextCharsets.CP932
                name in setOf("euc-jp", "x-euc-jp") -> return EUC
                name.startsWith("utf") -> return Charsets.UTF_8
            }
        }
        if (head.contains("\u001b\$b") || head.contains("\u001b\$@")) return JIS
        if (TextCharsets.isValidUtf8(b)) return Charsets.UTF_8
        return if (looksEuc(b)) EUC else TextCharsets.CP932
    }

    /** 2バイトの文字がみな EUC-JP の範囲（0xA1〜0xFE の組か、半角カナの 0x8E）なら EUC-JP とみる */
    private fun looksEuc(b: ByteArray): Boolean {
        var i = 0
        var pairs = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x80) {
                i++
                continue
            }
            val d = b.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: return false
            if ((c in 0xA1..0xFE || c == 0x8E) && d in 0xA1..0xFE) {
                pairs++
                i += 2
            } else {
                return false
            }
        }
        return pairs > 0
    }

    /** translate_tag。行の区切りになるタグは改行、リンクは \_a、画像の alt は〔〕、ほかのタグは消し、48桁で折って120行まで */
    fun translateTags(html: String): String {
        var s = html.replace("\r", "").replace("\n", "")
        s = s.replace(Regex("(?i)<hr[^>]*>"), "\n" + "-".repeat(47) + "\n")
        s = s.replace(Regex("(?i)</tr>|</li>|<br\\s*/?>|<p(\\s[^>]*)?>"), "\n")
        s = s.replace(Regex("(?i)<a\\s[^>]*href\\s*=\\s*[\"']?([^\"'\\s>]+)[^>]*>")) { "\\_a[" + it.groupValues[1] + "]" }
        s = s.replace(Regex("(?i)</a\\s*>"), "\\\\_a")
        s = s.replace(Regex("(?i)<img\\s[^>]*alt\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>")) { "〔" + it.groupValues[1] + "〕" }
        s = s.replace(Regex("<[^>]*>"), "")
        s = s.replace("&nbsp;", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "\\&").replace("&copy;", "(c)")
        s = s.replace(Regex("\n{2,}"), "\n")
        val rows = ArrayList<String>()
        for (line in s.split('\n')) {
            var width = 0
            val row = StringBuilder()
            for (ch in line) {
                val w = if (ch.code < 0x80 || ch in '\uFF61'..'\uFF9F') 1 else 2
                if (width + w > WRAP && row.isNotEmpty()) {
                    rows += row.toString()
                    row.clear()
                    width = 0
                }
                row.append(ch)
                width += w
            }
            rows += row.toString()
            if (rows.size >= MAX_ROWS) break
        }
        return rows.take(MAX_ROWS).joinToString("\\n")
    }

    /** [start] と [end] に挟まれたものを、前から順に何個でも */
    fun extract(text: String, start: String, end: String): List<String> {
        if (start.isEmpty() || end.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var from = 0
        while (out.size < MAX_HITS) {
            val a = text.indexOf(start, from).takeIf { it >= 0 } ?: break
            val b = text.indexOf(end, a + start.length).takeIf { it >= 0 } ?: break
            out += text.substring(a + start.length, b)
            from = b + end.length
        }
        return out
    }

    private const val WRAP = 48
    private const val MAX_ROWS = 120
    private const val MAX_HITS = 256
}

/**
 * string.dll（りゅう）。正規表現（match / replace / split / get）、文字の種類の判定、大文字・小文字・全角・半角の変換、trim。
 * 正規表現は Perl と同じ /pat/opts・m/pat/opts・s/pat/rep/opts（g・i・m・s・x・h）。Arg1 か Arg2 を省けば前のものを使う
 */
internal class StringModule {
    private var lastText: String? = null
    private var lastPattern: String? = null

    /** get で返すもの（match は 0 が全体・1 からが括弧、replace は 0 が置き換えた文、split は切った順） */
    private var got: List<String> = emptyList()

    fun answer(args: List<String>): SaoriResponse {
        val name = args.getOrNull(0)?.trim()?.lowercase() ?: return SaoriResponse.error(400, "invalid parameter")
        val a1 = args.getOrNull(1)
        return when (name) {
            "match", "replace", "split" -> regex(name, args)
            "get" -> {
                val index = a1?.trim()?.toIntOrNull() ?: 0
                got.getOrNull(index)?.let { SaoriResponse(200, it) } ?: SaoriResponse(204)
            }
            "isalpha" -> bool(a1) { it.all { c -> c in 'a'..'z' || c in 'A'..'Z' } }
            "isupper" -> bool(a1) { it.all { c -> c in 'A'..'Z' } }
            "islower" -> bool(a1) { it.all { c -> c in 'a'..'z' } }
            "isnumber" -> bool(a1) { it.all { c -> c in '0'..'9' } }
            "isalnum" -> bool(a1) { it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' } }
            "isinteger" -> bool(a1) { Regex("[+-]?[0-9]+").matches(it) }
            "isdecimal" -> bool(a1) { Regex("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)").matches(it) }
            // 文字コードの判定は、その文字コードで書ける文字だけかどうか
            "issjis" -> bool(a1) { TextCharsets.CP932.newEncoder().canEncode(it) }
            "iseuc" -> bool(a1) { Charset.forName("EUC-JP").newEncoder().canEncode(it) }
            "isjis" -> bool(a1) { Charset.forName("ISO-2022-JP").newEncoder().canEncode(it) }
            "toupper" -> text(a1) { it.uppercase() }
            "tolower" -> text(a1) { it.lowercase() }
            "tozenkaku" -> text(a1, ::toZenkaku)
            "tohankaku" -> text(a1, ::toHankaku)
            // 文字はもう文字コードから読んであるので、変換しても同じ
            "tosjis", "tojis", "toeuc" -> text(a1) { it }
            "trim" -> text(a1) { it.trim(' ', '\t', '\u3000') }
            else -> SaoriResponse.error(400, "unknown function")
        }
    }

    private fun bool(s: String?, test: (String) -> Boolean): SaoriResponse =
        SaoriResponse(200, if (!s.isNullOrEmpty() && test(s)) "1" else "0")

    private fun text(s: String?, f: (String) -> String): SaoriResponse = SaoriResponse(200, f(s.orEmpty()))

    private fun regex(name: String, args: List<String>): SaoriResponse {
        val target = args.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: lastText ?: return SaoriResponse.error(400, "invalid parameter")
        val source = args.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: lastPattern ?: return SaoriResponse.error(400, "invalid parameter")
        lastText = target
        lastPattern = source
        val spec = PerlRegex.parse(source, replace = name == "replace") ?: return SaoriResponse.error(400, "invalid regex")
        val input = if (spec.half) toHankaku(target) else target
        val pattern = try {
            Pattern.compile(if (spec.half) toHankaku(spec.pattern) else spec.pattern, spec.flags)
        } catch (e: PatternSyntaxException) {
            return SaoriResponse.error(400, "invalid regex")
        }
        val m = pattern.matcher(input)
        return when (name) {
            "match" -> {
                if (spec.global) {
                    var count = 0
                    var first: List<String>? = null
                    while (m.find()) {
                        if (first == null) first = groups(m)
                        count++
                    }
                    if (first != null) got = first
                    SaoriResponse(200, count.toString())
                } else if (m.find()) {
                    got = groups(m)
                    SaoriResponse(200, "1")
                } else {
                    SaoriResponse(200, "0")
                }
            }
            "replace" -> {
                val sb = StringBuffer()
                var count = 0
                val replacement = spec.replacement.orEmpty()
                while (m.find()) {
                    m.appendReplacement(sb, replacement)
                    count++
                    if (!spec.global) break
                }
                m.appendTail(sb)
                got = listOf(sb.toString())
                SaoriResponse(200, count.toString())
            }
            else -> {
                // Perl と同じく、括弧で取ったものも並べ、後ろの空は落とす
                val pieces = ArrayList<String>()
                var at = 0
                while (m.find()) {
                    if (m.end() == 0 && m.start() == 0) continue
                    pieces += input.substring(at, m.start())
                    for (g in 1..m.groupCount()) m.group(g)?.let { pieces += it }
                    at = m.end()
                }
                pieces += input.substring(at)
                while (pieces.isNotEmpty() && pieces.last().isEmpty()) pieces.removeAt(pieces.lastIndex)
                got = pieces
                SaoriResponse(200, pieces.size.toString())
            }
        }
    }

    private fun groups(m: Matcher) = (0..m.groupCount()).map { m.group(it).orEmpty() }

    companion object {
        /** 英数字と記号だけを全角に（かなはそのまま） */
        fun toZenkaku(s: String) = buildString {
            for (c in s) append(if (c in '!'..'~') (c.code + 0xFEE0).toChar() else if (c == ' ') '\u3000' else c)
        }

        /** 全角の英数字と記号だけを半角に（かなはそのまま） */
        fun toHankaku(s: String) = buildString {
            for (c in s) append(if (c in '\uFF01'..'\uFF5E') (c.code - 0xFEE0).toChar() else if (c == '\u3000') ' ' else c)
        }
    }
}

/** Perl の /pat/opts・m/pat/opts・s/pat/rep/opts を読む */
internal object PerlRegex {
    class Spec(val pattern: String, val replacement: String?, val flags: Int, val global: Boolean, val half: Boolean)

    fun parse(source: String, replace: Boolean): Spec? {
        var s = source.trim()
        val isSubst = s.startsWith("s") && s.length > 1 && !s[1].isLetterOrDigit() && !s[1].isWhitespace()
        if (s.startsWith("m") && s.length > 1 && !s[1].isLetterOrDigit() && !s[1].isWhitespace()) s = s.substring(1)
        if (isSubst) s = s.substring(1)
        if (replace && !isSubst) return null
        val delim = s.firstOrNull() ?: return null
        if (delim.isLetterOrDigit() || delim == '\\') {
            // 区切りの無い、そのままのパターン
            return if (replace) null else Spec(source, null, 0, false, false)
        }
        val parts = splitBy(s.substring(1), delim, if (isSubst) 2 else 1) ?: return null
        val opts = parts.last()
        var flags = 0
        if ('i' in opts) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if ('m' in opts) flags = flags or Pattern.MULTILINE
        if ('s' in opts) flags = flags or Pattern.DOTALL
        if ('x' in opts) flags = flags or Pattern.COMMENTS
        val replacement = if (isSubst) perlReplacement(parts[1]) else null
        return Spec(parts[0], replacement, flags, 'g' in opts, 'h' in opts)
    }

    /** 区切り文字で [count] 個に分け、残り（オプション）を最後に。\区切り はそのまま区切り文字 */
    private fun splitBy(s: String, delim: Char, count: Int): List<String>? {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == delim) {
                cur.append(delim)
                i += 2
                continue
            }
            if (c == '\\' && i + 1 < s.length) {
                cur.append(c).append(s[i + 1])
                i += 2
                continue
            }
            if (c == delim && out.size < count) {
                out += cur.toString()
                cur.clear()
                i++
                continue
            }
            cur.append(c)
            i++
        }
        if (out.size < count) return null
        out += cur.toString()
        return out
    }

    /** Perl の置き換え（$1・\1・${1}）を Java の書き方に */
    private fun perlReplacement(r: String): String = buildString {
        var i = 0
        while (i < r.length) {
            val c = r[i]
            when {
                c == '\\' && i + 1 < r.length && r[i + 1].isDigit() -> {
                    append('$').append(r[i + 1])
                    i += 2
                }
                c == '\\' && i + 1 < r.length -> {
                    append(Matcher.quoteReplacement(r[i + 1].toString()))
                    i += 2
                }
                c == '$' && i + 1 < r.length && (r[i + 1].isDigit() || r[i + 1] == '{') -> {
                    append(c)
                    i++
                }
                c == '$' -> {
                    append("\\$")
                    i++
                }
                else -> {
                    append(c)
                    i++
                }
            }
        }
    }
}
