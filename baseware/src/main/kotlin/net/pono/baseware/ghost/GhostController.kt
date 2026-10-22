package net.pono.baseware.ghost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.withTimeoutOrNull
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ScriptHost
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.sakura.Token
import net.pono.baseware.shell.Shell
import net.pono.baseware.shiori.ShioriFactory
import net.pono.baseware.shiori.ShioriSession
import net.pono.baseware.update.HttpFetcher
import net.pono.baseware.update.NetworkUpdater
import net.pono.baseware.update.UpdateResult
import net.pono.baseware.update.UrlConnectionFetcher
import java.io.File
import java.util.Calendar
import java.util.Properties

/** プラットフォーム側に頼むこと。 */
interface GhostPlatform {
    fun openUrl(url: String)

    /** `\-` などでゴーストが終了した */
    fun onQuit()
    fun log(message: String)
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
    private val userName: String = "ユーザ",
    private val player: ScriptPlayer = ScriptPlayer(),
    private val balloonTimeoutMs: Long = 15_000,
    private val choiceTimeoutMs: Long = 30_000,
    private val updateFetcher: HttpFetcher = UrlConnectionFetcher("${ShioriSession.SENDER}/$BASEWARE_VERSION"),
) {
    private val _state = MutableStateFlow(GhostState())
    val state: StateFlow<GhostState> = _state.asStateFlow()

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

    private val profileFile = File(ghost.dir, "pono/profile.properties")

    fun start() {
        scope.launch {
            resetSurfaces()
            if (!loadShiori()) return@launch

            val profile = loadProfile()
            val firstBoot = profile.getProperty("booted") == null
            profile.setProperty("booted", "1")
            saveProfile(profile)

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
            platform.log("SHIORI ${ghost.shioriName} には対応していません")
            return false
        }
        val s = ShioriSession(shiori, ghost.charset)
        if (!s.load(ghost.masterDir)) {
            platform.log("SHIORI の読み込みに失敗しました")
            return false
        }
        session = s
        s.get("version")?.value?.let { platform.log("SHIORI version: $it") }
        s.notify("basewareversion", ShioriSession.SENDER, BASEWARE_VERSION)
        s.notify("OnNotifySelfInfo", ghost.name, ghost.sakuraName, ghost.keroName, shell.name, shell.dir.absolutePath + "/", "", "")
        s.notify("ownerghostname", ghost.name)
        return true
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
            try {
                val refs = { r0: String, r1: String -> listOf(r0, r1, "", "ghost") }
                val homeUrl = ghost.descript["homeurl"]
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
        session?.unload()
        session = null
        platform.onQuit()
    }

    // ---- ユーザー操作 ----

    /**
     * SSP と同じく、1回叩くごとに呼ぶ。[count] は続けて叩いた回数で、
     * 1 なら OnMouseClick、2 なら OnMouseDoubleClick、3 以上なら OnMouseMultipleClick（Reference7 が回数）を送る。
     */
    fun onMouseClick(scopeId: Int, x: Int, y: Int, collision: String?, count: Int) {
        lastUserActivity = System.currentTimeMillis()
        val refs = listOf(x.toString(), y.toString(), "0", scopeId.toString(), collision.orEmpty(), "0", "touch")
        when (count) {
            1 -> raise("OnMouseClick", refs, userInitiated = false)
            2 -> raise("OnMouseDoubleClick", refs)
            else -> raise("OnMouseMultipleClick", refs + count.toString())
        }
    }

    /** バルーンをタップした。\x の待ちなら進め、話し終わっていれば閉じる。 */
    fun onBalloonClick(scopeId: Int) {
        lastUserActivity = System.currentTimeMillis()
        val waiter = clickWaiter
        if (waiter != null) {
            waiter.complete(Unit)
            return
        }
        val s = _state.value
        if (!s.talking && !s.waitingChoice) hideBalloons()
    }

    fun onChoice(choice: Choice) {
        lastUserActivity = System.currentTimeMillis()
        choiceWaiter?.complete(choice)
    }

    // ---- イベント ----

    /**
     * イベントを SHIORI に送り、返ってきたスクリプトを再生する。
     * [userInitiated] なら話している途中でも割り込む。そうでなければ話していないときだけ再生する。
     */
    fun raise(event: String, references: List<String>, userInitiated: Boolean = true) {
        scope.launch {
            val script = request(event, references) ?: return@launch
            if (!userInitiated && (_state.value.talking || _state.value.waitingChoice)) return@launch
            cancelScript()
            runScript(script)
        }
    }

    private suspend fun request(event: String, references: List<String>): String? {
        val s = session ?: return null
        val value = requestLock.withLock {
            runCatching { s.get(event, *references.toTypedArray()) }
                .onFailure { platform.log("$event: ${it.message}") }
                .getOrNull()?.value
        }
        // 毎秒・毎分のイベントは多すぎるので、話すときだけ記録する
        if (value != null || event !in timerEvents) {
            platform.log("$event $references -> ${value?.take(120) ?: "(no content)"}")
        }
        return value
    }

    private fun startTimer() {
        timerJob = scope.launch {
            var lastMinute = Calendar.getInstance().get(Calendar.MINUTE)
            while (isActive) {
                delay(1000)
                val talking = _state.value.talking || _state.value.waitingChoice
                val uptimeHours = ((System.currentTimeMillis() - bootTime) / 3_600_000).toString()
                val idleSec = ((System.currentTimeMillis() - lastUserActivity) / 1000).toString()
                val refs = listOf(uptimeHours, "0", "0", if (talking) "0" else "1", idleSec)
                raise("OnSecondChange", refs, userInitiated = false)
                val minute = Calendar.getInstance().get(Calendar.MINUTE)
                if (minute != lastMinute) {
                    lastMinute = minute
                    raise("OnMinuteChange", refs, userInitiated = false)
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

    private fun runScript(script: String): Job {
        // talking は launch の前に立てる（呼び出し側が「話し終わるまで待つ」ときに取りこぼさないため）
        hideBalloons()
        _state.update { it.copy(talking = true) }
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
            for ((event, refs) in raised) raise(event, refs)
            if (raised.isNotEmpty()) return@launch

            if (result.hasChoices) {
                val waiter = CompletableDeferred<Choice?>()
                choiceWaiter = waiter
                _state.update { it.copy(waitingChoice = true) }
                val choice = if (host.choiceTimeoutMs > 0) {
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
            } else if (host.balloonTimeoutMs > 0) {
                delay(host.balloonTimeoutMs)
                hideBalloons()
            }
        }
        scriptJob = job
        return job
    }

    private fun selectChoice(choice: Choice) {
        val id = choice.id
        when {
            id.startsWith("http://") || id.startsWith("https://") -> {
                hideBalloons()
                platform.openUrl(id)
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

    private fun hideBalloons() {
        _state.update { s -> s.copy(characters = s.characters.mapValues { it.value.copy(balloon = null) }) }
    }

    private fun resetSurfaces() {
        _state.value = GhostState(
            characters = mapOf(
                0 to CharacterState(0, shell.defaultSurface(0)),
                1 to CharacterState(1, shell.defaultSurface(1)),
            ),
        )
    }

    private inner class Host(private val raised: MutableList<Pair<String, List<String>>>) : ScriptHost {
        /** このスクリプトのあいだだけ有効。0 以下なら消さない／待ち続ける */
        var balloonTimeoutMs = this@GhostController.balloonTimeoutMs
        var choiceTimeoutMs = this@GhostController.choiceTimeoutMs

        /** `\![updatebymyself]`。スクリプトが終わってから始める */
        var updateRequested = false

        override fun requestUpdate() {
            updateRequested = true
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

        override fun setSurface(scope: Int, surface: Int) = _state.update { it.update(scope) { c -> c.copy(surface = surface) } }

        override fun appendText(scope: Int, text: String) = _state.update { it.update(scope) { c -> c.appendText(text) } }

        override fun newLine(scope: Int) = _state.update { it.update(scope) { c -> c.append(BalloonItem.NewLine) } }

        override fun clearBalloon(scope: Int) = _state.update { it.update(scope) { c -> c.copy(balloon = emptyList()) } }

        override fun addChoice(scope: Int, choice: Choice) =
            _state.update { it.update(scope) { c -> c.append(BalloonItem.ChoiceItem(choice)) } }

        override fun startAnimation(scope: Int, id: Int) =
            _state.update { it.update(scope) { c -> c.copy(animationRequest = id to ++animationCounter) } }

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

        override fun openUrl(url: String) = platform.openUrl(url)

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
                else -> null
            }
        }

        override fun unknownTag(tag: Token.Tag) = platform.log("未対応のタグ: \\${tag.name}${tag.args}")
    }

    private fun loadProfile() = Properties().apply {
        if (profileFile.isFile) profileFile.inputStream().use(::load)
    }

    private fun saveProfile(p: Properties) {
        profileFile.parentFile?.mkdirs()
        profileFile.outputStream().use { p.store(it, null) }
    }

    companion object {
        const val BASEWARE_VERSION = "0.1.0"
        private val timerEvents = setOf("OnSecondChange", "OnMinuteChange")
    }
}
