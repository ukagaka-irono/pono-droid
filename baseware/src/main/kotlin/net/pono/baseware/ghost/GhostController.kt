package net.pono.baseware.ghost

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.MoveCommand
import net.pono.baseware.sakura.ScriptHost
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.sakura.TextAlign
import net.pono.baseware.sakura.TextStyle
import net.pono.baseware.sakura.Token
import net.pono.baseware.shell.Shell
import net.pono.baseware.shiori.ShioriFactory
import net.pono.baseware.shiori.ShioriMethod
import net.pono.baseware.shiori.ShioriRequest
import net.pono.baseware.shiori.ShioriSession
import net.pono.baseware.shiori.ShioriTimeoutException
import net.pono.baseware.text.TextCharsets
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import net.pono.baseware.update.NetworkUpdater
import net.pono.baseware.update.NullOutputStream
import net.pono.baseware.update.UpdateResult
import net.pono.baseware.update.UrlConnectionFetcher
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 端末の情報。OnNotifyOSInfo・OnDisplayChange・プロパティで伝える。分からないところは空や 0 でよい */
data class DeviceInfo(
    val osName: String = "Android",
    val osVersion: String = "",
    val osBuild: String = "",
    val arch: String = "",
    val cpuCount: Int = 0,
    val memoryTotalMb: Long = 0,
    val memoryFreeMb: Long = 0,
    val uptimeSeconds: Long = 0,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val dpi: Int = 0,
)

/** 電池の状態。[percent] は -1 なら不明 */
data class BatteryInfo(val percent: Int, val charging: Boolean, val plugged: Boolean)

/** プラットフォーム側に頼むこと。 */
interface GhostPlatform {
    fun openUrl(url: String)

    /** `\-` などでゴーストが終了した */
    fun onQuit()
    fun log(message: String)

    /** ユーザーに知らせるべき失敗（SHIORI が読めないなど） */
    fun onError(message: String) = log(message)

    /** 端末の情報。null なら OnNotifyOSInfo などを送らない */
    fun deviceInfo(): DeviceInfo? = null
    fun batteryInfo(): BatteryInfo? = null

    /** readme のような長い文を見せる（\![open,readme]） */
    fun showText(title: String, text: String) {}

    /** pono-droid の設定画面（SSP の本体設定やゴーストエクスプローラの代わり） */
    fun openSettings() {}

    /** 音を鳴らす（\8 \_v \![sound]）。[volume] は 0〜100（null なら既定） */
    fun playSound(file: File, loop: Boolean, volume: Int?) {}

    /** 鳴っている音を全部止める */
    fun stopSound() {}

    /** 鳴り終わるまで待つ（鳴っていなければすぐ戻る） */
    suspend fun waitSound() {}

    /** シェルが切り替わった。ウィンドウを作り直す */
    fun onShellChanged(shell: Shell) {}

    /** バルーンが切り替わった（null なら簡易バルーン） */
    fun onBalloonChanged(balloon: Balloon?) {}

    /**
     * ゴーストを切り替える（\![change,ghost] とメニュー）。このゴーストは SHIORI を閉じ終わっている。
     * [dirName] のゴーストを、[previous] を伝えて起動する（OnGhostChanged）。既定では終了するだけ
     */
    fun switchGhost(dirName: String, previous: PreviousGhost) = onQuit()
}

/** ゴーストの切り替えで、次のゴーストに伝える前のゴーストのこと（OnGhostChanged の Reference） */
data class PreviousGhost(val sakuraName: String, val script: String, val name: String, val path: String)

/**
 * ゴースト1体を動かす。SHIORI にイベントを送り、返ってきたさくらスクリプトを再生して [state] に反映する。
 * イベントの送信とスクリプトの再生はすべて [scope] の中で行う。
 */
class GhostController(
    val ghost: Ghost,
    shell: Shell,
    private val shioriFactory: ShioriFactory,
    private val scope: CoroutineScope,
    private val platform: GhostPlatform,
    /** 使っているバルーン（null なら pono-droid の簡易バルーン） */
    balloon: Balloon? = null,
    /** 入っているゴーストとバルーンの一覧（プロパティシステムの ghostlist などに使う） */
    private val repository: GhostRepository? = null,
    userName: String = "ユーザ",
    private val player: ScriptPlayer = ScriptPlayer(),
    private val balloonTimeoutMs: Long = 15_000,
    private val choiceTimeoutMs: Long = 30_000,
    /** ゴーストに伝える pono-droid のバージョン（basewareversion、%property[baseware.version]、通信の User-Agent） */
    private val basewareVersion: String = BASEWARE_VERSION,
    private val updateFetcher: HttpFetcher = UrlConnectionFetcher("${ShioriSession.SENDER}/$basewareVersion"),
    /** 別のゴーストから切り替わって起動したとき、その前のゴースト（OnGhostChanged を送る） */
    private val previous: PreviousGhost? = null,
) {
    private val _state = MutableStateFlow(GhostState())
    val state: StateFlow<GhostState> = _state.asStateFlow()

    /** 使っているシェル。\![change,shell] やメニューで変わる */
    var shell: Shell = shell
        private set

    /** 使っているバルーン（null なら簡易バルーン）。\![change,balloon] やメニューで変わる */
    var balloon: Balloon? = balloon
        private set

    /** %username。SHIORI が username リソースを返せばそれに変わる */
    private var userName = userName
    private var session: ShioriSession? = null
    private var scriptJob: Job? = null
    private var timerJob: Job? = null
    private val requestLock = Mutex()
    private var clickWaiter: CompletableDeferred<Unit>? = null
    private var choiceWaiter: CompletableDeferred<Choice?>? = null
    private val bootTime = System.currentTimeMillis()
    private var lastUserActivity = System.currentTimeMillis()
    private var animationCounter = 0
    private var closing = false
    private var inputSerial = 0
    private var inputTimeout: Job? = null
    private var httpGetsInFlight = 0

    /** 最後に再生したスクリプト（OnBalloonClose などで伝える） */
    private var lastScript = ""

    /** \![timerraise] の予約（イベント名ごとに1つ） */
    private val timers = HashMap<String, Job>()

    /** SHIORI の homeurl リソース。descript.txt より優先する */
    private var homeUrlOverride: String? = null
    private var lastBattery: BatteryInfo? = null

    fun start() {
        scope.launch {
            resetSurfaces()
            if (!loadShiori()) {
                // 話せないゴーストを出しっぱなしにしない
                closing = true
                platform.onQuit()
                return@launch
            }

            val profile = ghost.loadProfile()
            val firstBoot = profile.getProperty("booted") == null
            profile.setProperty("booted", "1")
            ghost.saveProfile(profile)

            // 切り替わって来たときは OnGhostChanged。答えが無ければ OnBoot（SSP と同じ）
            val changed = if (!firstBoot && previous != null) {
                request(
                    "OnGhostChanged",
                    listOf(previous.sakuraName, previous.script, previous.name, previous.path, "", "", "", shell.name),
                )
            } else {
                null
            }
            when {
                firstBoot -> raise("OnFirstBoot", listOf("0"))
                changed != null -> runScript(changed)
                else -> raise("OnBoot", listOf(shell.name, "", "", "", "", "", "", ""))
            }
            startTimer()
        }
    }

    /** SHIORI を読み込み、起動時の NOTIFY を送る。 */
    private suspend fun loadShiori(): Boolean {
        val shiori = shioriFactory.create(ghost.shioriName, ghost.masterDir)
        if (shiori == null) {
            platform.onError("${ghost.name}: ${ghost.shioriName} は未対応です（YAYA・里々・華和梨・Kagari に対応）")
            return false
        }
        val s = ShioriSession(shiori, ghost.charset)
        // 読み込みで固まる辞書（load で無限ループ）は時間切れの例外になる
        if (!runCatching { s.load(ghost.masterDir) }.getOrDefault(false)) {
            runCatching { s.unload() }
            platform.onError("${ghost.name}: SHIORI を読み込めませんでした")
            return false
        }
        // SHIORI が別プロセスで落ちたら、話せないゴーストを残さず閉じる（アプリは落ちない）
        shiori.setOnCrash {
            scope.launch {
                if (session !== s || closing) return@launch
                closing = true
                platform.onError("${ghost.name}: SHIORI が異常終了しました")
                cancelScript()
                shutdown()
            }
        }
        session = s
        val notified = runCatching {
            s.get("version")?.value?.let { platform.log("SHIORI version: $it") }
            s.notify("basewareversion", ShioriSession.SENDER, basewareVersion)
            s.notify(
                "OnNotifySelfInfo", ghost.name, ghost.sakuraName, ghost.keroName, shell.name, shell.dir.absolutePath + "/",
                balloon?.name.orEmpty(), balloon?.let { it.dir.absolutePath + "/" }.orEmpty(),
            )
            s.notify("ownerghostname", ghost.name)
            // SHIORI が持っていればそちらを使う（SSP と同じ）
            homeUrlOverride = s.get("homeurl")?.value?.trim()?.takeIf { it.isNotEmpty() }
            s.get("username")?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { userName = it }
            notifyEnvironment(s)
        }
        if (notified.isFailure) {
            session = null
            runCatching { s.unload() }
            platform.onError("${ghost.name}: SHIORI が応答しません")
            return false
        }
        return true
    }

    /** SSP が起動時に NOTIFY で伝える、シェル・バルーン・着せ替え・ユーザー・端末の情報 */
    private suspend fun notifyEnvironment(s: ShioriSession) {
        s.notify(
            "OnNotifyBalloonInfo", balloon?.name.orEmpty(), balloon?.let { it.dir.absolutePath + "/" }.orEmpty(),
            balloon?.let { b -> characterScopes.joinToString(" ") { scope -> "$scope:" + b.images(scope).joinToString(",") { it.index.toString() } } }.orEmpty(),
        )
        s.notify("OnNotifyShellInfo", shell.name, shell.dir.absolutePath + "/", shell.surfaceIds().joinToString(","))
        val dressup = dressupInfo()
        if (dressup.isNotEmpty()) s.notify("OnNotifyDressupInfo", *dressup.toTypedArray())
        s.notify("OnNotifyUserInfo", userName, "", "", "undef")
        platform.deviceInfo()?.let { d ->
            s.notify(
                "OnNotifyOSInfo",
                "${d.osName},${d.osVersion},${d.osName} ${d.osVersion}".trim(),
                "${d.arch},0,",
                "${d.memoryTotalMb * 1024},${d.memoryTotalMb * 1024}",
                (d.uptimeSeconds / 60).toString(),
            )
            s.notify("OnDisplayChange", "32", d.screenWidth.toString(), d.screenHeight.toString())
        }
        val tz = TimeZone.getDefault()
        val now = Date()
        val locale = Locale.getDefault()
        // SSP の約束では UTC より進んでいる地域が負（日本なら -540）
        s.notify(
            "OnNotifyInternationalInfo", (-(tz.getOffset(now.time) / 60_000)).toString(),
            if (tz.inDaylightTime(now)) "1" else "0", locale.country, locale.language,
        )
        platform.batteryInfo()?.let { b ->
            lastBattery = b
            s.notify("OnBatteryNotify", *batteryReferences(b).toTypedArray())
        }
    }

    /** OnNotifyDressupInfo の Reference: [キャラ ID, カテゴリ, パーツ, オプション, 有効 1/0, サムネイル] を 0x01 でつないだもの */
    private fun dressupInfo(): List<String> = characterScopes.flatMap { scope ->
        val binds = _state.value.characters[scope]?.binds.orEmpty()
        shell.bindGroups(scope).map { g ->
            listOf(scope.toString(), g.category, g.part, "", if (g.id in binds) "1" else "0", "").joinToString("\u0001")
        }
    }

    private fun batteryReferences(b: BatteryInfo): List<String> {
        val flags = buildList {
            if (b.percent >= 67) add("high")
            if (b.percent in 0..33) add("low")
            if (b.percent in 0..5) add("critical")
            if (b.charging) add("charging")
            if (b.percent < 0) add("no_battery")
        }
        return listOf(b.percent.toString(), "-1", if (b.plugged) "online" else "offline", flags.joinToString(","))
    }

    // ---- ネットワーク更新 ----

    private var updating = false

    /**
     * SSP と同じ流れでネットワーク更新する: OnUpdateBegin → OnUpdateReady → OnUpdateComplete / OnUpdateFailure。
     * 辞書が変わったら SHIORI を読み込み直してから OnUpdateComplete を送る。シェルの変更は次の起動から反映される。
     */
    fun networkUpdate() {
        if (updating || closing) return
        updating = true
        scope.launch {
            val refs = { r0: String, r1: String -> listOf(r0, r1, "", "ghost") }
            try {
                val homeUrl = homeUrlOverride ?: ghost.descript["homeurl"]
                if (homeUrl.isNullOrBlank()) {
                    raise("OnUpdateFailure", refs("none", ""))
                    return@launch
                }
                talkAndWait("OnUpdateBegin", listOf(ghost.name, ghost.dir.absolutePath + "/", "", "ghost"))
                val result = NetworkUpdater(ghost.dir, updateFetcher).update(homeUrl) { count ->
                    // Reference0 は「ファイル数 - 1」（SSP の仕様）
                    talkAndWait("OnUpdateReady", refs((count - 1).toString(), ""))
                }
                platform.log("network update: $result")
                when (result) {
                    UpdateResult.NoChange -> raise("OnUpdateComplete", refs("none", ""))
                    is UpdateResult.Changed -> {
                        reloadShiori()
                        raise("OnUpdateComplete", refs("changed", result.files.joinToString(",")))
                    }
                    is UpdateResult.Failure -> raise("OnUpdateFailure", refs(result.reason, ""))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 更新サーバーが返した変な一覧などで落ちないように。ゴーストには失敗として伝える
                platform.log("network update failed: $e")
                raise("OnUpdateFailure", refs(if (e is IOException) "fileio" else "unknown", ""))
            } finally {
                updating = false
            }
        }
    }

    private suspend fun reloadShiori() {
        requestLock.withLock {
            session?.unload()
            session = null
        }
        loadShiori()
    }

    /**
     * SSP の `\![execute,http-get,URL,--async=ID,--file=名前,...]`。
     * 取ってきた中身を ghost/master/var/名前 に保存し、OnExecuteHTTPComplete / OnExecuteHTTPFailure を送る
     * （Reference0 = "http-get"、Reference1 = --async の ID、Reference2 = URL、Reference3 = 保存先か失敗の理由）。
     * 結果のトークは、話している途中なら話し終わるのを待ってから出す。
     */
    private fun httpGet(url: String, options: List<String>) {
        val opts = options.mapNotNull { o ->
            o.removePrefix("--").split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].lowercase() to it[1] }
        }.toMap()
        val id = opts["async"].orEmpty()
        scope.launch {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                deliverLater("OnExecuteHTTPFailure", listOf("http-get", id, url, "invalid url"))
                return@launch
            }
            // 毎秒頼まれても際限なく通信しないよう、同時に走る数に上限を置く
            if (httpGetsInFlight >= MAX_HTTP_GETS) {
                deliverLater("OnExecuteHTTPFailure", listOf("http-get", id, url, "busy"))
                return@launch
            }
            httpGetsInFlight++
            val (ok, detail) = try {
                withContext(Dispatchers.IO) { download(url, opts["file"]?.let(NetworkUpdater::safeFileName)) }
            } finally {
                httpGetsInFlight--
            }
            deliverLater(if (ok) "OnExecuteHTTPComplete" else "OnExecuteHTTPFailure", listOf("http-get", id, url, detail))
        }
    }

    /**
     * [url] の中身を ghost/master/var/[name] に保存する（[name] が無ければ中身は捨てて、届いたかだけ見る）。
     * 大きな応答をメモリに溜めないよう、ファイルに流しながら受け取る。
     * @return 成功なら true と保存先（var/名前）、失敗なら false と理由
     */
    private fun download(url: String, name: String?): Pair<Boolean, String> {
        if (name == null) {
            return when (val r = updateFetcher.fetch(url, NullOutputStream, HTTP_GET_MAX_BYTES)) {
                FetchResult.Ok -> true to ""
                else -> false to NetworkUpdater.describe(r)
            }
        }
        val file = File(ghost.masterDir, "var/$name")
        val part = File(file.path + ".part")
        return try {
            file.parentFile?.mkdirs()
            val r = part.outputStream().use { updateFetcher.fetch(url, it, HTTP_GET_MAX_BYTES) }
            if (r != FetchResult.Ok) return false to NetworkUpdater.describe(r)
            if (!part.renameTo(file)) part.copyTo(file, overwrite = true)
            true to "var/$name"
        } catch (e: IOException) {
            false to "fileio"
        } finally {
            part.delete()
        }
    }

    /** 話し終わってから（割り込まずに）イベントを送る */
    private suspend fun deliverLater(event: String, references: List<String>) {
        _state.first { !it.talking && !it.waitingChoice }
        raise(event, references, userInitiated = false)
    }

    /** イベントを送って話させ、話し終わるまで待つ（バルーンが消えるのは待たない）。 */
    private suspend fun talkAndWait(event: String, references: List<String>) {
        val script = request(event, references) ?: return
        cancelScript()
        runScript(script)
        _state.first { !it.talking }
    }

    /** OnClose を送り、そのスクリプトが終わったら SHIORI を閉じる。 */
    fun close() {
        if (closing) return
        closing = true
        timerJob?.cancel()
        scope.launch {
            val script = request("OnClose", listOf("user"))
            if (script != null) {
                cancelScript()
                runScript(script).join()
            }
            shutdown()
        }
    }

    /** SHIORI を閉じて終わる。[switchTo] があれば、そのゴーストに切り替える */
    private suspend fun shutdown(switchTo: Pair<Ghost, PreviousGhost>? = null) {
        timerJob?.cancel()
        timers.values.forEach { it.cancel() }
        timers.clear()
        platform.stopSound()
        session?.unload()
        session = null
        if (switchTo != null) platform.switchGhost(switchTo.first.dir.name, switchTo.second) else platform.onQuit()
    }

    // ---- ゴーストの切り替え ----

    /**
     * 別のゴーストに切り替える（\![change,ghost,名前] とメニュー）。[name] はゴースト名・本体側の名前・ディレクトリ名、
     * random / sequential / lastinstalled。メニューから（[byUser]）か --option=raise-event なら先に OnGhostChanging
     * （答えが無ければ OnClose）で別れを話してもらう。スクリプトからなら、そのスクリプトが話し終わってから切り替える
     */
    fun changeGhost(name: String, byUser: Boolean = false): Boolean {
        if (closing) return false
        val ghosts = repository?.list() ?: return false
        val self = ghost.dir.canonicalFile
        val others = ghosts.filter { it.dir.canonicalFile != self }
        val target = when (name.lowercase()) {
            "random" -> others.randomOrNull()
            "sequential" -> ghosts.getOrNull((ghosts.indexOfFirst { it.dir.canonicalFile == self } + 1) % ghosts.size.coerceAtLeast(1))
                ?.takeIf { it.dir.canonicalFile != self }
            "lastinstalled" -> others.maxByOrNull { it.dir.lastModified() }
            else -> others.firstOrNull { it.name == name || it.sakuraName == name || it.dir.name == name }
        } ?: run {
            platform.log("切り替え先のゴーストがありません: $name")
            return false
        }
        closing = true
        timerJob?.cancel()
        scope.launch {
            val farewell = if (byUser) {
                val script = request("OnGhostChanging", listOf(target.sakuraName, "manual", target.name, target.dir.absolutePath + "/"))
                    ?: request("OnClose", listOf("user"))
                if (script != null) {
                    cancelScript()
                    runScript(script)
                }
                script.orEmpty()
            } else {
                lastScript
            }
            // 話し終わるのを待つ（スクリプトからの切り替えなら、そのスクリプトの残りを話し終えてから）
            withTimeoutOrNull(CHANGE_WAIT_MS) { _state.first { !it.talking } }
            shutdown(target to PreviousGhost(ghost.sakuraName, farewell, ghost.name, ghost.dir.absolutePath + "/"))
        }
        return true
    }

    // ---- キャラクター ----

    /**
     * このゴーストのキャラクター。\0 と \1 に加え、descript.txt に charN.name か charN.seriko.defaultsurface がある \p[N]
     * （Emily/Phase4.5 のエミリオなど）。スクリプトが \p[N] で話しかけた番号も、そのとき加わる
     */
    val characterScopes: List<Int>
        get() = listOf(0, 1) + (2..MAX_SCOPE).filter { n ->
            ghost.descript["char$n.name"] != null || defaultSurfaceDeclared(n)
        }

    private fun defaultSurfaceDeclared(scope: Int) =
        shell.defaultSurfaceOrNull(scope) != null || ghost.descript.int("${Shell.scopeName(scope)}.seriko.defaultsurface") != null

    /** 最初のサーフェス。シェルの descript.txt、なければゴーストの descript.txt（SSP と同じ）、どちらも無ければ既定の値 */
    fun defaultSurface(scope: Int): Int =
        shell.defaultSurfaceOrNull(scope) ?: ghost.descript.int("${Shell.scopeName(scope)}.seriko.defaultsurface") ?: shell.defaultSurface(scope)

    // ---- シェルとバルーンの切り替え ----

    /**
     * シェルを切り替える（\![change,shell,名前] とメニュー）。[name] はシェルの名前かディレクトリ名、random。
     * メニューからのとき（[byUser]）は SSP と同じく先に OnShellChanging で話してもらう。
     */
    fun changeShell(name: String, byUser: Boolean = false) {
        scope.launch {
            val dirs = ghost.shellDirs
            val dir = when (name) {
                "random" -> dirs.randomOrNull()
                else -> dirs.firstOrNull { it.name == name } ?: dirs.firstOrNull { runCatching { Shell(it).name }.getOrNull() == name }
            } ?: return@launch platform.log("シェルがありません: $name")
            val next = Shell(dir)
            if (byUser) talkAndWait("OnShellChanging", listOf(next.name, shell.name, dir.absolutePath + "/"))
            cancelScript()
            hideBalloons()
            ghost.selectShell(dir.name)
            shell = next
            resetSurfaces()
            platform.onShellChanged(next)
            notify("OnNotifyShellInfo", listOf(next.name, dir.absolutePath + "/", next.surfaceIds().joinToString(",")))
            dressupInfo().takeIf { it.isNotEmpty() }?.let { notify("OnNotifyDressupInfo", it) }
            raise("OnShellChanged", listOf(next.name, ghost.name, dir.absolutePath + "/"))
        }
    }

    /** バルーンを切り替える（\![change,balloon,名前] とメニュー）。[name] は名前かディレクトリ名、random、"/simple"（簡易バルーン） */
    fun changeBalloon(name: String) {
        val balloons = repository?.balloons ?: return
        val next: Balloon? = when (name) {
            SIMPLE_BALLOON -> null
            "random" -> balloons.list().randomOrNull()
            else -> balloons.findByName(name) ?: balloons.find(name) ?: return platform.log("バルーンがありません: $name")
        }
        ghost.selectBalloon(next?.dir?.name)
        balloon = next
        platform.onBalloonChanged(next)
        val path = next?.let { it.dir.absolutePath + "/" }.orEmpty()
        notify(
            "OnNotifyBalloonInfo", listOf(next?.name.orEmpty(), path,
                next?.let { b -> characterScopes.joinToString(" ") { s -> "$s:" + b.images(s).joinToString(",") { it.index.toString() } } }.orEmpty()),
        )
        raise("OnBalloonChange", listOf(next?.name.orEmpty(), path))
    }

    /** 着せ替えを付け外しする。[on] が null なら切り替え。[byUser] はメニューから */
    private fun applyBind(scopeId: Int, group: net.pono.baseware.shell.BindGroup, on: Boolean?, byUser: Boolean) {
        var enabled = false
        _state.update {
            it.update(scopeId) { c ->
                enabled = on ?: (group.id !in c.binds)
                // 同じカテゴリのパーツは1つだけ（服を着替えるように差し替える）
                val others = shell.bindGroups(scopeId).filter { g -> g.category == group.category }.map { g -> g.id }.toSet()
                c.copy(binds = if (enabled) c.binds - others + group.id else c.binds - group.id)
            }
        }
        raise("OnDressupChanged", listOf(scopeId.toString(), group.part, if (enabled) "1" else "0", group.category, if (byUser) "user" else "script"), userInitiated = false)
        dressupInfo().takeIf { it.isNotEmpty() }?.let { notify("OnNotifyDressupInfo", it) }
    }

    // ---- 音 ----

    /** ghost/master からの相対パス（か絶対パス）。ゴーストのディレクトリの外は鳴らさない */
    private fun soundFile(name: String): File? = ghostFile(name)

    /** ghost/master からの相対パス（か絶対パス）のファイル。ゴーストのディレクトリの外は使わない（画像・音） */
    private fun ghostFile(name: String): File? {
        val raw = name.trim().replace('\\', '/')
        val file = if (raw.startsWith("/")) File(raw) else File(ghost.masterDir, raw)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val root = ghost.dir.canonicalFile
        return canonical.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
    }

    // ---- ユーザー操作 ----

    private fun mouseReferences(scopeId: Int, x: Int, y: Int, collision: String?, button: Int) =
        listOf(x.toString(), y.toString(), "0", scopeId.toString(), collision.orEmpty(), button.toString(), "touch")

    /** 指が触れた。SSP の OnMouseDown（左ボタン） */
    fun onMouseDown(scopeId: Int, x: Int, y: Int, collision: String?) {
        lastUserActivity = System.currentTimeMillis()
        raise("OnMouseDown", mouseReferences(scopeId, x, y, collision, 0), userInitiated = false)
    }

    /**
     * 指が離れた（動かさずに）。SSP と同じく OnMouseUp を送り、ゴーストが何も返さなければ OnMouseClick を送る。
     * [count] は続けて叩いた回数で、2 なら OnMouseDoubleClick、3 以上なら OnMouseMultipleClick（Reference7 が回数）も送る。
     */
    fun onMouseUp(scopeId: Int, x: Int, y: Int, collision: String?, count: Int) {
        lastUserActivity = System.currentTimeMillis()
        scope.launch {
            val script = request("OnMouseUp", mouseReferences(scopeId, x, y, collision, 0))
            if (script != null) {
                if (!_state.value.talking && !_state.value.waitingChoice) {
                    cancelScript()
                    runScript(script)
                }
                if (count == 1) return@launch
            }
            onMouseClick(scopeId, x, y, collision, count)
        }
    }

    /**
     * 1回叩くごとに呼ぶ。[count] は続けて叩いた回数で、
     * 1 なら OnMouseClick、2 なら OnMouseDoubleClick、3 以上なら OnMouseMultipleClick（Reference7 が回数）を送る。
     */
    fun onMouseClick(scopeId: Int, x: Int, y: Int, collision: String?, count: Int) {
        lastUserActivity = System.currentTimeMillis()
        val refs = mouseReferences(scopeId, x, y, collision, 0)
        when (count) {
            1 -> raise("OnMouseClick", refs, userInitiated = false)
            2 -> raise("OnMouseDoubleClick", refs)
            else -> raise("OnMouseMultipleClick", refs + count.toString())
        }
    }

    /**
     * 長押し。スマートフォンには右クリックが無いので、その代わりにする。
     * SSP と同じく OnMouseClick（Reference5 = 1）を送り、ゴーストが何も返さなければ pono-droid のメニューを出す。
     */
    fun onMouseLongPress(scopeId: Int, x: Int, y: Int, collision: String?) {
        lastUserActivity = System.currentTimeMillis()
        scope.launch {
            val script = request("OnMouseClick", mouseReferences(scopeId, x, y, collision, 1))
            cancelScript()
            runScript(script ?: basewareMenu(scopeId))
        }
    }

    // ---- メニュー（SSP のオーナードローメニューの代わり） ----

    /** おすすめ・ポータルの1つ。SHIORI のリソースは 名前[1]URL[1]バナー[1]スクリプト を [2] でつないだもの */
    private data class Site(val name: String, val url: String, val banner: String, val script: String)

    private fun parseSites(value: String?): List<Site> = value.orEmpty().split('\u0002').mapNotNull { entry ->
        val f = entry.split('\u0001')
        val name = f.getOrNull(0)?.trim().orEmpty()
        if (name.isEmpty()) null else Site(name, f.getOrNull(1)?.trim().orEmpty(), f.getOrNull(2).orEmpty(), f.getOrNull(3).orEmpty())
    }

    /** SHIORI のリソース（おすすめサイトやボタンの名前）。無ければ null */
    private suspend fun resource(id: String): String? = request(id, emptyList(), translate = false)?.takeIf { it.isNotBlank() }

    /** \q の題名に使えるようにする（カンマや " を含むときは "..." で囲む） */
    private fun quote(s: String): String = if (s.any { it == ',' || it == '"' || it == ']' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /**
     * 長押しのメニュー。SSP のオーナードローメニューと同じく、おすすめ・ポータル・きせかえ・シェル・バルーン・Read Me・
     * ネットワーク更新・終了を出す。選択肢の ID は [selectChoice] が pono: で見分ける
     */
    private suspend fun basewareMenu(scopeId: Int): String {
        val scopeName = Shell.scopeName(scopeId)
        val sb = StringBuilder("\\p[$scopeId]\\_q【${ShioriSession.SENDER}】\\n")
        if (parseSites(resource("$scopeName.recommendsites")).isNotEmpty()) {
            sb.append("\\q[${quote(resource("$scopeName.recommendbuttoncaption") ?: "おすすめ")},pono:sites,recommend,$scopeId]\\n")
        }
        if (parseSites(resource("$scopeName.portalsites")).isNotEmpty()) {
            sb.append("\\q[${quote(resource("$scopeName.portalbuttoncaption") ?: "ポータル")},pono:sites,portal,$scopeId]\\n")
        }
        sb.append("\\q[ゴーストのメニュー（中クリック）,pono:middle,$scopeId]\\n")
        if (shell.bindGroups(scopeId).isNotEmpty()) sb.append("\\q[きせかえ,pono:dressup,$scopeId]\\n")
        if ((repository?.list()?.size ?: 0) > 1) sb.append("\\q[ゴースト切り替え,pono:ghosts,$scopeId]\\n")
        if (ghost.shellDirs.size > 1) sb.append("\\q[シェル,pono:shells,$scopeId]\\n")
        if (repository != null) sb.append("\\q[バルーン,pono:balloons,$scopeId]\\n")
        if (readmeFile() != null) sb.append("\\q[${quote(resource("readmebuttoncaption") ?: "Read Me")},pono:readme]\\n")
        sb.append("\\q[${quote(resource("updatebuttoncaption") ?: "ネットワーク更新")},pono:update]\\n")
        sb.append("\\q[ゴーストを終了,pono:close]\\n\\q[閉じる,pono:cancel]\\e")
        return sb.toString()
    }

    /** おすすめ（recommend）かポータル（portal）の一覧 */
    private suspend fun sitesMenu(kind: String, scopeId: Int): String {
        val sites = parseSites(resource("${Shell.scopeName(scopeId)}.${kind}sites"))
        val sb = StringBuilder("\\p[$scopeId]\\_q")
        sites.forEachIndexed { i, site ->
            // "-" は区切り線
            if (site.name == "-") sb.append("\\n") else sb.append("\\q[${quote(site.name)},pono:site,$kind,$scopeId,$i]\\n")
        }
        sb.append("\\q[戻る,pono:menu,$scopeId]\\e")
        return sb.toString()
    }

    private fun dressupMenu(scopeId: Int): String {
        val binds = _state.value.characters[scopeId]?.binds.orEmpty()
        val sb = StringBuilder("\\p[$scopeId]\\_q")
        for (g in shell.bindGroups(scopeId)) {
            sb.append("\\q[${quote((if (g.id in binds) "● " else "○ ") + g.category + ": " + g.part)},pono:bind,$scopeId,${g.id}]\\n")
        }
        sb.append("\\q[戻る,pono:menu,$scopeId]\\e")
        return sb.toString()
    }

    private fun shellsMenu(scopeId: Int): String {
        val sb = StringBuilder("\\p[$scopeId]\\_q")
        for (dir in ghost.shellDirs) {
            val name = runCatching { Shell(dir).name }.getOrDefault(dir.name)
            sb.append("\\q[${quote((if (dir == shell.dir) "● " else "○ ") + name)},pono:shell,${dir.name}]\\n")
        }
        sb.append("\\q[戻る,pono:menu,$scopeId]\\e")
        return sb.toString()
    }

    /** 入っているほかのゴースト。選ぶとそのゴーストに切り替える */
    private fun ghostsMenu(scopeId: Int): String {
        val self = ghost.dir.canonicalFile
        val sb = StringBuilder("\\p[$scopeId]\\_q")
        for (g in repository?.list().orEmpty()) {
            if (g.dir.canonicalFile == self) continue
            sb.append("\\q[${quote(g.name)},pono:ghost,${quote(g.dir.name)}]\\n")
        }
        sb.append("\\q[戻る,pono:menu,$scopeId]\\e")
        return sb.toString()
    }

    private fun balloonsMenu(scopeId: Int): String {
        val sb = StringBuilder("\\p[$scopeId]\\_q")
        sb.append("\\q[${if (balloon == null) "● " else "○ "}簡易バルーン,pono:balloon,$SIMPLE_BALLOON]\\n")
        for (b in repository?.balloons?.list().orEmpty()) {
            sb.append("\\q[${quote((if (b.dir == balloon?.dir) "● " else "○ ") + b.name)},pono:balloon,${b.dir.name}]\\n")
        }
        sb.append("\\q[戻る,pono:menu,$scopeId]\\e")
        return sb.toString()
    }

    /** メニューで選ばれたおすすめ・ポータル。SSP と同じく OnRecommendsiteChoice を送り、サイトを開く */
    private suspend fun openSite(kind: String, scopeId: Int, index: Int) {
        val site = parseSites(resource("${Shell.scopeName(scopeId)}.${kind}sites")).getOrNull(index) ?: return
        val reply = request("OnRecommendsiteChoice", listOf(site.name, site.url, site.banner, kind, scopeId.toString(), index.toString()))
        val script = reply ?: site.script.takeIf { it.isNotBlank() }
        if (script != null) {
            cancelScript()
            runScript(script)
            _state.first { !it.talking }
        }
        when {
            site.url.startsWith("script:") -> {
                cancelScript()
                runScript(site.url.removePrefix("script:"))
            }
            site.url.startsWith("http://") || site.url.startsWith("https://") -> platform.openUrl(site.url)
        }
    }

    /** descript.txt の readme（無ければ readme.txt） */
    private fun readmeFile(): File? {
        val name = ghost.descript["readme"]?.takeIf { it.isNotBlank() } ?: "readme.txt"
        return File(ghost.masterDir, name).takeIf { it.isFile } ?: File(ghost.dir, name).takeIf { it.isFile }
    }

    /**
     * バルーンをタップした。\x の待ちなら進め、話している途中なら止めて（OnBalloonBreak）、
     * 話し終わっていれば閉じる（OnBalloonClose）。
     */
    fun onBalloonClick(scopeId: Int) {
        lastUserActivity = System.currentTimeMillis()
        val waiter = clickWaiter
        if (waiter != null) {
            waiter.complete(Unit)
            return
        }
        val s = _state.value
        when {
            s.waitingChoice -> {}
            s.talking -> {
                val script = lastScript
                cancelScript()
                _state.update { it.copy(talking = false, waitingClick = false) }
                hideBalloons()
                raise("OnBalloonBreak", listOf(script, scopeId.toString(), ""), userInitiated = false)
            }
            else -> {
                hideBalloons()
                raise("OnBalloonClose", listOf(lastScript), userInitiated = false)
            }
        }
    }

    // ---- 端末の変化 ----

    /** 電池の状態が変わった。残量か充電の状態が変わったときだけ OnBatteryNotify を送る */
    fun onBatteryChanged(info: BatteryInfo) {
        val previous = lastBattery
        lastBattery = info
        if (previous == info) return
        if (previous != null && previous.charging != info.charging) {
            raise(if (info.charging) "OnBatteryChargingStart" else "OnBatteryChargingStop", batteryReferences(info), userInitiated = false)
        }
        if (previous != null && info.percent in 0..5 && previous.percent > 5) {
            raise("OnBatteryCritical", batteryReferences(info), userInitiated = false)
        } else if (previous != null && info.percent in 0..33 && previous.percent > 33) {
            raise("OnBatteryLow", batteryReferences(info), userInitiated = false)
        }
        if (previous == null || previous.percent != info.percent || previous.plugged != info.plugged) {
            raise("OnBatteryNotify", batteryReferences(info), userInitiated = false)
        }
    }

    /** 画面の向きや大きさが変わった */
    fun onDisplayChanged(width: Int, height: Int) {
        raise("OnDisplayChange", listOf("32", width.toString(), height.toString()), userInitiated = false)
    }

    // ---- 入力の箱 ----

    /** スクリプトから開く。前の箱が開いていれば差し替える（SSP と同じく1つだけ） */
    private fun openInput(request: InputRequest) {
        inputTimeout?.cancel()
        _state.update { it.copy(input = request) }
        if (request.timeoutMs > 0) {
            inputTimeout = scope.launch {
                delay(request.timeoutMs)
                if (_state.value.input?.serial == request.serial) onInputCancel(request.serial, timeout = true)
            }
        }
    }

    /** \![close,inputbox,ID]。ID が違えば閉じない（ID 無しなら閉じる） */
    private fun closeInput(id: String?) {
        val current = _state.value.input ?: return
        if (id.isNullOrEmpty() || current.id == id) {
            inputTimeout?.cancel()
            _state.update { it.copy(input = null) }
        }
    }

    /**
     * 入力が確定した。SSP と同じイベントを送る:
     * inputbox は ID が On で始まればそのイベント（Reference0 = 文字）、それ以外は OnUserInput（ID, 文字）。
     * communicatebox は OnCommunicate（"user", 文字）、teachbox は OnTeach（文字）。
     * [serial] が今開いている箱と違えば（もう閉じた箱への答えなら）何もしない。
     */
    fun onInputSubmit(serial: Int, text: String) {
        val request = _state.value.input?.takeIf { it.serial == serial } ?: return
        lastUserActivity = System.currentTimeMillis()
        inputTimeout?.cancel()
        _state.update { it.copy(input = null) }
        // ユーザーが打った文字（パスワードのこともある）はログに残さない
        // ID が On で始まればそのイベント（Reference0 = 答え）、そうでなければ OnUserInput（ID, 答え）
        fun answer(event: String, extra: List<String> = emptyList(), sensitive: Boolean = true) {
            if (request.id.startsWith("On")) {
                raise(request.id, listOf(text) + extra, sensitive = sensitive)
            } else {
                raise(event, listOf(request.id, text) + extra, sensitive = sensitive)
            }
        }
        when (request.kind) {
            InputRequest.Kind.INPUT, InputRequest.Kind.PASSWORD -> answer("OnUserInput")
            InputRequest.Kind.DATE, InputRequest.Kind.TIME -> answer("OnUserInput", sensitive = false)
            // スライダーは Reference2 に「最小,最大」
            InputRequest.Kind.SLIDER -> answer("OnUserInput", listOf("${request.values.getOrNull(1) ?: 0},${request.values.getOrNull(2) ?: 0}"), sensitive = false)
            InputRequest.Kind.TEXTAREA -> {
                val joined = text.replace("\r\n", "\n").replace('\n', '\u0001')
                if (request.id.startsWith("On")) raise(request.id, listOf(joined), sensitive = true) else raise("OnTextAreaInput", listOf(request.id, joined), sensitive = true)
            }
            InputRequest.Kind.COMMUNICATE -> raise("OnCommunicate", listOf("user", text), sensitive = true)
            InputRequest.Kind.TEACH -> raise("OnTeach", listOf(text), sensitive = true)
        }
    }

    /** 入力を取り消した（[timeout] なら時間切れ）。OnUserInputCancel などを送る */
    fun onInputCancel(serial: Int, timeout: Boolean = false) {
        val request = _state.value.input?.takeIf { it.serial == serial } ?: return
        inputTimeout?.cancel()
        _state.update { it.copy(input = null) }
        val reason = if (timeout) "timeout" else "cancel"
        when (request.kind) {
            InputRequest.Kind.INPUT, InputRequest.Kind.PASSWORD, InputRequest.Kind.DATE, InputRequest.Kind.TIME, InputRequest.Kind.SLIDER ->
                raise("OnUserInputCancel", listOf(request.id, reason))
            InputRequest.Kind.TEXTAREA -> raise("OnTextAreaInputCancel", listOf(request.id, reason))
            InputRequest.Kind.COMMUNICATE -> raise("OnCommunicateInputCancel", listOf("", reason))
            InputRequest.Kind.TEACH -> {}
        }
    }

    fun onChoice(choice: Choice) {
        lastUserActivity = System.currentTimeMillis()
        choiceWaiter?.complete(choice)
    }

    // ---- イベント ----

    /**
     * イベントを SHIORI に送り、返ってきたスクリプトを再生する。
     * [userInitiated] なら話している途中でも割り込む。そうでなければ話していないときだけ再生する。
     * [sensitive] ならユーザーの入力を含むので、ログに残さない。
     * [chain] は `\![raise]` で続けて起こされた回数（スクリプトが自分を起こし続けるのを止めるため）。
     */
    fun raise(event: String, references: List<String>, userInitiated: Boolean = true, sensitive: Boolean = false, chain: Int = 0) {
        scope.launch {
            val script = request(event, references, sensitive) ?: return@launch
            if (!userInitiated && (_state.value.talking || _state.value.waitingChoice)) return@launch
            cancelScript()
            runScript(script, chain)
        }
    }

    /** NOTIFY（応答を使わない） */
    private fun notify(event: String, references: List<String>) {
        scope.launch {
            val s = session ?: return@launch
            requestLock.withLock {
                runCatching { s.notify(event, *references.toTypedArray()) }
                    .onFailure { platform.log("$event (notify): ${it.message}") }
            }
        }
    }

    /**
     * GET。返ってきたスクリプトは、SSP と同じく OnTranslate に通してから使う
     * （[translate] が false なら通さない。OnTranslate 自身やリソースの問い合わせ）
     */
    private suspend fun request(event: String, references: List<String>, sensitive: Boolean = false, translate: Boolean = true): String? {
        val s = session ?: return null
        val value = requestLock.withLock {
            runCatching { s.get(event, *references.toTypedArray()) }
                .onFailure {
                    if (it is ShioriTimeoutException) platform.onError("${ghost.name}: SHIORI が応答しません") else platform.log("$event: ${it.message}")
                }
                .getOrNull()?.value
        }
        // 毎秒・毎分のイベントは多すぎるので、話すときだけ記録する
        if (value != null || event !in timerEvents) {
            if (sensitive) {
                platform.log("$event [入力] -> ${if (value != null) "(script)" else "(no content)"}")
            } else {
                platform.log("$event $references -> ${value?.take(120) ?: "(no content)"}")
            }
        }
        if (value == null || !translate) return value
        return translateScript(s, value, event, references)
    }

    /** OnTranslate: Reference0 = スクリプト、Reference2 = 元のイベント、Reference3 = その Reference（0x01 区切り） */
    private suspend fun translateScript(s: ShioriSession, script: String, event: String, references: List<String>): String {
        val translated = requestLock.withLock {
            runCatching {
                s.request(
                    ShioriRequest(
                        ShioriMethod.GET, "OnTranslate", listOf(script),
                        mapOf("Reference2" to event, "Reference3" to references.joinToString("\u0001")),
                    ),
                )
            }.onFailure { platform.log("OnTranslate: ${it.message}") }.getOrNull()?.value
        } ?: return script
        if (translated != script) platform.log("OnTranslate -> ${translated.take(120)}")
        return translated
    }

    /**
     * 画面が消えたら毎秒・毎分のイベントを止め、点いたら再開する（電池のため）。
     * 止めている間は SHIORI を起こさないので、ゴーストが勝手に話し出すこともない。
     */
    fun setScreenOn(on: Boolean) {
        if (_state.value.screenOn == on) return
        _state.update { it.copy(screenOn = on) }
        platform.log(if (on) "screen on: timer resumed" else "screen off: timer paused")
        if (!on) {
            timerJob?.cancel()
            timerJob = null
        } else if (session != null && !closing) {
            startTimer()
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        if (!_state.value.screenOn) return
        timerJob = scope.launch {
            var lastMinute = Calendar.getInstance().get(Calendar.MINUTE)
            var lastHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            while (isActive) {
                delay(1000)
                val s = _state.value
                val talking = s.talking || s.waitingChoice
                val uptimeHours = ((System.currentTimeMillis() - bootTime) / 3_600_000).toString()
                val idleSec = ((System.currentTimeMillis() - lastUserActivity) / 1000).toString()
                // Reference3 は「話せるか」。パッシブモードでは常に 0（SSP と同じ）
                val refs = listOf(uptimeHours, "0", "0", if (talking || s.passive) "0" else "1", idleSec)
                raise("OnSecondChange", refs, userInitiated = false)
                val now = Calendar.getInstance()
                val minute = now.get(Calendar.MINUTE)
                if (minute != lastMinute) {
                    lastMinute = minute
                    raise("OnMinuteChange", refs, userInitiated = false)
                }
                val hour = now.get(Calendar.HOUR_OF_DAY)
                if (hour != lastHour) {
                    lastHour = hour
                    // 時報。話している途中なら、話し終わってから
                    launch { deliverLater("OnHourTimeSignal", listOf(uptimeHours, "0", "0", "1", idleSec)) }
                }
            }
        }
    }

    // ---- スクリプト再生 ----

    private fun cancelScript() {
        scriptJob?.cancel()
        clickWaiter?.cancel()
        choiceWaiter?.cancel()
        clickWaiter = null
        choiceWaiter = null
    }

    private fun runScript(script: String, chain: Int = 0): Job {
        // talking は launch の前に立てる（呼び出し側が「話し終わるまで待つ」ときに取りこぼさないため）
        // 先頭が \C なら、今出ているバルーンを消さずに続ける
        if (!script.startsWith("\\C")) hideBalloons()
        _state.update { it.copy(talking = true) }
        lastScript = script
        val job = scope.launch {
            val raised = mutableListOf<Pair<String, List<String>>>()
            val host = Host(raised)
            val result = try {
                player.play(script, host)
            } finally {
                _state.update { it.copy(talking = false, waitingClick = false) }
            }

            // 終了中（OnClose のスクリプト）なら、話し終えた時点で close() に戻す
            if (closing) return@launch
            if (result.quit) {
                closing = true
                shutdown()
                return@launch
            }
            if (host.updateRequested) networkUpdate()
            if (raised.isNotEmpty()) {
                // \![raise] が \![raise] を呼び続けると SHIORI を叩き続けてしまうので、連鎖の長さに上限を置く
                if (chain >= MAX_RAISE_CHAIN) {
                    platform.log("\\![raise] の連鎖が $MAX_RAISE_CHAIN 回を超えたので止めました: ${raised.map { it.first }}")
                } else {
                    for ((event, refs) in raised) raise(event, refs, chain = chain + 1)
                }
                return@launch
            }

            // パッシブモードでは選択肢もバルーンも時間では消えない（SSP と同じ）
            val passive = _state.value.passive
            if (result.hasChoices) {
                val waiter = CompletableDeferred<Choice?>()
                choiceWaiter = waiter
                _state.update { it.copy(waitingChoice = true) }
                val choice = if (host.choiceTimeoutMs > 0 && !passive) {
                    withTimeoutOrNull(host.choiceTimeoutMs) { waiter.await() }
                } else {
                    waiter.await()
                }
                _state.update { it.copy(waitingChoice = false) }
                choiceWaiter = null
                if (choice == null) {
                    hideBalloons()
                    raise("OnChoiceTimeout", listOf(script))
                } else {
                    selectChoice(choice)
                }
            } else if (host.balloonTimeoutMs > 0 && !passive) {
                delay(host.balloonTimeoutMs)
                hideBalloons()
                raise("OnBalloonTimeout", listOf(script, "0"), userInitiated = false)
            }
        }
        scriptJob = job
        return job
    }

    private fun selectChoice(choice: Choice) {
        val id = choice.id
        when {
            // pono-droid 自身のメニュー（長押し）
            id.startsWith("pono:") -> {
                hideBalloons()
                val refs = choice.references
                val scopeId = refs.firstOrNull()?.toIntOrNull() ?: 0
                when (id) {
                    "pono:middle" -> {
                        // 中クリック（Reference5 = 2）。ゴーストによってはこれでメニューを開く
                        raise("OnMouseClick", listOf("0", "0", "0", scopeId.toString(), "", "2", "touch"))
                    }
                    "pono:update" -> networkUpdate()
                    "pono:close" -> close()
                    "pono:menu" -> scope.launch { runScript(basewareMenu(scopeId)) }
                    "pono:sites" -> scope.launch { runScript(sitesMenu(refs.getOrNull(0) ?: "recommend", refs.getOrNull(1)?.toIntOrNull() ?: 0)) }
                    "pono:site" -> scope.launch { openSite(refs.getOrNull(0) ?: "recommend", refs.getOrNull(1)?.toIntOrNull() ?: 0, refs.getOrNull(2)?.toIntOrNull() ?: 0) }
                    "pono:dressup" -> runScript(dressupMenu(scopeId))
                    "pono:bind" -> {
                        val group = shell.bindGroups(scopeId).firstOrNull { it.id == refs.getOrNull(1)?.toIntOrNull() }
                        if (group != null) applyBind(scopeId, group, null, byUser = true)
                        runScript(dressupMenu(scopeId))
                    }
                    "pono:shells" -> runScript(shellsMenu(scopeId))
                    "pono:shell" -> refs.firstOrNull()?.let { changeShell(it, byUser = true) }
                    "pono:balloons" -> runScript(balloonsMenu(scopeId))
                    "pono:ghosts" -> runScript(ghostsMenu(scopeId))
                    "pono:ghost" -> refs.firstOrNull()?.let { changeGhost(it, byUser = true) }
                    "pono:balloon" -> refs.firstOrNull()?.let { changeBalloon(it) }
                    "pono:readme" -> readmeFile()?.let { platform.showText(ghost.name, readmeText()) }
                }
            }
            id.startsWith("http://") || id.startsWith("https://") -> {
                hideBalloons()
                platform.openUrl(id)
            }
            // script: で始まる ID は、その続きをスクリプトとして再生する（SSP と同じ）
            id.startsWith("script:") -> {
                cancelScript()
                runScript(id.removePrefix("script:"))
            }
            // \_a のジャンパは OnAnchorSelectEx → OnAnchorSelect
            choice.anchor -> scope.launch {
                val script = request("OnAnchorSelectEx", listOf(choice.title, id) + choice.references)
                    ?: request("OnAnchorSelect", listOf(id) + choice.references)
                cancelScript()
                if (script != null) runScript(script) else hideBalloons()
            }
            // "On" で始まる ID はそのままイベントとして送る（SSP と同じ）
            id.startsWith("On") -> raise(id, choice.references)
            else -> scope.launch {
                val script = request("OnChoiceSelectEx", listOf(choice.title, id) + choice.references)
                    ?: request("OnChoiceSelect", listOf(id) + choice.references)
                cancelScript()
                if (script != null) runScript(script) else hideBalloons()
            }
        }
    }

    /** バルーンを閉じる。\b[n] で選んだ番号もここで戻す（次のスクリプトはまた文字の量で選ぶ） */
    private fun hideBalloons() {
        _state.update { s -> s.copy(characters = s.characters.mapValues { it.value.copy(balloon = null, balloonId = null) }) }
    }

    private fun resetSurfaces() {
        _state.update {
            GhostState(
                characters = characterScopes.associateWith { scope ->
                    CharacterState(scope, defaultSurface(scope), binds = shell.bindGroups(scope).filter { g -> g.default }.map { g -> g.id }.toSet())
                },
                screenOn = it.screenOn,
                passive = it.passive,
            )
        }
    }

    // ---- プロパティシステム ----

    /**
     * SSP のプロパティシステム（%property[...] と \![get,property,...]）のうち、pono-droid で意味のあるもの。
     * 知らない名前は null（空として扱う）
     */
    fun property(name: String): String? {
        val n = name.trim()
        val now = Calendar.getInstance()
        return when {
            n == "system.year" -> now.get(Calendar.YEAR).toString()
            n == "system.month" -> (now.get(Calendar.MONTH) + 1).toString()
            n == "system.day" -> now.get(Calendar.DAY_OF_MONTH).toString()
            n == "system.hour" -> now.get(Calendar.HOUR_OF_DAY).toString()
            n == "system.minute" -> now.get(Calendar.MINUTE).toString()
            n == "system.second" -> now.get(Calendar.SECOND).toString()
            n == "system.millisecond" -> now.get(Calendar.MILLISECOND).toString()
            n == "system.dayofweek" -> (now.get(Calendar.DAY_OF_WEEK) - 1).toString()
            n == "system.cursor.pos" -> "0,0"
            n.startsWith("system.os.") -> osProperty(n.removePrefix("system.os."))
            n.startsWith("system.cpu.") -> when (n.removePrefix("system.cpu.")) {
                "num" -> (platform.deviceInfo()?.cpuCount ?: 0).toString()
                "name" -> platform.deviceInfo()?.arch.orEmpty()
                "load", "clock" -> "0"
                else -> ""
            }
            n.startsWith("system.memory.") -> platform.deviceInfo()?.let { d ->
                when (n.removePrefix("system.memory.")) {
                    "phyt" -> d.memoryTotalMb.toString()
                    "phya" -> d.memoryFreeMb.toString()
                    "load" -> if (d.memoryTotalMb > 0) ((d.memoryTotalMb - d.memoryFreeMb) * 100 / d.memoryTotalMb).toString() else "0"
                    else -> null
                }
            }
            n == "system.monitor.count" -> "1"
            n.startsWith("system.monitor.index(") -> platform.deviceInfo()?.let { d ->
                when (n.substringAfter(").", "")) {
                    "rect", "work" -> "0,0,${d.screenWidth},${d.screenHeight}"
                    "bpp" -> "32"
                    "dpi" -> d.dpi.toString()
                    "primary" -> "1"
                    else -> null
                }
            }
            n.startsWith("system.power.") -> {
                val b = platform.batteryInfo()
                when (n.removePrefix("system.power.")) {
                    "source" -> if (b == null) "unknown" else if (b.plugged) "ac" else "battery"
                    "battery.percent" -> (b?.percent ?: -1).toString()
                    "battery.lifetime" -> "-1"
                    "battery.flag" -> b?.let { batteryReferences(it)[3] }.orEmpty()
                    else -> null
                }
            }
            n == "baseware.version" -> basewareVersion
            n == "baseware.name" -> ShioriSession.SENDER
            n == "baseware.windowmode" -> "normal"
            n == "ghostlist.count" -> (repository?.list()?.size ?: 1).toString()
            n.startsWith("ghostlist") -> listProperty(n.removePrefix("ghostlist"), repository?.list() ?: listOf(ghost), ghost) { g, key, index -> ghostProperty(g, key, index) }
            n == "balloonlist.count" -> (repository?.balloons?.list()?.size ?: 0).toString()
            n.startsWith("balloonlist") -> listProperty(n.removePrefix("balloonlist"), repository?.balloons?.list().orEmpty(), balloon) { b, key, index -> balloonProperty(b, key, index) }
            n.startsWith("currentghost.") -> currentGhostProperty(n.removePrefix("currentghost."))
            else -> null
        }
    }

    private fun osProperty(key: String): String? {
        val d = platform.deviceInfo()
        val locale = Locale.getDefault()
        val tz = TimeZone.getDefault()
        return when (key) {
            "type" -> d?.osName ?: "Android"
            "arch" -> d?.arch.orEmpty()
            "name" -> d?.let { "${it.osName} ${it.osVersion}".trim() } ?: "Android"
            "version" -> d?.osVersion.orEmpty()
            "build" -> d?.osBuild.orEmpty()
            "parenttype", "parentname" -> ""
            "locale" -> "${locale.language}-${locale.country}"
            "locale.language" -> locale.language
            "locale.country" -> locale.country
            "timezone.offset" -> (-(tz.getOffset(System.currentTimeMillis()) / 60_000)).toString()
            "dst" -> if (tz.inDaylightTime(Date())) "1" else "0"
            "uptime" -> (d?.uptimeSeconds ?: 0).toString()
            "unixtime" -> (System.currentTimeMillis() / 1000).toString()
            "idletime" -> ((System.currentTimeMillis() - lastUserActivity) / 1000).toString()
            else -> null
        }
    }

    /**
     * "(名前).キー" / ".index(N).キー" / ".current.キー" の形で一覧の1つを選ぶ。
     * [current] は今使っているもの（無ければ null）
     */
    private fun <T> listProperty(rest: String, items: List<T>, current: T?, resolve: (T, String, Int) -> String?): String? {
        val key = rest.substringAfter(").", rest.substringAfter("current.", ""))
        val item = when {
            rest.startsWith("(") -> {
                val selector = rest.substring(1, rest.indexOf(')').takeIf { it > 0 } ?: return null)
                items.firstOrNull { resolve(it, "name", 0) == selector || resolve(it, "sakuraname", 0) == selector || resolve(it, "path", 0)?.trimEnd('/') == selector.trimEnd('/') }
            }
            rest.startsWith(".index(") -> rest.substring(7, rest.indexOf(')').takeIf { it > 7 } ?: return null).toIntOrNull()?.let(items::getOrNull)
            rest.startsWith(".current.") -> current
            else -> null
        } ?: return null
        return resolve(item, key, items.indexOf(item))
    }

    private fun ghostProperty(g: Ghost, key: String, index: Int): String? = when (key) {
        "name" -> g.name
        "sakuraname" -> g.sakuraName
        "keroname" -> g.keroName
        "craftmanw" -> g.descript["craftmanw"] ?: g.descript["craftman"].orEmpty()
        "craftmanurl" -> g.descript["craftmanurl"].orEmpty()
        "path" -> g.dir.absolutePath + "/"
        "homeurl" -> g.descript["homeurl"].orEmpty()
        "index" -> index.toString()
        else -> null
    }

    private fun balloonProperty(b: Balloon, key: String, index: Int): String? = when (key) {
        "name" -> b.name
        "craftmanw" -> b.descript["craftmanw"] ?: b.descript["craftman"].orEmpty()
        "path" -> b.dir.absolutePath + "/"
        "index" -> index.toString()
        else -> null
    }

    private fun currentGhostProperty(key: String): String? {
        val s = _state.value
        val scopeNumber = Regex("""^scope\((\d+)\)\.(.*)$""")
        return when {
            key == "status" -> buildList {
                if (s.talking) add("talking")
                if (s.passive) add("passive")
                if (s.waitingChoice) add("choosing")
                s.input?.let { add("opening(" + (if (it.kind == InputRequest.Kind.COMMUNICATE) "communicate" else "input") + ")") }
            }.joinToString(",")
            key == "shelllist.count" -> ghost.shellDirs.size.toString()
            key.startsWith("shelllist") -> listProperty(key.removePrefix("shelllist"), ghost.shellDirs, shell.dir) { dir, k, index ->
                when (k) {
                    "name" -> runCatching { Shell(dir).name }.getOrDefault(dir.name)
                    "path" -> dir.absolutePath + "/"
                    "index" -> index.toString()
                    else -> null
                }
            }
            key == "scope.count" -> characterScopes.size.toString()
            key.startsWith("balloon.scope(") -> {
                val m = Regex("""^balloon\.scope\((\d+)\)\.(.*)$""").find(key) ?: return null
                val scope = m.groupValues[1].toIntOrNull() ?: return null
                when (m.groupValues[2]) {
                    "num" -> (s.characters[scope]?.balloonId ?: 0).toString()
                    "count" -> (balloon?.images(scope)?.size ?: 1).toString()
                    else -> null
                }
            }
            key == "balloon.count" -> (repository?.balloons?.list()?.size ?: 0).toString()
            key.startsWith("balloon.") -> balloon?.let { balloonProperty(it, key.removePrefix("balloon."), 0) }
            key.startsWith("scope(") -> {
                val m = scopeNumber.find(key) ?: return null
                val scope = m.groupValues[1].toIntOrNull() ?: return null
                when (m.groupValues[2]) {
                    "surface.num" -> (s.characters[scope]?.surface ?: -1).toString()
                    "seriko.defaultsurface" -> defaultSurface(scope).toString()
                    "name" -> if (scope == 0) ghost.sakuraName else if (scope == 1) ghost.keroName else ghost.descript["char$scope.name"].orEmpty()
                    "scaling" -> "100"
                    "x", "y" -> "0"
                    "rect" -> "0,0,0,0"
                    "animation.num" -> (shell.surfaces[s.characters[scope]?.surface ?: -1]?.animations?.size ?: 0).toString()
                    else -> null
                }
            }
            key == "seriko.surfacelist.all" || key == "seriko.surfacelist.defined" -> shell.surfaceIds().joinToString(",")
            else -> ghostProperty(ghost, key, repository?.list()?.indexOfFirst { it.dir == ghost.dir } ?: 0)
        }
    }

    /** descript.txt の readme（無ければ readme.txt）の中身 */
    private fun readmeText(): String {
        val name = ghost.descript["readme"]?.takeIf { it.isNotBlank() } ?: "readme.txt"
        val file = File(ghost.masterDir, name).takeIf { it.isFile } ?: File(ghost.dir, name).takeIf { it.isFile }
            ?: return "$name がありません"
        return runCatching { TextCharsets.decode(file.readBytes()) }.getOrDefault("$name を読めませんでした")
    }

    private inner class Host(private val raised: MutableList<Pair<String, List<String>>>) : ScriptHost {
        /** このスクリプトのあいだだけ有効。0 以下なら消さない／待ち続ける */
        var balloonTimeoutMs = this@GhostController.balloonTimeoutMs
        var choiceTimeoutMs = this@GhostController.choiceTimeoutMs

        /** `\![updatebymyself]`。スクリプトが終わってから始める */
        var updateRequested = false

        /** \f の飾り（キャラクターごと） */
        private val styles = HashMap<Int, TextStyle>()

        override fun requestUpdate() {
            updateRequested = true
        }

        override fun execute(name: String, args: List<String>): Boolean {
            if (name != "http-get" || args.isEmpty()) return false
            httpGet(args[0], args.drop(1))
            return true
        }

        override fun bind(scope: Int, category: String, part: String, on: Boolean?) {
            val group = shell.bindGroups(scope).firstOrNull { it.category == category && it.part == part } ?: return
            applyBind(scope, group, on, byUser = false)
        }

        override fun playSound(file: String, loop: Boolean, volume: Int?) {
            val f = soundFile(file) ?: return platform.log("音が見つかりません: $file")
            platform.playSound(f, loop, volume)
        }

        override fun stopSound(file: String?) = platform.stopSound()

        override suspend fun waitSound() = platform.waitSound()

        override fun changeGhost(name: String, raiseEvent: Boolean): Boolean = this@GhostController.changeGhost(name, raiseEvent)

        override fun change(kind: String, name: String): Boolean {
            when (kind) {
                "shell" -> changeShell(name)
                "balloon" -> changeBalloon(name)
                else -> return false
            }
            return true
        }

        override fun setOption(scope: Int, name: String, args: List<String>): Boolean {
            // --time などのオプションは読み飛ばす（すぐに変える）
            val numbers = args.map { it.trim() }.filterNot { it.startsWith("--") }.mapNotNull { it.toFloatOrNull() }
            when (name) {
                // \![set,scaling,倍率] / \![set,scaling,横倍率,縦倍率]（%）。負なら反転。大きすぎる値は抑える
                "scaling" -> {
                    val x = numbers.getOrNull(0) ?: return false
                    val y = numbers.getOrNull(1) ?: x
                    fun clamp(v: Float) = (v / 100f).coerceIn(-MAX_SCALING, MAX_SCALING)
                    _state.update { it.update(scope) { c -> c.copy(scaleX = clamp(x), scaleY = clamp(y)) } }
                }
                // \![set,alpha,0〜100]。負の値は「そのまま」
                "alpha" -> {
                    val a = numbers.getOrNull(0) ?: return false
                    if (a >= 0) _state.update { it.update(scope) { c -> c.copy(alpha = (a / 100f).coerceIn(0f, 1f)) } }
                }
                else -> return setOption(name, args)
            }
            return true
        }

        override fun move(scope: Int, command: MoveCommand) {
            _state.update { it.update(scope) { c -> c.copy(move = ++animationCounter to command) } }
        }

        override fun setPosition(scope: Int, x: Int?, y: Int?) {
            // 固定していない（出ていない）キャラクターを放すときに、わざわざ作らない
            if (x == null || y == null) {
                if (_state.value.characters[scope]?.pinned == null) return
                _state.update { it.update(scope) { c -> c.copy(pinned = null) } }
            } else {
                _state.update { it.update(scope) { c -> c.copy(pinned = x to y) } }
            }
        }

        override fun addImage(scope: Int, file: String, x: Int?, y: Int?, opaque: Boolean) {
            val f = ghostFile(file) ?: return platform.log("画像が見つかりません: $file")
            _state.update { it.update(scope) { c -> c.append(BalloonItem.Image(f.path, x, y, opaque)) } }
        }

        override fun setOption(name: String, args: List<String>): Boolean {
            val ms = args.firstOrNull()?.trim()?.toLongOrNull() ?: return false
            when (name) {
                "balloontimeout" -> balloonTimeoutMs = ms
                "choicetimeout" -> choiceTimeoutMs = ms
                else -> return false
            }
            return true
        }

        override fun setSurface(scope: Int, surface: Int) {
            val changed = _state.value.characters[scope]?.surface != surface
            _state.update { it.update(scope) { c -> c.copy(surface = surface) } }
            if (changed) {
                val chars = _state.value.characters
                notify(
                    "OnSurfaceChange",
                    listOf((chars[0]?.surface ?: -1).toString(), (chars[1]?.surface ?: -1).toString(), "$scope,$surface,0,0"),
                )
            }
        }

        override fun appendText(scope: Int, text: String) =
            _state.update { it.update(scope) { c -> c.appendText(text, styles[scope] ?: TextStyle.Default) } }

        override fun newLine(scope: Int) = _state.update { it.update(scope) { c -> c.append(BalloonItem.NewLine) } }

        override fun clearBalloon(scope: Int) = _state.update { it.update(scope) { c -> c.copy(balloon = emptyList()) } }

        override fun addChoice(scope: Int, choice: Choice) =
            _state.update { it.update(scope) { c -> c.append(BalloonItem.ChoiceItem(choice)) } }

        override fun startAnimation(scope: Int, id: Int) = command(scope, AnimationCommand.Start(id))

        override fun animation(scope: Int, args: List<String>): Boolean {
            fun int(i: Int) = args.getOrNull(i)?.trim()?.toIntOrNull()
            when (args.firstOrNull()?.trim()?.lowercase()) {
                "offset" -> command(scope, AnimationCommand.Offset(int(1) ?: return false, int(2) ?: 0, int(3) ?: 0))
                "clear" -> command(scope, AnimationCommand.Clear(int(1) ?: return false))
                "stop" -> command(scope, AnimationCommand.StopAll)
                else -> {}
            }
            return true
        }

        private fun command(scope: Int, command: AnimationCommand) =
            _state.update { it.update(scope) { c -> c.command(++animationCounter, command) } }

        override fun addMarker(scope: Int) = _state.update { it.update(scope) { c -> c.append(BalloonItem.Marker) } }

        override fun moveCursor(scope: Int, x: Int) = _state.update { it.update(scope) { c -> c.append(BalloonItem.Column) } }

        override fun setBalloonId(scope: Int, id: Int) = _state.update { it.update(scope) { c -> c.copy(balloonId = id) } }

        override fun setFont(scope: Int, style: TextStyle) {
            styles[scope] = style
        }

        override fun setAlign(scope: Int, align: TextAlign) = _state.update { it.update(scope) { c -> c.append(BalloonItem.Align(align)) } }

        override fun open(kind: String, args: List<String>): Boolean {
            InputRequest.fromOpenTag(kind, args, inputSerial + 1)?.let {
                inputSerial++
                openInput(it)
                return true
            }
            when (kind) {
                "readme" -> platform.showText(ghost.name, readmeText())
                "configurationdialog", "ghostexplorer", "shellexplorer", "balloonexplorer", "dressupexplorer" -> platform.openSettings()
                else -> return false
            }
            return true
        }

        override fun close(kind: String, args: List<String>): Boolean {
            if (kind !in setOf("inputbox", "passwordinput", "communicatebox", "teachbox", "dateinput", "timeinput", "sliderinput", "textareabox")) return false
            closeInput(args.firstOrNull())
            return true
        }

        override suspend fun waitClick() {
            val waiter = CompletableDeferred<Unit>()
            clickWaiter = waiter
            _state.update { it.copy(waitingClick = true) }
            try {
                waiter.await()
            } finally {
                clickWaiter = null
                _state.update { it.copy(waitingClick = false) }
            }
        }

        override fun raise(event: String, references: List<String>) {
            raised += event to references
        }

        override fun notify(event: String, references: List<String>) = this@GhostController.notify(event, references)

        override suspend fun embed(event: String, references: List<String>): String? = request(event, references, translate = false)

        override fun timerRaise(delayMs: Long, once: Boolean, event: String, references: List<String>) {
            timers.remove(event)?.cancel()
            if (delayMs <= 0 || closing) return
            if (timers.size >= MAX_TIMERS) {
                platform.log("\\![timerraise] が多すぎます: $event")
                return
            }
            // 繰り返しは1秒より短くしない（SHIORI を叩き続けないように）
            val interval = if (once) delayMs else delayMs.coerceAtLeast(1000)
            timers[event] = scope.launch {
                do {
                    delay(interval)
                    this@GhostController.raise(event, references)
                } while (!once && isActive)
                timers.remove(event)
            }
        }

        override fun getProperty(event: String, names: List<String>) {
            raised += event to names.map { property(it).orEmpty() }
        }

        override fun property(name: String): String? = this@GhostController.property(name)

        override fun reload(what: String) {
            when (what) {
                "shiori", "ghost" -> scope.launch { reloadShiori() }
                else -> platform.log("未対応の reload: $what")
            }
        }

        override fun setPassive(on: Boolean) = _state.update { it.copy(passive = on) }

        /** 開くのはウェブのページだけ。tel: や他のアプリのスキームをゴーストに開かせない */
        override fun openUrl(url: String) {
            val lower = url.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) platform.openUrl(url) else platform.log("開けない URL: $url")
        }

        override fun envVariable(name: String): String? {
            val now = Calendar.getInstance()
            return when (name) {
                "username" -> userName
                "selfname" -> ghost.sakuraName
                "selfname2" -> ghost.descript["sakura.name2"] ?: ghost.sakuraName
                "keroname" -> ghost.keroName
                "year" -> now.get(Calendar.YEAR).toString()
                "month" -> (now.get(Calendar.MONTH) + 1).toString()
                "day" -> now.get(Calendar.DAY_OF_MONTH).toString()
                "hour" -> now.get(Calendar.HOUR_OF_DAY).toString()
                "minute" -> now.get(Calendar.MINUTE).toString()
                "second" -> now.get(Calendar.SECOND).toString()
                "screenwidth" -> platform.deviceInfo()?.screenWidth?.toString()
                "screenheight" -> platform.deviceInfo()?.screenHeight?.toString()
                else -> null
            }
        }

        override fun unknownTag(tag: Token.Tag) = platform.log("未対応のタグ: \\${tag.name}${tag.args}")
    }

    companion object {
        /** アプリから渡されないとき（テストなど）のバージョン */
        const val BASEWARE_VERSION = "0.1.0"
        private val timerEvents = setOf("OnSecondChange", "OnMinuteChange")

        /** `\![raise]` から起きたスクリプトがさらに `\![raise]` できる回数 */
        const val MAX_RAISE_CHAIN = 16

        /** 同時に走らせる http-get の数 */
        const val MAX_HTTP_GETS = 4

        /** http-get で受け取る大きさの上限 */
        const val HTTP_GET_MAX_BYTES = 64L * 1024 * 1024

        /** `\![timerraise]` で同時に予約できる数 */
        const val MAX_TIMERS = 32

        /** \p[N] で使えるキャラクターの番号の上限（ウィンドウをいくつも作らせないため） */
        const val MAX_SCOPE = ScriptPlayer.MAX_SCOPE

        /** \![set,scaling] の倍率の上限（4 = 400%）。画面より大きなウィンドウを作らせない */
        const val MAX_SCALING = 4f

        /** ゴーストを切り替えるとき、前のゴーストが話し終わるのを待つ長さ */
        const val CHANGE_WAIT_MS = 30_000L

        /** メニューと [changeBalloon] で簡易バルーンを表す名前（ディレクトリ名と重ならないよう / を含める） */
        const val SIMPLE_BALLOON = "/simple"
    }
}
