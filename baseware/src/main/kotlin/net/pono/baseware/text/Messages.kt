package net.pono.baseware.text

import java.util.concurrent.ConcurrentHashMap

/**
 * baseware が出す文言（右クリックメニュー・確認・エラー）の言語表。SSP の message.txt と同じく `キー,値` の行で、// から始まる行はコメント。
 * 表は messages/<言語>.txt（[LANGUAGES]）。値の %1 %2 … に引数を入れる。値はそのまま SakuraScript に入るので、\n は改行のタグになる。
 * 今の言語の表に無いキーは英語の表から、それにも無ければキーをそのまま返す
 */
object Messages {
    /** pono-droid が訳している言語。アプリの values-xx と同じ並び */
    val LANGUAGES = listOf("en", "ja", "zh-Hans", "zh-Hant", "ko")

    /** SSP の言語の名前と言語 ID（OnLanguageChange の Reference0・1）。ssp-i18n の descript.txt の name・id と同じ */
    private val SSP_LANGUAGES = mapOf(
        "en" to ("English" to 1033),
        "ja" to ("Japanese" to 1041),
        "zh-Hans" to ("Chinese-Simplified" to 2052),
        "zh-Hant" to ("Chinese-Traditional" to 1028),
        "ko" to ("Korean" to 1042),
    )

    /**
     * 今の言語。アプリが Android の選んだ言語（values-xx の language_code）を入れる。
     * 入れられるまでは日本語（JVM のテストは日本語の文言で確かめる）
     */
    @Volatile
    var language: String = "ja"
        set(value) {
            field = if (value in LANGUAGES) value else "en"
        }

    private val tables = ConcurrentHashMap<String, Map<String, String>>()

    /** 文言から作った正規表現（[pattern]）。言語とキーごと */
    private val patterns = ConcurrentHashMap<String, Regex>()

    /** [key] の文言の %1 %2 … に [args] を入れたもの */
    fun get(key: String, vararg args: Any?): String {
        val text = table(language)[key] ?: table("en")[key] ?: key
        return format(text, args)
    }

    /** [language] の SSP の言語名（English・Japanese・Chinese-Simplified…） */
    fun sspName(language: String = this.language): String = (SSP_LANGUAGES[language] ?: SSP_LANGUAGES.getValue("en")).first

    /** [language] の Windows の言語 ID（1041 など） */
    fun sspId(language: String = this.language): Int = (SSP_LANGUAGES[language] ?: SSP_LANGUAGES.getValue("en")).second

    /** [language] の表（キー → 値）。テストで言語の間の抜けを確かめるのにも使う */
    fun table(language: String): Map<String, String> = tables.getOrPut(language) {
        Messages::class.java.getResourceAsStream("/net/pono/baseware/messages/$language.txt")
            ?.use { parse(it.readBytes().toString(Charsets.UTF_8)) }
            .orEmpty()
    }

    /**
     * [text] が、今の言語の [key] の文言から作った文か（%1〜%9 には何が入っていてもよい）。
     * ログの文がどの文言から作られたかを、言語によらず見分けるのに使う。キーがどの表にも無ければ false
     */
    fun matches(key: String, text: String): Boolean = pattern(language, key)?.matches(text) == true

    /** [text] が [keys] のどれかの文言から作った文か（[matches]） */
    fun matchesAny(keys: Iterable<String>, text: String): Boolean = keys.any { matches(it, text) }

    /** [language] の [key] の文言（無ければ英語の文言）から作った正規表現。一度作ったら覚えておく */
    private fun pattern(language: String, key: String): Regex? {
        val text = table(language)[key] ?: table("en")[key] ?: return null
        return patterns.getOrPut("$language\u0000$key") { patternOf(text) }
    }

    /** 文言の %1〜%9 を何にでも合うものに、ほかを文字どおりに合うものにした正規表現（%% は % 1文字） */
    internal fun patternOf(text: String): Regex {
        val out = StringBuilder()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) out.append(Regex.escape(literal.toString()))
            literal.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '%' && next == '%' -> {
                    literal.append('%')
                    i += 2
                }
                c == '%' && next != null && next in '1'..'9' -> {
                    flush()
                    out.append("(.*)")
                    i += 2
                }
                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flush()
        return Regex(out.toString(), RegexOption.DOT_MATCHES_ALL)
    }

    /** `キー,値` の行を読む。値の前後の空白は残す（文の続きに付ける文言があるため） */
    internal fun parse(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.removeSuffix("\r")
            if (line.isBlank() || line.startsWith("//")) continue
            val comma = line.indexOf(',')
            if (comma <= 0) continue
            map.putIfAbsent(line.substring(0, comma).trim(), line.substring(comma + 1))
        }
        return map
    }

    /** %1 〜 %9 を [args] で置き換える。%% は % */
    internal fun format(text: String, args: Array<out Any?>): String {
        if ('%' !in text) return text
        val out = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                c == '%' && next == '%' -> {
                    out.append('%')
                    i += 2
                }
                c == '%' && next != null && next in '1'..'9' -> {
                    out.append(args.getOrNull(next - '1')?.toString().orEmpty())
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
