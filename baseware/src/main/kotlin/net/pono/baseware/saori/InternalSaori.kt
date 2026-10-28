package net.pono.baseware.saori

import net.pono.baseware.text.TextCharsets
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.security.SecureRandom
import java.time.ZoneId

/** 内蔵モジュールの失敗。[description] は応答の ErrorDescription（固定の英語の文字列） */
class SaoriException(val code: Int, val description: String) : Exception(description) {
    companion object {
        fun bad(description: String) = SaoriException(400, description)
        fun fail(description: String) = SaoriException(500, description)
    }
}

/** 関数の結果。[result] が Result、[values] が Value0 以降 */
class SaoriResult(val result: String, val values: List<String> = emptyList()) {
    companion object {
        fun bool(b: Boolean) = SaoriResult(if (b) "1" else "0")

        /** 一覧を Result にカンマ区切りで、Value0 以降に1つずつ */
        fun list(items: List<String>) = SaoriResult(items.joinToString(","), items)
    }
}

/** 音声・動画・画像の情報（internal_file の MediaInfo）。[kind] は audio / video / image か空、[values] はキー（小文字）ごとの値 */
class MediaInfo(val kind: String, val values: Map<String, String>)

/** 内蔵モジュールが使う、呼び出し元のゴーストと端末のもの */
interface SaoriEnv {
    /** 呼び出し元のゴーストの ghost/master。ゴーストなしの呼び出しは null */
    val ghostMaster: File?

    /** 呼び出し元のゴーストのフォルダ（読み書きしてよい範囲） */
    val ghostRoot: File?

    val random: SecureRandom

    /** 今の時刻（エポックミリ秒） */
    fun now(): Long = System.currentTimeMillis()

    /** --tz=local のゾーン */
    val localZone: ZoneId get() = ZoneId.systemDefault()

    /** Windows のタイムゾーン名（Tokyo Standard Time など）を IANA の名前にする。知らなければ null */
    fun windowsZone(name: String): String? = null

    /** データを端末の鍵で包む（internal_crypto の Protect） */
    fun protect(data: ByteArray): ByteArray

    /** [protect] で包んだものを戻す。戻せなければ null */
    fun unprotect(data: ByteArray): ByteArray?

    /** ファイルのタグ・長さ・大きさ。読めない形式は null */
    fun mediaInfo(file: File): MediaInfo? = null

    /**
     * --async の処理。[work] を別のスレッドで動かし、返ってきたイベントを呼び出し元のゴーストに送る。
     * それまでにゴーストが終わった（SHIORI を読み込み直した）なら送らない
     */
    fun async(work: () -> Pair<String, List<String>>)

    // ---- 互換モジュール（[CompatSaori]）が使うもの ----

    /** クリップボードに写す（textcopy / clipcopy）。写せたら true */
    fun copyText(text: String): Boolean = false

    /** 端末の IPv4 アドレス（saori_ip の GetLocalIP） */
    fun localAddress(): String? = null

    /** 端末の名前（saori_ip の GetLocalHostname） */
    fun hostName(): String? = null

    /** OS・CPU・メモリ（saori_cpuid） */
    fun device(): net.pono.baseware.ghost.DeviceInfo? = null

    /** いちばん新しい CPU の負荷（%。cpuusage） */
    fun cpuLoad(): Int? = null

    /** ネットワークにつながっているか（httpc の引数なし） */
    fun online(): Boolean = false

    /** [url] を取ってくる（httpc）。取れなければ null。この呼び出しの間は待つ */
    fun httpGet(url: String): ByteArray? = null

    /** mciaudio の play（鳴っていれば一時停止の切り替え）・loop・stop */
    fun audio(command: String, file: File) {}

    /** houchi を読み込んだ。端末を放っておいている間、1分ごとに OnHouchi を送る */
    fun houchi() {}

    /** hanayu の show（スタイル名と、ラベルと値の組）。出せたら true */
    fun showHanayu(style: String, data: List<Pair<String, Double>>): Boolean = false

    /** hanayu の hide。出していなければ false */
    fun hideHanayu(): Boolean = false
}

/**
 * 内蔵モジュールの関数。Argument1 から [positional] 個（[required] 個は必須）が位置引数で、その後ろに [options] のオプションを書ける。
 * [positional] が [VARIADIC] なら、Argument2 以降で最初に -- で始まる値から後ろをオプションとして読む
 */
class SaoriFunction(
    val name: String,
    val required: Int,
    val positional: Int,
    options: Collection<String>,
    val body: (SaoriCall) -> SaoriResult,
) {
    val options: Set<String> = options.map { it.lowercase() }.toSet() + "async"

    companion object {
        const val VARIADIC = -1
    }
}

class SaoriModule(val name: String, val functions: List<SaoriFunction>) {
    fun function(name: String) = functions.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/** 1回の呼び出し。位置引数は [args]（Argument1 が 0 番）、オプションは小文字の名前ごと（後に書いたものが有効） */
class SaoriCall(
    val module: SaoriModule,
    val function: SaoriFunction,
    val args: List<String>,
    val options: Map<String, String>,
    val env: SaoriEnv,
) {
    fun arg(i: Int): String = args.getOrElse(i) { "" }

    /** --charset=（既定は UTF-8）。知らない名前・UTF-16 は invalid parameter */
    val charset: Charset by lazy {
        val name = options["charset"] ?: return@lazy Charsets.UTF_8
        val cs = TextCharsets.resolve(name) ?: throw SaoriException.bad("invalid parameter")
        if (cs.name().uppercase().startsWith("UTF-16") || cs.name().uppercase().startsWith("UTF-32")) throw SaoriException.bad("invalid parameter")
        cs
    }

    /** 位置引数 [i] のバイト列。読み方は --[name]-in=（既定は [default]） */
    fun bytes(i: Int, name: String, default: String): ByteArray = decode(arg(i), name, default)

    /** --[name]= のバイト列（--[name]-in= で読み方を変える）。無ければ null */
    fun optionBytes(name: String, default: String): ByteArray? = options[name]?.let { decode(it, name, default) }

    private fun decode(value: String, name: String, default: String): ByteArray {
        val format = options["$name-in"] ?: default
        if (format.equals("file", ignoreCase = true)) throw SaoriException.bad("invalid parameter")
        return SaoriCodec.decode(value, format, charset)
    }

    /** 位置引数 [i] を、--[name]-in=file ならファイルの中身として少しずつ、ほかはバイト列として [consumer] に渡す */
    fun feed(i: Int, name: String, default: String, consumer: (ByteArray, Int, Int) -> Unit) {
        if (options["$name-in"]?.equals("file", ignoreCase = true) == true) {
            val file = ghostFile(arg(i))
            if (!file.isFile) throw SaoriException.bad("file not found")
            val input: InputStream = try {
                file.inputStream()
            } catch (e: Exception) {
                throw SaoriException.bad("file not found")
            }
            input.use {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = try {
                        it.read(buf)
                    } catch (e: Exception) {
                        throw SaoriException.fail("file read failed")
                    }
                    if (n < 0) break
                    consumer(buf, 0, n)
                }
            }
        } else {
            val b = bytes(i, name, default)
            consumer(b, 0, b.size)
        }
    }

    /** バイト列を --out=（既定は [default]）の形式で書く。bech32 は [allowBech32] のとき（Convert）だけ */
    fun output(bytes: ByteArray, default: String, allowBech32: Boolean = false): String {
        val format = options["out"] ?: default
        if (!SaoriCodec.isOutputFormat(format)) throw SaoriException.bad("invalid parameter")
        if (format.equals("bech32", ignoreCase = true) && !allowBech32) throw SaoriException.bad("invalid parameter")
        return SaoriCodec.encode(bytes, format, charset, options["hrp"])
    }

    /** 整数のオプション。数でなければ invalid parameter、[range] の外は out of range */
    fun intOption(name: String, default: Int, range: IntRange): Int {
        val raw = options[name] ?: return default
        val v = raw.trim().toLongOrNull() ?: throw SaoriException.bad("invalid parameter")
        if (v !in range.first.toLong()..range.last.toLong()) throw SaoriException.bad("out of range")
        return v.toInt()
    }

    /** 位置引数の整数。数でなければ invalid parameter、[range] の外は out of range */
    fun intArg(i: Int, range: IntRange): Int {
        val v = arg(i).trim().toLongOrNull() ?: throw SaoriException.bad("invalid parameter")
        if (v !in range.first.toLong()..range.last.toLong()) throw SaoriException.bad("out of range")
        return v.toInt()
    }

    /**
     * ゴーストのファイル。相対パスは ghost/master が基準で、区切りは / も \ も書ける。ゴーストなしで相対パスなら ghost required。
     * ゴーストのフォルダの外は使わない（path not allowed）
     */
    fun ghostFile(path: String, outside: String = "file not found"): File {
        val raw = path.trim().replace('\\', '/')
        if (raw.isEmpty()) throw SaoriException.bad("invalid parameter")
        val given = File(raw)
        val base = env.ghostMaster
        val file = when {
            given.isAbsolute -> given
            base == null -> throw SaoriException.bad("ghost required")
            else -> File(base, raw)
        }
        val root = env.ghostRoot?.canonicalFile ?: throw SaoriException.bad(if (given.isAbsolute) outside else "ghost required")
        val canon = file.canonicalFile
        if (canon != root && !canon.path.startsWith(root.path + File.separator)) throw SaoriException.bad(outside)
        return canon
    }
}

/**
 * SAORI over DirectSSTP の受け口。SAORI/1.0 の要求（Module ヘッダ付き）に、内蔵モジュール（internal_*）で答える。
 * ゴーストの SHIORI（YAYA の FUNCTIONEX・里々の（internal_…））から、呼び出し元のゴーストごとに使う
 */
class InternalSaori(private val env: SaoriEnv) {
    /** 互換モジュール（textcopy などの DLL の代わり）。呼び出し元のゴーストごとに状態を持つ */
    private val compat = CompatSaori(env)

    /** 要求のバイト列に答える。[module] は Module ヘッダが無いときに使う名前（YAYA が DLL として読んだもの） */
    fun handle(request: ByteArray, module: String? = null): ByteArray {
        val req = SaoriRequest.parse(request) ?: return SaoriResponse.error(400, "invalid parameter").encode(TextCharsets.CP932)
        return answer(req, module).encode(req.charset)
    }

    fun answer(req: SaoriRequest, fallbackModule: String? = null): SaoriResponse {
        val moduleName = (req["Module"] ?: fallbackModule)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SaoriResponse.error(400, "invalid parameter")
        moduleName.lowercase().takeIf { it in CompatSaori.NAMES }?.let { return compat.answer(it, req) }
        val module = MODULES.firstOrNull { it.name.equals(moduleName, ignoreCase = true) }
            ?: return SaoriResponse.error(400, "unknown module")
        if (req.method == SaoriRequest.GET_VERSION) return SaoriResponse(200, VERSION_RESULT)
        val args = req.arguments
        val fname = args.firstOrNull()?.trim().orEmpty()
        if (fname.isEmpty()) return SaoriResponse.error(400, "invalid parameter")
        val function = module.function(fname) ?: return SaoriResponse.error(400, "unknown function")
        return try {
            val call = accept(module, function, args.drop(1))
            val asyncId = call.options["async"]
            if (asyncId != null) {
                runAsync(call, asyncId)
                SaoriResponse(204)
            } else {
                SaoriResponse.of(function.body(call))
            }
        } catch (e: SaoriException) {
            SaoriResponse.error(e.code, e.description)
        } catch (e: Exception) {
            SaoriResponse.error(500, "internal error")
        }
    }

    /** 引数を位置引数とオプションに分ける。数の足りない・知らないオプションなどはここで 400 */
    private fun accept(module: SaoriModule, function: SaoriFunction, rest: List<String>): SaoriCall {
        val split = if (function.positional == SaoriFunction.VARIADIC) {
            // 2番目以降の位置で最初に -- で始まる値から後ろがオプション
            val at = rest.indices.firstOrNull { it >= 1 && rest[it].startsWith("--") } ?: rest.size
            at
        } else {
            minOf(function.positional, rest.size)
        }
        val positional = rest.subList(0, split)
        if (positional.size < function.required) throw SaoriException.bad("invalid parameter")
        val options = LinkedHashMap<String, String>()
        for (raw in rest.subList(split, rest.size)) {
            if (!raw.startsWith("--")) throw SaoriException.bad("invalid parameter")
            val name = raw.substring(2).substringBefore('=').lowercase()
            if (name !in function.options) throw SaoriException.bad("invalid parameter")
            options[name] = raw.substringAfter('=', "")
        }
        val call = SaoriCall(module, function, positional, options, env)
        options["async"]?.let { id ->
            if (id.isEmpty()) throw SaoriException.bad("invalid parameter")
            if (env.ghostMaster == null) throw SaoriException.bad("ghost required")
        }
        return call
    }

    private fun runAsync(call: SaoriCall, id: String) {
        val moduleName = call.module.name
        val fname = call.function.name
        env.async {
            val outcome = try {
                Result.success(call.function.body(call))
            } catch (e: SaoriException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(SaoriException.fail("internal error"))
            }
            outcome.fold(
                { r -> (if (id.startsWith("On")) id else "OnInternalSaoriComplete") to listOf(id, r.result, moduleName, fname) + r.values },
                { e -> (if (id.startsWith("On")) id + "Failure" else "OnInternalSaoriFailure") to listOf(id, (e as SaoriException).description, moduleName, fname) },
            )
        }
    }

    companion object {
        /** GET Version に返す Result（SSP は SSP Internal） */
        const val VERSION_RESULT = "pono-droid Internal"

        /** 内蔵モジュール（並び順に意味は無い） */
        val MODULES: List<SaoriModule> by lazy {
            listOf(
                CryptoModule.module, RandomModule.module, TimeModule.module, DataModule.module, TextModule.module,
                JwtModule.module, SystemModule.module, FileModule.module,
            )
        }

        /** ゴーストが読もうとした SAORI の名前（パスや .dll 付きでもよい）が内蔵モジュールか互換モジュールなら、そのモジュールの名前 */
        fun moduleNameOf(path: String): String? {
            val base = path.replace('\\', '/').substringAfterLast('/').let {
                if (it.endsWith(".dll", ignoreCase = true)) it.dropLast(4) else it
            }
            return base.lowercase().takeIf { it.startsWith("internal_") || it in CompatSaori.NAMES }
        }
    }
}

/** SAORI/1.0 の要求。ヘッダの名前は大文字小文字を区別しない */
class SaoriRequest(val method: String, val headers: List<Pair<String, String>>, val charset: Charset) {
    operator fun get(name: String): String? = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Argument0 から番号順に（途中が抜けていればそこまで） */
    val arguments: List<String> by lazy {
        val byIndex = HashMap<Int, String>()
        for ((k, v) in headers) {
            if (k.length > 8 && k.startsWith("Argument", ignoreCase = true)) {
                k.substring(8).toIntOrNull()?.let { byIndex[it] = v }
            }
        }
        generateSequence(0) { it + 1 }.takeWhile { it in byIndex }.map { byIndex.getValue(it) }.toList()
    }

    companion object {
        const val EXECUTE = "EXECUTE"
        const val GET_VERSION = "GET Version"

        /** 要求を読む。Charset ヘッダの文字コードで読み直す（無ければ SAORI/1.0 の既定の Shift_JIS）。SAORI/1.0 の要求でなければ null */
        fun parse(bytes: ByteArray): SaoriRequest? {
            val ascii = String(bytes, Charsets.ISO_8859_1)
            val charsetName = Regex("""(?im)^Charset:\s*([^\r\n]+)""").find(ascii)?.groupValues?.get(1)?.trim()
            val charset = TextCharsets.resolve(charsetName) ?: TextCharsets.CP932
            val text = String(bytes, charset).substringBefore("\u0000")
            val lines = text.split("\r\n")
            val first = lines.firstOrNull()?.trim() ?: return null
            val method = when {
                first.equals("EXECUTE SAORI/1.0", ignoreCase = true) -> EXECUTE
                first.equals("GET Version SAORI/1.0", ignoreCase = true) -> GET_VERSION
                else -> return null
            }
            val headers = lines.drop(1).takeWhile { it.isNotEmpty() }.mapNotNull { line ->
                val i = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, i).trim() to line.substring(i + 1).removePrefix(" ")
            }
            return SaoriRequest(method, headers, charset)
        }
    }
}

/** SAORI/1.0 の応答 */
class SaoriResponse(val code: Int, val result: String? = null, val values: List<String> = emptyList(), val error: String? = null) {
    fun encode(charset: Charset): ByteArray {
        val sb = StringBuilder("SAORI/1.0 $code ${MESSAGES[code] ?: ""}\r\n")
        sb.append("Charset: ").append(if (charset == TextCharsets.CP932) "Shift_JIS" else charset.name()).append("\r\n")
        result?.let { sb.append("Result: ").append(clean(it)).append("\r\n") }
        values.forEachIndexed { i, v -> sb.append("Value").append(i).append(": ").append(clean(v)).append("\r\n") }
        error?.let { sb.append("ErrorDescription: ").append(it).append("\r\n") }
        sb.append("\r\n")
        return sb.toString().toByteArray(charset)
    }

    /** ヘッダの値に書けない改行は、念のため空白にする（関数は改行を返さない） */
    private fun clean(s: String) = s.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace("\u0000", "")

    companion object {
        val MESSAGES = mapOf(200 to "OK", 204 to "No Content", 400 to "Bad Request", 500 to "Internal Server Error")

        fun error(code: Int, description: String) = SaoriResponse(code, error = description)

        /** 結果が空で値も無ければ 204 */
        fun of(r: SaoriResult) = if (r.result.isEmpty() && r.values.isEmpty()) SaoriResponse(204) else SaoriResponse(200, r.result, r.values)
    }
}
