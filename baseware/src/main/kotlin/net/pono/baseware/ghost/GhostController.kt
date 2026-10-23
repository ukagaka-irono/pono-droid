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
}

/**
 * ゴースト1体を動かす。SHIORI にイベントを送り、返ってきたさくらスクリプトを再生して [state] に反映する。
 * イベントの送信とスクリプトの再生はすべて [scope] の中で行う。
 */
class GhostController(
    val ghost: Ghost,
    val shell: Shell,
    private val shioriFactory: ShioriFactory,
    private val scope: CoroutineScope,
    private val platform: GhostPlatform,
    /** 使っているバルーン（null なら pono-droid の簡易バルーン） */
    private val balloon: Balloon? = null,
    /** 入っているゴーストとバルーンの一覧（プロパティシステムの ghostlist などに使う） */
    private val repository: GhostRepository? = null,
    userName: String = "ユーザ",
    private val player: ScriptPlayer = ScriptPlayer(),
    private val balloonTimeoutMs: Long = 15_000,
    private val choiceTimeoutMs: Long = 30_000,
    private val updateFetcher: HttpFetcher = UrlConnectionFetcher("${ShioriSession.SENDER}/$BASEWARE_VERSION"),
) {
    private val _state = MutableStateFlow(GhostState())
    val state: StateFlow<GhostState> = _state.asStateFlow()

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

            if (firstBoot) {
                raise("OnFirstBoot", listOf("0"))
            } else {
                raise("OnBoot", listOf(shell.name, "", "", "", "", "", "", ""))
            }
            startTimer()
        }
    }

    /** SHIORI を読み込み、起動時の NOTIFY を送る。 */
    private suspend fun loadShiori(): Boolean {
        val shiori = shioriFactory.create(ghost.shioriName, ghost.masterDir)
        if (shiori == null) {
            platform.onError("${ghost.name}: ${ghost.shioriName} は未対応です（YAYA のみ対応）")
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
            s.notify("basewareversion", ShioriSession.SENDER, BASEWARE_VERSION)
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
            balloon?.let { b -> (0..1).joinToString(" ") { scope -> "$scope:" + b.images(scope).joinToString(",") { it.index.toString() } } }.orEmpty(),
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
    private fun dressupInfo(): List<String> = (0..1).flatMap { scope ->
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

    private suspend fun shutdown() {
        timerJob?.cancel()
        timers.values.forEach { it.cancel() }
        timers.clear()
        session?.unload()
        session = null
        platform.onQuit()
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

    /** 右クリックのメニューの代わり。選択肢の ID は [selectChoice] が pono: で見分ける */
    private fun basewareMenu(scopeId: Int): String =
        "\\p[$scopeId]\\_q【${ShioriSession.SENDER}】\\n" +
            "\\q[ゴーストのメニュー（中クリック）,pono:middle,$scopeId]\\n" +
            "\\q[ネットワーク更新,pono:update]\\n" +
            "\\q[ゴーストを終了,pono:close]\\n" +
            "\\q[閉じる,pono:cancel]\\e"

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
        when (request.kind) {
            InputRequest.Kind.INPUT, InputRequest.Kind.PASSWORD ->
                if (request.id.startsWith("On")) {
                    raise(request.id, listOf(text), sensitive = true)
                } else {
                    raise("OnUserInput", listOf(request.id, text), sensitive = true)
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
            InputRequest.Kind.INPUT, InputRequest.Kind.PASSWORD -> raise("OnUserInputCancel", listOf(request.id, reason))
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
                when (id) {
                    "pono:middle" -> {
                        // 中クリック（Reference5 = 2）。ゴーストによってはこれでメニューを開く
                        val target = choice.references.firstOrNull() ?: "0"
                        raise("OnMouseClick", listOf("0", "0", "0", target, "", "2", "touch"))
                    }
                    "pono:update" -> networkUpdate()
                    "pono:close" -> close()
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
                characters = (0..1).associateWith { scope ->
                    CharacterState(scope, shell.defaultSurface(scope), binds = shell.bindGroups(scope).filter { g -> g.default }.map { g -> g.id }.toSet())
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
            n == "baseware.version" -> BASEWARE_VERSION
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
            key == "scope.count" -> "2"
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
                    "seriko.defaultsurface" -> shell.defaultSurface(scope).toString()
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
            _state.update {
                it.update(scope) { c ->
                    val enable = on ?: (group.id !in c.binds)
                    // 同じカテゴリのパーツは1つだけ（服を着替えるように差し替える）
                    val others = shell.bindGroups(scope).filter { g -> g.category == category }.map { g -> g.id }.toSet()
                    c.copy(binds = if (enable) c.binds - others + group.id else c.binds - group.id)
                }
            }
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

        override fun startAnimation(scope: Int, id: Int) =
            _state.update { it.update(scope) { c -> c.copy(animationRequest = id to ++animationCounter) } }

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
            if (kind !in setOf("inputbox", "passwordinput", "communicatebox", "teachbox")) return false
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
    }
}
