package net.pono.baseware.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.GhostHub
import net.pono.baseware.ghost.GhostRepository
import net.pono.baseware.shiori.Shiori
import net.pono.baseware.text.TextCharsets
import java.io.File
import java.nio.charset.Charset

/** プラグインの実体を動かすもの。[filename] は descript.txt の filename（yaya.dll など）、[dir] はその DLL のあるフォルダ。動かせなければ null */
fun interface PluginEngineFactory {
    fun create(filename: String, dir: File): Shiori?
}

/**
 * インストール済みのプラグインを動かす（PLUGIN/2.0）。アプリが1つ持ち、どのゴーストにも同じものを渡す。
 * プラグインへの要求は [scope] から順に送り、答え（Event・Script）は Target のゴースト（無ければ呼んだゴーストか、最初に起動したゴースト）に渡す
 */
class PluginHost(
    val repository: PluginRepository,
    private val hub: GhostHub,
    private val engines: PluginEngineFactory,
    private val scope: CoroutineScope,
    /** ゴーストとバルーンの置き場（installedghostname・ghostpathlist などを伝える） */
    private val ghosts: GhostRepository? = null,
    private val log: (String) -> Unit = {},
    private val loadTimeoutMs: Long = 30_000,
    private val requestTimeoutMs: Long = 10_000,
) {
    /** 読み込んだプラグイン1つ。要求は1つずつ送る（SHIORI と同じくスレッドセーフではない） */
    private class Loaded(val plugin: Plugin, val engine: Shiori) {
        @Volatile var charset: Charset = TextCharsets.CP932
        val lock = Mutex()
        var secondChange: Job? = null
    }

    private val loaded = LinkedHashMap<String, Loaded>()
    private val timers = HashMap<Pair<String, String>, Job>()

    /** 最後にインストールしたプラグイン（\![raiseplugin,lastinstalled,…]）。アプリを終えると忘れる */
    @Volatile private var lastInstalled: String? = null

    /** インストール済みのプラグイン（読み込めなかったものも） */
    val plugins: List<Plugin> get() = repository.list()

    /** 読み込めて動いているプラグイン */
    val running: List<Plugin> get() = synchronized(loaded) { loaded.values.map { it.plugin } }

    /** 全部読み込み直す（起動したときと、プラグインを入れたり消したりしたとき） */
    fun start(): Job = scope.launch { reload() }

    fun stop(): Job = scope.launch { unloadAll() }

    private suspend fun reload() {
        unloadAll()
        for (p in repository.list()) load(p)
    }

    private suspend fun unloadAll() {
        val all = synchronized(loaded) { loaded.values.toList().also { loaded.clear() } }
        synchronized(timers) {
            timers.values.forEach { it.cancel() }
            timers.clear()
        }
        for (l in all) {
            l.secondChange?.cancel()
            withContext(Dispatchers.IO) { runCatching { l.engine.unload() } }
        }
    }

    private suspend fun load(p: Plugin) {
        val dll = File(p.dir, p.filename.replace('\\', '/'))
        val dir = dll.parentFile ?: p.dir
        // プラグインのフォルダの外を指す filename は使わない
        if (!dir.canonicalPath.startsWith(p.dir.canonicalPath)) return
        val engine = engines.create(dll.name, dir)
        if (engine == null) {
            log("プラグイン「${p.name}」の ${p.filename} は pono-droid では動かせません")
            return
        }
        val ok = withContext(Dispatchers.IO) { withTimeoutOrNull(loadTimeoutMs) { runCatching { engine.load(dir) }.getOrDefault(false) } } ?: false
        if (!ok) {
            log("プラグイン「${p.name}」を読み込めませんでした")
            withContext(Dispatchers.IO) { runCatching { engine.unload() } }
            return
        }
        val l = Loaded(p, engine)
        synchronized(loaded) { loaded[key(p)] = l }
        // ロードの直後に version。答えの Charset で、以後の要求の文字コードが決まる（決めなければ Shift_JIS）
        call(l, notify = false, "version", emptyList(), null)?.charset?.let(TextCharsets::resolve)?.let { l.charset = it }
        for ((id, refs) in installInfo()) call(l, notify = true, id, refs, null)
        val interval = p.secondChangeInterval
        if (interval > 0) {
            l.secondChange = scope.launch {
                while (isActive) {
                    delay(interval * 1000L)
                    send(l, notify = false, "OnSecondChange", emptyList(), null)
                }
            }
        }
    }

    private fun key(p: Plugin) = p.dir.absolutePath

    private fun loadedOf(p: Plugin): Loaded? = synchronized(loaded) { loaded[key(p)] }

    /** 読み込みの後に伝えるもの（installedplugin・installedghostname・installedballoonname・ghostpathlist・balloonpathlist・pluginpathlist） */
    private fun installInfo(): List<Pair<String, List<String>>> = buildList {
        add("installedplugin" to repository.list().map { "${it.name}\u0001${it.id}" })
        ghosts?.let { g ->
            val list = g.list()
            add("installedghostname" to listOf(list.joinToString("\u0001") { it.name }, list.joinToString("\u0001") { it.sakuraName }, list.joinToString("\u0001") { it.keroName }))
            add("installedballoonname" to listOf(g.balloons.list().joinToString("\u0001") { it.name }))
            add("ghostpathlist" to listOf(g.root.absolutePath + "/"))
            add("balloonpathlist" to listOf(g.balloons.root.absolutePath + "/"))
        }
        add("pluginpathlist" to listOf(repository.root.absolutePath + "/"))
    }

    // ---- 送る ----

    /**
     * プラグインに送って答えを返す（[notify] なら NOTIFY）。[sender] はゴーストから呼ばれたときのゴースト名。
     * 止まったり落ちたりしたプラグインからは null
     */
    private suspend fun call(l: Loaded, notify: Boolean, id: String, references: List<String>, sender: String?): PluginProtocol.Response? =
        l.lock.withLock {
            val req = PluginProtocol.request(notify, id, references, l.charset, sender)
            val bytes = withContext(Dispatchers.IO) { withTimeoutOrNull(requestTimeoutMs) { runCatching { l.engine.request(req) }.getOrNull() } }
            if (bytes == null) {
                log("プラグイン「${l.plugin.name}」が $id に応えません")
                return@withLock null
            }
            PluginProtocol.parse(bytes, l.charset)
        }

    /** 送って、答えのイベントやスクリプトをゴーストに渡す（NOTIFY の答えは使わない）。答えの状態コードを返す */
    private suspend fun send(l: Loaded, notify: Boolean, id: String, references: List<String>, caller: GhostController?): Int? {
        val r = call(l, notify, id, references, caller?.ghost?.name) ?: return null
        if (!notify) deliver(l.plugin, r, caller)
        return r.statusCode
    }

    /** 答えの Event・Script を、Target のゴースト（__SYSTEM_ALL_GHOST__ なら全部）か呼んだゴースト、どちらも無ければ最初に起動したゴーストへ */
    private fun deliver(plugin: Plugin, r: PluginProtocol.Response, caller: GhostController?) {
        if (r.event == null && r.script == null) return
        val running = hub.running
        val fallback = caller?.takeIf { it in running } ?: running.firstOrNull()
        val targets = when (val t = r.target) {
            null -> listOfNotNull(fallback)
            ALL_GHOSTS -> running
            else -> running.filter { it.ghost.name == t || it.ghost.sakuraName == t }.ifEmpty { listOfNotNull(fallback) }
        }
        for (g in targets) g.fromPlugin(plugin, r.event, r.references, r.eventNotify, r.script, r.scriptOptions)
    }

    /** 名前・ID・パス（random / lastinstalled も）で、読み込んであるプラグインを探す */
    private fun resolve(key: String): Loaded? {
        val all = synchronized(loaded) { loaded.values.toList() }
        return when (key.trim().lowercase()) {
            "random" -> all.randomOrNull()
            "lastinstalled" -> lastInstalled?.let { k -> all.firstOrNull { it.plugin.matches(k) } }
            else -> all.firstOrNull { it.plugin.matches(key) }
        }
    }

    /**
     * \![raiseplugin] / \![notifyplugin]。見つからなければ "notfound"（呼んだゴーストに OnRaisePluginFailure などを送る）。
     * GET で 200 以外が返ったときは、その状態コードを [onFailure] に渡す
     */
    fun raise(key: String, event: String, references: List<String>, notify: Boolean, caller: GhostController?, onFailure: (String) -> Unit): Boolean {
        val l = resolve(key) ?: return false
        scope.launch {
            val status = send(l, notify, event, references, caller)
            if (!notify && status != null && status != 200) onFailure(status.toString())
        }
        return true
    }

    /**
     * \![timerraiseplugin] / \![timernotifyplugin]。[delayMs] ごと（[repeat] でなければ1回だけ）に送る。同じプラグインの同じイベントは上書きし、
     * [delayMs] が 0 なら取り消す。プラグインが無ければ false
     */
    fun timer(delayMs: Long, repeat: Boolean, key: String, event: String, references: List<String>, notify: Boolean, caller: GhostController?): Boolean {
        val l = resolve(key) ?: return false
        val k = key(l.plugin) to event
        synchronized(timers) {
            timers.remove(k)?.cancel()
            if (delayMs <= 0) return true
            timers[k] = scope.launch {
                do {
                    delay(delayMs)
                    send(l, notify, event, references, caller)
                } while (repeat && isActive)
                synchronized(timers) { if (timers[k] === coroutineContext[Job]) timers.remove(k) }
            }
        }
        return true
    }

    /** オーナードローメニューから選ばれた（OnMenuExec） */
    fun menuExec(plugin: Plugin, caller: GhostController) {
        val l = loadedOf(plugin) ?: return
        scope.launch { send(l, notify = false, "OnMenuExec", ghostRefs(caller), caller) }
    }

    /**
     * プラグインのスクリプトかイベントへの答えにあった選択肢・アンカーが選ばれた（その1回だけ、同じイベントをプラグインにも）。
     * [events] を順に送り、最初に Event か Script の答えがあれば、それをゴーストに渡すものを返す（ゴーストが答えなかったときに呼ぶ）
     */
    suspend fun choiceSelected(plugin: Plugin, events: List<Pair<String, List<String>>>, caller: GhostController): (() -> Unit)? {
        val l = loadedOf(plugin) ?: return null
        for ((event, refs) in events) {
            val r = call(l, notify = false, event, refs, caller.ghost.name) ?: return null
            if (r.statusCode == 200 && (r.event != null || r.script != null)) return { deliver(plugin, r, caller) }
        }
        return null
    }

    /** ゴーストが起動した・終わった・情報が変わった（OnGhostBoot・OnGhostExit・OnGhostInfoUpdate） */
    fun ghostBooted(c: GhostController) = everyone(notify = false, "OnGhostBoot", ghostRefs(c), c)

    fun ghostExited(c: GhostController) = everyone(notify = true, "OnGhostExit", ghostRefs(c), null)

    fun ghostInfoUpdated(c: GhostController) = everyone(notify = true, "OnGhostInfoUpdate", ghostRefs(c), null)

    /** 何かをインストールした（OnInstallComplete）。種類・名前・パスは 0x01 でつなぐ */
    fun installComplete(types: List<String>, names: List<String>, paths: List<String>) {
        if ("plugin" in types) {
            lastInstalled = paths.getOrNull(types.indexOf("plugin"))
            // 入ったプラグインを読み込んでから知らせる
            scope.launch {
                reload()
                everyone(notify = false, "OnInstallComplete", listOf(types.joinToString("\u0001"), names.joinToString("\u0001"), paths.joinToString("\u0001")), null)
            }
            return
        }
        everyone(notify = false, "OnInstallComplete", listOf(types.joinToString("\u0001"), names.joinToString("\u0001"), paths.joinToString("\u0001")), null)
    }

    /**
     * ゴーストが話した（OnOtherGhostTalk）。descript.txt の otherghosttalk が [timing]（before / after）のプラグインにだけ送る。
     * [flags] は break・sstp-send・plugin-script などをカンマでつないだもの
     */
    fun ghostTalked(c: GhostController, timing: Plugin.OtherGhostTalk, flags: String, event: String, script: String, references: List<String>) {
        val refs = listOf(c.ghost.name, c.ghost.sakuraName, flags, event, script, references.joinToString("\u0001"))
        val targets = synchronized(loaded) { loaded.values.filter { it.plugin.otherGhostTalk == timing } }
        for (l in targets) scope.launch { send(l, notify = false, "OnOtherGhostTalk", refs, c) }
    }

    /** pluginlist(…).ext.名前 を読む（property.get の Value）。動いていないプラグインは null */
    suspend fun propertyGet(plugin: Plugin, name: String): String? {
        val l = loadedOf(plugin) ?: return null
        return call(l, notify = false, "property.get", listOf(name), null)?.takeIf { it.statusCode == 200 }?.value
    }

    /** pluginlist(…).ext.名前 に書く（property.set）。成功（204 か 200）なら true */
    suspend fun propertySet(plugin: Plugin, name: String, value: String): Boolean {
        val l = loadedOf(plugin) ?: return false
        return call(l, notify = false, "property.set", listOf(name, value), null)?.statusCode in setOf(200, 204)
    }

    private fun everyone(notify: Boolean, id: String, refs: List<String>, caller: GhostController?) {
        val all = synchronized(loaded) { loaded.values.toList() }
        for (l in all) scope.launch { send(l, notify, id, refs, caller) }
    }

    /**
     * OnGhostBoot などの Reference。HWND は Android に無いので 0 をキャラクターの数だけ並べ、ゴースト名・シェル名・ID（Owned SSTP の uniqueid）・
     * ゴーストのフォルダのパスを続ける
     */
    private fun ghostRefs(c: GhostController): List<String> {
        val count = c.state.value.characters.size.coerceAtLeast(2)
        return listOf(List(count) { "0" }.joinToString("\u0001"), c.ghost.name, c.shell.name, c.uniqueId, c.ghost.dir.absolutePath + "/")
    }

    companion object {
        /** Target のこの値は、起動中の全部のゴースト */
        const val ALL_GHOSTS = "__SYSTEM_ALL_GHOST__"
    }
}
