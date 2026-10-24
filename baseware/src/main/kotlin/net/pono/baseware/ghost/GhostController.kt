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
import net.pono.baseware.net.FeedParser
import net.pono.baseware.net.FileWatch
import net.pono.baseware.net.NetTools
import net.pono.baseware.net.SocketWebSocketConnector
import net.pono.baseware.net.WebSocketConnector
import net.pono.baseware.net.WebSocketSession
import net.pono.baseware.net.HttpClient
import net.pono.baseware.net.MailAccount
import net.pono.baseware.net.MailChecker
import net.pono.baseware.net.MailResult
import net.pono.baseware.net.TlsMailChecker
import net.pono.baseware.net.SstpRequest
import net.pono.baseware.net.SstpResponse
import net.pono.baseware.net.HttpCommand
import net.pono.baseware.net.HttpOutcome
import net.pono.baseware.net.HttpRequest
import net.pono.baseware.net.HttpStreamer
import net.pono.baseware.net.ICal
import net.pono.baseware.net.UrlConnectionHttpClient
import net.pono.baseware.sakura.Choice
import net.pono.baseware.sakura.ChoiceLooks
import net.pono.baseware.sakura.MoveCommand
import net.pono.baseware.sakura.ScriptHost
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.sakura.SoundOptions
import net.pono.baseware.sakura.TextAlign
import net.pono.baseware.sakura.TextStyle
import net.pono.baseware.sakura.Token
import net.pono.baseware.sakura.VerticalAlign
import net.pono.baseware.shell.PatternFrame
import net.pono.baseware.shell.Shell
import net.pono.baseware.shell.SurfaceDefinition
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
import net.pono.baseware.update.UpdateProgress
import net.pono.baseware.update.UpdateResult
import net.pono.baseware.update.UrlConnectionFetcher
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

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

/**
 * 端末の見た目・言語・フォント・更新（OnDarkTheme・OnLanguageChange・OnNotifyFontInfo・OnOSUpdateInfo）。
 * [languageName] は SSP と同じく英語の言語名（Japanese など）、[languageId] は Windows の言語 ID（日本語なら 1041）。
 * [osUpdated] は端末を最後に更新した時刻（分からなければ null）、[osUpdateTitle] はその内容
 */
data class SystemInfo(
    val darkTheme: Boolean = false,
    val languageName: String = "",
    val languageId: Int = 0,
    val fonts: List<String> = emptyList(),
    val osUpdated: Long? = null,
    val osUpdateTitle: String = "",
    /** 国（祝日に使う）。JP なら日本の祝日を伝える */
    val country: String = "",
    /** おやすみモード（通知を鳴らさない）にしているか（system.dnd.mode） */
    val doNotDisturb: Boolean = false,
)

/** 画面の上の四角（画面の px）。プロパティシステムの rect と同じく 左,上,右,下 で書く */
data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    override fun toString() = "$left,$top,$right,$bottom"
}

/**
 * キャラクターのウィンドウの今の位置（currentghost.scope(ID).x など）。[surface] はサーフェスのウィンドウ、[balloon] は
 * バルーンのウィンドウ（出していなければ null）。[scale] と [balloonScale] は実際の拡大率（1.0 が等倍）
 */
data class WindowGeometry(
    val surface: ScreenRect?,
    val balloon: ScreenRect? = null,
    val scale: Double = 1.0,
    val balloonScale: Double = 1.0,
)

/**
 * \![set,wallpaper]。[file] の画像（無ければ [color] の単色）を壁紙にする。[position] は center / tile / stretch / fit / fill（既定）
 */
data class WallpaperRequest(val file: File?, val position: String, val color: Int?)

/**
 * \![open,dialog,open / save / folder / color]。[kind] は open / save / folder / color。選んだファイルはゴーストのフォルダの
 * [dir] に写して渡す（Android では、ほかのアプリのファイルのパスをそのまま渡せないため）。[filters] は 名前 → *.txt;*.dic
 */
data class SystemDialogRequest(
    val serial: Int,
    val kind: String,
    val id: String,
    val title: String,
    val filters: List<Pair<String, String>>,
    val defaultName: String,
    val color: Int?,
    val dir: File,
)

/** 端末のカレンダーの予定1件（ユーザーの予定。アプリの設定で許したときだけ読む）。終日なら [start] はその日の 0 時 */
data class DeviceEvent(val title: String, val description: String, val start: java.time.LocalDateTime, val end: java.time.LocalDateTime, val allDay: Boolean)

/** 端末の記憶領域の1つ（system.disk.index(ID).*）。[type] は fixed / removable など */
data class DiskInfo(val mountpoint: String, val type: String, val totalMb: Long, val freeMb: Long)

/** 鳴らしている音の1つ（currentghost.sound.*）。位置と長さはミリ秒で、分からなければ -1 */
data class SoundStatus(
    val file: File,
    val id: Int,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val paused: Boolean,
    val loop: Boolean,
    val preload: Boolean,
    val error: String = "",
)

/**
 * ネットワークの状態（OnNetworkStatusChange）。[type] は SSP と同じ wifi / cellular / ethernet / none / other / unknown、
 * 速さは MBps（分からなければ 0）、[metering] は unknown / unrestricted / fixed / variable
 */
data class NetworkStatus(
    val online: Boolean,
    val addresses: List<String> = emptyList(),
    val type: String = "unknown",
    val maxMbps: Double = 0.0,
    val effectiveMbps: Double = 0.0,
    val metering: String = "unknown",
)

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

    /** 今のネットワークの状態。null なら起動時の OnNetworkStatusChange を送らない */
    fun networkStatus(): NetworkStatus? = null

    /** 端末の見た目・言語など。null なら OnDarkTheme・OnLanguageChange などを送らない */
    fun systemInfo(): SystemInfo? = null

    /** ゴーストのフォルダの [file] を、Android のほかのアプリ（画像を見る・音を鳴らすなど）で開く。開けたら true */
    fun openFile(file: File, mimeType: String): Boolean = false

    /** readme のような長い文を見せる（\![open,readme]） */
    fun showText(title: String, text: String) {}

    /** ゴーストのフォルダの [file] を、Android の「共有」でほかのアプリに渡せるようにする（作った .nar を保存・送る） */
    fun shareFile(file: File, mimeType: String) {}

    /** \![create,shortcut]。ホーム画面に、[dirName] のゴーストを起こすショートカットを置くよう頼む。頼めたら true */
    fun createShortcut(dirName: String, label: String): Boolean = false

    /** \![execute,dumpsurface]。[dump] のサーフェスを PNG に書き出し、書いたファイルの数を返す */
    suspend fun dumpSurfaces(dump: SurfaceDump): Int = 0

    /** \![execute,dumpballoon]。[scope] のバルーンの今の絵を [file] に PNG で書く。書けたら true */
    suspend fun dumpBalloon(scope: Int, file: File): Boolean = false

    /** pono-droid の設定画面（SSP の本体設定やゴーストエクスプローラの代わり） */
    fun openSettings() {}

    /** 音を鳴らす（\8 \_v \![sound]）。[volume] は 0〜100（null なら既定） */
    fun playSound(file: File, loop: Boolean, volume: Int?) {}

    /**
     * 音を鳴らす（オプション付き）。同じファイルなら鳴らし直し、ほかの音はそのまま鳴らし続ける（SSP と同じく何個でも）。
     * 鳴り終わったら [GhostController.onSoundStopped]、ループの頭に戻るたびに [GhostController.onSoundLooped]、
     * 鳴らせなければ [GhostController.onSoundError] を呼ぶ
     */
    fun playSound(file: File, loop: Boolean, options: SoundOptions) = playSound(file, loop, options.volume)

    /** load（先に読んでおく）/ pause / resume / option（音量・左右・速さ・位置を変える）。[file] が null なら鳴っている音すべて */
    fun soundControl(command: String, file: File?, options: SoundOptions) {}

    /** [file] の音だけ止める。null ならすべて（[stopSound]） */
    fun stopSound(file: File?) {
        if (file == null) stopSound()
    }

    /**
     * \![set,trayballoon]。Android の通知で知らせる。触れたら [GhostController.onTrayBalloonClick]、
     * 消したら [GhostController.onTrayBalloonTimeout]。[icon] は info / warning / error / none
     */
    fun showTrayBalloon(title: String, text: String, icon: String) {}

    /** \![set,tasktrayicon,ファイル,文字]。常駐の通知に [text] を出す（アイコンの画像は Android の通知では替えられない） */
    fun setTrayText(text: String) {}

    /**
     * ファイルを選ぶ・保存先を選ぶ・フォルダを選ぶ・色を選ぶ画面を出す。答えは [GhostController.onSystemDialogResult] に返す。
     * 出せたら true
     */
    fun openSystemDialog(request: SystemDialogRequest): Boolean = false

    /** \![close,dialog,ID]。[id] が null なら全部 */
    fun closeSystemDialog(id: String?) {}

    /** アプリの設定の「開発者向けの機能」。\![open,surfacetest / developer / shiorirequest] はこれを選んだときだけ */
    fun developerMode(): Boolean = false

    /** 開発用の画面を開く（page は backlog / log / shiorirequest / surfacetest / developer） */
    fun openDeveloperScreen(page: String) {}

    /** [day] のユーザーの予定（端末のカレンダー）。読む許可が無ければ空 */
    fun deviceSchedule(day: java.time.LocalDate): List<DeviceEvent> = emptyList()

    /** \![set,wallpaper]。アプリの設定で許したときだけ壁紙を変える。変えたら true */
    fun setWallpaper(request: WallpaperRequest): Boolean = false

    /**
     * \![open,editor,ファイル,行]。ゴーストのフォルダの [file] を、書き換えられるようにほかのアプリで開く
     * （アプリの設定の「開発者向けの機能」を選んだときだけ）。開けたら true
     */
    fun editFile(file: File, line: Int?): Boolean = false

    /** [scope] のキャラクターのウィンドウの今の位置（出していなければ null） */
    fun windowGeometry(scope: Int): WindowGeometry? = null

    /** 記憶領域（system.disk.*） */
    fun diskInfo(): List<DiskInfo> = emptyList()

    /** 鳴らしている音（先に読んだだけのものも） */
    fun soundStatus(): List<SoundStatus> = emptyList()

    /** メールを確かめるアカウント（アプリの設定で入れたもの。最初は無い） */
    fun mailAccounts(): List<MailAccount> = emptyList()

    /** 何分ごとにメールを確かめるか。0 なら \![biff] とメニューのときだけ */
    fun mailCheckMinutes(): Int = 0

    /** マイクで声を聞くか（アプリの設定とマイクの許可。OnVoiceRecognitionStatus で伝える） */
    fun voiceRecognitionEnabled(): Boolean = false

    /** 話した文字を読み上げるか（アプリの設定。OnSpeechSynthesisStatus で伝える） */
    fun speechEnabled(): Boolean = false

    /** [scope] のキャラクターが話した [text] を読み上げる（[speechEnabled] のときだけ呼ぶ） */
    fun speak(scope: Int, text: String) {}

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

/** 発言履歴の1つ（\![open,backlogviewer]）。[time] は話し終えた時刻（ミリ秒） */
data class BacklogEntry(val time: Long, val scope: Int, val text: String)

/**
 * \![execute,dumpsurface] で書き出すもの。ファイル名は [prefix] + サーフェス番号 + .png、[animation] があれば続けて
 * そのアニメーションのコマを [prefix] + サーフェス番号 + _0000.png からの連番で。[binds] は身に着けている着せ替え
 */
data class SurfaceDump(
    val dir: File,
    val scope: Int,
    val surfaces: List<Int>,
    val prefix: String,
    val binds: Set<Int>,
    val animation: Int? = null,
)

/**
 * ゴーストの切り替えで、次のゴーストに伝える前のゴーストのこと（OnGhostChanged の Reference）。
 * [vanished] なら前のゴーストは消去された（OnVanished を送る）。[reload] なら同じゴーストの読み直しで、起動のイベントは送らない
 */
data class PreviousGhost(
    val sakuraName: String,
    val script: String,
    val name: String,
    val path: String,
    val vanished: Boolean = false,
    val reload: Boolean = false,
)

/**
 * ゴースト1体を動かす。SHIORI にイベントを送り、返ってきたさくらスクリプトを再生して [state] に反映する。
 * イベントの送信とスクリプトの再生はすべて [scope] の中で行う。
 */
class GhostController(
    val ghost: Ghost,
    shell: Shell,
    private val shioriFactory: ShioriFactory,
    private val scope: CoroutineScope,
    platform: GhostPlatform,
    /** 使っているバルーン（null なら pono-droid の簡易バルーン） */
    balloon: Balloon? = null,
    /** 入っているゴーストとバルーンの一覧（プロパティシステムの ghostlist などに使う） */
    private val repository: GhostRepository? = null,
    userName: String = "ユーザ",
    private val player: ScriptPlayer = ScriptPlayer(),
    private val balloonTimeoutMs: Long = 15_000,
    private val choiceTimeoutMs: Long = 30_000,
    /** バルーンが閉じてから OnSurfaceRestore を送るまで（SSP と同じ 15 秒） */
    private val surfaceRestoreMs: Long = 15_000,
    /** ゴーストに伝える pono-droid のバージョン（basewareversion、%property[baseware.version]、通信の User-Agent） */
    private val basewareVersion: String = BASEWARE_VERSION,
    private val updateFetcher: HttpFetcher = UrlConnectionFetcher("${ShioriSession.SENDER}/$basewareVersion"),
    /** \![execute,http-*] などの通信 */
    private val httpClient: HttpClient = UrlConnectionHttpClient("${ShioriSession.SENDER}/$basewareVersion"),
    /** WebSocket の通信 */
    private val webSocketConnector: WebSocketConnector = SocketWebSocketConnector("${ShioriSession.SENDER}/$basewareVersion"),
    /** メールの確認（\![biff]）。いつも TLS でつなぐ */
    private val mailChecker: MailChecker = TlsMailChecker(),
    /** 別のゴーストから切り替わって起動したとき、その前のゴースト（OnGhostChanged を送る） */
    private val previous: PreviousGhost? = null,
) {
    private val _state = MutableStateFlow(GhostState())
    val state: StateFlow<GhostState> = _state.asStateFlow()

    /** 開発のための記録（developer.log.*、\![open,errorlog]） */
    val developerLog = DeveloperLog()

    /** 使ったゴーストとバルーン（history.*）と使用率（rateofuselist.*）。ゴースト置き場が無ければ記録しない */
    private val history: UsageHistory? = repository?.let { UsageHistory(File(it.root.parentFile ?: it.root, "pono/history.properties")) }

    /** エラーと「できなかった」記録を、開発のための記録にも残す */
    private val platform: GhostPlatform = object : GhostPlatform by platform {
        override fun onError(message: String) {
            developerLog.add("error", "Error", ghost.name, message)
            platform.onError(message)
        }

        override fun log(message: String) {
            if (" -> " !in message && WARNING_WORDS.any { it in message }) developerLog.add("error", "Warning", ghost.name, message)
            platform.log(message)
        }
    }

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

    /** runScript した回数。いちばん新しいスクリプトがどれかを見分ける */
    private var scriptRuns = 0
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
    private var lastNetwork: NetworkStatus? = null

    /** OnSysSuspend を送って、まだ OnSysResume を送っていない */
    private var suspended = false

    fun start() {
        // 辞書の大きいゴーストは読み込みに数秒かかるので、その間は読み込み中と出しておく。
        // 呼んだ側がすぐ「読み込み中か」を見られるよう、launch の前に立てる
        _state.update { it.copy(loading = "読み込み中…") }
        scope.launch {
            resetSurfaces()
            val loaded = try {
                loadShiori()
            } finally {
                _state.update { it.copy(loading = null) }
            }
            if (!loaded) {
                // 話せないゴーストを出しっぱなしにしない
                closing = true
                platform.onQuit()
                return@launch
            }
            // 位置を覚えていないキャラクターは、ゴーストの決めた位置に出す
            val defaults = loadDefaultPositions()
            if (defaults.isNotEmpty()) _state.update { it.copy(window = it.window.copy(defaultPositions = defaults)) }

            val profile = ghost.loadProfile()
            val firstBoot = profile.getProperty("booted") == null
            profile.setProperty("booted", "1")
            history?.ghostBooted(ghost.dir.name)
            balloon?.let { history?.balloonUsed(it.dir.name) }
            ghost.saveProfile(profile)

            // 切り替わって来たときは OnGhostChanged（前のゴーストが消去されたなら OnVanished）。答えが無ければ OnBoot（SSP と同じ）。
            // \![reload,ghost] の読み直しでは何も送らない
            val changed = if (!firstBoot && previous != null && !previous.reload) {
                request(
                    if (previous.vanished) "OnVanished" else "OnGhostChanged",
                    listOf(previous.sakuraName, previous.script, previous.name, if (previous.vanished) "" else previous.path, "", "", "", shell.name),
                )
            } else {
                null
            }
            when {
                previous?.reload == true -> {}
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
        // Owned SSTP の ID（起動ごとに替わる）
        s.notify("uniqueid", uniqueId)
        // 今日の予定（ゴースト用の予定表と、許していれば端末のカレンダー）
        val today = todaySchedule()
        lastTodaySchedule = today
        s.notify("OnScheduleTodayNotify", *today.toTypedArray())
        s.notify("OnSpeechSynthesisStatus", if (platform.speechEnabled()) "1" else "0")
        s.notify("OnVoiceRecognitionStatus", if (platform.voiceRecognitionEnabled()) "1" else "0")
        // 今日が祝日なら、OnNotifyUserInfo のすぐ後に（SSP と同じ。祝日表の代わりに日本の祝日を求める）
        platform.systemInfo()?.takeIf { it.country.equals("JP", ignoreCase = true) }?.let {
            val today = java.time.LocalDate.now()
            JapaneseHolidays.name(today)?.let { name ->
                s.notify("OnNotifyHolidayInfo", name, "%04d,%02d,%02d".format(today.year, today.monthValue, today.dayOfMonth), "JP")
            }
        }
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
        // SSP と同じく、起動直後だけ NOTIFY で（以後は変わったときにイベントで）
        platform.networkStatus()?.let { n ->
            lastNetwork = n
            s.notify("OnNetworkStatusChange", *networkReferences(n).toTypedArray())
        }
        platform.systemInfo()?.let { info ->
            lastSystem = info
            s.notify("OnDarkTheme", *darkReferences(info).toTypedArray())
            s.notify("OnLanguageChange", *languageReferences(info).toTypedArray())
            if (info.fonts.isNotEmpty()) s.notify("OnNotifyFontInfo", *info.fonts.toTypedArray())
            s.notify("OnOSUpdateInfo", *osUpdateReferences(info).toTypedArray())
        }
    }

    private var lastSystem: SystemInfo? = null

    /** アプリの設定と端末の設定（pono-droid は端末の設定に従うので、どちらも同じ） */
    private fun darkReferences(info: SystemInfo) = List(2) { if (info.darkTheme) "1" else "0" }

    /** 言語名・言語 ID・言語ファイルのフォルダ（無い）・ヘルプの URL（無い） */
    private fun languageReferences(info: SystemInfo) = listOf(info.languageName, info.languageId.toString(), "", "")

    /** 確認した時刻・更新した時刻と、履歴（状態[1]エラーコード[1]時刻[1]内容）。Android では最後の更新だけ分かる */
    private fun osUpdateReferences(info: SystemInfo): List<String> {
        val updated = info.osUpdated ?: return listOf("", "")
        val c = Calendar.getInstance().apply { timeInMillis = updated }
        val time = "%d,%d,%d,%d,%d,%d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
        )
        return listOf(time, time, listOf("success", "0", time.replace(',', '/'), info.osUpdateTitle).joinToString("\u0001"))
    }

    /** OnNotifyDressupInfo の Reference: [キャラ ID, カテゴリ, パーツ, オプション, 有効 1/0, サムネイル] を 0x01 でつないだもの */
    private fun dressupInfo(): List<String> = characterScopes.flatMap { scope ->
        val binds = _state.value.characters[scope]?.binds.orEmpty()
        shell.bindGroups(scope).map { g ->
            listOf(scope.toString(), g.category, g.part, "", if (g.id in binds) "1" else "0", "").joinToString("\u0001")
        }
    }

    /** OnNetworkStatusChange の Reference: online/offline、アドレス（0x01 区切り）、種類、最大と実効の速さ（MBps）、課金 */
    private fun networkReferences(n: NetworkStatus): List<String> {
        fun mbps(v: Double) = if (v > 0) String.format(Locale.US, "%.1f", v) else "0"
        return listOf(
            if (n.online) "online" else "offline",
            n.addresses.joinToString("\u0001"),
            n.type,
            mbps(n.maxMbps),
            mbps(n.effectiveMbps),
            n.metering,
        )
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
        set(value) {
            field = value
            refreshOnline()
        }

    /** \![enter,onlinemode] の間 */
    private var onlineMode = false
        set(value) {
            field = value
            refreshOnline()
        }

    /** バルーンのオンラインの印は、\![enter,onlinemode] の間とネットワーク更新の間に出す */
    private fun refreshOnline() = _state.update { it.copy(online = onlineMode || updating || httpShowing > 0) }

    /** ネットワーク更新するもの。[type] は ghost / shell / balloon、[name] は名前（OnUpdateResultEx） */
    private class UpdateTarget(val type: String, val name: String, val dir: File, val homeUrl: String?)

    /**
     * SSP と同じ流れでネットワーク更新する（\![updatebymyself]・メニュー・通知）。ゴーストは OnUpdateBegin → OnUpdateReady →
     * OnUpdateComplete / OnUpdateFailure、シェルとバルーンは OnUpdateOther*。辞書が変わったら SHIORI を読み込み直してから Complete を送る。
     * 最後に結果をまとめて OnUpdateResult（checkonly なら OnUpdateCheckResult）を送る。[options] は checkonly / testonly / recovery、
     * [reason] は SSP の更新実行理由（script / user）。シェルの変更は次の起動から反映される
     */
    fun networkUpdate(types: List<String> = listOf("ghost"), options: Set<String> = emptySet(), reason: String = "user") {
        val targets = types.mapNotNull { type ->
            when (type) {
                "ghost" -> UpdateTarget("ghost", ghost.name, ghost.dir, homeUrlOverride ?: ghost.descript["homeurl"])
                "shell" -> UpdateTarget("shell", shell.name, shell.dir, shell.descript["homeurl"])
                "balloon" -> balloon?.let { UpdateTarget("balloon", it.name, it.dir, it.descript["homeurl"]) }
                else -> null
            }
        }
        runUpdates(targets, options, reason, extended = false)
    }

    /** other_homeurl_override（種類, 名前, 作者, フォルダ名, 本来の homeurl）。空や答えが無ければ null（替えない） */
    private suspend fun otherHomeUrl(t: UpdateTarget): String? {
        val descript = runCatching { net.pono.baseware.text.Descript.read(File(t.dir, "descript.txt")) }.getOrNull()
        val craftman = descript?.get("craftmanw") ?: descript?.get("craftman") ?: ""
        return request("other_homeurl_override", listOf(t.type, t.name, craftman, t.dir.name, t.homeUrl.orEmpty()), translate = false)
            ?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** \![updateother,--shell=名前,--balloon=名前]。入っているシェルとバルーンを名前で更新し、OnUpdateResultEx でまとめる */
    fun updateOther(args: List<String>) {
        val opts = args.map { it.trim() }.filter { it.startsWith("--") }
        val options = opts.filter { it.startsWith("--option=") }.flatMap { it.removePrefix("--option=").split('+', ',') }.map { it.trim().lowercase() }.toSet()
        val targets = opts.mapNotNull { o ->
            val name = o.substringAfter('=', "").trim()
            when {
                o.startsWith("--shell=") -> ghost.shellDirs.map(::Shell).firstOrNull { it.name == name || it.dir.name == name }
                    ?.let { UpdateTarget("shell", it.name, it.dir, it.descript["homeurl"]) }
                o.startsWith("--balloon=") -> repository?.balloons?.list()?.firstOrNull { it.name == name || it.dir.name == name }
                    ?.let { UpdateTarget("balloon", it.name, it.dir, it.descript["homeurl"]) }
                // プラグイン・ヘッドライン・言語は pono-droid に無い
                else -> null
            }
        }
        if (targets.isNotEmpty()) runUpdates(targets, options, "script", extended = true)
    }

    private fun runUpdates(targets: List<UpdateTarget>, options: Set<String>, reason: String, extended: Boolean) {
        if (updating || closing || targets.isEmpty()) return
        updating = true
        val check = "checkonly" in options
        val mode = when {
            check -> NetworkUpdater.Mode.CHECK
            "testonly" in options -> NetworkUpdater.Mode.TEST
            else -> NetworkUpdater.Mode.NORMAL
        }
        scope.launch {
            val results = mutableListOf<String>()
            try {
                for (t in targets) results += updateOne(t, mode, reason, extended)
            } finally {
                updating = false
            }
            val summary = when {
                check && extended -> "OnUpdateCheckResultEx"
                check -> "OnUpdateCheckResult"
                extended -> "OnUpdateResultEx"
                else -> "OnUpdateResult"
            }
            raise(summary, results, userInitiated = false)
        }
    }

    /** 1つ更新して、OnUpdateResult の1つ分（種類[1]OK|NG[1]ファイル数か理由。Ex は名前を先に付ける）を返す */
    private suspend fun updateOne(t: UpdateTarget, mode: NetworkUpdater.Mode, reason: String, extended: Boolean): String {
        val ghostSide = t.type == "ghost"
        // ゴースト以外は OnUpdateOther*。Reference3 は種類、Reference4 は実行理由
        val prefix = if (ghostSide) "OnUpdate" else "OnUpdateOther"
        fun refs(r0: String, r1: String) = listOf(r0, r1, "", t.type, reason)
        fun row(ok: Boolean, detail: String) = (if (extended) listOf(t.name, t.type) else listOf(t.type)).plus(listOf(if (ok) "OK" else "NG", detail)).joinToString("\u0001")
        val check = mode == NetworkUpdater.Mode.CHECK
        try {
            // シェルとバルーンの更新先は、ゴーストが other_homeurl_override で替えられる
            val homeUrl = if (ghostSide) t.homeUrl else otherHomeUrl(t) ?: t.homeUrl
            // useorigin1 が 1 なら、ファイルの番号と数を 1 から数える（ふつうは MATERIA と同じ 0 から）
            val origin = if (resource("useorigin1")?.trim() == "1") 1 else 0
            if (homeUrl.isNullOrBlank()) {
                raise(if (check) "OnUpdateCheckFailure" else "${prefix}Failure", refs("none", ""), userInitiated = !check)
                return row(false, "none")
            }
            if (!check) talkAndWait("${prefix}Begin", listOf(t.name, t.dir.absolutePath + "/", "", t.type, reason))
            val result = watchNetwork {
                NetworkUpdater(t.dir, updateFetcher).update(
                    homeUrl, mode,
                    onReady = { count ->
                        // Reference0 は「ファイル数 - 1」（SSP の仕様。useorigin1 なら数そのもの）
                        talkAndWait("${prefix}Ready", refs((count - 1 + origin).toString(), ""))
                    },
                    onProgress = { p ->
                        when (p) {
                            is UpdateProgress.Download ->
                                raise("$prefix.OnDownloadBegin", listOf(p.path, (p.index + origin).toString(), (p.count - 1 + origin).toString(), t.type, reason), userInitiated = false)
                            is UpdateProgress.Md5 -> {
                                val r = listOf(p.path, p.expected, p.actual, t.type, reason)
                                raise("$prefix.OnMD5CompareBegin", r, userInitiated = false)
                                raise(if (p.matched) "$prefix.OnMD5CompareComplete" else "$prefix.OnMD5CompareFailure", r, userInitiated = false)
                            }
                        }
                    },
                )
            }
            platform.log("network update ${t.type}: $result")
            return when (result) {
                UpdateResult.NoChange -> {
                    raise("${prefix}Complete", refs("none", ""))
                    row(true, "0")
                }
                is UpdateResult.Changed -> {
                    if (ghostSide) reloadShiori()
                    raise("${prefix}Complete", refs("changed", result.files.joinToString(",")))
                    row(true, result.files.size.toString())
                }
                // checkonly は更新の有無だけ（ファイル数は1を引かない）。testonly は置き換えずに終わる
                is UpdateResult.Checked -> {
                    if (check) {
                        raise("OnUpdateCheckComplete", refs(if (result.files.isEmpty()) "none" else result.files.size.toString(), result.files.joinToString(",")), userInitiated = false)
                    } else {
                        raise("${prefix}Complete", refs(if (result.files.isEmpty()) "none" else "changed", result.files.joinToString(",")))
                    }
                    row(true, result.files.size.toString())
                }
                is UpdateResult.Failure -> {
                    raise(if (check) "OnUpdateCheckFailure" else "${prefix}Failure", refs(result.reason, ""), userInitiated = !check)
                    row(false, result.reason)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 更新サーバーが返した変な一覧などで落ちないように。ゴーストには失敗として伝える
            platform.log("network update failed: $e")
            val why = if (e is IOException) "fileio" else "unknown"
            raise(if (check) "OnUpdateCheckFailure" else "${prefix}Failure", refs(why, ""))
            return row(false, why)
        }
    }

    /**
     * 通信の相手が [NETWORK_HEAVY_MS] たっても答えなければ、待っている間 OnNetworkHeavy を送る
     * （Reference0 はあきらめるまでの秒数、Reference1 は待った秒数。SSP と同じく1回の通信につき1回）
     */
    private suspend fun <T> watchNetwork(block: suspend () -> T): T {
        val started = System.currentTimeMillis()
        val watch = scope.launch {
            delay(NETWORK_HEAVY_MS)
            val elapsed = (System.currentTimeMillis() - started) / 1000
            raise("OnNetworkHeavy", listOf((NETWORK_TIMEOUT_MS / 1000).toString(), elapsed.toString()), userInitiated = false)
        }
        try {
            return block()
        } finally {
            watch.cancel()
        }
    }

    // ---- 予定（\![execute,schedule-add / delete / get]、OnScheduleTodayNotify、OnSchedule5MinutesToGo） ----

    /** ゴースト用の予定表。ユーザーの予定とは別 */
    private val schedule = GhostSchedule(File(ghost.dir, GhostPackager.PONO_DIR + "/schedule.ics"), ghost.name)

    /** 最後に知らせた「今日の予定」（変わったときだけ知らせ直す） */
    private var lastTodaySchedule: List<String>? = null

    private fun todaySchedule(day: java.time.LocalDate = java.time.LocalDate.now()): List<String> {
        val user = runCatching { platform.deviceSchedule(day) }.getOrDefault(emptyList()).sortedBy { it.start }.map { d ->
            val e = net.pono.baseware.net.ICalEvent(
                uid = "", start = d.start, end = d.end, allDay = d.allDay, summary = d.title, description = d.description,
                location = "", url = "", status = "", categories = "", rrule = "", exdates = emptySet(),
            )
            GhostSchedule.todayReference("user", d.title, d.description, "", e, day)
        }
        return schedule.today(day) + user
    }

    /** 毎分: 日付が変わったら今日の予定を知らせ直し、知らせる時刻になった予定は OnSchedule5MinutesToGo */
    private fun checkSchedule() {
        notifyTodayIfChanged()
        val now = java.time.LocalDateTime.now().withSecond(0).withNano(0)
        for ((e, _) in schedule.dueNotifications(now)) {
            raise("OnSchedule5MinutesToGo", listOf("ghost", e.summary, e.description, ""), userInitiated = false)
        }
        for (d in runCatching { platform.deviceSchedule(now.toLocalDate()) }.getOrDefault(emptyList())) {
            if (d.allDay) continue
            val at = d.start.minusMinutes(GhostSchedule.DEFAULT_NOTIFY_MINUTES.toLong())
            if (!at.isBefore(now) && at.isBefore(now.plusMinutes(1))) raise("OnSchedule5MinutesToGo", listOf("user", d.title, d.description, ""), userInitiated = false)
        }
    }

    private fun notifyTodayIfChanged() {
        val today = todaySchedule()
        if (today == lastTodaySchedule) return
        lastTodaySchedule = today
        notify("OnScheduleTodayNotify", today)
    }

    /** --event が On で始まればそれ（失敗は後ろに Failure）、無ければ OnExecuteSchedule* */
    private fun scheduleEvents(event: String, base: String) =
        if (event.startsWith("On")) event to event + "Failure" else base to "OnExecuteScheduleFailure"

    /** \![execute,schedule-add,--uid,--summary,--start,--end,--rrule,--notify,--description,--location,--event] */
    private fun scheduleAdd(args: List<String>) {
        val (_, opts) = splitArgs(args)
        val (ok, failed) = scheduleEvents(opts["event"].orEmpty(), "OnExecuteScheduleComplete")
        val uid = opts["uid"]?.trim().orEmpty().ifEmpty { "pono-" + java.util.UUID.randomUUID().toString() }
        val start = opts["start"]?.let(GhostSchedule::normalizeDate)
        val end = opts["end"]?.let { GhostSchedule.normalizeDate(it) ?: "" }.orEmpty()
        val summary = opts["summary"]?.trim().orEmpty()
        if (summary.isEmpty() || start == null || (opts["end"] != null && end.isEmpty()) || !GhostSchedule.validUid(uid)) {
            raise(failed, listOf("add", "invalid", opts["uid"].orEmpty()), userInitiated = false)
            return
        }
        val entry = GhostSchedule.Entry(
            uid, summary, start, end, opts["rrule"]?.trim()?.replace("\r", "")?.replace("\n", "").orEmpty(),
            opts["description"].orEmpty(), opts["location"].orEmpty(), opts["notify"]?.trim()?.toIntOrNull()?.coerceIn(0, 7 * 24 * 60),
        )
        val saved = runCatching { schedule.add(entry) }.isSuccess
        if (saved) raise(ok, listOf("add", uid), userInitiated = false) else raise(failed, listOf("add", "save", uid), userInitiated = false)
        notifyTodayIfChanged()
    }

    /** \![execute,schedule-delete,UID,--event] */
    private fun scheduleDelete(args: List<String>) {
        val (positional, opts) = splitArgs(args)
        val (ok, failed) = scheduleEvents(opts["event"].orEmpty(), "OnExecuteScheduleComplete")
        val uid = positional.firstOrNull()?.trim().orEmpty()
        when {
            uid.isEmpty() -> raise(failed, listOf("delete", "invalid", ""), userInitiated = false)
            runCatching { schedule.delete(uid) }.getOrDefault(false) -> raise(ok, listOf("delete", uid), userInitiated = false)
            else -> raise(failed, listOf("delete", "notfound", uid), userInitiated = false)
        }
        notifyTodayIfChanged()
    }

    /** \![execute,schedule-get,--from,--to,--expand,--limit,--event]。答えは OnExecuteICalComplete と同じ形 */
    private fun scheduleGet(args: List<String>) {
        val (_, opts) = splitArgs(args)
        val event = opts["event"].orEmpty().takeIf { it.startsWith("On") } ?: "OnExecuteScheduleGetComplete"
        fun day(k: String) = opts[k]?.let { runCatching { java.time.LocalDate.parse(it.trim(), java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
        val range = net.pono.baseware.net.ICal.range(
            day("from"), day("to"), opts["expand"]?.toIntOrNull()?.coerceIn(1, 1830), opts.containsKey("expand"), opts["limit"]?.toIntOrNull()?.coerceAtLeast(0),
        )
        raise(event, net.pono.baseware.net.ICal.references(schedule.calendar(), range), userInitiated = false)
    }

    // ---- ダイアログ（\![open,dialog,...]、OnSystemDialog / OnSystemDialogCancel） ----

    private var dialogSerial = 0
    private val openDialogs = HashMap<Int, SystemDialogRequest>()

    /** \![open,dialog,種類,--title,--filter,--ext,--name,--id,--color]。--dir は Android では使えない（いつも端末の選ぶ画面から） */
    private fun openSystemDialog(args: List<String>): Boolean {
        val kind = args.firstOrNull()?.trim()?.lowercase()?.takeIf { it in setOf("open", "save", "folder", "color") } ?: return false
        val (_, opts) = splitArgs(args.drop(1))
        val filters = opts["filter"]?.split('|')?.chunked(2)?.mapNotNull { c -> c.getOrNull(1)?.let { c[0] to it } }.orEmpty()
        val ext = opts["ext"]?.trim()?.removePrefix(".").orEmpty()
        val name = opts["name"]?.trim().orEmpty().let { n -> if (n.isNotEmpty() && ext.isNotEmpty() && !n.contains('.')) "$n.$ext" else n }
        val color = opts["color"]?.let { ScriptPlayer.parseColor(it.split(' ', ',').filter(String::isNotBlank)) }
        val request = SystemDialogRequest(
            ++dialogSerial, kind, opts["id"].orEmpty(), opts["title"].orEmpty(), filters, name, color,
            File(ghost.dir, GhostPackager.PONO_DIR + "/dialog"),
        )
        openDialogs[request.serial] = request
        if (!platform.openSystemDialog(request)) {
            openDialogs.remove(request.serial)
            return false
        }
        return true
    }

    /**
     * ダイアログの答え。[value] はゴーストのフォルダに写したファイル・フォルダのパスか "R,G,B"、null なら取り消し。
     * --id が On で始まればそのイベント（取り消しは後ろに Cancel）、無ければ OnSystemDialog / OnSystemDialogCancel
     */
    fun onSystemDialogResult(serial: Int, value: String?) {
        val r = openDialogs.remove(serial) ?: return
        val custom = r.id.startsWith("On")
        if (value != null) {
            raise(if (custom) r.id else "OnSystemDialog", listOf(r.kind, r.id, value))
        } else {
            raise(if (custom) r.id + "Cancel" else "OnSystemDialogCancel", listOf(r.kind, r.id))
        }
    }

    // ---- 開発用の画面から ----

    /** SHIORI リクエスト送信の画面から。[event] を送り、返ってきたスクリプトを返す（[play] なら再生もする） */
    suspend fun sendShioriRequest(event: String, references: List<String>, play: Boolean): String? {
        val name = event.trim().takeIf { it.isNotEmpty() } ?: return null
        val script = request(name, references)
        if (play && script != null) {
            cancelScript()
            runScript(script)
        }
        return script
    }

    /** サーフェステストの画面から。[scope] のキャラクターに [surface] を出す */
    fun testSurface(scope: Int, surface: Int) {
        cancelScript()
        runScript("\\p[$scope]\\s[$surface]\\e")
    }

    /** 開発用パレットから: SHIORI・シェル・ゴーストを読み直す */
    fun reloadFromDeveloper(what: String) {
        when (what) {
            "shiori" -> scope.launch { reloadShiori() }
            "shell" -> reloadShell()
            "ghost" -> reloadGhost()
        }
    }

    // ---- SSTP（この端末のほかのアプリから） ----

    /** Owned SSTP の ID。SHIORI に NOTIFY uniqueid で伝え、この ID の付いた SSTP はゴースト自身と同じに扱う */
    val uniqueId: String = java.util.UUID.randomUUID().toString().replace("-", "")

    /** SSTP で届いて再生中のスクリプト（runScript の通し番号）。バルーンをタップして止めたら OnSSTPBreak */
    private var sstpRun = -1

    /** 最後に SSTP でスクリプトを送ってきたもの（メニューの「受けない」で選べる）と、受けないことにした送り手 */
    private var lastSstpSender: String? = null
    private val blockedSstpSenders = HashSet<String>()

    /** メニューの「SSTP を受けない」。この起動の間、その送り手からは受けない（OnSSTPBlacklisting。端末の中なので 127.0.0.1） */
    private fun blockLastSstpSender() {
        val sender = lastSstpSender ?: return
        blockedSstpSenders += sender
        lastSstpSender = null
        raise("OnSSTPBlacklisting", listOf("127.0.0.1"), userInitiated = false)
    }

    /** EXECUTE Quiet で黙る期限 */
    private var quietUntil = 0L

    /** EXECUTE SetCookie / GetCookie（送り手ごと） */
    private val sstpCookies = HashMap<String, MutableMap<String, String>>()

    /**
     * SSTP の要求に答える。SEND / NOTIFY はイベント（答えが無ければ Script ヘッダ。IfGhost で選ぶ）、COMMUNICATE は OnCommunicate、
     * GIVE は OnCommunicate / OnMusicPlay、EXECUTE は情報を返す。ほかのアプリからのスクリプトは [ScriptHost.untrusted] で再生する
     * （ID がこのゴーストの uniqueid なら制限しない）
     */
    suspend fun handleSstp(req: SstpRequest): SstpResponse {
        val sender = req["Sender"] ?: req["User-Agent"] ?: return SstpResponse(400)
        val owned = req["ID"] == uniqueId
        if (!owned && sender in blockedSstpSenders) return SstpResponse(420)
        val target = req["ReceiverGhostName"] ?: req["Ghost"]
        if (target != null && target != ghost.sakuraName && target != ghost.name) return SstpResponse(404)
        if (closing) return SstpResponse(503)
        if (req.method == "EXECUTE") return sstpExecute(req, sender, owned)
        val s = _state.value
        if (s.window.minimized) return SstpResponse(512)
        if (!owned && (s.timeCritical || s.passive)) return SstpResponse(409)
        val refs = req.references
        val script = when (req.method) {
            "SEND", "NOTIFY" -> {
                val event = req["Event"]?.trim()?.takeIf { it.isNotEmpty() }
                val answered = when {
                    event == null -> null
                    "notify" in req.options -> {
                        notify(event, refs)
                        null
                    }
                    else -> request(event, refs, translate = "notranslate" !in req.options)
                }
                answered ?: sstpScript(req)
            }
            "COMMUNICATE" -> request("OnCommunicate", listOf(sender, req["Sentence"].orEmpty()) + refs)
            "GIVE" -> when {
                req["Document"] != null -> request("OnCommunicate", listOf(sender, req["Document"].orEmpty()))
                req["Song"] != null -> request("OnMusicPlay", listOf(req["Song"].orEmpty()))
                else -> return SstpResponse(400)
            }
            else -> return SstpResponse(501)
        } ?: return SstpResponse(204)
        if ("nobreak" in req.options) withTimeoutOrNull(SSTP_NOBREAK_WAIT_MS) { _state.first { !it.talking && !it.waitingChoice } }
        cancelScript()
        runScript(script, untrusted = !owned)
        sstpRun = scriptRuns
        if (!owned) lastSstpSender = sender
        return SstpResponse(200, listOf("Script" to script))
    }

    /** IfGhost の後の Script はそのゴーストだけ、IfGhost より前の Script はほかのゴースト向け（さくら・エミリ宛てもほかのゴースト向け） */
    private fun sstpScript(req: SstpRequest): String? {
        var pending: String? = null
        var fallback: String? = null
        for ((name, value) in req.headers) {
            when {
                name.equals("IfGhost", ignoreCase = true) -> pending = value
                name.equals("Script", ignoreCase = true) -> {
                    val forGhost = pending
                    pending = null
                    if (forGhost == null) {
                        if (fallback == null) fallback = value
                        continue
                    }
                    val names = forGhost.split(',').map { it.trim() }
                    val match = names[0] == ghost.sakuraName && (names.size < 2 || names[1] == ghost.keroName)
                    if (match) return value
                    if (names[0] in DEFAULT_GHOST_NAMES && fallback == null) fallback = value
                }
            }
        }
        return fallback
    }

    private suspend fun sstpExecute(req: SstpRequest, sender: String, owned: Boolean): SstpResponse {
        val raw = req["Command"]?.trim() ?: return SstpResponse(400)
        val command = raw.substringBefore('[').trim()
        val params = if ('[' in raw) raw.substringAfter('[').substringBeforeLast(']').split(',').map { it.trim() } else req.references
        fun data(text: String?) = if (text.isNullOrEmpty()) SstpResponse(204) else SstpResponse(200, data = text)
        fun lines(items: List<String>) = SstpResponse(200, data = items.joinToString("") { "$it\r\n" })
        return when (command.lowercase()) {
            "getname" -> data("${ghost.sakuraName},${ghost.keroName}")
            "getnames" -> lines(repository?.list()?.map { it.sakuraName } ?: listOf(ghost.sakuraName))
            "getghostname" -> data(ghost.name)
            "getshellname" -> data(shell.name)
            "getballoonname" -> data(balloon?.name)
            "getversion" -> data("${ShioriSession.SENDER}/$basewareVersion")
            "getshortversion" -> data(basewareVersion.takeWhile { it.isDigit() || it == '.' })
            "getghostnamelist" -> lines(repository?.list()?.map { it.name } ?: listOf(ghost.name))
            "getshellnamelist" -> lines(ghost.shellDirs.map { runCatching { Shell(it).name }.getOrDefault(it.name) })
            "getballoonnamelist" -> lines(repository?.balloons?.list()?.map { it.name }.orEmpty())
            "getheadlinenamelist", "getpluginnamelist" -> SstpResponse(204)
            "getstatus" -> data(property("currentghost.status"))
            "quiet" -> {
                quietUntil = System.currentTimeMillis() + SSTP_QUIET_MS
                SstpResponse(204)
            }
            "restore" -> {
                quietUntil = 0
                SstpResponse(204)
            }
            "getproperty" -> data(params.firstOrNull()?.let(::property))
            "setcookie" -> {
                val name = params.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: return SstpResponse(400)
                val cookies = sstpCookies.getOrPut(sender) { HashMap() }
                if (cookies.size >= MAX_SSTP_COOKIES && name !in cookies) return SstpResponse(413)
                cookies[name] = params.getOrNull(1).orEmpty().take(MAX_SSTP_COOKIE_LENGTH)
                SstpResponse(204)
            }
            "getcookie" -> data(sstpCookies[sender]?.get(params.getOrNull(0).orEmpty()))
            "settrayballoon" -> if (Host(mutableListOf()).setOption("trayballoon", params)) SstpResponse(204) else SstpResponse(400)
            "settrayicon" -> if (Host(mutableListOf()).setOption("tasktrayicon", params)) SstpResponse(204) else SstpResponse(400)
            // ゴーストのファイルや状態を変えるものは、Owned SSTP（ゴースト自身）からだけ
            "setproperty", "moveasync", "dumpsurface", "compressarchive", "extractarchive" -> if (!owned) {
                SstpResponse(420)
            } else {
                when (command.lowercase()) {
                    "setproperty" -> if (setProperty(params.getOrNull(0).orEmpty(), params.getOrNull(1).orEmpty())) SstpResponse(204) else SstpResponse(400)
                    "moveasync" -> {
                        runScript("\\![moveasync," + params.joinToString(",") { quote(it) } + "]")
                        SstpResponse(204)
                    }
                    "dumpsurface" -> when (val r = dumpSurface(params)) {
                        is Job -> {
                            r.join()
                            SstpResponse(204)
                        }
                        false -> SstpResponse(400)
                        else -> SstpResponse(204)
                    }
                    else -> {
                        archive(command.lowercase() == "compressarchive", params)?.join()
                        SstpResponse(204)
                    }
                }
            }
            else -> SstpResponse(501)
        }
    }

    /** %lastghostname / %lastobjectname */
    private var lastInstalledGhost: String? = null
    private var lastInstalledObject: String? = null

    /** \![set,trayballoon] の通知に触れた（OnTrayBalloonClick）。何行かあれば 0x01 でつなぐ */
    fun onTrayBalloonClick(title: String, text: String) = raise("OnTrayBalloonClick", listOf(title, text.replace('\n', '\u0001')))

    /** \![set,trayballoon] の通知を消した（OnTrayBalloonTimeout） */
    fun onTrayBalloonTimeout(title: String, text: String) =
        raise("OnTrayBalloonTimeout", listOf(title, text.replace('\n', '\u0001')), userInitiated = false)

    // ---- 音と読み上げ ----

    /** 鳴らした音のファイルと、ゴーストが書いた名前（OnSoundStop などの Reference0） */
    private val soundNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun soundName(file: File) = soundNames[file.path] ?: file.name

    /** 音が鳴り終わった（OnSoundStop、Reference1 = end） */
    fun onSoundStopped(file: File) = raise("OnSoundStop", listOf(soundName(file), "end"), userInitiated = false)

    /** ループの音が最後まで鳴り、頭に戻る（OnSoundLoop） */
    fun onSoundLooped(file: File) = raise("OnSoundLoop", listOf(soundName(file)), userInitiated = false)

    /** 音を鳴らせなかった（OnSoundError）。[code] は Android の再生のエラーの番号 */
    fun onSoundError(file: File, code: Int, message: String) =
        raise("OnSoundError", listOf("play", code.toString(), soundName(file), message), userInitiated = false)

    // ---- メールの確認（\![biff]、OnBIFFBegin / Complete / Failure、OnBIFF2Complete） ----

    /** アカウントごとの、前に確かめたときの数（増えたら OnBIFF2Complete） */
    private val mailCounts = HashMap<String, Int>()
    private var mailChecking = false
    private var lastMailCheck = 0L

    private fun checkMailPeriodically() {
        val minutes = platform.mailCheckMinutes()
        if (minutes <= 0 || _state.value.passive) return
        if (System.currentTimeMillis() - lastMailCheck < minutes * 60_000L) return
        checkMail(null)
    }

    /**
     * メールを確かめる（[account] が null なら全部）。受信箱の数と大きさ、新しいものの差出人と件名を OnBIFFComplete で伝え、
     * 答えが無くて前より増えていれば OnBIFF2Complete。メールの中身は読まない・消さない
     */
    fun checkMail(account: String?) {
        val accounts = platform.mailAccounts().filter { account == null || it.name == account }
        if (accounts.isEmpty() || mailChecking || closing) return platform.log("確かめるメールのアカウントがありません")
        mailChecking = true
        lastMailCheck = System.currentTimeMillis()
        scope.launch {
            try {
                for (a in accounts) checkOne(a)
            } finally {
                mailChecking = false
            }
        }
    }

    private suspend fun checkOne(a: MailAccount) {
        raise("OnBIFFBegin", listOf("", "", a.name), userInitiated = false)
        when (val r = withContext(Dispatchers.IO) { runCatching { mailChecker.check(a) }.getOrElse { MailResult.Failure("defect") } }) {
            is MailResult.Failure -> deliverLater("OnBIFFFailure", listOf(r.reason, "", a.name))
            is MailResult.Ok -> {
                val before = mailCounts[a.name]
                mailCounts[a.name] = r.count
                fun clean(s: String) = s.replace('\u0001', ' ').replace('\u0002', ' ').replace('\r', ' ').replace('\n', ' ')
                val top = r.items.joinToString("\u0002") { m -> m.headers.joinToString("\u0001") { clean(it) } }
                val list = r.items.joinToString("\u0001") { "${it.id} ${it.size}" }
                val uidl = r.items.joinToString("\u0001") { "${it.id} ${it.uid}" }
                val senders = r.items.joinToString("\u0001") { "${clean(it.from)}\u0001${clean(it.subject)}" }
                val diff = (r.count - (before ?: r.count)).toString()
                val refs = listOf(r.count.toString(), r.bytes.toString(), a.name, diff, top, list, uidl, senders)
                _state.first { !it.talking && !it.waitingChoice }
                val script = request("OnBIFFComplete", refs)
                when {
                    script != null -> runScript(script)
                    before != null && r.count > before ->
                        request("OnBIFF2Complete", listOf(r.count.toString(), r.bytes.toString(), a.name, top))?.let { runScript(it) }
                }
            }
        }
    }

    // ---- 声を聞く（OnVoiceRecognitionWord） ----

    /** 名前を呼ばれたキャラクターと、その時刻。呼ばれてからしばらくの言葉は、そのキャラクターへのもの */
    private var voiceCalled: Pair<Int, Long>? = null

    /** キャラクターの名前（呼びかけを見分けるため）。長い名前から順に */
    private fun characterNames(): List<Pair<Int, String>> = characterScopes.mapNotNull { s ->
        val name = when (s) {
            0 -> ghost.sakuraName
            1 -> ghost.keroName
            else -> ghost.descript["char$s.name"].orEmpty()
        }.trim()
        name.takeIf { it.isNotEmpty() }?.let { s to it }
    }.sortedByDescending { it.second.length }

    /**
     * 声を聞き取った（[text]）。キャラクターの名前で呼びかけたら OnVoiceRecognitionWord（Reference1 は名前そのもの）、
     * 名前に続けて言ったことや、呼んでから [VOICE_FOLLOW_MS] のうちに言ったことは、そのキャラクターへの言葉として送る。
     * 誰にも呼びかけていない話し声は送らない（まわりの会話にいちいち答えないように）
     */
    fun onVoiceRecognized(text: String) {
        val heard = text.trim().take(MAX_VOICE_TEXT)
        if (heard.isEmpty() || closing) return
        val now = System.currentTimeMillis()
        val called = characterNames().firstOrNull { (_, name) -> heard.startsWith(name) }
        if (called != null) {
            val (scope, name) = called
            voiceCalled = scope to now
            val rest = heard.removePrefix(name).trimStart(' ', '　', '、', ',', '!', '！')
            raise("OnVoiceRecognitionWord", listOf(scope.toString(), name))
            if (rest.isNotEmpty()) raise("OnVoiceRecognitionWord", listOf(scope.toString(), rest))
            return
        }
        val (scope, at) = voiceCalled ?: return
        if (now - at > VOICE_FOLLOW_MS) return
        voiceCalled = scope to now
        raise("OnVoiceRecognitionWord", listOf(scope.toString(), heard))
    }

    /** 声を聞く設定が変わった（OnVoiceRecognitionStatus） */
    fun onVoiceRecognitionChanged(enabled: Boolean) =
        raise("OnVoiceRecognitionStatus", listOf(if (enabled) "1" else "0"), userInitiated = false)

    /** 読み上げの設定が変わった（OnSpeechSynthesisStatus） */
    fun onSpeechSettingChanged(enabled: Boolean) = raise("OnSpeechSynthesisStatus", listOf(if (enabled) "1" else "0"), userInitiated = false)

    private val _backlog = ArrayDeque<BacklogEntry>()

    /** 発言履歴（新しいものが後ろ）。\__v[disable] の間に話したものは入らない */
    val backlog: List<BacklogEntry> get() = synchronized(_backlog) { _backlog.toList() }

    private fun addBacklog(scope: Int, text: String) = synchronized(_backlog) {
        _backlog.addLast(BacklogEntry(System.currentTimeMillis(), scope, text))
        while (_backlog.size > MAX_BACKLOG) _backlog.removeFirst()
    }

    /** \![reload,shell] / \![reloadsurface]。今のシェルをファイルから読み直す（今のサーフェスとイベントはそのまま） */
    private fun reloadShell() {
        scope.launch {
            val next = runCatching { withContext(Dispatchers.Default) { Shell(shell.dir) } }.getOrNull()
                ?: return@launch platform.log("シェルを読み直せません: ${shell.dir.name}")
            shell = next
            platform.onShellChanged(next)
        }
    }

    /** \![reload,balloon]。今のバルーンをファイルから読み直す */
    private fun reloadBalloon() {
        val current = balloon ?: return
        balloon = runCatching { Balloon(current.dir) }.getOrNull() ?: return platform.log("バルーンを読み直せません: ${current.dir.name}")
        platform.onBalloonChanged(balloon)
    }

    private suspend fun reloadShiori() {
        requestLock.withLock {
            session?.unload()
            session = null
        }
        loadShiori()
    }

    // ---- \![execute,http-*] / rss-* / ical-* ----

    /** 通信中のもの（URL ごと）。同じ URL は重ねて取りに行かない。\![cancel,http,URL] で止める */
    private val httpJobs = HashMap<String, Job>()

    /** 通信している数。オンラインの印を出す（--nodescript のものは数えない） */
    private var httpShowing = 0
        set(value) {
            field = value
            refreshOnline()
        }

    /**
     * SSP の \![execute,http-get,URL,--async=ID,--file=名前,...] など。取ってきた中身は ghost/master/var/名前 に保存するか
     * （--file が無ければ URL の最後の名前）、--nofile なら Reference3 にそのまま入れる。rss / ical は読んで項目を渡す。
     * 結果のイベントは、話している途中なら話し終わるのを待ってから送る。返す Job は通信が終わるまで（--async でなければスクリプトが待つ）
     */
    private fun executeHttp(cmd: HttpCommand): Job {
        val method = cmd.method.lowercase()
        val url = cmd.url
        fun failureRefs(reason: String) = listOf(method, cmd.id, url, "", reason, "", "")
        if (url in httpJobs) {
            return scope.launch { deliverLater(cmd.event("Failure"), failureRefs("downloading")) }
        }
        // 毎秒頼まれても際限なく通信しないよう、同時に走る数に上限を置く
        if (httpGetsInFlight >= MAX_HTTP_GETS) {
            return scope.launch { deliverLater(cmd.event("Failure"), failureRefs("busy")) }
        }
        if (cmd.insecure) platform.log("--insecure は使えません（証明書は確かめます）: $url")
        httpGetsInFlight++
        if (!cmd.nodescript) httpShowing++
        // 結果のイベントは、通信が終わってから話し終わるのを待って送る（--sync で待っているスクリプトとは別に）
        val job = scope.launch {
            val result = try {
                runHttp(cmd, ::failureRefs)
            } finally {
                httpGetsInFlight--
                if (!cmd.nodescript) httpShowing--
                if (httpJobs[url] === coroutineContext[Job]) httpJobs.remove(url)
            }
            scope.launch { deliverLater(result.first, result.second) }
        }
        httpJobs[url] = job
        httpCommands[url] = cmd
        job.invokeOnCompletion { if (httpJobs[url] == null) httpCommands.remove(url) }
        return job
    }

    /** 通信して、送るイベントとその Reference を返す */
    private suspend fun runHttp(cmd: HttpCommand, failureRefs: (String) -> List<String>): Pair<String, List<String>> {
        val method = cmd.method.lowercase()
        val input = cmd.paramInputFile?.let { name ->
            ghostFile(name)?.takeIf { it.length() <= HTTP_BODY_MAX_BYTES }?.readBytes()
                ?: return cmd.event("Failure") to failureRefs("fileio")
        }
        val body = cmd.body(input)
        val headers = cmd.headers + listOfNotNull(body?.let { "Content-Type" to it.second })
            .filter { (k, _) -> cmd.headers.none { it.first.equals(k, true) } }
        val request = HttpRequest(cmd.method, cmd.requestUrl(), headers, body?.first, cmd.timeoutSec * 1000)
        // 保存先。--nofile と rss / ical はメモリに受け取る（rss / ical は --file があればファイルにも）
        val memory = if (cmd.nofile != null || cmd.kind != "http") java.io.ByteArrayOutputStream() else null
        val name = cmd.file?.let(NetworkUpdater::safeFileName) ?: if (memory == null) NetworkUpdater.safeFileName(urlFileName(cmd.url)) else null
        if (memory == null && name == null) return cmd.event("Failure") to failureRefs("fileio")
        val file = name?.let { File(ghost.masterDir, "var/$it") }
        val part = file?.let { File(it.path + ".part") }
        var lastProgress = 0L
        var cookies = ""
        var headerText = ""
        var code = 0
        val streamer = cmd.streaming?.let { HttpStreamer(it) { data -> raise(cmd.event("Streaming"), listOf(method, cmd.id, cmd.url, "", data, cookies, headerText), userInitiated = false) } }
        val limit = when {
            cmd.nofile != null -> NOFILE_MAX_BYTES
            memory != null -> FEED_MAX_BYTES
            else -> HTTP_GET_MAX_BYTES
        }
        val outcome = try {
            file?.parentFile?.mkdirs()
            val fileOut = part?.outputStream()
            try {
                val sink = object : java.io.OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        memory?.write(b, off, len)
                        fileOut?.write(b, off, len)
                        streamer?.feed(b, off, len)
                    }
                }
                watchNetwork {
                    withContext(Dispatchers.IO) {
                        httpClient.execute(
                            request, sink, limit,
                            onResponse = { c, h ->
                                code = c
                                cookies = h.filter { it.first.equals("Set-Cookie", true) }.joinToString("; ") { it.second.substringBefore(';').trim() }
                                headerText = h.joinToString("\u0001") { "${it.first}: ${it.second}" }
                                streamer?.contentType(h.firstOrNull { it.first.equals("Content-Type", true) }?.second.orEmpty())
                            },
                            onProgress = { bytes, total ->
                                val now = System.currentTimeMillis()
                                if (cmd.progress && now - lastProgress >= PROGRESS_INTERVAL_MS) {
                                    lastProgress = now
                                    val progressEvent = if (cmd.kind == "ical" && !cmd.id.startsWith("On")) "OnExecuteICalProgress" else cmd.event("Progress")
                                    raise(progressEvent, listOf(method, cmd.id, cmd.url, file?.absolutePath.orEmpty(), code.toString(), "$bytes/$total", ""), userInitiated = false)
                                }
                            },
                        )
                    }
                }
            } finally {
                fileOut?.close()
            }
        } catch (e: IOException) {
            HttpOutcome.Failed("fileio")
        }
        streamer?.finish()
        val tls = when (outcome) {
            is HttpOutcome.Response -> outcome.tls
            is HttpOutcome.Failed -> outcome.tls
        }
        // SSL の様子は、取ってこられなくてもつながっていれば送る（イベント名は ID で変わらない）
        tls?.let { t ->
            val sslEvent = when (cmd.kind) {
                "rss" -> "OnExecuteRSS_SSLInfo"
                "ical" -> "OnExecuteICal_SSLInfo"
                else -> "OnExecuteHTTPSSLInfo"
            }
            notify(sslEvent, listOf(cmd.id, cmd.url, code.toString(), t.protocol, t.cipher, t.subject, t.notBefore, t.notAfter, t.issuer))
        }
        if (outcome is HttpOutcome.Failed) {
            part?.delete()
            return cmd.event("Failure") to failureRefs(outcome.reason)
        }
        outcome as HttpOutcome.Response
        if (outcome.code !in 200..299) {
            part?.delete()
            return cmd.event("Failure") to listOf(method, cmd.id, cmd.url, "", outcome.code.toString(), cookies, headerText)
        }
        if (file != null && part != null) {
            if (!part.renameTo(file)) runCatching { part.copyTo(file, overwrite = true) }
            part.delete()
        }
        val bytes = memory?.toByteArray()
        return when (cmd.kind) {
            "rss" -> {
                val items = bytes?.let(FeedParser::parse) ?: return cmd.event("Failure") to failureRefs("parse")
                cmd.event("") to FeedParser.filter(items, cmd.from, cmd.to, cmd.limit).map { it.reference() }
            }
            "ical" -> {
                val cal = bytes?.let { ICal.parse(TextCharsets.decode(it)) } ?: return cmd.event("Failure") to failureRefs("parse")
                cmd.event("") to ICal.references(cal, ICal.range(cmd.from, cmd.to, cmd.expandDays, cmd.expand, cmd.limit))
            }
            else -> {
                // --nofile なら中身（改行はバイト値1）、そうでなければ保存したファイルのフルパス
                val content = cmd.nofile?.let { cs -> String(bytes ?: ByteArray(0), cs).replace("\r\n", "\u0001").replace('\n', '\u0001').replace('\r', '\u0001') }
                    ?: file?.absolutePath.orEmpty()
                cmd.event("") to listOf(method, cmd.id, cmd.url, content, outcome.code.toString(), cookies, headerText)
            }
        }
    }

    // ---- WebSocket（\![execute,websocket] / \![send,websocket] / \![close,websocket] / \![cancel,websocket]） ----

    private class Socket(val url: String, val id: String, val job: Job) {
        @Volatile var session: WebSocketSession? = null
        @Volatile var closing = false
    }

    private val sockets = HashMap<String, Socket>()

    /** SSP のイベント名。ID が On で始まれば、受信は ID、そのほかは ID の後ろに Open などを付けた名前 */
    private fun socketEvent(id: String, kind: String): String =
        if (id.startsWith("On")) id + (if (kind == "Receive") "" else kind) else "OnExecuteWebSocket$kind"

    /**
     * WebSocket でつなぎ、受け取るたびに OnExecuteWebSocketReceive を送る。エラーで切れたら5回までつなぎ直す
     * （成功すれば Reconnect、全部だめなら Failure）。いつも待たずに続ける（--sync は使えない）
     */
    private fun openWebSocket(url: String, options: List<String>): Boolean {
        val u = url.trim()
        if (!u.startsWith("ws://") && !u.startsWith("wss://")) return false
        val opts = options.map { it.trim() }.filter { it.startsWith("--") }.associate { it.substring(2).substringBefore('=').lowercase() to it.substringAfter('=', "") }
        val id = opts["async"] ?: opts["sync"] ?: ""
        if (u in sockets) {
            raise(socketEvent(id, "Failure"), listOf(id, u, "already connected"), userInitiated = false)
            return true
        }
        if (sockets.size >= MAX_WEBSOCKETS) {
            raise(socketEvent(id, "Failure"), listOf(id, u, "busy"), userInitiated = false)
            return true
        }
        val headers = options.map { it.trim() }.filter { it.startsWith("--header=") }.mapNotNull { h ->
            val v = h.removePrefix("--header=")
            v.substringBefore(':').trim().takeIf { ':' in v && it.isNotEmpty() && '\r' !in v && '\n' !in v }?.let { it to v.substringAfter(':').trim() }
        } + listOfNotNull(opts["authorization"]?.let { "Authorization" to it }, opts["cookie"]?.let { "Cookie" to it })
        val protocol = opts["m_websocketprotocol"]
        lateinit var socket: Socket
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var attempt = 0
            var opened = false
            try {
                while (true) {
                    val session = try {
                        withContext(Dispatchers.IO) { webSocketConnector.open(u, headers, protocol) }
                    } catch (e: IOException) {
                        if (!opened) {
                            raise(socketEvent(id, "Failure"), listOf(id, u, e.message ?: "error"), userInitiated = false)
                            return@launch
                        }
                        // 切れた後のつなぎ直し（だんだん間を空ける）
                        if (++attempt > MAX_WEBSOCKET_RETRIES) {
                            raise(socketEvent(id, "Failure"), listOf(id, u, "reconnect failed"), userInitiated = false)
                            return@launch
                        }
                        delay(1000L shl (attempt - 1))
                        continue
                    }
                    socket.session = session
                    if (!opened) {
                        session.tls?.let { t -> notify("OnExecuteWebSocket_SSLInfo", listOf(id, u, "101", t.protocol, t.cipher, t.subject, t.notBefore, t.notAfter, t.issuer)) }
                        raise(socketEvent(id, "Open"), listOf(id, u, "200"), userInitiated = false)
                        opened = true
                    } else {
                        raise(socketEvent(id, "Reconnect"), listOf(id, u, "200"), userInitiated = false)
                    }
                    attempt = 0
                    val ended = try {
                        while (true) {
                            val m = withContext(Dispatchers.IO) { session.read() } ?: break
                            val data = if (m.binary) java.util.Base64.getEncoder().encodeToString(m.data) else
                                String(m.data, Charsets.UTF_8).replace("\r\n", "\u0001").replace('\n', '\u0001')
                            raise(socketEvent(id, "Receive"), listOf(id, u, if (m.binary) "2" else "1", data), userInitiated = false)
                        }
                        true
                    } catch (e: IOException) {
                        false
                    }
                    // 相手が閉じた・こちらが閉じたなら終わり。エラーで切れたらつなぎ直す
                    if (ended || socket.closing) {
                        raise(socketEvent(id, "Close"), listOf(id, u, (session.closeCode ?: 1000).toString()), userInitiated = false)
                        return@launch
                    }
                    if (++attempt > MAX_WEBSOCKET_RETRIES) {
                        raise(socketEvent(id, "Failure"), listOf(id, u, "reconnect failed"), userInitiated = false)
                        return@launch
                    }
                    delay(1000L shl (attempt - 1))
                }
            } finally {
                socket.session?.abort()
                if (sockets[u] === socket) sockets.remove(u)
            }
        }
        socket = Socket(u, id, job)
        sockets[u] = socket
        job.start()
        return true
    }

    /** \![send,websocket,URL,data1,data2...]（CRLF でつなぐ）と \![send,websocket-binary,URL,base64]。つながっていなければ何もしない */
    private fun sendWebSocket(binary: Boolean, url: String, data: List<String>) {
        val session = sockets[url.trim()]?.session ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (binary) session.sendBinary(java.util.Base64.getDecoder().decode(data.firstOrNull().orEmpty().trim()))
                else session.sendText(data.joinToString("\r\n"))
            }
        }
    }

    /** \![close,websocket,URL,code]。Close を送り、相手の Close を待ってから OnExecuteWebSocketClose */
    private fun closeWebSocket(url: String, code: Int) {
        val socket = sockets[url.trim()] ?: return
        socket.closing = true
        scope.launch(Dispatchers.IO) { socket.session?.close(code) }
    }

    /** \![cancel,websocket,URL]。Close を送らずに切り、OnExecuteWebSocketClose（userbreak） */
    private fun cancelWebSocket(url: String): Boolean {
        val socket = sockets.remove(url.trim()) ?: return false
        socket.job.cancel()
        socket.session?.abort()
        raise(socketEvent(socket.id, "Close"), listOf(socket.id, socket.url, "userbreak"), userInitiated = false)
        return true
    }

    // ---- ping / nslookup ----

    /** \![execute,ping,--host=,--count=,--timeout=,--event=]。1回ごとに OnPingProgress、最後に OnPingComplete */
    private fun ping(options: Map<String, String>): Boolean {
        val host = options["host"]?.takeIf { it.isNotBlank() } ?: return false
        val event = options["event"].orEmpty()
        val count = options["count"]?.toIntOrNull()?.coerceIn(1, MAX_PINGS) ?: 3
        val timeout = options["timeout"]?.toIntOrNull()?.coerceIn(100, 30_000) ?: 5_000
        val complete = if (event.startsWith("On")) event else "OnPingComplete"
        val progress = if (event.startsWith("On")) event + "Progress" else "OnPingProgress"
        scope.launch {
            val replies = mutableListOf<NetTools.PingReply>()
            val address = withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(host) }.getOrNull() }
            val source = withContext(Dispatchers.IO) { runCatching { InetAddress.getLocalHost().hostAddress }.getOrNull() }.orEmpty()
            fun refs(): List<String> {
                val ok = replies.count { it.ok }
                return listOf(event, listOf(source, replies.size, ok, replies.size - ok).joinToString("\u0001")) +
                    replies.map { listOf(if (it.ok) "OK" else it.error, it.address, it.rttMs).joinToString("\u0001") }
            }
            repeat(count) {
                replies += address?.let { a -> withContext(Dispatchers.IO) { NetTools.ping(a, timeout) } } ?: NetTools.PingReply(false, host, 0, "unknown host")
                if (replies.size < count) {
                    raise(progress, refs(), userInitiated = false)
                    delay(1000)
                }
            }
            deliverLater(complete, refs())
        }
        return true
    }

    /** \![execute,nslookup,--host=,--event=]。名前なら IP（複数はカンマ区切り）、IP なら名前を調べる */
    private fun nslookup(options: Map<String, String>): Boolean {
        val host = options["host"]?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val event = options["event"].orEmpty()
        val reverse = NetTools.isAddress(host)
        val mode = if (reverse) "reverse" else "lookup"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (reverse) InetAddress.getByName(host).canonicalHostName.takeIf { it != host } else InetAddress.getAllByName(host).joinToString(",") { it.hostAddress.orEmpty() }
                }.getOrNull()
            }
            if (result.isNullOrEmpty()) {
                deliverLater(if (event.startsWith("On")) event + "Failure" else "OnNSLookupFailure", listOf(event, host, mode))
            } else {
                deliverLater(if (event.startsWith("On")) event else "OnNSLookupComplete", listOf(event, host, mode, result))
            }
        }
        return true
    }

    // ---- ファイルの見張り（\![execute,filewatch] / \![cancel,filewatch]） ----

    private val watches = HashMap<String, Job>()

    /** ゴーストのフォルダの中のパス（ghost/master からの相対か、絶対パス）。外を指していれば null */
    private fun ghostPath(name: String): File? {
        val raw = name.trim().replace('\\', '/')
        if (raw.isEmpty()) return null
        val file = if (raw.startsWith("/")) File(raw) else File(ghost.masterDir, raw)
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val root = ghost.dir.canonicalFile
        return canonical.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    private fun fileWatch(path: String, options: Map<String, String>): Boolean {
        val id = options["async"].orEmpty()
        val change = if (id.startsWith("On")) id else "OnExecuteFileWatchChange"
        val failure = if (id.startsWith("On")) id + "Failure" else "OnExecuteFileWatchFailure"
        val target = ghostPath(path)
        when {
            path.isBlank() -> raise(failure, listOf(id, path, "invalid"), userInitiated = false)
            // ゴーストのフォルダの外は見張らない
            target == null -> raise(failure, listOf(id, path, "notauthorized"), userInitiated = false)
            !target.isDirectory && target.parentFile?.isDirectory != true -> raise(failure, listOf(id, path, "notfound"), userInitiated = false)
            path !in watches && watches.size >= MAX_WATCHES -> raise(failure, listOf(id, path, "limit"), userInitiated = false)
            else -> {
                watches.remove(path)?.cancel()
                val debounce = options["debounce"]?.toLongOrNull()?.coerceIn(0, 60_000) ?: 500
                val watch = FileWatch(target, debounce)
                watches[path] = scope.launch {
                    while (true) {
                        delay(WATCH_INTERVAL_MS)
                        val kind = withContext(Dispatchers.IO) { watch.poll(System.currentTimeMillis()) } ?: continue
                        if (kind == "failed") {
                            raise(failure, listOf(id, path, "notfound"), userInitiated = false)
                            break
                        }
                        raise(change, listOf(id, path, kind, target.absolutePath), userInitiated = false)
                        if (kind == "deleted" && target.isDirectory.not() && watch.target.parentFile?.isDirectory != true) break
                    }
                    watches.remove(path)
                }
            }
        }
        return true
    }

    private fun cancelFileWatch(path: String): Boolean {
        watches.remove(path.trim())?.cancel() ?: return false
        return true
    }

    // ---- ZIP（\![execute,extractarchive / compressarchive]） ----

    /** 待つ（--event が無い）なら終わるまでの Job を返す */
    // ---- 開発のための書き出し（\![execute,createnar / createupdatedata / dumpsurface / dumpballoon]） ----

    /** "--名前=値" のオプションと、それ以外の引数（現れた順） */
    private fun splitArgs(args: List<String>): Pair<List<String>, Map<String, String>> {
        val (opts, positional) = args.map { it.trim() }.partition { it.startsWith("--") }
        return positional to opts.associate { it.substring(2).substringBefore('=').lowercase() to it.substringAfter('=', "") }
    }

    /**
     * ゴースト自身の .nar を作る（OnNarCreating → OnNarCreated）。出力先は ghost/master からの相対パスか絶対パスで、ゴーストの
     * フォルダの中だけ。省略すれば pono/nar/ディレクトリ名.nar に作り、Android の「共有」で保存や送り先を選んでもらう
     */
    private fun createNar(args: List<String>) {
        val given = args.map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val out = if (given != null) ghostPath(given) else File(ghost.dir, GhostPackager.PONO_DIR + "/nar/${ghost.dir.name}.nar")
        if (out == null || out.isDirectory) return platform.log("nar の出力先にできません: $given")
        scope.launch {
            val refs = listOf(ghost.name, out.absolutePath, "ghost")
            deliverLater("OnNarCreating", refs)
            withContext(Dispatchers.IO) { runCatching { GhostPackager(ghost.dir).createNar(out) } }
                .onSuccess {
                    deliverLater("OnNarCreated", refs)
                    if (given == null) platform.shareFile(out, "application/x-nar")
                }
                .onFailure { platform.log("nar を作れませんでした: ${it.message}") }
        }
    }

    /**
     * 更新の一覧を作る（OnUpdatedataCreating → OnUpdatedataCreated）。出力先を指定すればそこに1つだけ（.dau なら updates2.dau の形、
     * ほかは updates.txt の形）、省略すればゴーストのフォルダと ghost/master の updates2.dau・updates.txt
     */
    private fun createUpdateData(args: List<String>) {
        val given = args.map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val outputs = if (given != null) {
            listOf(ghostPath(given)?.takeIf { !it.isDirectory } ?: return platform.log("更新データの出力先にできません: $given"))
        } else {
            listOf(ghost.dir, ghost.masterDir).flatMap { listOf(File(it, "updates2.dau"), File(it, "updates.txt")) }
        }
        scope.launch {
            deliverLater("OnUpdatedataCreating", emptyList())
            withContext(Dispatchers.IO) { runCatching { GhostPackager(ghost.dir).createUpdateData(outputs) } }
                .onSuccess { deliverLater("OnUpdatedataCreated", emptyList()) }
                .onFailure { platform.log("更新データを作れませんでした: ${it.message}") }
        }
    }

    /** 書き出すファイル名の頭。フォルダの外に出られないよう、区切りや .. は使わせない */
    private fun dumpPrefix(raw: String?, default: String): String? {
        val p = raw?.trim().orEmpty().ifEmpty { default }
        return p.takeIf { it.none { c -> c == '/' || c == '\\' || c == ':' } && ".." !in it }
    }

    /**
     * 書き出すサーフェスの番号。"0-9,!5" のような書き方（surface を前に付けてもよい）か、__system_surface_all__（既定）・
     * __system_surface_defined__（surfaces.txt に書いたものだけ）
     */
    private fun dumpSurfaceIds(list: String): List<Int> {
        val all = shell.surfaceIds()
        val ids = when (list.trim().lowercase()) {
            "", "__system_surface_all__" -> all
            "__system_surface_defined__" -> shell.surfaces.definitions.keys.sorted()
            else -> {
                val include = linkedSetOf<Int>()
                val exclude = mutableSetOf<Int>()
                for (raw in list.split(',')) {
                    val token = raw.trim().lowercase()
                    val negated = token.startsWith("!")
                    val m = DUMP_RANGE.matchEntire(token.removePrefix("!").removePrefix("surface")) ?: continue
                    val from = m.groupValues[1].toIntOrNull() ?: continue
                    val to = m.groupValues[2].toIntOrNull() ?: from
                    if (maxOf(from, to).toLong() - minOf(from, to) > MAX_DUMP_SURFACES) continue
                    for (i in minOf(from, to)..maxOf(from, to)) (if (negated) exclude else include) += i
                }
                (include - exclude).toList()
            }
        }
        return ids.filter { shell.hasSurface(it) }.take(MAX_DUMP_SURFACES)
    }

    /**
     * サーフェスを PNG に書き出す。引数は ディレクトリ, スコープ, サーフェスの一覧, prefix, イベント, ゼロ位置切り出し, アニメーション ID
     * （--dir= などでもよい）。書き出せるのはゴーストのフォルダの中だけ。イベントがあれば終わってから（Reference0 = ファイルの数）。
     * イベントが無ければ返す Job が終わるまでスクリプトが待つ。引数がおかしければ false
     */
    private fun dumpSurface(args: List<String>): Any {
        val (positional, opts) = splitArgs(args)
        val dir = (opts["dir"] ?: positional.getOrNull(0))?.let(::ghostPath)
        val scopeId = (opts["scope"] ?: positional.getOrNull(1))?.trim()?.toIntOrNull() ?: 0
        val prefix = dumpPrefix(opts["prefix"] ?: positional.getOrNull(3), "surface")
        val event = opts["event"] ?: opts["async"] ?: positional.getOrNull(4).orEmpty()
        val animation = (opts["animation"] ?: positional.getOrNull(6))?.trim()?.toIntOrNull()
        if (dir == null || dir.isFile || prefix == null) {
            platform.log("サーフェスを書き出せる場所ではありません（ゴーストのフォルダの中だけ）")
            return false
        }
        val dump = SurfaceDump(
            dir, scopeId, dumpSurfaceIds(opts["surface"] ?: positional.getOrNull(2).orEmpty()), prefix,
            _state.value.characters[scopeId]?.binds.orEmpty(), animation,
        )
        val job = scope.launch {
            val count = runCatching {
                withContext(Dispatchers.IO) { dir.mkdirs() }
                platform.dumpSurfaces(dump)
            }.getOrDefault(0)
            if (event.isNotEmpty()) deliverLater(event, listOf(count.toString()))
        }
        return if (event.isEmpty()) job else true
    }

    /** バルーンの今の絵を書き出す（prefix + スコープ + .png、既定は balloon0.png）。引数は ディレクトリ, スコープ, prefix, イベント */
    private fun dumpBalloon(args: List<String>): Any {
        val (positional, opts) = splitArgs(args)
        val dir = (opts["dir"] ?: positional.getOrNull(0))?.let(::ghostPath)
        val scopeId = (opts["scope"] ?: positional.getOrNull(1))?.trim()?.toIntOrNull() ?: 0
        val prefix = dumpPrefix(opts["prefix"] ?: positional.getOrNull(2), "balloon")
        val event = opts["event"] ?: opts["async"] ?: positional.getOrNull(3).orEmpty()
        if (dir == null || dir.isFile || prefix == null) {
            platform.log("バルーンを書き出せる場所ではありません（ゴーストのフォルダの中だけ）")
            return false
        }
        val job = scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { dir.mkdirs() }
                platform.dumpBalloon(scopeId, File(dir, "$prefix$scopeId.png"))
            }.getOrDefault(false)
            if (event.isNotEmpty()) deliverLater(event, listOf(if (ok) "1" else "0"))
        }
        return if (event.isEmpty()) job else true
    }

    /**
     * \![set,wallpaper,ファイル] / \![set,wallpaper,--file=ファイル,--position=fit,--color=navy]。ファイルは ghost/master か
     * ゴーストのフォルダからの相対パス（ゴーストのフォルダの外は使わない）。ファイルも色も無ければ null
     */
    private fun wallpaperRequest(args: List<String>): WallpaperRequest? {
        val (positional, opts) = splitArgs(args)
        val name = opts["file"] ?: positional.firstOrNull()
        val root = ghost.dir.canonicalFile
        val file = name?.let { n ->
            val raw = n.trim().replace('\\', '/')
            listOf(File(ghost.masterDir, raw), File(ghost.dir, raw)).asSequence()
                .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
                .firstOrNull { it.path.startsWith(root.path + File.separator) && it.isFile }
        }
        if (name != null && file == null) {
            platform.log("壁紙の画像が見つかりません: $name")
            return null
        }
        val color = opts["color"]?.let { ScriptPlayer.parseColor(it.split(',', ' ').filter(String::isNotBlank)) }
        if (file == null && color == null) return null
        val position = opts["position"]?.lowercase()?.takeIf { it in WALLPAPER_POSITIONS } ?: "fill"
        return WallpaperRequest(file, position, color)
    }

    /** \![open,file,ファイル]。ゴーストのフォルダの中のファイルを、Android のほかのアプリで開く */
    private fun openGhostFile(name: String): Boolean {
        val file = ghostPath(name)?.takeIf { it.isFile } ?: return false
        val mime = mimeOf(file.name).takeIf { it != "application/octet-stream" } ?: "*/*"
        return platform.openFile(file, mime)
    }

    private fun archive(compress: Boolean, args: List<String>): Job? {
        val file = args.getOrNull(0)?.let(::ghostPath)
        val dir = args.getOrNull(1)?.let(::ghostPath)
        val opts = args.drop(2).map { it.trim() }.filter { it.startsWith("--") }.associate { it.substring(2).substringBefore('=').lowercase() to it.substringAfter('=', "") }
        val event = opts["event"].orEmpty()
        val base = if (compress) "OnCompressArchive" else "OnExtractArchive"
        val complete = if (event.startsWith("On")) event else base + "Complete"
        val failure = if (event.startsWith("On")) event + "Failure" else base + "Failure"
        val job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        // ゴーストのフォルダの外には書かない・外は読まない
                        file == null || dir == null -> throw IOException("notauthorized")
                        opts.containsKey("password") -> throw IOException("password not supported")
                        compress -> NetTools.compress(dir, file)
                        else -> NetTools.extract(file, dir)
                    }
                }
            }
            // 結果は、待っているスクリプトとは別に、話し終わってから送る
            scope.launch {
                result.onSuccess { r -> deliverLater(complete, listOf(event, r.files.toString(), r.compressed.toString(), r.uncompressed.toString())) }
                    .onFailure { e -> deliverLater(failure, listOf(event, e.message ?: "error")) }
            }
        }
        return if (event.isEmpty()) job else null
    }

    // ---- 時刻合わせ（\7 / \![executesntp]） ----

    /** SNTP でずれを測り、OnSNTPCompareEx（答えが無ければ OnSNTPCompare）を送る。端末の時計は直さない（\6 は何もしない） */
    private fun startSntp() {
        val server = SNTP_SERVER
        scope.launch {
            raise("OnSNTPBegin", listOf(server), userInitiated = false)
            val r = withContext(Dispatchers.IO) { runCatching { NetTools.sntp(server) }.getOrNull() }
            if (r == null) {
                deliverLater("OnSNTPFailure", listOf(server))
                return@launch
            }
            fun time(millis: Long, ms: Boolean): String {
                val c = Calendar.getInstance().apply { timeInMillis = millis }
                val base = "%d,%d,%d,%d,%d,%d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                    c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
                return if (ms) "$base,${c.get(Calendar.MILLISECOND)}" else base
            }
            val ex = listOf(server, time(r.serverTime, true), time(r.localTime, true), "%.3f".format(r.offsetMs / 1000.0), r.offsetMs.toString())
            _state.first { !it.talking && !it.waitingChoice }
            val script = request("OnSNTPCompareEx", ex)
                ?: request(
                    "OnSNTPCompare",
                    listOf(server, time(if (abs(r.offsetMs) < 1000) r.localTime else r.serverTime, false), time(r.localTime, false), (abs(r.offsetMs) / 1000).toString()),
                )
            if (script != null) {
                cancelScript()
                runScript(script)
            }
        }
    }

    // ---- インストール（.nar を入れたとき・\![execute,install]）と、文字や URL の共有 ----

    /**
     * .nar を入れる（アプリの画面・共有・\![execute,install,path]）。起動中のゴーストに OnInstallBegin を送り、
     * 済んだら OnInstallCompleteEx（答えが無ければ OnInstallComplete）と OnInstallCompleteAll、だめなら OnInstallFailure か
     * OnInstallRefuse（シェルなどが別のゴーストを名指ししていた）を送る。シェルと追加ファイルはこのゴーストに入れる
     */
    suspend fun installNar(nar: File): Result<NarInstaller.Result> {
        val installer = repository?.installer() ?: return Result.failure(IOException("ゴーストの置き場がありません"))
        raise("OnInstallBegin", emptyList(), userInitiated = false)
        val result = withContext(Dispatchers.IO) { runCatching { installer.install(nar, ghost) } }
        result.onSuccess { r ->
            val items = r.installed
            lastInstalledObject = items.firstOrNull()?.name
            items.firstOrNull { it.type == NarInstaller.Type.GHOST }?.let { lastInstalledGhost = it.name }
            val refs = listOf(
                items.joinToString("\u0001") { it.type.id },
                items.joinToString("\u0001") { it.name },
                items.joinToString("\u0001") { it.dir.absolutePath + "/" },
            )
            scope.launch {
                _state.first { !it.talking && !it.waitingChoice }
                val script = request("OnInstallCompleteEx", refs)
                    ?: request("OnInstallComplete", listOf(items[0].type.id, items[0].name, items.getOrNull(1)?.name.orEmpty()))
                if (script != null) {
                    cancelScript()
                    runScript(script).join()
                }
                raise("OnInstallCompleteAll", refs, userInitiated = false)
            }
        }.onFailure { e ->
            if (e is NarInstaller.RefusedException) {
                raise("OnInstallRefuse", listOf(e.accept, e.type, e.name), userInitiated = false)
            } else {
                raise("OnInstallFailure", listOf(installFailure(e)), userInitiated = false)
            }
        }
        return result
    }

    /** OnInstallFailure の理由（SSP の決まった言葉に近いもの） */
    private fun installFailure(e: Throwable): String = when {
        e.message?.contains("install.txt") == true -> "invalid"
        e.message?.contains("type=") == true -> "unsupported"
        e is IOException -> "extraction"
        else -> "unknown"
    }

    /**
     * \![execute,install,path,ファイル名] / \![execute,install,url,URL,nar]。ゴーストのフォルダの中のファイルか、URL から取ってきた .nar を入れる
     */
    private fun executeInstall(args: List<String>): Job? {
        val kind = args.firstOrNull()?.trim()?.lowercase()
        return when (kind) {
            "path" -> {
                val file = args.getOrNull(1)?.let(::ghostPath)?.takeIf { it.isFile } ?: run {
                    raise("OnInstallFailure", listOf("invalid"), userInitiated = false)
                    return null
                }
                scope.launch { installNar(file) }
            }
            "url" -> {
                val url = args.getOrNull(1)?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
                // feed（RSS で配られる .nar の一覧）は扱えない
                if (args.getOrNull(2)?.trim()?.lowercase() == "feed") {
                    raise("OnInstallFailure", listOf("unsupported"), userInitiated = false)
                    return null
                }
                scope.launch { downloadAndInstall(url, scopeId = 0, dropped = false) }
            }
            else -> null
        }
    }

    /**
     * URL の .nar を取ってきて入れる。[dropped] なら共有で渡された URL として、OnURLDropping / OnURLDropped / OnURLDropFailure も送る
     */
    private suspend fun downloadAndInstall(url: String, scopeId: Int, dropped: Boolean) {
        if (dropped) raise("OnURLDropping", listOf(url, scopeId.toString()), userInitiated = false)
        val file = File(FileDrop.dir(ghost), NetworkUpdater.safeFileName(url.substringBefore('?').substringAfterLast('/')) ?: "download.nar")
        file.parentFile?.mkdirs()
        val r = withContext(Dispatchers.IO) {
            runCatching { file.outputStream().use { updateFetcher.fetch(url, it, FileDrop.MAX_TOTAL_BYTES) } }.getOrElse { FetchResult.Failed("fileio") }
        }
        if (r != FetchResult.Ok) {
            if (dropped) raise("OnURLDropFailure", listOf(file.absolutePath, NetworkUpdater.describe(r), url, scopeId.toString()), userInitiated = false)
            else raise("OnInstallFailure", listOf("download"), userInitiated = false)
            file.delete()
            return
        }
        if (dropped) raise("OnURLDropped", listOf(file.absolutePath, url, scopeId.toString()), userInitiated = false)
        installNar(file)
        file.delete()
    }

    /**
     * Android の「共有」で文字を渡された。URL なら OnURLQuery を送り、ゴーストが答えなければ、.nar なら取ってきて入れる。
     * それ以外の文字（と、扱えない URL）は OnTextDrop（改行はバイト値1）
     */
    fun onTextShared(text: String, scopeId: Int = 0) {
        lastUserActivity = System.currentTimeMillis()
        val t = text.trim().take(MAX_SHARED_TEXT)
        val url = t.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && it.none { c -> c.isWhitespace() } }
        scope.launch {
            if (url != null) {
                val nar = url.substringBefore('?').lowercase().endsWith(".nar")
                val mime = if (nar) "application/x-nar" else "text/html"
                val script = request("OnURLQuery", listOf(url, scopeId.toString(), mime, if (nar) "nar" else "unknown"))
                if (script != null) {
                    cancelScript()
                    runScript(script)
                    return@launch
                }
                if (nar) {
                    downloadAndInstall(url, scopeId, dropped = true)
                    return@launch
                }
            }
            raise("OnTextDrop", listOf(t.replace("\r\n", "\u0001").replace('\n', '\u0001').replace('\r', '\u0001'), scopeId.toString()), userInitiated = false)
        }
    }

    /**
     * x-ukagaka-link:type=event&ghost=名前&info=文字 を開いた。このゴースト宛てなら OnXUkagakaLinkOpen（Reference0 は info）を送る。
     * 宛てでなければ false
     */
    fun onUkagakaLink(link: String): Boolean {
        val query = link.substringAfter(':', "").substringAfter('?')
        val params = query.split('&').associate { p ->
            p.substringBefore('=').lowercase() to runCatching { java.net.URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }
        if (params["type"]?.lowercase() != "event") return false
        val target = params["ghost"].orEmpty()
        if (target != ghost.name && target != ghost.sakuraName) return false
        raise("OnXUkagakaLinkOpen", listOf(params["info"].orEmpty()), userInitiated = false)
        return true
    }

    /**
     * 渡したファイルにゴーストが答えなかったとき（と \![execute,dropdefault,file]）の、SSP のいつもの扱い。
     * .nar なら入れ、画像なら画像を見るアプリ、音や動画なら再生するアプリで開いて OnPictureViewerOpen / OnMediaPlayerOpen を送る
     */
    private suspend fun dropDefault(paths: List<String>, mimeTypes: List<String>, scopeId: Int) {
        val first = paths.firstOrNull()?.let(::File) ?: return
        val mime = mimeTypes.firstOrNull().orEmpty()
        val refs = listOf(paths.joinToString("\u0001"), scopeId.toString(), mimeTypes.joinToString("\u0001"))
        when {
            first.name.lowercase().endsWith(".nar") -> installNar(first)
            mime.startsWith("image/") -> if (platform.openFile(first, mime)) raise("OnPictureViewerOpen", refs, userInitiated = false)
            mime.startsWith("audio/") || mime.startsWith("video/") -> if (platform.openFile(first, mime)) raise("OnMediaPlayerOpen", refs, userInitiated = false)
        }
    }

    /** URL の最後の部分（クエリは落とす）。無ければ index.html */
    private fun urlFileName(url: String): String =
        url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/').substringAfter("//").ifEmpty { "index.html" }

    /**
     * \![cancel,http,URL] など。止めたら true。通信は止まるまで少しかかることがあるので、
     * Failure（userbreak）はすぐに送り、後から来た結果は捨てる
     */
    private fun cancelHttp(url: String, cmdId: String = ""): Boolean {
        val job = httpJobs.remove(url.trim()) ?: return false
        job.cancel()
        val cmd = httpCommands.remove(url.trim())
        scope.launch {
            deliverLater(cmd?.event("Failure") ?: "OnExecuteHTTPFailure", listOf(cmd?.method?.lowercase().orEmpty(), cmd?.id ?: cmdId, url.trim(), "", "userbreak", "", ""))
        }
        return true
    }

    /** 通信中のものの頼み方（URL ごと。取り消したときのイベント名に使う） */
    private val httpCommands = HashMap<String, HttpCommand>()

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

    /**
     * 終わる。SSP でただ1体のゴーストを終えるときと同じく OnCloseAll を送り（答えが無ければ OnClose）、そのスクリプトが終わったら
     * SHIORI を閉じる。[reason] は user（メニューや通知から）か system（端末の電源を切るとき）、[scopeId] はメニューを開いたキャラクター
     */
    fun close(reason: String = "user", scopeId: Int = 0) {
        if (closing) return
        closing = true
        timerJob?.cancel()
        scope.launch {
            val script = request("OnCloseAll", listOf(reason, scopeId.toString(), scopeId.toString())) ?: request("OnClose", listOf(reason))
            if (script != null) {
                cancelScript()
                runScript(script).join()
            }
            shutdown()
        }
    }

    /** SHIORI を閉じて終わる。[switchTo] があれば、そのゴーストに切り替える。[uninstall] ならこのゴーストを消してから */
    private suspend fun shutdown(switchTo: Pair<Ghost, PreviousGhost>? = null, uninstall: Boolean = false) {
        timerJob?.cancel()
        // WebSocket とファイルの見張りはゴーストが終わるときにすべてやめる
        for (url in sockets.keys.toList()) cancelWebSocket(url)
        watches.values.forEach { it.cancel() }
        watches.clear()
        timers.values.forEach { it.cancel() }
        timers.clear()
        platform.stopSound()
        session?.unload()
        session = null
        if (uninstall && repository?.uninstall(ghost) != true) platform.log("${ghost.name} を消去できませんでした")
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

    /**
     * \![reload,ghost] / \![reload,descript]。今のスクリプトを話し終えたら、descript.txt・シェル・バルーン・SHIORI をすべて
     * 読み直して起動し直す（イベントは送らない）
     */
    private fun reloadGhost() {
        if (closing) return
        closing = true
        timerJob?.cancel()
        scope.launch {
            withTimeoutOrNull(CHANGE_WAIT_MS) { _state.first { !it.talking } }
            shutdown(ghost to PreviousGhost(ghost.sakuraName, "", ghost.name, ghost.dir.absolutePath + "/", reload = true))
        }
    }

    // ---- 消去（vanish） ----

    /** OnVanishSelecting を話している消去の手続き。そのあいだにバルーンをタップすると取りやめる（OnVanishButtonHold） */
    private var vanishJob: Job? = null

    /** \![vanishbymyself,ゴースト名] で指定した、消去したあとに切り替える先 */
    private var vanishNext: String? = null

    /**
     * 消去したあとに起こすゴースト（[vanishNext] があればそれ、無ければ並びで次のもの）。ほかにゴーストがいなければ、
     * 誰もいなくなるので消去できない
     */
    private fun ghostAfterVanish(): Ghost? {
        val self = ghost.dir.canonicalFile
        val ghosts = repository?.list().orEmpty()
        vanishNext?.let { name ->
            ghosts.firstOrNull { it.dir.canonicalFile != self && (it.name == name || it.sakuraName == name || it.dir.name == name) }?.let { return it }
        }
        val i = ghosts.indexOfFirst { it.dir.canonicalFile == self }
        return (ghosts.drop(i + 1) + ghosts.take(i.coerceAtLeast(0))).firstOrNull { it.dir.canonicalFile != self }
    }

    /** メニューに「消去」を出すか。ゴーストが vanishbuttonvisible を 0 にしていれば出さない（答えが無ければ出す） */
    private suspend fun vanishVisible(): Boolean = ghostAfterVanish() != null && resource("vanishbuttonvisible")?.trim() != "0"

    /**
     * メニューの「消去」。OnVanishSelecting で話してもらってから、本当に消去するかバルーンで確かめる
     * （はい → OnVanishSelected、いいえ → OnVanishCancel）。話している途中でバルーンをタップすると OnVanishButtonHold で取りやめる
     */
    fun requestVanish(scopeId: Int = 0) {
        if (closing || ghostAfterVanish() == null) return
        vanishJob?.cancel()
        vanishJob = scope.launch {
            val script = request("OnVanishSelecting", emptyList())
            if (script != null) {
                cancelScript()
                runScript(script)
                _state.first { !it.talking }
            }
            vanishJob = null
            confirmVanish(scopeId)
        }
    }

    private fun confirmVanish(scopeId: Int) {
        runScript(
            "\\p[$scopeId]\\_q" + literalScript(ghost.name) + " を消去しますか？\\n（ゴーストのファイルをすべて消します。元に戻せません）\\n\\n" +
                "\\q[消去する,pono:vanish-yes]\\n\\q[やめる,pono:vanish-no]\\e",
        )
    }

    /**
     * 消去する。[farewell] なら OnVanishSelected で別れを話してもらい、話し終えたらゴーストを消して次のゴーストに切り替える
     * （次のゴーストには OnVanished）。\![vanishbymyself] は確かめずにすぐ（今のスクリプトを話し終えてから）
     */
    private fun vanish(farewell: Boolean) {
        val next = ghostAfterVanish() ?: return platform.log("ほかにゴーストがいないので消去できません")
        if (closing) return
        closing = true
        timerJob?.cancel()
        scope.launch {
            val script = if (farewell) {
                request("OnVanishSelected", emptyList())?.also {
                    cancelScript()
                    runScript(it)
                }.orEmpty()
            } else {
                lastScript
            }
            withTimeoutOrNull(CHANGE_WAIT_MS) { _state.first { !it.talking } }
            shutdown(next to PreviousGhost(ghost.sakuraName, script, ghost.name, ghost.dir.absolutePath + "/", vanished = true), uninstall = true)
        }
    }

    /** \![vanishbymyself(,ゴースト名)(,--option=query)]。消去できるのは自分だけ。query なら先に確かめる */
    private fun vanishByMyself(args: List<String>) {
        // ゴースト名は、消去したあとに切り替える先（見つからなければいつもの次のゴースト）
        vanishNext = args.map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("--") }
        if (args.none { it.trim().lowercase() == "--option=query" }) return vanish(farewell = false)
        if (ghostAfterVanish() == null) return platform.log("ほかにゴーストがいないので消去できません")
        scope.launch {
            withTimeoutOrNull(CHANGE_WAIT_MS) { _state.first { !it.talking } }
            confirmVanish(0)
        }
    }

    // ---- 利用規約（\![open,terms]） ----

    private fun termsFile(): File? = File(ghost.masterDir, "terms.txt").takeIf { it.isFile } ?: File(ghost.dir, "terms.txt").takeIf { it.isFile }

    /**
     * ghost/master の terms.txt をバルーンに出し、同意（OnGhostTermsAccept）か拒否（OnGhostTermsDecline）を選んでもらう。
     * 読むのに時間がかかるので、選ぶまで待つ。terms.txt が無ければ false
     */
    private fun showTerms(): Boolean {
        val file = termsFile() ?: return false
        val text = runCatching { TextCharsets.decode(file.readBytes()) }.getOrNull() ?: return false
        scope.launch {
            withTimeoutOrNull(CHANGE_WAIT_MS) { _state.first { !it.talking } }
            runScript(
                "\\![set,choicetimeout,0]\\0\\_q" + literalScript(text) + "\\n\\n" +
                    "\\q[同意する,pono:terms,accept]\\n\\q[同意しない,pono:terms,decline]\\e",
            )
        }
        return true
    }

    /** ファイルの文字やゴースト名を、そのままバルーンに出すスクリプトにする（タグや %変数として読ませない） */
    private fun literalScript(text: String): String =
        text.replace("\r\n", "\n").trimEnd().split('\n').joinToString("\\n") { it.replace("\\", "\\\\").replace("%", "\\%") }

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
            // surfaces.txt の大きいシェルもあるので、読むのは画面を描くスレッドの外で。その間は読み込み中と出す
            _state.update { it.copy(loading = "シェルを読み込み中…") }
            val found = try {
                withContext(Dispatchers.Default) {
                    val dirs = ghost.shellDirs
                    val dir = when (name) {
                        "random" -> dirs.randomOrNull()
                        else -> dirs.firstOrNull { it.name == name } ?: dirs.firstOrNull { runCatching { Shell(it).name }.getOrNull() == name }
                    }
                    dir?.let { it to Shell(it) }
                }
            } finally {
                _state.update { it.copy(loading = null) }
            }
            val (dir, next) = found ?: return@launch platform.log("シェルがありません: $name")
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
        next?.let { history?.balloonUsed(it.dir.name) }
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

    /**
     * \f[name,...] の候補の1つ。フォントファイル（.ttf など）ならゴーストの ghost/master か、今のバルーンのフォルダの中で探し、
     * 見つかればその絶対パスにする。見つからないファイルは空にし、名前（serif など）はそのまま返す
     */
    private fun fontCandidate(name: String): String {
        val n = name.trim()
        if (n.substringAfterLast('.', "").lowercase() !in FONT_EXTENSIONS) return n
        ghostFile(n)?.let { return it.path }
        val dir = balloon?.dir?.canonicalFile ?: return ""
        val file = runCatching { File(dir, n.replace('\\', '/')).canonicalFile }.getOrNull() ?: return ""
        return file.takeIf { it.path.startsWith(dir.path + File.separator) && it.isFile }?.path.orEmpty()
    }

    // ---- ユーザー操作 ----

    private fun mouseReferences(scopeId: Int, x: Int, y: Int, collision: String?, button: Int) =
        listOf(x.toString(), y.toString(), "0", scopeId.toString(), collision.orEmpty(), button.toString(), "touch")

    /** 指が触れた。SSP の OnMouseDown（左ボタン） */
    fun onMouseDown(scopeId: Int, x: Int, y: Int, collision: String?) {
        if (touchBlocked()) return
        lastUserActivity = System.currentTimeMillis()
        raise("OnMouseDown", mouseReferences(scopeId, x, y, collision, 0), userInitiated = false)
    }

    /** OnMouseMove を SHIORI に聞いている最中か。指の動きは細かく届くので、答えを待つ間の分は送らずに捨てる */
    private var mouseMoveInFlight = false

    /**
     * 当たり判定の上で指を動かしている（なでている）。SSP の OnMouseMove（マウスを動かしたとき）の代わりに送る。
     * なでるトークは OnMouseMove を数えて返すゴーストが多いので、動くたびに送る。話している間は返事を出さない
     */
    fun onMouseMove(scopeId: Int, x: Int, y: Int, collision: String?) {
        if (touchBlocked()) return
        lastUserActivity = System.currentTimeMillis()
        if (mouseMoveInFlight) return
        mouseMoveInFlight = true
        scope.launch {
            try {
                val script = request("OnMouseMove", mouseReferences(scopeId, x, y, collision, 0)) ?: return@launch
                if (_state.value.talking || _state.value.waitingChoice) return@launch
                cancelScript()
                runScript(script)
            } finally {
                mouseMoveInFlight = false
            }
        }
    }

    /**
     * 指が離れた（動かさずに）。SSP と同じく OnMouseUp を送り、ゴーストが何も返さなければ OnMouseClick を送る。
     * [count] は続けて叩いた回数で、2 なら OnMouseDoubleClick、3 以上なら OnMouseMultipleClick（Reference7 が回数）も送る。
     */
    fun onMouseUp(scopeId: Int, x: Int, y: Int, collision: String?, count: Int) {
        if (touchBlocked()) return
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
        if (touchBlocked()) return
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
        if (touchBlocked()) return
        lastUserActivity = System.currentTimeMillis()
        scope.launch {
            val script = request("OnMouseClick", mouseReferences(scopeId, x, y, collision, 1))
            // popupmenu.visible が 0 なら、ゴーストが答えなくてもメニューは出さない
            if (script == null && resource("${Shell.scopeName(scopeId)}.popupmenu.visible")?.trim() == "0") return@launch
            cancelScript()
            runScript(script ?: basewareMenu(scopeId))
        }
    }

    // ---- 指の出入り・ドラッグ・ジェスチャー ----

    /** \t の間は、マウス（タッチ）のイベントを送らない */
    private fun touchBlocked() = _state.value.timeCritical

    /** キャラクターごとの、いま指が乗っている当たり判定（OnMouseEnter / OnMouseLeave を出し分ける） */
    private val touchedCollision = HashMap<Int, String?>()

    /**
     * 指がキャラクターに触れた。マウスがウィンドウに入ったのと同じに扱い、OnMouseEnterAll と
     * （当たり判定の上なら）OnMouseEnter を送る
     */
    fun onTouchEnter(scopeId: Int, x: Int, y: Int, collision: String?) {
        if (touchBlocked()) return
        touchedCollision[scopeId] = collision
        raise("OnMouseEnterAll", hoverReferences(scopeId, x, y, collision), userInitiated = false)
        if (collision != null) raise("OnMouseEnter", hoverReferences(scopeId, x, y, collision), userInitiated = false)
    }

    /** なでている指が別の当たり判定に移った。前の判定の OnMouseLeave と、新しい判定の OnMouseEnter を送る */
    fun onTouchCollision(scopeId: Int, x: Int, y: Int, collision: String?) {
        if (touchBlocked()) return
        val previous = touchedCollision[scopeId]
        if (previous == collision) return
        touchedCollision[scopeId] = collision
        if (previous != null) raise("OnMouseLeave", hoverReferences(scopeId, x, y, previous), userInitiated = false)
        if (collision != null) raise("OnMouseEnter", hoverReferences(scopeId, x, y, collision), userInitiated = false)
    }

    /** 指が離れた。乗っていた当たり判定の OnMouseLeave と、OnMouseLeaveAll を送る */
    fun onTouchLeave(scopeId: Int, x: Int, y: Int) {
        if (touchBlocked()) return
        val previous = touchedCollision.remove(scopeId)
        if (previous != null) raise("OnMouseLeave", hoverReferences(scopeId, x, y, previous), userInitiated = false)
        raise("OnMouseLeaveAll", hoverReferences(scopeId, x, y, previous), userInitiated = false)
    }

    private fun hoverReferences(scopeId: Int, x: Int, y: Int, collision: String?) =
        listOf(x.toString(), y.toString(), "0", scopeId.toString(), collision.orEmpty(), "0", "touch")

    /** ウィンドウをドラッグで動かし始めた・動かし終えた（OnMouseDragStart / End）。パッシブモードでは送らない（SSP と同じ） */
    fun onMouseDragStart(scopeId: Int, x: Int, y: Int, collision: String?) {
        if (touchBlocked()) return
        lastUserActivity = System.currentTimeMillis()
        if (!_state.value.passive) raise("OnMouseDragStart", mouseReferences(scopeId, x, y, collision, 0), userInitiated = false)
    }

    fun onMouseDragEnd(scopeId: Int, x: Int, y: Int, collision: String?) {
        if (touchBlocked()) return
        if (!_state.value.passive) raise("OnMouseDragEnd", mouseReferences(scopeId, x, y, collision, 0), userInitiated = false)
    }

    /**
     * キャラクターの上をさっと払った（SSP のタッチパネルでのジェスチャー）。OnMouseGesture を送り、
     * 上下に払ったのに答えが無ければ OnMouseWheel を代わりに送る（SSP 2.3.53 以降と同じ。回した量は縦に動いた距離）。
     * 最後に方向 end を送る。[direction] は up / down / left / right / left_up などの SSP の名前、
     * [angle] は払った向き（右が 0 度で反時計回り）。座標はシェル画像の座標
     */
    fun onMouseGesture(
        scopeId: Int, x: Int, y: Int, collision: String?, startX: Int, startY: Int, startCollision: String?, direction: String, angle: Int,
    ) {
        if (touchBlocked()) return
        lastUserActivity = System.currentTimeMillis()
        fun refs(dir: String) = listOf(
            scopeId.toString(), "$x\u0001$y", collision.orEmpty(), "$startX\u0001$startY", startCollision.orEmpty(), dir, angle.toString(),
        )
        scope.launch {
            val script = request("OnMouseGesture", refs(direction))
                ?: if (direction == "up" || direction == "down") {
                    // 上に払ったら奥へ回したのと同じ（正の値）
                    val amount = maxOf(1, abs(y - startY)) * (if (direction == "up") 1 else -1)
                    request("OnMouseWheel", listOf(x.toString(), y.toString(), amount.toString(), scopeId.toString(), collision.orEmpty(), "0", "touch"))
                } else {
                    null
                }
            if (script != null && !_state.value.waitingChoice) {
                cancelScript()
                runScript(script)
            }
            raise("OnMouseGesture", refs("end"), userInitiated = false)
        }
    }

    /** 2本指で上下になぞった（ホイールの代わり）。[amount] は上へなら正。座標はシェル画像の座標 */
    fun onMouseWheel(scopeId: Int, x: Int, y: Int, collision: String?, amount: Int) {
        if (touchBlocked()) return
        lastUserActivity = System.currentTimeMillis()
        scope.launch {
            val script = request("OnMouseWheel", listOf(x.toString(), y.toString(), amount.toString(), scopeId.toString(), collision.orEmpty(), "0", "touch"))
                ?: return@launch
            if (_state.value.waitingChoice) return@launch
            cancelScript()
            runScript(script)
        }
    }

    /**
     * 中クリック（2本指で叩く・長押しメニューの「ゴーストのメニュー（中クリック）」）。SSP と同じく OnMouseDownEx / OnMouseUpEx を送り、
     * 答えが無ければ OnMouseClickEx（middle）、それにも答えが無ければ古いゴースト向けに OnMouseClick（Reference5 = 2）を送る
     */
    fun onMiddleClick(scopeId: Int, x: Int = 0, y: Int = 0, collision: String? = null) {
        if (touchBlocked()) return
        lastUserActivity = System.currentTimeMillis()
        val refs = listOf(x.toString(), y.toString(), "0", scopeId.toString(), collision.orEmpty(), "middle", "touch")
        raise("OnMouseDownEx", refs, userInitiated = false)
        scope.launch {
            val script = request("OnMouseUpEx", refs)
                ?: request("OnMouseClickEx", refs)
                ?: request("OnMouseClick", mouseReferences(scopeId, x, y, collision, 2))
                ?: return@launch
            cancelScript()
            runScript(script)
        }
    }

    // ---- ウィンドウ（最小化・位置の初期化・重なり・見切れ・大きさ） ----

    /** キャラクターを隠す（\![set,windowstate,minimize] は script、メニューの「隠す」は user） */
    fun minimize(reason: String) {
        if (_state.value.window.minimized) return
        _state.update { it.copy(window = it.window.copy(minimized = true)) }
        raise("OnWindowStateMinimize", listOf(reason), userInitiated = false)
    }

    /** 隠したキャラクターを出す（通知の「表示」） */
    fun restoreWindows(reason: String = "user") {
        if (!_state.value.window.minimized) return
        _state.update { it.copy(window = it.window.copy(minimized = false)) }
        raise("OnWindowStateRestore", listOf(reason), userInitiated = false)
    }

    /** ウィンドウの位置を初期位置に戻す（\![execute,resetwindowpos]） */
    fun resetWindowPositions() {
        _state.update { it.copy(window = it.window.copy(resetRequests = it.window.resetRequests + 1)) }
    }

    /** メニューの「位置を戻す」。OnResetWindowPos に答えが無ければ戻す（SSP と同じ） */
    private fun requestResetWindowPos() {
        scope.launch {
            val script = request("OnResetWindowPos", emptyList())
            if (script != null) {
                cancelScript()
                runScript(script)
            } else {
                resetWindowPositions()
            }
        }
    }

    private var lastOverlaps = ""
    private var lastOffscreen = ""

    /**
     * キャラクターのウィンドウが動いた後の重なりと見切れ。変わったときだけ OnOverlap / OnOffscreen を送る。
     * [overlaps] は重なっているキャラクターの組（小さい番号が先）、[offscreen] は画面からはみ出しているキャラクター
     */
    fun onWindowLayout(overlaps: Collection<Pair<Int, Int>>, offscreen: Collection<Int>) {
        val o = overlaps.map { (a, b) -> minOf(a, b) to maxOf(a, b) }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("\u0001") { "${it.first}-${it.second}" }
        val f = offscreen.distinct().sorted().joinToString("\u0001")
        if (o != lastOverlaps) {
            raise("OnOverlap", listOf(o, lastOverlaps), userInitiated = false)
            lastOverlaps = o
        }
        if (f != lastOffscreen) {
            raise("OnOffscreen", listOf(f, lastOffscreen), userInitiated = false)
            lastOffscreen = f
        }
    }

    /** アプリの画面で「キャラクターの大きさ」を変えた（パーセント）。OnShellScaling（横・縦とも同じ倍率） */
    fun onShellScaling(newPercent: Int, oldPercent: Int) {
        if (newPercent == oldPercent) return
        raise("OnShellScaling", listOf(newPercent.toString(), oldPercent.toString(), newPercent.toString(), oldPercent.toString()), userInitiated = false)
    }

    // ---- アニメーションを待つ（\i[ID,wait]・\__w[animation,ID]） ----

    /** キャラクターごとの、いまコマを進めているアニメーションの番号。表示する側が知らせる */
    private val playingAnimations = MutableStateFlow<Map<Int, Set<Int>>>(emptyMap())

    /** 表示する側から: [scope] のキャラクターで、いまコマを進めているアニメーション */
    fun onAnimationsPlaying(scope: Int, ids: Set<Int>) = playingAnimations.update { it + (scope to ids) }

    /**
     * [id] のアニメーションが終わるまで待つ。頼んだばかりでまだ動いていなければ、少しの間だけ動き出すのを待つ
     * （画面が消えているなどで動かなければ、すぐに戻る）。いつまでも止まらないよう、待つのは長くても1分
     */
    private suspend fun waitAnimation(scope: Int, id: Int) {
        withTimeoutOrNull(ANIMATION_START_WAIT_MS) { playingAnimations.first { id in it[scope].orEmpty() } } ?: return
        withTimeoutOrNull(MAX_ANIMATION_WAIT_MS) { playingAnimations.first { id !in it[scope].orEmpty() } }
    }

    // ---- 選択モード（\![enter,selectmode]） ----

    /**
     * \![enter,selectmode,rect,左,上,右,下] / \![enter,selectmode,rect,当たり判定名]。[scope] のキャラクターの上で矩形を選ばせる。
     * 選んでいる間はサーフェスを描き直さない（SSP と同じ）
     */
    private fun enterSelectMode(scope: Int, args: List<String>) {
        val rest = args.drop(1).map { it.trim() }
        val numbers = rest.mapNotNull { it.toIntOrNull() }
        val initial = when {
            numbers.size >= 4 -> SelectRect(numbers[0], numbers[1], numbers[2], numbers[3])
            rest.isNotEmpty() -> {
                val surface = _state.value.characters[scope]?.surface ?: -1
                shell.surfaces[surface]?.collisions?.values?.firstOrNull { it.id == rest[0] }?.let { SelectRect(it.left, it.top, it.right, it.bottom) }
            }
            else -> null
        }
        selecting = true
        _state.update { it.copy(selectMode = SelectMode(scope, initial)).withLocks() }
        raise("OnSelectModeBegin", listOf(scope.toString(), "rect"), userInitiated = false)
    }

    /** \![leave,selectmode]。選んだ範囲があれば OnSelectModeComplete、無ければ OnSelectModeCancel */
    private fun leaveSelectMode() {
        val mode = _state.value.selectMode ?: return
        selecting = false
        _state.update { it.copy(selectMode = null).withLocks() }
        val rect = mode.selection
        if (rect == null || rect.isEmpty) {
            raise("OnSelectModeCancel", listOf(mode.scope.toString(), "rect"), userInitiated = false)
        } else {
            raise("OnSelectModeComplete", listOf(mode.scope.toString(), "rect", rect.toString()), userInitiated = false)
        }
    }

    /** 選択モードで指が触れた・離れた（シェル画像の座標）。[selection] はそのときまでに選んだ範囲 */
    fun onSelectDown(scope: Int, x: Int, y: Int) {
        if (_state.value.selectMode?.scope != scope) return
        raise("OnSelectModeMouseDown", listOf(scope.toString(), "rect", "$x,$y"), userInitiated = false)
    }

    fun onSelectUp(scope: Int, x: Int, y: Int, selection: SelectRect) {
        if (_state.value.selectMode?.scope != scope) return
        onSelecting(scope, selection)
        raise("OnSelectModeMouseUp", listOf(scope.toString(), "rect", "$x,$y"), userInitiated = false)
    }

    /** 指を動かして、選んでいる範囲が変わった */
    fun onSelecting(scope: Int, selection: SelectRect) {
        _state.update { s -> s.selectMode?.takeIf { it.scope == scope }?.let { s.copy(selectMode = it.copy(selection = selection)) } ?: s }
    }

    /** アプリの画面で「バルーンの大きさ」を変えた（パーセント）。OnBalloonScaling（横・縦とも同じ倍率） */
    fun onBalloonScaling(newPercent: Int, oldPercent: Int) {
        if (newPercent == oldPercent) return
        raise("OnBalloonScaling", listOf(newPercent.toString(), oldPercent.toString(), newPercent.toString(), oldPercent.toString()), userInitiated = false)
    }

    /**
     * \![set,zorder,1,0]。左ほど手前。数だけならそのキャラクターのバルーンとサーフェス（バルーンが手前）、
     * "s1" / "b1"（surface1 / balloon1）なら片方だけ。新しい組と重なる古い組は消す。引数が無ければ全部やめる
     */
    private fun setZorder(args: List<String>) {
        val group = args.flatMap { arg ->
            val t = arg.trim().lowercase()
            val n = t.toIntOrNull()
            when {
                n != null -> listOf("b$n", "s$n")
                t.startsWith("balloon") -> listOfNotNull(t.removePrefix("balloon").toIntOrNull()?.let { "b$it" })
                t.startsWith("surface") -> listOfNotNull(t.removePrefix("surface").toIntOrNull()?.let { "s$it" })
                t.startsWith("b") || t.startsWith("s") -> listOfNotNull(t.drop(1).toIntOrNull()?.let { "${t[0]}$it" })
                else -> emptyList()
            }
        }.distinct()
        _state.update {
            val groups = if (group.size < 2) emptyList() else it.window.zorder.filter { g -> g.none(group::contains) } + listOf(group)
            it.copy(window = it.window.copy(zorder = groups))
        }
    }

    /** \![set,sticky-window,1,0]。一緒に動くキャラクターの組。新しい組に入ったキャラクターは古い組から抜ける。引数が無ければ全部やめる */
    private fun setSticky(args: List<String>) {
        val group = args.mapNotNull { it.trim().toIntOrNull()?.takeIf { n -> n in 0..ScriptPlayer.MAX_SCOPE } }.toSet()
        _state.update {
            val groups = if (group.size < 2) {
                emptyList()
            } else {
                it.window.stickyGroups.map { g -> g - group }.filter { g -> g.size >= 2 } + listOf(group)
            }
            it.copy(window = it.window.copy(stickyGroups = groups))
        }
    }

    /**
     * 最初の位置。SHIORI リソースの sakura.defaultx / defaulty / defaultleft / defaulttop（無ければゴーストの descript.txt）。
     * キャラクターごとに、決まっているものだけ持つ
     */
    private suspend fun loadDefaultPositions(): Map<Int, DefaultPosition> {
        val out = HashMap<Int, DefaultPosition>()
        for (scopeId in characterScopes) {
            val name = Shell.scopeName(scopeId)
            suspend fun value(key: String) = ScreenCoordinate.parse(resource("$name.$key") ?: ghost.descript["$name.$key"])
            val pos = DefaultPosition(value("defaultx"), value("defaulty"), value("defaultleft"), value("defaulttop"))
            if (!pos.isEmpty) out[scopeId] = pos
        }
        return out
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
    /** メニューの項目名。ゴーストが返したもの（[names] の前のものほど先。新しい名前・古い名前の順）、無ければ [default] */
    private suspend fun caption(default: String, vararg names: String): String {
        for (n in names) resource(n)?.let { return quote(it) }
        return quote(default)
    }

    /** menu.background.font.color.* を項目の文字の色に、menu.foreground.font.color.* を選んでいる項目の文字の色にする */
    private suspend fun menuColors(): String {
        suspend fun rgb(prefix: String): String? {
            val parts = listOf("r", "g", "b").map { c -> resource("$prefix.$c")?.trim()?.toIntOrNull()?.coerceIn(0, 255) ?: return null }
            return parts.joinToString(",")
        }
        val sb = StringBuilder()
        rgb("menu.background.font.color")?.let { sb.append("\\f[color,$it]\\f[cursornotselectfontcolor,$it]") }
        rgb("menu.foreground.font.color")?.let { sb.append("\\f[cursorfontcolor,$it]") }
        return sb.toString()
    }

    /**
     * 長押しのメニュー。SSP のオーナードローメニューと同じく、おすすめ・ポータル・きせかえ・シェル・バルーン・Read Me・
     * ネットワーク更新・終了を出す。選択肢の ID は [selectChoice] が pono: で見分ける。項目名は *button.caption で替えられ、
     * popupmenu.type が 1 なら省略メニュー（おすすめ・ポータル・着せ替え・隠す・終了だけ）。popupmenu.applybindtoself が 0 なら、
     * 本体側のメニューの着せ替えは本体側のもの
     */
    private suspend fun basewareMenu(scopeId: Int): String {
        val scopeName = Shell.scopeName(scopeId)
        val short = resource("$scopeName.popupmenu.type")?.trim() == "1"
        val bindScope = if (!short && scopeId != 0 && resource("$scopeName.popupmenu.applybindtoself")?.trim() == "0") 0 else scopeId
        val sb = StringBuilder("\\p[$scopeId]\\_q").append(menuColors()).append("【${ShioriSession.SENDER}】\\n")
        if (parseSites(resource("$scopeName.recommendsites")).isNotEmpty()) {
            sb.append("\\q[${caption("おすすめ", "$scopeName.recommendbuttoncaption", "recommendrootbutton.caption")},pono:sites,recommend,$scopeId]\\n")
        }
        if (parseSites(resource("$scopeName.portalsites")).isNotEmpty()) {
            sb.append("\\q[${caption("ポータル", "$scopeName.portalbuttoncaption", "portalrootbutton.caption")},pono:sites,portal,$scopeId]\\n")
        }
        if (!short) sb.append("\\q[ゴーストのメニュー（中クリック）,pono:middle,$scopeId]\\n")
        if (shell.bindGroups(bindScope).isNotEmpty() && bindMenu(bindScope) != "hidden") {
            sb.append("\\q[${caption("きせかえ", "dressuprootbutton.caption")},pono:dressup,$bindScope]\\n")
        }
        if (!short) {
            if ((repository?.list()?.size ?: 0) > 1) sb.append("\\q[${caption("ゴースト切り替え", "ghostrootbutton.caption")},pono:ghosts,$scopeId]\\n")
            if (ghost.shellDirs.size > 1) sb.append("\\q[${caption("シェル", "shellrootbutton.caption")},pono:shells,$scopeId]\\n")
            if (repository != null) sb.append("\\q[${caption("バルーン", "balloonrootbutton.caption")},pono:balloons,$scopeId]\\n")
            if (readmeFile() != null) sb.append("\\q[${caption("Read Me", "readmebutton.caption", "readmebuttoncaption")},pono:readme]\\n")
            if (termsFile() != null) sb.append("\\q[${caption("利用規約", "termsbutton.caption")},pono:terms-open]\\n")
            if (aiStates().isNotEmpty()) sb.append("\\q[${caption("AI グラフ", "aistatebutton.caption")},pono:aistate,$scopeId]\\n")
            if (history?.usage()?.isNotEmpty() == true) sb.append("\\q[${caption("使用率", "rateofusebutton.caption")},pono:rateofuse,$scopeId]\\n")
            sb.append("\\q[${caption("ネットワーク更新", "updatebutton.caption", "updatebuttoncaption")},pono:update]\\n")
            sb.append("\\q[位置を戻す,pono:resetpos]\\n")
        }
        if (!short && platform.mailAccounts().isNotEmpty()) sb.append("\\q[${caption("メールを確認", "biffbutton.caption")},pono:biff]\\n")
        sb.append("\\q[${caption("隠す", "hidebutton.caption")},pono:hide]\\n")
        lastSstpSender?.let { sb.append("\\q[${quote(literalScript("「$it」の SSTP を受けない"))},pono:sstp-block]\\n") }
        if (!short && vanishVisible()) sb.append("\\q[${caption("消去", "vanishbutton.caption", "vanishbuttoncaption")},pono:vanish,$scopeId]\\n")
        sb.append("\\q[${caption("ゴーストを終了", "quitbutton.caption", "closebutton.caption")},pono:close]\\n")
        sb.append("\\q[${caption("閉じる", "closeballoonbutton.caption")},pono:cancel]\\e")
        return sb.toString()
    }

    /**
     * getaistateex（Reference0 = 何個目か）か getaistate の答え。1つは 値群 [1] ラベル群 [1] 加算値群 [1] 最大値群（後ろの3つは省ける）。
     * 答えが無ければ空
     */
    private suspend fun aiStates(): List<String> {
        val ex = mutableListOf<String>()
        for (i in 0 until MAX_AI_GRAPHS) {
            ex += request("getaistateex", listOf(i.toString()), translate = false)?.takeIf { it.isNotBlank() } ?: break
        }
        return ex.ifEmpty { listOfNotNull(resource("getaistate")) }
    }

    /** \![open,aigraph] とメニューの「AI グラフ」。グラフの代わりに、ラベルと値をバルーンに並べる */
    private suspend fun aiStateScript(scopeId: Int): String? {
        val graphs = aiStates().takeIf { it.isNotEmpty() } ?: return null
        val sb = StringBuilder("\\p[$scopeId]\\_q【AI グラフ】\\n")
        graphs.forEachIndexed { gi, raw ->
            val parts = raw.split('\u0001')
            val values = parts[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val labels = parts.getOrNull(1)?.split(',')?.map { it.trim() }?.takeIf { it.any(String::isNotEmpty) } ?: AI_LABELS
            val max = parts.getOrNull(3)?.split(',')?.map { it.trim() }
            if (gi > 0) sb.append("\\n")
            values.forEachIndexed { i, v ->
                val label = labels.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: (i + 1).toString()
                val limit = max?.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { " / $it" }.orEmpty()
                sb.append(literalScript("$label: $v$limit")).append("\\n")
            }
        }
        sb.append("\\q[戻る,pono:menu,$scopeId]\\e")
        return sb.toString()
    }

    /** \![open,rateofusegraph] とメニューの「使用率」。起動していた時間の長いゴーストから順に、割合と時間を並べる */
    private fun rateOfUseScript(scopeId: Int): String? {
        val usage = history?.usage()?.takeIf { it.isNotEmpty() } ?: return null
        val total = usage.sumOf { it.minutes }
        val sb = StringBuilder("\\p[$scopeId]\\_q【使用率】\\n")
        for (u in usage.take(MAX_RATE_ROWS)) {
            val name = repository?.find(u.dirName)?.name ?: u.dirName
            val percent = if (total > 0) formatNumber(u.minutes * 100.0 / total) else "0"
            sb.append(literalScript("$name: $percent%（${u.minutes}分・${u.boots}回）")).append("\\n")
        }
        sb.append("\\q[戻る,pono:menu,$scopeId]\\e")
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
            // \![set,property,currentghost.shelllist(名前).menu,hidden] とシェルの descript.txt の menu,hidden
            if (shellMenu(dir) == "hidden" && dir != shell.dir) continue
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
            // \![enter,nouserbreakmode] の間は、話している途中で止めさせない
            s.talking && s.noUserBreak -> {}
            s.talking -> {
                val script = lastScript
                // OnVanishSelecting の途中なら、消去を取りやめる
                val vanishing = vanishJob?.also { it.cancel() } != null
                vanishJob = null
                cancelScript()
                _state.update { it.copy(talking = false, waitingClick = false) }
                hideBalloons()
                val event = when {
                    vanishing -> "OnVanishButtonHold"
                    sstpRun == scriptRuns -> "OnSSTPBreak"
                    else -> "OnBalloonBreak"
                }
                raise(event, listOf(script, scopeId.toString(), ""), userInitiated = false)
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

    /**
     * ネットワークが変わった。つながる・切れる、種類（Wi-Fi・モバイルなど）、アドレスが変わったときだけ OnNetworkStatusChange を送る
     * （速さはしょっちゅう揺れるので、それだけでは送らない）
     */
    fun onNetworkChanged(status: NetworkStatus) {
        val previous = lastNetwork
        lastNetwork = status
        if (previous != null && previous.online == status.online && previous.type == status.type && previous.addresses == status.addresses) return
        raise("OnNetworkStatusChange", networkReferences(status), userInitiated = false)
    }

    /**
     * 端末が休んだ（Android では画面が消えた）。SSP の OnSysSuspend（NOTIFY）。休むたびに1回だけ送る
     */
    fun onSystemSuspend() {
        if (suspended) return
        suspended = true
        notify("OnSysSuspend", emptyList())
    }

    /**
     * 端末が戻ってきて、ユーザーがキャラクターを見られるようになった（ロックを解いた）。SSP の OnSysResume（Reference0 は normal）。
     * 休んだ後に1回だけ送る
     */
    fun onSystemResume() {
        if (!suspended) return
        suspended = false
        lastUserActivity = System.currentTimeMillis()
        raise("OnSysResume", listOf("normal"), userInitiated = false)
    }

    private var sessionLocked = false

    /** ロック画面に戻った（画面が消えた）・ロックを解いた。OnSessionLock / OnSessionUnlock を1回ずつ送る */
    fun onSessionLock() {
        if (sessionLocked) return
        sessionLocked = true
        raise("OnSessionLock", emptyList(), userInitiated = false)
    }

    fun onSessionUnlock() {
        if (!sessionLocked) return
        sessionLocked = false
        raise("OnSessionUnlock", emptyList(), userInitiated = false)
    }

    /** 画面の電源が入った・切れた（OnDisplayPowerStatus） */
    fun onDisplayPower(on: Boolean) = raise("OnDisplayPowerStatus", listOf(if (on) "1" else "0"), userInitiated = false)

    /** Android のスクリーンセーバー（スクリーンセーバー / Daydream）が始まった（NOTIFY）・終わった */
    fun onScreenSaver(started: Boolean) {
        if (started) notify("OnScreenSaverStart", listOf("Android", "", "0")) else raise("OnScreenSaverEnd", emptyList(), userInitiated = false)
    }

    /** 端末の見た目や言語が変わった。変わったものだけ OnDarkTheme / OnLanguageChange を送る（今度は話せる） */
    fun onSystemInfoChanged(info: SystemInfo) {
        val previous = lastSystem
        lastSystem = info
        if (previous == null) return
        if (info.darkTheme != previous.darkTheme) raise("OnDarkTheme", darkReferences(info), userInitiated = false)
        if (info.languageName != previous.languageName || info.languageId != previous.languageId) {
            raise("OnLanguageChange", languageReferences(info), userInitiated = false)
        }
        if (info.osUpdated != previous.osUpdated) raise("OnOSUpdateInfo", osUpdateReferences(info), userInitiated = false)
    }

    private var cpuHighCount = 0
    private var cpuHigh = false
    private var memoryHighCount = 0
    private var memoryHigh = false

    /**
     * 端末の負荷（パーセント。CPU が分からなければ null）。数秒ごとに呼ぶ。80% 以上が続いたら OnCPULoadHigh / OnMemoryLoadHigh、
     * その後 60% を下回ったら OnCPULoadLow / OnMemoryLoadLow（SSP と同じ）
     */
    fun onLoad(cpuPercent: Int?, memoryPercent: Int?) {
        fun check(value: Int?, count: Int, high: Boolean, name: String, set: (Int, Boolean) -> Unit) {
            value ?: return
            when {
                !high && value >= LOAD_HIGH -> {
                    val n = count + 1
                    if (n >= LOAD_HIGH_SAMPLES) {
                        set(0, true)
                        raise("On${name}LoadHigh", listOf(value.toString()), userInitiated = false)
                    } else {
                        set(n, false)
                    }
                }
                !high -> set(0, false)
                value < LOAD_LOW -> {
                    set(0, false)
                    raise("On${name}LoadLow", listOf(value.toString()), userInitiated = false)
                }
            }
        }
        check(cpuPercent, cpuHighCount, cpuHigh, "CPU") { n, h -> cpuHighCount = n; cpuHigh = h }
        check(memoryPercent, memoryHighCount, memoryHigh, "Memory") { n, h -> memoryHighCount = n; memoryHigh = h }
    }

    /**
     * ファイルを渡された（Android の「共有」。SSP でキャラクターにファイルを落とすのと同じ）。
     * OnFileDrop2（Reference0 はパス、Reference2 は MIME タイプ。どちらも 0x01 区切り）を送り、
     * 答えが無ければ古いゴースト向けに OnFileDropEx も送る。[scopeId] は渡した先のキャラクター
     */
    fun onFileDrop(paths: List<String>, mimeTypes: List<String>, scopeId: Int = 0) {
        if (paths.isEmpty()) return
        lastUserActivity = System.currentTimeMillis()
        val refs = listOf(paths.joinToString("\u0001"), scopeId.toString(), mimeTypes.joinToString("\u0001"))
        scope.launch {
            val script = request("OnFileDrop2", refs) ?: request("OnFileDropEx", refs)
            if (script == null) {
                // 答えが無ければ SSP のいつもの扱い（.nar を入れる・画像や音を開く）
                dropDefault(paths, mimeTypes, scopeId)
                return@launch
            }
            cancelScript()
            runScript(script)
        }
    }

    /** \![execute,dropdefault,file,パス...] / \![execute,dropdefault,url,URL]。ゴーストが受け取らなかったときの扱いを、改めて頼む */
    private fun dropDefaultCommand(args: List<String>): Boolean {
        when (args.firstOrNull()?.trim()?.lowercase()) {
            "file" -> {
                val files = args.drop(1).mapNotNull { ghostPath(it) }.filter { it.isFile }
                if (files.isEmpty()) return false
                scope.launch { dropDefault(files.map { it.path }, files.map { mimeOf(it.name) }, 0) }
            }
            "url" -> {
                val url = args.getOrNull(1)?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return false
                if (url.substringBefore('?').lowercase().endsWith(".nar")) scope.launch { downloadAndInstall(url, 0, dropped = true) } else platform.openUrl(url)
            }
            else -> return false
        }
        return true
    }

    /** 拡張子からの MIME タイプ（画像・音・動画と .nar だけ見分ける） */
    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "mid", "midi" -> "audio/midi"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "nar" -> "application/x-nar"
        "txt", "log", "ini", "csv" -> "text/plain"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
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
        if (request.kind == InputRequest.Kind.TEACH) raise("OnTeachStart", emptyList(), userInitiated = false)
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
            InputRequest.Kind.DATE, InputRequest.Kind.TIME, InputRequest.Kind.IP -> answer("OnUserInput", sensitive = false)
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
            InputRequest.Kind.INPUT, InputRequest.Kind.PASSWORD, InputRequest.Kind.DATE, InputRequest.Kind.TIME, InputRequest.Kind.SLIDER, InputRequest.Kind.IP ->
                raise("OnUserInputCancel", listOf(request.id, reason))
            InputRequest.Kind.TEXTAREA -> raise("OnTextAreaInputCancel", listOf(request.id, reason))
            InputRequest.Kind.COMMUNICATE -> raise("OnCommunicateInputCancel", listOf("", reason))
            InputRequest.Kind.TEACH -> raise("OnTeachInputCancel", listOf("", reason))
        }
    }

    fun onChoice(choice: Choice) {
        lastUserActivity = System.currentTimeMillis()
        val waiter = choiceWaiter
        if (waiter != null) {
            waiter.complete(choice)
            return
        }
        // 選択肢を待っていないとき（話している途中、\C で続けた後など）も、出ている選択肢なら SSP と同じく選べる
        // 描くときに題名の前後の空白を落としているので、ID と Reference で見分ける
        fun same(c: Choice) = c.id == choice.id && c.references == choice.references && c.anchor == choice.anchor
        val shown = _state.value.characters.values.any { c -> c.balloon?.any { it is BalloonItem.ChoiceItem && same(it.choice) } == true }
        if (!shown) return
        cancelScript()
        _state.update { it.copy(talking = false, waitingClick = false, waitingChoice = false) }
        selectChoice(choice)
    }

    /** 選択肢の長押しの通し番号（指を離したあとに届いたツールチップを捨てるため） */
    private var choiceHoverSerial = 0

    /**
     * 選択肢・リンクを長押しした（[choice]）・指を離した（null）。カーソルを乗せたのと同じに扱い、
     * OnChoiceEnter / OnAnchorEnter と OnChoiceHover / OnAnchorHover を送る。離れたときは Reference なしの Enter（SSP と同じ）
     */
    fun onChoiceHover(choice: Choice?, anchor: Boolean = choice?.anchor == true) {
        lastUserActivity = System.currentTimeMillis()
        val kind = if (anchor) "Anchor" else "Choice"
        // 指を離したら、まだ答えの来ていないツールチップも出さない
        val hover = ++choiceHoverSerial
        if (choice == null) {
            _state.update { it.copy(choiceTooltip = null) }
            raise("On${kind}Enter", emptyList(), userInitiated = false)
            return
        }
        val refs = listOf(choice.title, choice.id) + choice.references
        raise("On${kind}Enter", refs, userInitiated = false)
        raise("On${kind}Hover", refs, userInitiated = false)
        // 選択肢のツールチップ（balloon_tooltip、無ければ tooltip）
        if (!anchor) {
            scope.launch {
                val tip = request("balloon_tooltip", refs, translate = false) ?: request("tooltip", refs, translate = false)
                if (hover != choiceHoverSerial) return@launch
                _state.update { s ->
                    // その選択肢が出ているバルーンのキャラクター（もう出ていなければ出さない）
                    val owner = s.characters.entries.firstOrNull { (_, c) ->
                        c.balloon?.any { it is BalloonItem.ChoiceItem && it.choice.id == choice.id && it.choice.references == choice.references } == true
                    }?.key
                    s.copy(choiceTooltip = tip?.takeIf { t -> t.isNotBlank() && owner != null }?.let { owner!! to it })
                }
            }
        }
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
        logEvent(event, references)
        // 毎秒・毎分のイベントとなでる（OnMouseMove）は多すぎるので、話すときだけ記録する
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
                // Reference3 は「話せるか」。パッシブモードとインダクションモードでは常に 0（SSP と同じ）
                // SSTP の EXECUTE Quiet の間も話せない
                val quiet = System.currentTimeMillis() < quietUntil
                val refs = listOf(uptimeHours, "0", "0", if (talking || s.passive || s.induction || quiet) "0" else "1", idleSec)
                raise("OnSecondChange", refs, userInitiated = false)
                val now = Calendar.getInstance()
                val minute = now.get(Calendar.MINUTE)
                if (minute != lastMinute) {
                    lastMinute = minute
                    raise("OnMinuteChange", refs, userInitiated = false)
                    history?.ghostMinute(ghost.dir.name)
                    checkSchedule()
                    checkMailPeriodically()
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

    private fun runScript(script: String, chain: Int = 0, untrusted: Boolean = false): Job {
        // talking は launch の前に立てる（呼び出し側が「話し終わるまで待つ」ときに取りこぼさないため）
        // 先頭が \C なら、今出ているバルーンを消さずに続ける
        if (!script.startsWith("\\C")) hideBalloons()
        // 前のスクリプトの \t と、manual でない \![lock] はここで外れる
        _state.update { it.withScriptEnded().copy(talking = true) }
        lastScript = script
        developerLog.add("script", "Ghost", ghost.name, script)
        val run = ++scriptRuns
        val job = scope.launch {
            val raised = mutableListOf<Pair<String, List<String>>>()
            val host = Host(raised, untrusted)
            // 割り込まれずに最後まで話したかどうか（話し終わりの SERIKO、yen-e を動かす）
            var ended = false
            val result = try {
                player.play(script, host).also { ended = true }
            } finally {
                host.endSpeaking()
                host.flushVoice()
                // 割り込まれて止まったスクリプトが、後から始まったスクリプトの「話している」を消さないようにする
                if (run == scriptRuns) {
                    _state.update {
                        it.withScriptEnded().copy(talking = false, waitingClick = false, scriptEnds = if (ended) it.scriptEnds + 1 else it.scriptEnds)
                    }
                }
            }

            // 終了中（OnClose のスクリプト）なら、話し終えた時点で close() に戻す
            if (closing) return@launch
            if (result.quit) {
                closing = true
                shutdown()
                return@launch
            }
            host.updateRequest?.let { (types, options) -> networkUpdate(types, options, "script") }
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

    /** \![lock,balloonrepaint] / \![lock,repaint] で止めているか。manual なら、スクリプトが終わっても外さない */
    private var balloonLocked = false
    private var repaintLocked = false
    private var manualBalloonLock = false
    private var manualRepaintLock = false

    /** \![enter,selectmode] の間。サーフェスを描き直さない */
    private var selecting = false

    /**
     * 止めているものに合わせて、止めたときの見た目を持つ（止め始めたときに取り、外したら捨てる）。
     * \![lock,repaint] はバルーンも止める
     */
    private fun GhostState.withLocks() = copy(
        balloonSnapshot = if (balloonLocked || repaintLocked) balloonSnapshot ?: characters else null,
        surfaceSnapshot = if (repaintLocked || selecting) surfaceSnapshot ?: characters else null,
    )

    /** スクリプトが終わった（止められた）ときに外れるもの: \t と、manual でない \![lock] */
    private fun GhostState.withScriptEnded(): GhostState {
        if (!manualBalloonLock) balloonLocked = false
        if (!manualRepaintLock) repaintLocked = false
        return copy(timeCritical = false).withLocks()
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
                    // 中クリック。ゴーストによってはこれでメニューを開く
                    "pono:middle" -> onMiddleClick(scopeId)
                    "pono:hide" -> minimize("user")
                    "pono:resetpos" -> requestResetWindowPos()
                    "pono:update" -> networkUpdate()
                    "pono:close" -> close("user", scopeId)
                    "pono:vanish" -> requestVanish(scopeId)
                    "pono:vanish-yes" -> vanish(farewell = true)
                    "pono:vanish-no" -> raise("OnVanishCancel", emptyList())
                    "pono:terms-open" -> showTerms()
                    "pono:sstp-block" -> blockLastSstpSender()
                    "pono:biff" -> checkMail(null)
                    "pono:aistate" -> scope.launch { aiStateScript(scopeId)?.let { runScript(it) } }
                    "pono:rateofuse" -> rateOfUseScript(scopeId)?.let { runScript(it) }
                    "pono:terms" -> raise(if (refs.firstOrNull() == "accept") "OnGhostTermsAccept" else "OnGhostTermsDecline", emptyList())
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
            // \_a のジャンパは OnAnchorSelectEx → OnAnchorSelect。選んだものは訪問済みの見た目にする
            choice.anchor -> scope.launch {
                _state.update { it.copy(visitedAnchors = it.visitedAnchors + id) }
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
        val shown = _state.value.characters.values.any { it.balloon != null }
        _state.update { s -> s.copy(characters = s.characters.mapValues { it.value.copy(balloon = null, balloonId = null, autoScroll = true) }) }
        if (shown) scheduleSurfaceRestore()
    }

    private var surfaceRestoreJob: Job? = null

    /**
     * 話したバルーンが閉じた。[surfaceRestoreMs] たっても次の話が始まらなければ、OnSurfaceRestore で表情を戻す機会を渡す
     * （SSP と同じく1回だけ。Reference0 / 1 は本体と相方の今のサーフェス）。
     * 次に文字や選択肢が出たら取り消す（\C\0\0\1\e のような、何も見せないスクリプトでは取り消さない）
     */
    private fun scheduleSurfaceRestore() {
        surfaceRestoreJob?.cancel()
        surfaceRestoreJob = scope.launch {
            delay(surfaceRestoreMs)
            // 何も見せないスクリプトがちょうど動いていたら、終わるのを待つ
            val s = _state.first { !it.talking && !it.waitingChoice }
            if (closing) return@launch
            val surfaces = listOf(0, 1).map { (s.characters[it]?.surface ?: -1).toString() }
            raise("OnSurfaceRestore", surfaces, userInitiated = false)
        }
    }

    /** 文字か選択肢が出た（話した）。閉じた前のバルーンのための OnSurfaceRestore は要らない */
    private fun talked() {
        surfaceRestoreJob?.cancel()
        surfaceRestoreJob = null
    }

    private fun resetSurfaces() {
        _state.update {
            GhostState(
                characters = characterScopes.associateWith { scope ->
                    CharacterState(
                        scope, defaultSurface(scope),
                        binds = shell.bindGroups(scope).filter { g -> g.default }.map { g -> g.id }.toSet(),
                        // 画面の端への吸着やバルーンの置き方・印もゴーストが終わるまで続く
                        alignment = it.characters[scope]?.alignment,
                        balloonOffset = it.characters[scope]?.balloonOffset,
                        balloonAlign = it.characters[scope]?.balloonAlign,
                        balloonMarker = it.characters[scope]?.balloonMarker,
                        balloonNum = it.characters[scope]?.balloonNum,
                    )
                },
                screenOn = it.screenOn,
                passive = it.passive,
                loading = it.loading,
                scriptEnds = it.scriptEnds,
                // 重なり順や一緒に動く組、enter で始めたモードはゴーストが終わるまで続く（SSP と同じ）
                window = it.window,
                online = it.online,
                noUserBreak = it.noUserBreak,
                induction = it.induction,
                visitedAnchors = it.visitedAnchors,
                collisionMode = it.collisionMode,
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
            // pono-droid の画面はいつも明るい色。端末のほうはダークモードかどうか
            n == "system.theme.app.mode" -> "light"
            n == "system.theme.os.mode" -> if (platform.systemInfo()?.darkTheme == true) "dark" else "light"
            n == "system.dnd.mode" -> if (platform.systemInfo()?.doNotDisturb == true) "quiet" else "normal"
            n.startsWith("system.network.") -> networkProperty(n.removePrefix("system.network."))
            n == "system.disk.count" -> platform.diskInfo().size.toString()
            n.startsWith("system.disk.index(") -> {
                val d = n.substring(18, n.indexOf(')').takeIf { it > 18 } ?: return null).toIntOrNull()?.let(platform.diskInfo()::getOrNull) ?: return null
                when (n.substringAfter(").", "")) {
                    "mountpoint" -> d.mountpoint
                    "type" -> d.type
                    "total" -> d.totalMb.toString()
                    "free" -> d.freeMb.toString()
                    else -> null
                }
            }
            n.startsWith("history.ghost") -> historyProperty(n.removePrefix("history.ghost"), history?.recentGhosts().orEmpty().mapNotNull { repository?.find(it) }) { g, key, i -> ghostProperty(g, key, i) }
            n.startsWith("history.balloon") -> historyProperty(n.removePrefix("history.balloon"), history?.recentBalloons().orEmpty().mapNotNull { repository?.balloons?.find(it) }) { b, key, i -> balloonProperty(b, key, i) }
            n.startsWith("rateofuselist.") -> rateOfUseProperty(n.removePrefix("rateofuselist."))
            n.startsWith("developer.log.") -> developerLogProperty(n.removePrefix("developer.log."))
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
                if (s.induction) add("induction")
                if (s.timeCritical) add("timecritical")
                if (s.noUserBreak) add("nouserbreak")
                if (s.online) add("online")
                if (s.window.minimized) add("minimizing")
                if (s.waitingChoice) add("choosing")
                s.input?.let { add("opening(" + (if (it.kind == InputRequest.Kind.COMMUNICATE) "communicate" else "input") + ")") }
            }.joinToString(",")
            key == "shelllist.count" -> ghost.shellDirs.size.toString()
            key.startsWith("shelllist") -> listProperty(key.removePrefix("shelllist"), ghost.shellDirs, shell.dir) { dir, k, index ->
                when (k) {
                    "name" -> runCatching { Shell(dir).name }.getOrDefault(dir.name)
                    "path" -> dir.absolutePath + "/"
                    "index" -> index.toString()
                    "menu" -> shellMenu(dir)
                    else -> null
                }
            }
            key == "scope.count" -> characterScopes.size.toString()
            key.startsWith("balloon.scope(") -> {
                val m = Regex("""^balloon\.scope\((\d+)\)\.(.*)$""").find(key) ?: return null
                val scope = m.groupValues[1].toIntOrNull() ?: return null
                val g = platform.windowGeometry(scope)
                when (m.groupValues[2]) {
                    "num" -> (s.characters[scope]?.balloonId ?: 0).toString()
                    "count" -> (balloon?.images(scope)?.size ?: 1).toString()
                    "x" -> g?.balloon?.left?.toString() ?: "0"
                    "y" -> g?.balloon?.top?.toString() ?: "0"
                    "rect" -> (g?.balloon ?: ScreenRect(0, 0, 0, 0)).toString()
                    "scaling" -> formatNumber((g?.balloonScale ?: 1.0) * 100)
                    "vertical" -> "0"
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
                    "scaling" -> formatNumber((platform.windowGeometry(scope)?.scale ?: 1.0) * 100)
                    "rect" -> (platform.windowGeometry(scope)?.surface ?: ScreenRect(0, 0, 0, 0)).toString()
                    // 基準点（ふつうはサーフェスの下の真ん中）
                    "x" -> platform.windowGeometry(scope)?.surface?.let { (it.left + it.right) / 2 }?.toString() ?: "0"
                    "y" -> platform.windowGeometry(scope)?.surface?.bottom?.toString() ?: "0"
                    "surface.x" -> platform.windowGeometry(scope)?.surface?.left?.toString() ?: "0"
                    "surface.y" -> platform.windowGeometry(scope)?.surface?.top?.toString() ?: "0"
                    "animation.num" -> (shell.surfaces[s.characters[scope]?.surface ?: -1]?.animations?.size ?: 0).toString()
                    else -> when {
                        m.groupValues[2].startsWith("currentmonitor.") -> property("system.monitor.index(0)." + m.groupValues[2].removePrefix("currentmonitor."))
                        m.groupValues[2].startsWith("surface(") -> {
                            val id = m.groupValues[2].substringAfter('(').substringBefore(')').toIntOrNull() ?: return null
                            if (m.groupValues[2].substringAfter(").") != "rect") return null
                            val (w, h) = surfaceSize(id) ?: return null
                            "0,0,$w,$h"
                        }
                        else -> null
                    }
                }
            }
            key == "seriko.surfacelist.all" -> shell.surfaceIds().joinToString(",")
            key == "seriko.surfacelist.defined" -> shell.surfaces.definitions.keys.sorted().joinToString(",")
            key == "seriko.sticky-window" -> s.window.stickyGroups.joinToString(";") { g -> g.sorted().joinToString(",") }
            key == "seriko.zorder" -> s.window.zorder.joinToString(";") { it.joinToString(",") }
            key.startsWith("seriko.tooltip.scope(") -> tooltipProperty(key.removePrefix("seriko.tooltip.scope("))
            key.startsWith("seriko.cursor.") || key.startsWith("mousecursor") || key.startsWith("balloon.mousecursor") -> cursorProperties[key].orEmpty()
            key == "sound.count" -> platform.soundStatus().size.toString()
            key.startsWith("sound.index(") -> {
                val i = key.substring(12, key.indexOf(')').takeIf { it > 12 } ?: return null).toIntOrNull() ?: return null
                platform.soundStatus().getOrNull(i)?.let { soundProperty(it, key.substringAfter(").", "")) }
            }
            key.startsWith("sound(") -> {
                val name = key.substring(6, key.lastIndexOf(").").takeIf { it > 6 } ?: return null)
                findSound(name)?.let { soundProperty(it, key.substring(key.lastIndexOf(").") + 2)) }
            }
            BIND_MENU.matches(key) -> bindMenu(scopeOfMenuKey(key))
            else -> ghostProperty(ghost, key, repository?.list()?.indexOfFirst { it.dir == ghost.dir } ?: 0)
        }
    }

    private fun formatNumber(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(Locale.ROOT, v).trimEnd('0').trimEnd('.')

    private fun networkProperty(key: String): String? {
        val n = platform.networkStatus() ?: return null
        return when (key) {
            "status" -> if (n.online) "online" else "offline"
            "ipaddress" -> n.addresses.firstOrNull().orEmpty()
            "type" -> n.type
            "cost" -> n.metering
            "downlink" -> formatNumber(n.maxMbps)
            "downlink.estimate" -> formatNumber(n.effectiveMbps)
            else -> null
        }
    }

    /** history.ghost(名前) / .index(N) / .count。使ったものから順 */
    private fun <T> historyProperty(rest: String, items: List<T>, resolve: (T, String, Int) -> String?): String? =
        if (rest == ".count") items.size.toString() else listProperty(rest, items, null, resolve)

    /** rateofuselist.index(順位).name など。起動していた時間の長い順 */
    private fun rateOfUseProperty(rest: String): String? {
        val h = history ?: return null
        if (!rest.startsWith("index(")) return null
        val rank = rest.substring(6, rest.indexOf(')').takeIf { it > 6 } ?: return null).toIntOrNull() ?: return null
        val all = h.usage()
        val u = all.getOrNull(rank) ?: return null
        val today = h.today()
        val g = repository?.find(u.dirName)
        fun percent(mine: Int, total: Int) = if (total <= 0) "0" else formatNumber(mine * 100.0 / total)
        return when (rest.substringAfter(").", "")) {
            "name" -> g?.name ?: u.dirName
            "sakuraname" -> g?.sakuraName.orEmpty()
            "keroname" -> g?.keroName.orEmpty()
            "boottime" -> u.boots.toString()
            "bootminute" -> u.minutes.toString()
            "boottimeweekly" -> u.boots(7, today).toString()
            "bootminuteweekly" -> u.minutes(7, today).toString()
            "boottimemonthly" -> u.boots(30, today).toString()
            "bootminutemonthly" -> u.minutes(30, today).toString()
            "percent" -> percent(u.minutes, all.sumOf { it.minutes })
            "percentweekly" -> percent(u.minutes(7, today), all.sumOf { it.minutes(7, today) })
            "percentmonthly" -> percent(u.minutes(30, today), all.sumOf { it.minutes(30, today) })
            else -> null
        }
    }

    /** developer.log.種別.count / .index(ID).項目 / 種別(名前).count / 種別(名前)(.index(ID)).項目。新しいものが 0 */
    private fun developerLogProperty(rest: String): String? {
        val kind = DeveloperLog.KINDS.firstOrNull { rest.startsWith("$it.") || rest.startsWith("$it(") } ?: return null
        var tail = rest.removePrefix(kind)
        var entries = developerLog.entries(kind)
        if (tail.startsWith("(")) {
            val close = tail.indexOf(')').takeIf { it > 0 } ?: return null
            val name = tail.substring(1, close)
            entries = entries.filter { it.name.equals(name, ignoreCase = true) }
            tail = tail.substring(close + 1)
            if (tail == ".count") return entries.size.toString()
            if (!tail.startsWith(".index(")) return entries.firstOrNull()?.let { logEntryProperty(it, tail.removePrefix(".")) }
        }
        if (tail == ".count") return entries.size.toString()
        if (!tail.startsWith(".index(")) return null
        val close = tail.indexOf(')').takeIf { it > 7 } ?: return null
        val entry = tail.substring(7, close).toIntOrNull()?.let(entries::getOrNull) ?: return null
        return logEntryProperty(entry, tail.substringAfter(").", ""))
    }

    private fun logEntryProperty(e: DeveloperLog.Entry, key: String): String? = when (key) {
        "type" -> e.type
        "name" -> e.name
        "value" -> e.value
        "time" -> java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT).format(Date(e.time))
        else -> null
    }

    /** 通信と更新のイベントを、開発のための記録に残す */
    private fun logEvent(event: String, references: List<String>) {
        val kind = when {
            NETWORK_EVENTS.any { event.startsWith(it) } -> "network"
            event.startsWith("OnUpdate") && !event.startsWith("OnUpdatedata") -> "update"
            else -> return
        }
        val type = if ("Failure" in event) "Error" else "Info"
        developerLog.add(kind, type, ghost.name, (listOf(event) + references.take(3)).joinToString(" "))
    }

    /** サーフェスの画像の大きさ（PNG の頭を読む）。画像が無ければ null */
    private fun surfaceSize(id: Int): Pair<Int, Int>? {
        val file = shell.surfaceImage(id) ?: return null
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(24)
                if (input.read(head) < 24) return null
                fun int(at: Int) = ((head[at].toInt() and 0xff) shl 24) or ((head[at + 1].toInt() and 0xff) shl 16) or
                    ((head[at + 2].toInt() and 0xff) shl 8) or (head[at + 3].toInt() and 0xff)
                int(16) to int(20)
            }
        }.getOrNull()
    }

    // ---- 書き換えられるプロパティ（\![set,property,名前,値]） ----

    /** \![set,property] で変えたツールチップ（キャラクターごとに 当たり判定 → 文字） */
    private val tooltipOverrides = HashMap<Int, MutableMap<String, String>>()

    /** マウスカーソルのプロパティ。スマートフォンにカーソルは無いので、書いたものを覚えて返すだけ */
    private val cursorProperties = HashMap<String, String>()

    /** [scope] のキャラクターの、当たり判定 [collision] に触れている間に出す文字（sakura.tooltips と \![set,property]） */
    fun tooltip(scope: Int, collision: String): String? =
        tooltipOverrides[scope]?.get(collision) ?: shell.surfaces.tooltips[scope]?.get(collision)

    private fun tooltips(scope: Int): Map<String, String> =
        LinkedHashMap(shell.surfaces.tooltips[scope].orEmpty()).apply { putAll(tooltipOverrides[scope].orEmpty()) }

    /** "0).textlist.count" / "0).textlist(Head).text" / "0).textlist.index(1).name" */
    private fun tooltipProperty(rest: String): String? {
        val scope = rest.substringBefore(')').toIntOrNull() ?: return null
        val tail = rest.substringAfter(").", "")
        val list = tooltips(scope)
        return when {
            tail == "textlist.count" -> list.size.toString()
            tail.startsWith("textlist.index(") -> {
                val i = tail.substring(15, tail.indexOf(')').takeIf { it > 15 } ?: return null).toIntOrNull() ?: return null
                val (name, text) = list.entries.toList().getOrNull(i)?.toPair() ?: return null
                when (tail.substringAfter(").", "")) {
                    "name" -> name
                    "text" -> text
                    else -> null
                }
            }
            tail.startsWith("textlist(") -> {
                val name = tail.substring(9, tail.lastIndexOf(").").takeIf { it > 9 } ?: return null)
                if (tail.endsWith(").text")) list[name].orEmpty() else null
            }
            else -> null
        }
    }

    /** シェル切り替えのメニューに出すか（hidden なら出さない）。\![set,property] で変えたものか、シェルの descript.txt の menu */
    private fun shellMenu(dir: File): String =
        ghost.loadProfile().getProperty("menu.shell.${dir.name}") ?: runCatching { Shell(dir).descript["menu"] }.getOrNull()?.trim().orEmpty()

    /** 着せ替えのメニュー（hidden / auto / none）。\![set,property] で変えたものか、シェルの descript.txt の sakura.menu など */
    private fun bindMenu(scope: Int): String =
        ghost.loadProfile().getProperty("menu.bind.${shell.dir.name}.$scope")
            ?: shell.descript["${Shell.scopeName(scope)}.menu"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "none"

    private fun scopeOfMenuKey(key: String): Int = when (val head = key.substringBefore(".bind.menu")) {
        "sakura" -> 0
        "kero" -> 1
        else -> head.removePrefix("char").toIntOrNull() ?: 0
    }

    private fun findSound(name: String): SoundStatus? = platform.soundStatus().firstOrNull { st ->
        soundName(st.file) == name || st.file.path == name || st.file.name == name
    }

    private fun soundProperty(st: SoundStatus, key: String): String? = when (key) {
        "name" -> soundName(st.file)
        "path" -> st.file.absolutePath
        "id" -> st.id.toString()
        "position" -> st.positionMs.toString()
        "duration" -> st.durationMs.toString()
        "playing" -> if (st.playing || st.paused) "1" else "0"
        "pause" -> if (st.paused) "1" else "0"
        "preload" -> if (st.preload) "1" else "0"
        "loop" -> if (st.loop) "1" else "0"
        "error" -> st.error
        else -> null
    }

    /**
     * \![set,property,名前,値]。書き換えられるのは SSP で [SET有効] のもののうち pono-droid にあるもの: シェルと着せ替えのメニューの
     * 出し方、sticky-window と zorder、ツールチップ、音の位置・再生・一時停止、マウスカーソル（覚えるだけ）。扱えたら true
     */
    fun setProperty(name: String, value: String): Boolean {
        val key = name.trim().removePrefix("currentghost.")
        if (!name.trim().startsWith("currentghost.")) return false
        when {
            key.startsWith("shelllist(") && key.endsWith(").menu") -> {
                val selector = key.substring(10, key.length - 6)
                val dir = ghost.shellDirs.firstOrNull { it.name == selector || runCatching { Shell(it).name }.getOrNull() == selector } ?: return false
                ghost.saveProfile(ghost.loadProfile().apply { setProperty("menu.shell.${dir.name}", value.trim().lowercase()) })
            }
            BIND_MENU.matches(key) -> {
                val v = value.trim().lowercase().takeIf { it in setOf("hidden", "auto", "none") } ?: return false
                ghost.saveProfile(ghost.loadProfile().apply { setProperty("menu.bind.${shell.dir.name}.${scopeOfMenuKey(key)}", v) })
            }
            key == "seriko.sticky-window" -> {
                val used = HashSet<Int>()
                val groups = value.split(';').mapNotNull { g ->
                    val ids = g.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
                    ids.takeIf { it.size >= 2 && ids.none { id -> id in used } }?.also { used += it }
                }
                _state.update { it.copy(window = it.window.copy(stickyGroups = groups)) }
            }
            key == "seriko.zorder" -> {
                val groups = value.split(';').map { g -> g.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } }.filter { it.size >= 2 }
                val explicit = groups.flatten().map { it.first() in "sb" }.distinct()
                if (explicit.size > 1) return false
                _state.update { it.copy(window = it.window.copy(zorder = groups)) }
            }
            key.startsWith("seriko.tooltip.scope(") -> {
                val scope = key.substringAfter("scope(").substringBefore(')').toIntOrNull() ?: return false
                val tail = key.substringAfter(").", "")
                if (!tail.startsWith("textlist(") || !tail.endsWith(").text")) return false
                tooltipOverrides.getOrPut(scope) { LinkedHashMap() }[tail.substring(9, tail.length - 6)] = value
            }
            key.startsWith("seriko.cursor.") || key.startsWith("mousecursor") || key.startsWith("balloon.mousecursor") -> cursorProperties[key] = value
            key.startsWith("sound(") || key.startsWith("sound.index(") -> {
                val st = if (key.startsWith("sound(")) {
                    findSound(key.substring(6, key.lastIndexOf(").").takeIf { it > 6 } ?: return false))
                } else {
                    key.substring(12, key.indexOf(')').takeIf { it > 12 } ?: return false).toIntOrNull()?.let(platform.soundStatus()::getOrNull)
                } ?: return false
                val on = value.trim() == "1"
                when (key.substring(key.lastIndexOf(").") + 2)) {
                    "position" -> platform.soundControl("option", st.file, SoundOptions(seekMs = SoundOptions.seekTime(value.trim().removePrefix("@"), value.trim().startsWith("@")), seekRelative = value.trim().startsWith("@")))
                    "playing" -> if (on) platform.playSound(st.file, st.loop, SoundOptions()) else platform.stopSound(st.file)
                    "pause" -> platform.soundControl(if (on) "pause" else "resume", st.file, SoundOptions())
                    else -> return false
                }
            }
            else -> return false
        }
        return true
    }

    /** descript.txt の readme（無ければ readme.txt）の中身 */
    private fun readmeText(): String {
        val name = ghost.descript["readme"]?.takeIf { it.isNotBlank() } ?: "readme.txt"
        val file = File(ghost.masterDir, name).takeIf { it.isFile } ?: File(ghost.dir, name).takeIf { it.isFile }
            ?: return "$name がありません"
        return runCatching { TextCharsets.decode(file.readBytes()) }.getOrDefault("$name を読めませんでした")
    }

    private inner class Host(private val raised: MutableList<Pair<String, List<String>>>, override val untrusted: Boolean = false) : ScriptHost {
        /** このスクリプトのあいだだけ有効。0 以下なら消さない／待ち続ける */
        var balloonTimeoutMs = this@GhostController.balloonTimeoutMs
        var choiceTimeoutMs = this@GhostController.choiceTimeoutMs

        /** `\![updatebymyself]`。スクリプトが終わってから始める */
        var updateRequest: Pair<List<String>, Set<String>>? = null

        /** `\![set,serikotalk,false]` の間は口パク（SERIKO の talk）を動かさない。このスクリプトの間だけ */
        private var serikoTalk = true

        override fun clearPart(scope: Int, lines: Boolean, count: Int, start: Int?) = _state.update {
            it.update(scope) { c ->
                val items = c.balloon ?: return@update c
                c.copy(balloon = if (lines) BalloonEdit.removeLines(items, count, start) else BalloonEdit.removeChars(items, count, start))
            }
        }

        override fun newLine(scope: Int, percent: Int) = _state.update { it.update(scope) { c -> c.append(BalloonItem.LineBreak(percent)) } }

        override fun setVerticalAlign(scope: Int, align: VerticalAlign) =
            _state.update { it.update(scope) { c -> c.append(BalloonItem.VAlign(align)) } }

        override fun setTimeCritical() = _state.update { it.copy(timeCritical = true) }

        override fun lock(what: String, locked: Boolean, manual: Boolean): Boolean {
            // 止めたときの見た目を取っておき、外すまでそれを描く（もう止めていれば、最初に止めたときのまま）
            when (what) {
                "balloonrepaint" -> {
                    balloonLocked = locked
                    manualBalloonLock = locked && manual
                    _state.update { it.withLocks() }
                }
                "repaint" -> {
                    repaintLocked = locked
                    manualRepaintLock = locked && manual
                    _state.update { it.withLocks() }
                }
                // バルーンは指で動かせない（いつもキャラクターに付いている）ので、止めるまでもない
                "balloonmove" -> {}
                else -> return false
            }
            return true
        }

        override fun setMode(scope: Int, name: String, on: Boolean, args: List<String>): Boolean {
            when (name) {
                // \![enter,collisionmode(,rect)]。rect なら名前を出さず枠だけ
                "collisionmode" -> _state.update {
                    it.copy(collisionMode = if (!on) null else if (args.firstOrNull()?.trim()?.lowercase() == "rect") "rect" else "names")
                }
                "selectmode" -> if (on) enterSelectMode(scope, args) else leaveSelectMode()
                else -> return setMode(name, on)
            }
            return true
        }

        override fun setMode(name: String, on: Boolean): Boolean {
            when (name) {
                "onlinemode" -> onlineMode = on
                "nouserbreakmode" -> _state.update { it.copy(noUserBreak = on) }
                "inductionmode" -> _state.update { it.copy(induction = on) }
                else -> return false
            }
            return true
        }

        /** \f の飾り（キャラクターごと） */
        private val styles = HashMap<Int, TextStyle>()

        override fun requestUpdate() = requestUpdate(listOf("ghost"), emptySet())

        override fun requestUpdate(types: List<String>, options: Set<String>) {
            if (types.isNotEmpty()) updateRequest = types to options
        }

        override fun updateOther(args: List<String>) = this@GhostController.updateOther(args)

        override fun execute(name: String, args: List<String>): Boolean {
            HttpCommand.parse(name, args)?.let {
                executeHttp(it)
                return true
            }
            val opts = args.map { it.trim() }.filter { it.startsWith("--") }
                .associate { it.substring(2).substringBefore('=').lowercase() to it.substringAfter('=', "") }
            when (name) {
                "resetwindowpos" -> resetWindowPositions()
                "websocket" -> return args.firstOrNull()?.let { openWebSocket(it, args.drop(1)) } ?: false
                "ping" -> return ping(opts)
                "nslookup" -> return nslookup(opts)
                "filewatch" -> return fileWatch(args.firstOrNull().orEmpty(), opts)
                "extractarchive", "compressarchive" -> archive(name == "compressarchive", args)
                "install" -> return executeInstall(args) != null
                "dropdefault" -> return dropDefaultCommand(args)
                "createnar" -> createNar(args)
                "createupdatedata" -> createUpdateData(args)
                "dumpsurface" -> return dumpSurface(args) != false
                "dumpballoon" -> return dumpBalloon(args) != false
                "schedule-add" -> scheduleAdd(args)
                "schedule-delete" -> scheduleDelete(args)
                "schedule-get" -> scheduleGet(args)
                else -> return false
            }
            return true
        }

        /** http などは --async でなければ通信が終わるまで、ZIP は --event が無ければ終わるまで待つ（SSP と同じ） */
        override suspend fun runExecute(name: String, args: List<String>): Boolean {
            HttpCommand.parse(name, args)?.let { cmd ->
                val job = executeHttp(cmd)
                if (!cmd.async) job.join()
                return true
            }
            if (name == "extractarchive" || name == "compressarchive") {
                archive(name == "compressarchive", args)?.join()
                return true
            }
            // イベントを指定しなければ、書き出し終わるまで待つ（SSP と同じ）
            if (name == "dumpsurface" || name == "dumpballoon") {
                when (val r = if (name == "dumpsurface") dumpSurface(args) else dumpBalloon(args)) {
                    is Job -> r.join()
                    false -> return false
                }
                return true
            }
            return execute(name, args)
        }

        override fun cancel(kind: String, args: List<String>): Boolean = when (kind) {
            "http", "ical", "rss" -> args.firstOrNull()?.let(::cancelHttp) ?: false
            "websocket" -> args.firstOrNull()?.let(::cancelWebSocket) ?: false
            "filewatch" -> args.firstOrNull()?.let(::cancelFileWatch) ?: false
            else -> false
        }

        override fun send(kind: String, args: List<String>): Boolean {
            val url = args.firstOrNull() ?: return false
            when (kind) {
                "websocket" -> sendWebSocket(false, url, args.drop(1))
                "websocket-binary" -> sendWebSocket(true, url, args.drop(1))
                else -> return false
            }
            return true
        }

        override fun adjustClock() = startSntp()

        override fun correctClock() = platform.log("\\6: 端末の時計は直せません（ずれを測るだけ）")

        override fun nudge(scope: Int, away: Boolean) {
            _state.update { it.update(scope) { c -> c.copy(nudge = ++animationCounter to away) } }
        }

        override fun bringToFront() {
            _state.update { it.copy(window = it.window.copy(frontRequests = it.window.frontRequests + 1)) }
        }

        override fun bind(scope: Int, category: String, part: String, on: Boolean?) {
            val group = shell.bindGroups(scope).firstOrNull { it.category == category && it.part == part } ?: return
            applyBind(scope, group, on, byUser = false)
        }

        override fun playSound(file: String, loop: Boolean, volume: Int?) = playSound(file, loop, SoundOptions(volume = volume))

        override fun playSound(file: String, loop: Boolean, options: SoundOptions) {
            val f = soundFile(file) ?: return platform.log("音が見つかりません: $file")
            soundNames[f.path] = file
            platform.playSound(f, loop, options)
        }

        override fun soundControl(command: String, file: String?, options: SoundOptions) {
            val f = file?.let { soundFile(it) ?: return platform.log("音が見つかりません: $it") }
            if (f != null && file != null) soundNames[f.path] = file
            platform.soundControl(command, f, options)
        }

        override fun stopSound(file: String?) {
            if (file == null) return platform.stopSound()
            platform.stopSound(soundFile(file) ?: return)
        }

        /** 読み上げと発言履歴に渡す文字。キャラクターが替わるか、スクリプトが終わったらまとめて渡す */
        private val voiceText = StringBuilder()
        private var voiceScope = 0

        override fun voice(scope: Int, text: String) {
            if (scope != voiceScope) flushVoice()
            voiceScope = scope
            voiceText.append(text)
        }

        fun flushVoice() {
            val text = voiceText.toString().trim()
            voiceText.clear()
            if (text.isEmpty()) return
            addBacklog(voiceScope, text)
            if (platform.speechEnabled()) platform.speak(voiceScope, text)
        }

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
                // \![set,windowstate,minimize]。stayontop はオーバーレイがいつもいちばん上なので何もしない
                "windowstate" -> when (args.firstOrNull()?.trim()?.lowercase()) {
                    "minimize" -> minimize("script")
                    "stayontop", "!stayontop" -> {}
                    else -> return false
                }
                // \![set,zorder,1,0]（左ほど手前）。\![reset,zorder] は引数なしで来る
                "zorder" -> setZorder(args)
                // \![set,sticky-window,1,0]。\![reset,sticky-window] は引数なしで来る
                "sticky-window" -> setSticky(args)
                // \![set,alignmenttodesktop,top|bottom|left|right|free|default]
                "alignmenttodesktop" -> {
                    val edge = when (val a = args.firstOrNull()?.trim()?.lowercase()) {
                        "top", "bottom", "left", "right" -> a
                        "free", "default" -> null
                        else -> return false
                    }
                    _state.update { it.update(scope) { c -> c.copy(alignment = edge) } }
                }
                // \![set,autoscroll,disable|enable]
                "autoscroll" -> {
                    val on = when (args.firstOrNull()?.trim()?.lowercase()) {
                        "disable", "false", "0" -> false
                        "enable", "true", "1" -> true
                        else -> return false
                    }
                    _state.update { it.update(scope) { c -> c.copy(autoScroll = on) } }
                }
                // \![set,balloonoffset,x,y]。@ 付きは既定の位置から（pono-droid の既定はずらさない位置なので、どちらも同じ）
                "balloonoffset" -> {
                    fun coord(i: Int) = args.getOrNull(i)?.trim()?.removePrefix("@")?.toIntOrNull()
                    val x = coord(0) ?: return false
                    val y = coord(1) ?: 0
                    fun clamp(v: Int) = v.coerceIn(-MAX_BALLOON_OFFSET, MAX_BALLOON_OFFSET)
                    _state.update { it.update(scope) { c -> c.copy(balloonOffset = clamp(x) to clamp(y)) } }
                }
                // \![set,balloonalign,left|center|top|right|bottom|none]
                "balloonalign" -> {
                    val align = when (val a = args.firstOrNull()?.trim()?.lowercase()) {
                        "left", "right", "bottom" -> a
                        "center", "top" -> "center"
                        else -> null
                    }
                    _state.update { it.update(scope) { c -> c.copy(balloonAlign = align) } }
                }
                // \![set,balloonmarker,文字]。空なら消す
                "balloonmarker" -> {
                    val marker = args.joinToString(",").trim().take(MAX_BALLOON_LABEL).takeIf { it.isNotEmpty() }
                    _state.update { it.update(scope) { c -> c.copy(balloonMarker = marker) } }
                }
                // \![set,balloonnum,名前,今,最大]。全部空なら消す
                "balloonnum" -> {
                    val name = args.getOrNull(0)?.trim().orEmpty().take(MAX_BALLOON_LABEL)
                    val now = args.getOrNull(1)?.trim()?.toIntOrNull()
                    val max = args.getOrNull(2)?.trim()?.toIntOrNull()
                    val count = listOfNotNull(now, max).joinToString("/")
                    val label = listOf(name, count).filter { it.isNotEmpty() }.joinToString(" ").takeIf { it.isNotEmpty() }
                    _state.update { it.update(scope) { c -> c.copy(balloonNum = label) } }
                }
                // \![set,serikotalk,true|false]
                "serikotalk" -> serikoTalk = when (args.firstOrNull()?.trim()?.lowercase()) {
                    "false", "0", "disable" -> false
                    "true", "1", "enable" -> true
                    else -> return false
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
            when (name) {
                "wallpaper" -> return platform.setWallpaper(wallpaperRequest(args) ?: return false)
                // 開発用パレットの SHIORI デバッグモード（enable_debug）。引数が無ければ入れる
                "shioridebugmode" -> {
                    val on = args.firstOrNull()?.trim()?.lowercase()?.let { it == "true" || it == "1" } ?: true
                    notify("enable_debug", listOf(if (on) "1" else "0"))
                    return true
                }
                // \![set,trayballoon,--text=...,--title=...,--icon=...]。--text は何個でも書け、1つずつ1行になる
                "trayballoon", "tasktrayballoon" -> {
                    fun opt(key: String) = args.map { it.trim() }.filter { it.startsWith("--$key=") }.map { it.substringAfter('=') }
                    val text = opt("text").joinToString("\n").takeIf { it.isNotEmpty() } ?: return false
                    platform.showTrayBalloon(opt("title").firstOrNull().orEmpty(), text, opt("icon").firstOrNull()?.lowercase() ?: "none")
                    return true
                }
                "trayicon", "tasktrayicon" -> {
                    platform.setTrayText(args.getOrNull(1)?.trim().orEmpty())
                    return true
                }
            }
            val ms = args.firstOrNull()?.trim()?.toLongOrNull() ?: return false
            when (name) {
                "balloontimeout" -> balloonTimeoutMs = ms
                "choicetimeout" -> choiceTimeoutMs = ms
                else -> return false
            }
            return true
        }

        /** \s[...]。シェルの surface.alias（と surfaces.txt の name）の別名なら、その番号（候補が複数ならランダムに1つ）にする */
        override fun setSurface(scope: Int, surface: String) {
            val id = shell.surfaces.resolve(scope, surface) ?: return platform.log("知らないサーフェスです: \\s[$surface]")
            setSurface(scope, id)
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

        /** いま話しているキャラクター（starttalk を動かした後、まだ endtalk を動かしていない） */
        private val speaking = LinkedHashSet<Int>()

        /**
         * [scope] が話した。初めてなら SERIKO の starttalk を、それまで話していたほかのキャラクターには endtalk を動かす
         * （SSP ではスコープを外れたときに endtalk。スコープが変わっただけでは分からないので、次のキャラクターが話したときにする）
         */
        private fun speak(scope: Int) {
            if (scope in speaking) return
            val ended = speaking.toList()
            speaking.clear()
            speaking += scope
            _state.update { s ->
                ended.fold(s) { acc, other -> acc.update(other) { c -> c.copy(talkEnds = c.talkEnds + 1) } }
                    .update(scope) { c -> c.copy(talkStarts = c.talkStarts + 1) }
            }
        }

        /** スクリプトが終わった（止められた）。話していたキャラクターの endtalk を動かす */
        fun endSpeaking() {
            val ended = speaking.toList()
            speaking.clear()
            if (ended.isNotEmpty()) _state.update { s -> ended.fold(s) { acc, other -> acc.update(other) { c -> c.copy(talkEnds = c.talkEnds + 1) } } }
        }

        override fun appendText(scope: Int, text: String) {
            talked()
            speak(scope)
            _state.update { it.update(scope) { c -> c.appendText(text, styles[scope] ?: TextStyle.Default, countTalk = serikoTalk) } }
        }

        override fun newLine(scope: Int) = _state.update { it.update(scope) { c -> c.append(BalloonItem.NewLine) } }

        override fun clearBalloon(scope: Int) = _state.update { it.update(scope) { c -> c.copy(balloon = emptyList()) } }

        override fun addChoice(scope: Int, choice: Choice) = addChoice(scope, choice, ChoiceLooks.Default)

        override fun addChoice(scope: Int, choice: Choice, looks: ChoiceLooks) {
            talked()
            speak(scope)
            _state.update { it.update(scope) { c -> c.append(BalloonItem.ChoiceItem(choice, looks)) } }
        }

        override fun startAnimation(scope: Int, id: Int) = command(scope, AnimationCommand.Start(id))

        override fun startAnimation(scope: Int, key: String) {
            animationId(scope, key)?.let { startAnimation(scope, it) } ?: platform.log("知らないアニメーションです: \\i[$key]")
        }

        override suspend fun waitAnimation(scope: Int, key: String) {
            val id = animationId(scope, key) ?: return
            this@GhostController.waitAnimation(scope, id)
        }

        override fun setSyncObject(name: String, signaled: Boolean) = SyncObjects.set(name, signaled)

        override suspend fun waitSyncObject(name: String, timeoutMs: Long) {
            SyncObjects.await(name, timeoutMs)
        }

        /** アニメーションの番号か、今のサーフェスの animation*.name の名前を番号にする */
        private fun animationId(scope: Int, key: String): Int? {
            val surface = _state.value.characters[scope]?.surface ?: -1
            return shell.surfaces[surface]?.animationId(key) ?: key.trim().toIntOrNull()
        }

        override fun animation(scope: Int, args: List<String>): Boolean {
            fun int(i: Int) = args.getOrNull(i)?.trim()?.toIntOrNull()
            fun id(i: Int) = args.getOrNull(i)?.let { animationId(scope, it) }
            when (args.firstOrNull()?.trim()?.lowercase()) {
                "offset" -> command(scope, AnimationCommand.Offset(id(1) ?: return false, int(2) ?: 0, int(3) ?: 0))
                "clear" -> command(scope, AnimationCommand.Clear(id(1) ?: return false))
                "stop" -> command(scope, AnimationCommand.StopAll)
                // SSP の例には \![anim,pouse,ID] という書き間違いもあるので、それも読む
                "pause", "pouse" -> command(scope, AnimationCommand.Pause(id(1) ?: return false))
                "resume" -> command(scope, AnimationCommand.Resume(id(1) ?: return false))
                "add" -> command(scope, addCommand(args.drop(1)) ?: return false)
                else -> return false
            }
            return true
        }

        /**
         * \![anim,add,overlay,ID,x,y,時間,ID,x,y,時間...] / overlayfast / base / move,x,y /
         * text,x,y,幅,高さ,文字,表示時間,r,g,b,大きさ,フォント
         */
        private fun addCommand(args: List<String>): AnimationCommand? {
            fun int(i: Int) = args.getOrNull(i)?.trim()?.toIntOrNull()
            return when (val method = args.firstOrNull()?.trim()?.lowercase()) {
                "overlay", "overlayfast", "base" -> {
                    val frames = args.drop(1).chunked(4).mapNotNull { g ->
                        val id = g.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
                        val wait = g.getOrNull(3)?.trim()?.toIntOrNull()?.coerceIn(0, MAX_ADD_WAIT_MS) ?: 0
                        PatternFrame(
                            if (method == "overlayfast") "overlay-fast" else method, id, wait, wait,
                            g.getOrNull(1)?.trim()?.toIntOrNull() ?: 0, g.getOrNull(2)?.trim()?.toIntOrNull() ?: 0,
                        )
                    }.take(SurfaceDefinition.MAX_PATTERNS)
                    if (frames.isEmpty()) null else AnimationCommand.Add(frames)
                }
                "move" -> AnimationCommand.Add(listOf(PatternFrame("move", -1, 0, 0, int(1) ?: 0, int(2) ?: 0)))
                "text" -> {
                    val text = args.getOrNull(5) ?: return null
                    val color = if (args.size >= 10) ScriptPlayer.parseColor(args.subList(7, 10)) else null
                    AnimationCommand.AddText(
                        int(1) ?: 0, int(2) ?: 0, int(3) ?: 0, int(4) ?: 0, text.take(MAX_BALLOON_LABEL * 10),
                        (args.getOrNull(6)?.trim()?.toLongOrNull() ?: 0L).coerceIn(0, MAX_ADD_TEXT_MS),
                        color, int(10)?.coerceIn(1, 200), args.getOrNull(11)?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
                else -> null
            }
        }

        private fun command(scope: Int, command: AnimationCommand) =
            _state.update { it.update(scope) { c -> c.command(++animationCounter, command) } }

        override fun addMarker(scope: Int) = _state.update { it.update(scope) { c -> c.append(BalloonItem.Marker) } }

        override fun moveCursor(scope: Int, x: Int) = _state.update { it.update(scope) { c -> c.append(BalloonItem.Column) } }

        override fun setBalloonId(scope: Int, id: Int) = _state.update { it.update(scope) { c -> c.copy(balloonId = id) } }

        override fun setFont(scope: Int, style: TextStyle) {
            styles[scope] = style.copy(font = style.font?.map(::fontCandidate))
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
                "terms" -> return showTerms()
                "aigraph" -> scope.launch { aiStateScript(0)?.let { runScript(it) } }
                "rateofusegraph", "rateofusegraphballoon", "rateofusegraphtotal" -> rateOfUseScript(0)?.let { runScript(it) } ?: return false
                // SSP のヘルプの代わりに、pono-droid の説明を開く
                "help" -> platform.openUrl(HELP_URL)
                "file" -> return openGhostFile(args.firstOrNull().orEmpty())
                // ファイラーは Android に無いので、そのファイルをほかのアプリで開く
                "explorer" -> return openGhostFile(args.firstOrNull().orEmpty())
                "editor" -> {
                    val file = ghostPath(args.firstOrNull().orEmpty())?.takeIf { it.isFile } ?: return false
                    return platform.editFile(file, args.getOrNull(1)?.trim()?.toIntOrNull())
                }
                "mailer" -> {
                    val address = args.firstOrNull()?.trim().orEmpty()
                    if (address.isNotEmpty() && !MAIL_ADDRESS.matches(address)) return false
                    platform.openUrl("mailto:$address")
                }
                "configurationdialog", "ghostexplorer", "shellexplorer", "balloonexplorer", "dressupexplorer" -> platform.openSettings()
                "dialog" -> return openSystemDialog(args)
                "backlogviewer" -> platform.openDeveloperScreen("backlog")
                "errorlog" -> platform.openDeveloperScreen("log")
                // 開発者向けの機能を選んでいなければ何もしない（SSP と同じ）
                "surfacetest", "developer", "shiorirequest" -> if (platform.developerMode()) platform.openDeveloperScreen(kind)
                else -> return false
            }
            return true
        }

        override fun close(kind: String, args: List<String>): Boolean {
            if (kind == "websocket") {
                args.firstOrNull()?.let { closeWebSocket(it, args.getOrNull(1)?.trim()?.toIntOrNull() ?: 1000) } ?: return false
                return true
            }
            if (kind == "dialog") {
                platform.closeSystemDialog(args.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "__SYSTEM_ALL_DIALOG__" })
                return true
            }
            if (kind !in setOf("inputbox", "passwordinput", "communicatebox", "teachbox", "dateinput", "timeinput", "sliderinput", "textareabox", "ipinput")) return false
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
                "shiori" -> scope.launch { reloadShiori() }
                "shell" -> reloadShell()
                "balloon" -> reloadBalloon()
                "ghost", "descript" -> reloadGhost()
                // MAKOTO と AI グラフは pono-droid に無い
                "makoto", "aigraph" -> {}
                else -> platform.log("未対応の reload: $what")
            }
        }

        override fun setShioriLoaded(loaded: Boolean) {
            scope.launch {
                if (!loaded) {
                    requestLock.withLock {
                        session?.unload()
                        session = null
                    }
                } else if (session == null) {
                    loadShiori()
                }
            }
        }

        override fun vanish(args: List<String>) = vanishByMyself(args)

        override fun checkMail(account: String?) = this@GhostController.checkMail(account)

        override fun setProperty(name: String, value: String): Boolean = this@GhostController.setProperty(name, value)

        override fun createShortcut(args: List<String>): Boolean {
            // Android にはスタートアップのフォルダが無い
            if (args.any { it.trim().lowercase() == "startup" }) return false
            return platform.createShortcut(ghost.dir.name, ghost.name)
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
                // 端末が起きてからの時間（時）。%et はわざと間違えた時間（単位つき）、%wronghour はわざと間違えた今の時
                "exh" -> ((platform.deviceInfo()?.uptimeSeconds ?: 0) / 3600).toString()
                "et" -> "${kotlin.random.Random.nextInt(2, 1000)}${listOf("年", "万年", "億年", "世紀", "光年").random()}"
                "wronghour" -> ((now.get(Calendar.HOUR_OF_DAY) + kotlin.random.Random.nextInt(1, 24)) % 24).toString()
                "ms", "mz", "ml", "mc", "mh", "mt", "me", "mp", "m?", "dms" -> EnvWords.pick(name)
                // 最後にインストールしたゴーストの名前と、最後にインストールしたものの名前
                "lastghostname" -> lastInstalledGhost.orEmpty()
                "lastobjectname" -> lastInstalledObject.orEmpty()
                else -> null
            }
        }

        override fun unknownTag(tag: Token.Tag) = platform.log("未対応のタグ: \\${tag.name}${tag.args}")

        override fun refused(tag: Token.Tag) = platform.log("SSTP のスクリプトでは使えないタグ: \\${tag.name}${tag.args}")
    }

    companion object {
        /** \![open,help] で開く pono-droid の説明 */
        const val HELP_URL = "https://github.com/ukagaka-irono/pono-droid#readme"

        private val MAIL_ADDRESS = Regex("""^[^@\s,;?&]+@[^@\s,;?&]+$""")
        private val DUMP_RANGE = Regex("""^(\d+)(?:-(\d+))?$""")

        /** 一度に書き出すサーフェスの数 */
        private const val MAX_DUMP_SURFACES = 4096

        /** 発言履歴に残す数 */
        private const val MAX_BACKLOG = 200

        /** 名前を呼んでから、続けて言ったことをそのキャラクターへの言葉とみなす時間 */
        private const val VOICE_FOLLOW_MS = 30_000L
        private const val MAX_VOICE_TEXT = 500

        private val WALLPAPER_POSITIONS = setOf("center", "tile", "stretch", "fit", "fill")

        /** SSTP の IfGhost で、ほかのゴースト向けとして扱う名前（SSP と同じ） */
        private val DEFAULT_GHOST_NAMES = setOf("さくら", "エミリ", "えみりぃ")
        private const val SSTP_QUIET_MS = 16_000L
        private const val SSTP_NOBREAK_WAIT_MS = 60_000L
        private const val MAX_SSTP_COOKIES = 64
        private const val MAX_SSTP_COOKIE_LENGTH = 4096

        /** getaistateex で聞くグラフの数 */
        private const val MAX_AI_GRAPHS = 8

        /** getaistate がラベルを返さないときの名前（SSP と同じ） */
        private val AI_LABELS = listOf("Neuron", "NeuronM", "NeuronK", "NeuronD", "NeuronE", "Synapse")

        /** 使用率に並べるゴーストの数 */
        private const val MAX_RATE_ROWS = 10

        /** この言葉を含むログは、エラーログ（developer.log.error）にも Warning として残す */
        private val WARNING_WORDS = listOf("未対応", "できません", "ありません", "見つかりません", "失敗")

        /** 通信のイベント（developer.log.network に残す） */
        private val NETWORK_EVENTS = listOf("OnExecuteHTTP", "OnExecuteRSS", "OnExecuteICal", "OnExecuteWebSocket", "OnPing", "OnNSLookup", "OnSNTP", "OnURLDrop")

        /** sakura.bind.menu / kero.bind.menu / char2.bind.menu */
        private val BIND_MENU = Regex("""^(sakura|kero|char\d+)\.bind\.menu$""")

        /** アプリから渡されないとき（テストなど）のバージョン */
        const val BASEWARE_VERSION = "0.1.0"
        private val timerEvents = setOf("OnSecondChange", "OnMinuteChange", "OnMouseMove")

        /** `\![raise]` から起きたスクリプトがさらに `\![raise]` できる回数 */
        const val MAX_RAISE_CHAIN = 16

        /** 同時に走らせる http-get の数 */
        const val MAX_HTTP_GETS = 4

        /** http-get で受け取る大きさの上限 */
        const val HTTP_GET_MAX_BYTES = 64L * 1024 * 1024

        /** --nofile で Reference に入れる中身の上限（SSP と同じ 128KB）、rss / ical を読むときの上限、送るファイルの上限 */
        const val NOFILE_MAX_BYTES = 128L * 1024
        const val FEED_MAX_BYTES = 8L * 1024 * 1024
        const val HTTP_BODY_MAX_BYTES = 16L * 1024 * 1024

        /** --progress-notify を送る間隔 */
        const val PROGRESS_INTERVAL_MS = 500L

        /** 同時につなげる WebSocket の数、切れたときにつなぎ直す回数、1回の ping の数、見張れるファイルの数と見る間隔 */
        const val MAX_WEBSOCKETS = 8
        const val MAX_WEBSOCKET_RETRIES = 5
        const val MAX_PINGS = 20
        const val MAX_WATCHES = 16
        const val WATCH_INTERVAL_MS = 300L

        /** 時刻合わせで時刻を聞くサーバー */
        const val SNTP_SERVER = "pool.ntp.org"

        /** `\![timerraise]` で同時に予約できる数 */
        const val MAX_TIMERS = 32

        /** \p[N] で使えるキャラクターの番号の上限（ウィンドウをいくつも作らせないため） */
        const val MAX_SCOPE = ScriptPlayer.MAX_SCOPE

        /** \![set,scaling] の倍率の上限（4 = 400%）。画面より大きなウィンドウを作らせない */
        const val MAX_SCALING = 4f

        /** \![set,balloonoffset] でずらせる量の上限（シェル画像のピクセル） */
        const val MAX_BALLOON_OFFSET = 2000

        /** 共有で受け取る文字の長さの上限 */
        const val MAX_SHARED_TEXT = 64 * 1024

        /** \![set,balloonmarker] / \![set,balloonnum] の文字の長さの上限 */
        const val MAX_BALLOON_LABEL = 100

        /** OnCPULoadHigh などの境目（パーセント）と、高いのが何回続いたら知らせるか */
        const val LOAD_HIGH = 80
        const val LOAD_LOW = 60
        const val LOAD_HIGH_SAMPLES = 3

        /** 通信の相手がこれだけ答えなければ OnNetworkHeavy を送る。[NETWORK_TIMEOUT_MS] は通信をあきらめるまで（接続と読み込み） */
        const val NETWORK_HEAVY_MS = 10_000L
        const val NETWORK_TIMEOUT_MS = 45_000L

        /** \![anim,add,...] の1コマの時間と、\![anim,add,text] を出しておく時間の上限 */
        const val MAX_ADD_WAIT_MS = 60_000
        const val MAX_ADD_TEXT_MS = 600_000L

        /** \i[ID,wait] で、アニメーションが動き出すのを待つ時間と、終わるのを待つ時間の上限 */
        const val ANIMATION_START_WAIT_MS = 1_000L
        const val MAX_ANIMATION_WAIT_MS = 60_000L

        /** \f[name,...] でファイルとして扱う拡張子 */
        val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

        /** ゴーストを切り替えるとき、前のゴーストが話し終わるのを待つ長さ */
        const val CHANGE_WAIT_MS = 30_000L

        /** メニューと [changeBalloon] で簡易バルーンを表す名前（ディレクトリ名と重ならないよう / を含める） */
        const val SIMPLE_BALLOON = "/simple"
    }
}
