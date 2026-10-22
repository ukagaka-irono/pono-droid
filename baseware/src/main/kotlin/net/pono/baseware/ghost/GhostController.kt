package net.pono.baseware.ghost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            val shiori = shioriFactory.create(ghost.shioriName, ghost.masterDir)
            if (shiori == null) {
                platform.log("SHIORI ${ghost.shioriName} には対応していません")
                return@launch
            }
            val s = ShioriSession(shiori, ghost.charset)
            if (!s.load(ghost.masterDir)) {
                platform.log("SHIORI の読み込みに失敗しました")
                return@launch
            }
            session = s
            s.get("version")?.value?.let { platform.log("SHIORI version: $it") }
            s.notify("basewareversion", ShioriSession.SENDER, BASEWARE_VERSION)
            s.notify("OnNotifySelfInfo", ghost.name, ghost.sakuraName, ghost.keroName, shell.name, shell.dir.absolutePath + "/", "", "")
            s.notify("ownerghostname", ghost.name)

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

    fun onMouseClick(scopeId: Int, x: Int, y: Int, collision: String?, double: Boolean) {
        lastUserActivity = System.currentTimeMillis()
        val event = if (double) "OnMouseDoubleClick" else "OnMouseClick"
        raise(event, listOf(x.toString(), y.toString(), "0", scopeId.toString(), collision.orEmpty(), "0", "touch"), userInitiated = double)
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
        return requestLock.withLock {
            runCatching { s.get(event, *references.toTypedArray()) }
                .onFailure { platform.log("$event: ${it.message}") }
                .getOrNull()?.value
        }
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
        val job = scope.launch {
            val raised = mutableListOf<Pair<String, List<String>>>()
            hideBalloons()
            _state.update { it.copy(talking = true) }
            val result = try {
                player.play(script, Host(raised))
            } finally {
                _state.update { it.copy(talking = false, waitingClick = false) }
            }

            if (result.quit && !closing) {
                closing = true
                shutdown()
                return@launch
            }
            for ((event, refs) in raised) raise(event, refs)
            if (raised.isNotEmpty()) return@launch

            if (result.hasChoices) {
                val waiter = CompletableDeferred<Choice?>()
                choiceWaiter = waiter
                _state.update { it.copy(waitingChoice = true) }
                val choice = withTimeoutOrNull(choiceTimeoutMs) { waiter.await() }
                _state.update { it.copy(waitingChoice = false) }
                choiceWaiter = null
                if (choice == null) {
                    hideBalloons()
                    raise("OnChoiceTimeout", listOf(script))
                } else {
                    selectChoice(choice)
                }
            } else {
                delay(balloonTimeoutMs)
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
    }
}
