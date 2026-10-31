package net.pono.baseware.plugin

import net.pono.baseware.ghost.Ghost
import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import java.io.File

/**
 * インストール済みのプラグイン（PLUGIN/2.0）。[dir] の descript.txt に name・id・filename が要る。
 * 実体の DLL は Windows のものなので、filename の名前（yaya.dll・kagari.dll など）で pono-droid の SHIORI のエンジンに置き換えて動かす
 */
class Plugin(val dir: File) {
    val descript: Descript = Descript.read(File(dir, "descript.txt"))

    /** descript.txt の文字列。\ や % で始まるタグは捨てる（さくらスクリプトや環境変数として働かないように。仕様の注意書き） */
    private fun text(key: String): String? = descript[key]?.let(Ghost::plainText)?.trim()?.takeIf { it.isNotEmpty() }

    val name: String get() = text("name") ?: dir.name

    /** プラグインの ID（GUID など。1バイト文字だけの 63 バイトまで） */
    val id: String get() = descript["id"]?.trim().orEmpty()

    /** 実体の DLL（descript.txt の filename） */
    val filename: String get() = descript["filename"]?.trim().orEmpty()

    val craftman: String get() = text("craftman").orEmpty()
    val craftmanW: String get() = text("craftmanw") ?: craftman

    /** 作者のページ（http か https のときだけ） */
    val craftmanUrl: String? get() = descript["craftmanurl"]?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    val homeUrl: String get() = descript["homeurl"]?.trim().orEmpty()

    /** OnSecondChange を送る間隔（秒）。0 なら送らない */
    val secondChangeInterval: Int get() = descript.int("secondchangeinterval")?.coerceAtLeast(0) ?: 1

    /** OnOtherGhostTalk を送るか（[OtherGhostTalk]） */
    val otherGhostTalk: OtherGhostTalk get() = when (descript["otherghosttalk"]?.trim()?.lowercase()) {
        "true", "1", "after" -> OtherGhostTalk.AFTER
        "before" -> OtherGhostTalk.BEFORE
        else -> OtherGhostTalk.NONE
    }

    /** name・id・filename がそろっていて、id が仕様どおり（1バイト文字だけで 63 バイトまで） */
    val isValid: Boolean
        get() = File(dir, "descript.txt").isFile && descript["name"]?.isNotBlank() == true && filename.isNotEmpty() &&
            id.isNotEmpty() && id.length <= MAX_ID && id.all { it.code in 0x21..0x7e }

    /** descript.txt の readme（無ければ readme.txt）。プラグインのフォルダの中だけ */
    fun readmeFile(): File? = TextCharsets.fileIn(dir, descript["readme"]?.takeIf { it.isNotBlank() } ?: "readme.txt")

    /** readme の中身（readme.charset があればその文字コードで読む）。無ければ null */
    fun readmeText(): String? = readmeFile()?.let { f -> runCatching { TextCharsets.decode(f.readBytes(), descript["readme.charset"]) }.getOrNull() }

    /** [key] が名前・フォルダのパス・ID のどれかに合うか（\![raiseplugin] や pluginlist(…) の指定） */
    fun matches(key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        return k == name || k.equals(id, ignoreCase = true) || k.trimEnd('/', '\\') == dir.absolutePath.trimEnd('/') || k == dir.name
    }

    /** \![set,otherghosttalk] と同じ区別 */
    enum class OtherGhostTalk { NONE, BEFORE, AFTER }

    companion object {
        private const val MAX_ID = 63
    }
}

/** プラグインの置き場（[root] の下に1つずつフォルダがある） */
class PluginRepository(val root: File) {
    fun list(): List<Plugin> =
        root.listFiles { f -> f.isDirectory }?.map(::Plugin)?.filter { it.isValid }?.sortedBy { it.name }.orEmpty()

    /** 名前・ID・パス・フォルダ名で探す */
    fun find(key: String): Plugin? = list().firstOrNull { it.matches(key) }

    /** プラグインを消す（元に戻せない）。置き場の外は消さない */
    fun uninstall(plugin: Plugin): Boolean {
        val base = root.canonicalFile
        val target = plugin.dir.canonicalFile
        if (target.parentFile != base) return false
        return target.deleteRecursively()
    }
}
