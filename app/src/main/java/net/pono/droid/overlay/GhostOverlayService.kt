package net.pono.droid.overlay

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.ghost.BatteryInfo
import net.pono.baseware.ghost.DeviceInfo
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.GhostHub
import net.pono.baseware.ghost.GhostPlatform
import net.pono.baseware.ghost.GhostRepository
import net.pono.baseware.ghost.InputRequest
import net.pono.baseware.ghost.NetworkStatus
import net.pono.baseware.ghost.PreviousGhost
import net.pono.baseware.ghost.ConnectedDevice
import net.pono.baseware.ghost.DeviceEvent
import net.pono.baseware.ghost.DiskInfo
import net.pono.baseware.ghost.SoundStatus
import net.pono.baseware.ghost.SurfaceDump
import net.pono.baseware.ghost.SystemDialogRequest
import net.pono.baseware.ghost.SystemInfo
import net.pono.baseware.ghost.WallpaperRequest
import net.pono.baseware.ghost.WindowGeometry
import net.pono.baseware.ghost.WindowState
import net.pono.baseware.net.SstpResponse
import net.pono.baseware.net.SstpServer
import net.pono.baseware.sakura.MoveCommand
import net.pono.baseware.sakura.SoundOptions
import net.pono.baseware.shell.Shell
import net.pono.droid.AppVersion
import net.pono.droid.DeveloperActivity
import net.pono.droid.GhostShortcutActivity
import net.pono.droid.GhostStore
import net.pono.droid.MainActivity
import net.pono.droid.R
import net.pono.droid.SystemDialogActivity
import net.pono.droid.TextViewerActivity
import java.io.File
import kotlin.math.roundToInt
import net.pono.shiori.yaya.RemoteShiori
import net.pono.shiori.yaya.ShioriService

/**
 * ゴーストを常駐させる。同時に動かすゴースト（[ShioriService.SLOTS] 体まで）ごとに [GhostSession] を持ち、
 * キャラクターごとのオーバーレイウィンドウを作る。画面・電池・ネットワークなど端末のことは、動いているゴーストすべてに伝える
 */
class GhostOverlayService : LifecycleService() {
    private val debuggable by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    private var loadJob: Job? = null
    private val speech by lazy { SpeechOutput(this) }

    /** 動いているゴースト（起動した順。先頭が今のゴースト） */
    private val sessions = mutableListOf<GhostSession>()

    /** ゴーストどうしをつなぐ（\![raiseother] や OnOtherGhost*） */
    private val hub = GhostHub()

    /** 動いているゴーストすべて */
    private val controllers: List<GhostController> get() = sessions.mapNotNull { it.controller }

    /** 今のゴースト（最初に起動したもの） */
    private val mainController: GhostController? get() = sessions.firstOrNull()?.controller

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            // 動いているゴーストをすべて終える（それぞれに OnCloseAll）
            ACTION_STOP -> if (controllers.isEmpty()) stopSelf() else controllers.forEach { it.close(force = true) }
            // アプリのゴースト一覧から始めたものは OnUpdateResultExplorer で結果を送る
            ACTION_UPDATE -> mainController?.networkUpdate(explorer = intent.getBooleanExtra(EXTRA_EXPLORER, false)) ?: toast("ゴーストが起動していません")
            ACTION_SHOW -> controllers.forEach { it.restoreWindows("user") }
            ACTION_TRAY_CLICK, ACTION_TRAY_DISMISS -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                // お知らせを出したゴーストに伝える（もういなければ今のゴースト）
                val dir = intent.getStringExtra(EXTRA_GHOST)
                val c = sessions.firstOrNull { it.dirName == dir }?.controller ?: mainController
                if (intent.action == ACTION_TRAY_CLICK) c?.onTrayBalloonClick(title, text) else c?.onTrayBalloonTimeout(title, text)
            }
            ACTION_START -> {
                startInForeground()
                val dirName = intent.getStringExtra(EXTRA_GHOST)
                val ghost = dirName?.let { GhostStore.repository(this).find(it) }
                val running = sessions.firstOrNull { it.dirName == dirName }
                when {
                    ghost == null || !Settings.canDrawOverlays(this) -> if (sessions.isEmpty()) stopSelf()
                    // 動いているゴーストの「再起動」やシェル・バルーンの切り替えは、ほかのゴーストを止めずにその場で
                    running != null -> restartInPlace(running, ghost)
                    else -> startOnly(ghost)
                }
            }
            // 一覧の「一緒に起動」。動いているゴーストはそのままにして、もう1体起動する
            ACTION_START_BESIDE -> {
                startInForeground()
                val ghost = intent.getStringExtra(EXTRA_GHOST)?.let { GhostStore.repository(this).find(it) }
                if (ghost != null && Settings.canDrawOverlays(this)) startBeside(ghost, calledBy = null)
                if (sessions.isEmpty()) stopSelf() else refreshForeground()
            }
        }
        return START_NOT_STICKY
    }

    /** 一覧から起動したゴースト。止めたゴーストの SHIORI を下ろし終えるまで待ってから起動する */
    private var pendingStart: Job? = null

    /**
     * アプリの一覧から起動した。[ghost] だけを動かす（動いているゴーストは、何も送らずに止める）。
     * 同じ SHIORI のプロセスに前のゴーストが残らないよう、下ろし終えてから読み込む
     */
    private fun startOnly(ghost: Ghost) {
        val stopping = teardown()
        pendingStart?.cancel()
        _loading.value = ghost.dir.name
        pendingStart = lifecycleScope.launch {
            withTimeoutOrNull(STOP_WAIT_MS) { stopping.joinAll() }
            startSession(ghost, previous = null, calledBy = null, slot = 0)
        }
    }

    /** 動いているゴースト [s] を、同じ場所（同じ SHIORI のプロセス）で起動し直す。何も送らずに止めてから */
    private fun restartInPlace(s: GhostSession, ghost: Ghost) {
        val i = sessions.indexOf(s)
        sessions.removeAt(i)
        val stopping = s.teardown()
        publish()
        _loading.value = ghost.dir.name
        lifecycleScope.launch {
            withTimeoutOrNull(STOP_WAIT_MS) { stopping?.join() }
            startSession(ghost, previous = null, calledBy = null, slot = s.slot, index = i)
        }
    }

    /** 一緒に動いているゴースト（本体側の名前が [sakuraName]）の \p[scope] のウィンドウ（\![move] の --base=本体側の名前/ID） */
    private fun otherGhostWindow(sakuraName: String, scope: Int): CharacterWindow? =
        sessions.firstOrNull { it.controller?.ghost?.sakuraName == sakuraName }?.window(scope)

    /** 使っていない SHIORI のプロセスの番号。どれも使っていれば null */
    private fun freeSlot(): Int? = (0 until ShioriService.SLOTS).firstOrNull { slot -> sessions.none { it.slot == slot } }

    /** ゴーストに呼ばれた（\![call,ghost]）。[dirName] のゴーストを一緒に起動する */
    private fun callGhost(dirName: String, caller: PreviousGhost): Boolean {
        val ghost = GhostStore.repository(this).find(dirName) ?: return false
        return startBeside(ghost, calledBy = caller)
    }

    /** 動いているゴーストはそのままにして、[ghost] も起動する（[calledBy] は呼んだゴースト。一覧からなら null） */
    private fun startBeside(ghost: Ghost, calledBy: PreviousGhost?): Boolean {
        if (sessions.any { it.dirName == ghost.dir.name }) return false
        val slot = freeSlot() ?: run {
            toast("同時に動かせるゴーストは ${ShioriService.SLOTS} 体までです")
            return false
        }
        startSession(ghost, previous = null, calledBy = calledBy, slot = slot)
        return true
    }

    private fun startSession(ghost: Ghost, previous: PreviousGhost?, calledBy: PreviousGhost?, slot: Int, index: Int = sessions.size) {
        val s = GhostSession(ghost.dir.name, slot)
        sessions.add(index.coerceIn(0, sessions.size), s)
        publish()
        s.start(ghost, previous, calledBy)
    }

    /** ゴーストが終わった。最後の1体ならサービスも止める */
    private fun endSession(s: GhostSession) {
        if (!sessions.remove(s)) return
        s.teardown()
        publish()
        if (sessions.isEmpty()) {
            teardown()
            stopSelf()
        } else {
            afterSessionsChanged()
        }
    }

    /** [s] のゴーストが閉じ終えたので、同じ場所（同じ SHIORI のプロセス）で次のゴーストを起こす */
    private fun replaceSession(s: GhostSession, next: Ghost, previous: PreviousGhost) {
        val i = sessions.indexOf(s)
        if (i < 0) return
        sessions.removeAt(i)
        s.teardown()
        startSession(next, previous, calledBy = null, slot = s.slot, index = i)
    }

    /** 動いているゴーストを、アプリの画面に知らせる */
    private fun publish() {
        _controllers.value = controllers
        _controller.value = mainController
        _running.value = sessions.firstOrNull()?.dirName
        _runningGhosts.value = sessions.map { it.dirName }
    }

    /** ゴーストが増えたり減ったりしたら、SSTP・声・通知を合わせる */
    private fun afterSessionsChanged() {
        updateSstp()
        updateVoice()
        refreshForeground()
    }

    /** 常駐の通知を、今の様子で出し直す（ゴーストがみな隠れていれば「表示」を出す） */
    private fun refreshForeground() {
        val all = controllers
        startInForeground(minimized = all.isNotEmpty() && all.all { it.state.value.window.minimized })
    }

    /**
     * 動いているゴーストすべてのキャラクターの重なりと見切れを、どのゴーストにも伝える（OnOtherOverlap / OnOtherOffscreen）。
     * キャラクターは「本体側の名前/番号」
     */
    private fun reportOtherLayout() {
        val shown = sessions.flatMap { s ->
            val name = s.controller?.ghost?.sakuraName ?: return@flatMap emptyList()
            s.shownRects().map { (scope, r) -> "$name/$scope" to r }
        }
        val screen = sessions.firstNotNullOfOrNull { it.screenBounds() } ?: return
        val overlaps = shown.flatMapIndexed { i, (a, ra) ->
            shown.drop(i + 1).filter { (_, rb) -> Rect.intersects(ra, rb) }.map { (b, _) -> a to b }
        }
        val offscreen = shown.filter { (_, r) -> !screen.contains(r) }.map { it.first }
        controllers.forEach { it.onOtherWindowLayout(overlaps, offscreen) }
    }

    /** 動いているゴースト1体ぶん（キャラクターのウィンドウ・音・SHIORI のプロセス）。[slot] は SHIORI のプロセスの番号 */
    private inner class GhostSession(val dirName: String, val slot: Int) {
        var controller: GhostController? = null

        private var touchJob: Job? = null
        private var inputJob: Job? = null
        private var startJob: Job? = null
        private var loadingJob: Job? = null
        private var scopesJob: Job? = null
        private var positionsJob: Job? = null
        private var windowJob: Job? = null

        /** 動かし終えた \![move] と \4 / \5 の通し番号（キャラクターごと） */
        private val handledMoves = HashMap<Int, Int>()
        private val handledNudges = HashMap<Int, Int>()
        private var passThrough = false
        private val sound = SoundPlayer(
            onStop = { controller?.onSoundStopped(it) },
            onLoop = { controller?.onSoundLooped(it) },
            onError = { file, code, message -> controller?.onSoundError(file, code, message) },
            onSpectrum = { file, bands, frame, position -> controller?.onSoundSpectrum(file, bands, frame, position) },
        )
        private val windows = mutableListOf<CharacterWindow>()

        /**
         * @param previous 切り替える前のゴースト（OnGhostChanged で渡す）。ふつうの起動なら null
         * @param calledBy このゴーストを呼んだゴースト（OnGhostCalled で渡す）
         */
        fun start(ghost: Ghost, previous: PreviousGhost?, calledBy: PreviousGhost?) {
            _loading.value = ghost.dir.name
            startJob = lifecycleScope.launch {
                // シェルの定義（surfaces*.txt）は大きいこともあるので、画面を描くスレッドの外で読む
                val loaded = withContext(Dispatchers.Default) {
                    val shellDir = ghost.shellDir() ?: return@withContext null
                    val repo = GhostStore.repository(this@GhostOverlayService)
                    // 選んだバルーン（無ければゴーストの指定）。どちらも無ければ簡易バルーン
                    Triple(Shell(shellDir), repo, ghost.balloon(repo.balloons))
                }
                if (loaded == null) {
                    _loading.value = null
                    toast("${ghost.name}: シェルがありません")
                    endSession(this@GhostSession)
                    return@launch
                }
                val (shell, repo, balloon) = loaded
                runGhost(ghost, previous, calledBy, shell, repo, balloon)
            }
        }


        private fun runGhost(ghost: Ghost, previous: PreviousGhost?, calledBy: PreviousGhost?, shell: Shell, repo: GhostRepository, balloon: Balloon?) {
            val c = GhostController(
                ghost = ghost,
                shell = shell,
                balloon = balloon,
                repository = repo,
                basewareVersion = AppVersion.of(this@GhostOverlayService).name,
                previous = previous,
                hub = hub,
                calledBy = calledBy,
                // 同時に動かすゴーストは、1体ずつ別の SHIORI のプロセスで
                shioriFactory = RemoteShiori.factory(this@GhostOverlayService, slot),
                scope = lifecycleScope,
                platform = object : GhostPlatform {
                    override fun deviceInfo(): DeviceInfo {
                        val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                        val metrics = resources.displayMetrics
                        return DeviceInfo(
                            osName = "Android",
                            osVersion = Build.VERSION.RELEASE.orEmpty(),
                            osBuild = Build.VERSION.SDK_INT.toString(),
                            arch = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                            cpuCount = Runtime.getRuntime().availableProcessors(),
                            memoryTotalMb = memory.totalMem / (1024 * 1024),
                            memoryFreeMb = memory.availMem / (1024 * 1024),
                            uptimeSeconds = SystemClock.elapsedRealtime() / 1000,
                            screenWidth = metrics.widthPixels,
                            screenHeight = metrics.heightPixels,
                            dpi = metrics.densityDpi,
                            cpuVendor = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE.orEmpty(),
                            cpuName = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "",
                            cpuFeatures = cpuFeatures,
                            cpuClockMhz = cpuClockMhz,
                        )
                    }

                    override fun soundMeta(file: File): Map<String, String> = SoundMeta.read(file, cacheDir)

                    // 内蔵 SAORI（internal_crypto の Protect / Unprotect、internal_file の MediaInfo、internal_time の --tz=）
                    override fun protectData(data: ByteArray) = SaoriPlatform.protect(data)

                    override fun unprotectData(data: ByteArray) = SaoriPlatform.unprotect(data)

                    override fun mediaInfo(file: File) = SaoriPlatform.mediaInfo(file)

                    override fun windowsZone(name: String) = SaoriPlatform.windowsZone(name)

                    override fun batteryInfo(): BatteryInfo? =
                        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::batteryInfo)

                    override fun systemInfo(): SystemInfo = this@GhostOverlayService.systemInfo()

                    /** 作った .nar などを「共有」で渡す。読む許可はそのファイル1つだけ */
                    override fun shareFile(file: File, mimeType: String) {
                        val uri = runCatching { FileProvider.getUriForFile(this@GhostOverlayService, "$packageName.files", file) }.getOrNull() ?: return
                        val send = Intent(Intent.ACTION_SEND).setType(mimeType).putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newRawUri(file.name, uri)
                        try {
                            startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (e: ActivityNotFoundException) {
                            toast("渡せるアプリが見つかりません")
                        }
                    }

                    /** ホーム画面にショートカットを置くよう頼む。置くかどうかは Android がユーザーに確かめる */
                    override fun createShortcut(dirName: String, label: String): Boolean {
                        val context = this@GhostOverlayService
                        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
                        val intent = Intent(context, GhostShortcutActivity::class.java).setAction(Intent.ACTION_VIEW)
                            .putExtra(GhostShortcutActivity.EXTRA_GHOST, dirName)
                        val info = ShortcutInfoCompat.Builder(context, "ghost:$dirName")
                            .setShortLabel(label.ifBlank { dirName })
                            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                            .setIntent(intent)
                            .build()
                        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
                    }

                    override suspend fun dumpSurfaces(dump: SurfaceDump): Int {
                        val r = renderer ?: return 0
                        val shell = controller?.shell ?: return 0
                        return withContext(Dispatchers.Default) {
                            var count = 0
                            for (id in dump.surfaces) {
                                val def = shell.surfaces[id]
                                val binds = SerikoAnimator.bindLayers(def, dump.binds)
                                if (writePng(r.render(id, binds, dump.binds), File(dump.dir, "${dump.prefix}$id.png"))) count++
                                // アニメーションのコマを、\i[ID] で動かしたときと同じ順に重ねて連番で
                                val anim = dump.animation?.let { def?.animations?.get(it) } ?: continue
                                var n = 0
                                for (frame in anim.frames) {
                                    if (frame.isControl) continue
                                    if (n >= MAX_DUMP_FRAMES) break
                                    if (writePng(r.render(id, binds + (anim.id to frame), dump.binds), File(dump.dir, "%s%d_%04d.png".format(dump.prefix, id, n)))) count++
                                    n++
                                }
                            }
                            count
                        }
                    }

                    override suspend fun dumpBalloon(scope: Int, file: File, hide: Set<String>): Boolean {
                        val bitmap = withContext(Dispatchers.Main) { windows.firstOrNull { it.scopeId == scope }?.balloonBitmap(hide) } ?: return false
                        return withContext(Dispatchers.IO) { writePng(bitmap, file) }
                    }

                    /** ゴーストのフォルダのファイルだけ、その1つを読む許可を付けて渡す */
                    override fun openFile(file: File, mimeType: String): Boolean {
                        val uri = runCatching {
                            androidx.core.content.FileProvider.getUriForFile(this@GhostOverlayService, "$packageName.files", file)
                        }.getOrNull() ?: return false
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        return try {
                            startActivity(intent)
                            true
                        } catch (e: ActivityNotFoundException) {
                            toast("開けるアプリが見つかりません")
                            false
                        }
                    }

                    override fun networkStatus(): NetworkStatus {
                        val cm = getSystemService(ConnectivityManager::class.java)
                        val network = cm.activeNetwork
                        return networkStatus(network?.let(cm::getNetworkCapabilities), network?.let(cm::getLinkProperties))
                    }

                    override fun showText(title: String, text: String) = TextViewerActivity.start(this@GhostOverlayService, title, text)

                    override fun openSystemDialog(request: SystemDialogRequest): Boolean {
                        SystemDialogActivity.start(this@GhostOverlayService, request, controller ?: return false)
                        return true
                    }

                    override fun closeSystemDialog(id: String?) {
                        controller?.let { SystemDialogActivity.close(id, it) }
                    }

                    override fun developerMode(): Boolean = OverlaySettings.developer(OverlaySettings.prefs(this@GhostOverlayService))

                    override fun alignmentPolicy(): String = OverlaySettings.alignmentPolicy(OverlaySettings.prefs(this@GhostOverlayService))

                    override fun openDeveloperScreen(page: String) = DeveloperActivity.start(this@GhostOverlayService, page, dirName)

                    override fun openSettings() {
                        startActivity(Intent(this@GhostOverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }

                    // メニューの入り切りは設定と同じところに書く（変わったことは設定の見張りが伝える）
                    override fun setCharacterScale(percent: Int): Boolean {
                        val v = (percent / 100f).coerceIn(OverlaySettings.MIN_CHARACTER_SCALE, OverlaySettings.MAX_CHARACTER_SCALE)
                        OverlaySettings.prefs(this@GhostOverlayService).edit().putFloat(OverlaySettings.KEY_CHARACTER_SCALE, v).apply()
                        return true
                    }

                    override fun characterScalePercent(): Int =
                        (OverlaySettings.characterScale(OverlaySettings.prefs(this@GhostOverlayService)) * 100).roundToInt()

                    override fun setSpeechEnabled(on: Boolean): Boolean {
                        OverlaySettings.prefs(this@GhostOverlayService).edit().putBoolean(OverlaySettings.KEY_SPEECH, on).apply()
                        return true
                    }

                    override fun setVoiceRecognitionEnabled(on: Boolean): Boolean {
                        val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        // マイクの許可は pono-droid の画面で聞く
                        if (on && !granted) {
                            openSettings()
                            return false
                        }
                        OverlaySettings.prefs(this@GhostOverlayService).edit().putBoolean(OverlaySettings.KEY_VOICE, on).apply()
                        return true
                    }

                    override fun playSound(file: File, loop: Boolean, volume: Int?) = sound.play(file, loop, SoundOptions(volume = volume))

                    override fun playSound(file: File, loop: Boolean, options: SoundOptions) = sound.play(file, loop, options)

                    override fun soundControl(command: String, file: File?, options: SoundOptions) {
                        when (command) {
                            "load" -> file?.let { sound.load(it, options) }
                            "option" -> file?.let { sound.option(it, options) }
                            "pause" -> sound.pause(file)
                            "resume" -> sound.resume(file)
                        }
                    }

                    override fun stopSound() = sound.stop()

                    override fun stopSound(file: File?) = sound.stop(file)

                    override fun showTrayBalloon(title: String, text: String, icon: String) = showGhostMessage(title, text, dirName)

                    /** 端末のカレンダーの [day] の予定。設定で許し、読む許可もあるときだけ */
                    override fun deviceSchedule(day: java.time.LocalDate): List<DeviceEvent> {
                        val context = this@GhostOverlayService
                        if (!OverlaySettings.calendar(OverlaySettings.prefs(context))) return emptyList()
                        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyList()
                        val zone = java.time.ZoneId.systemDefault()
                        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
                        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                            android.content.ContentUris.appendId(it, from)
                            android.content.ContentUris.appendId(it, to)
                        }.build()
                        val columns = arrayOf(
                            android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.DESCRIPTION,
                            android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.END,
                            android.provider.CalendarContract.Instances.ALL_DAY,
                        )
                        return runCatching {
                            contentResolver.query(uri, columns, null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                                buildList {
                                    while (c.moveToNext() && size < MAX_DEVICE_EVENTS) {
                                        val allDay = c.getInt(4) == 1
                                        // 終日の予定は UTC の 0 時で入っている
                                        val tz = if (allDay) java.time.ZoneOffset.UTC else zone
                                        fun time(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(tz).toLocalDateTime()
                                        add(DeviceEvent(c.getString(0).orEmpty(), c.getString(1).orEmpty(), time(c.getLong(2)), time(c.getLong(3)), allDay))
                                    }
                                }
                            }.orEmpty()
                        }.getOrDefault(emptyList())
                    }

                    override fun desktopWallpaperColor(): Int? =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                            runCatching {
                                android.app.WallpaperManager.getInstance(this@GhostOverlayService)
                                    .getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()?.and(0xFFFFFF)
                            }.getOrNull()
                        } else {
                            null
                        }

                    override fun setWallpaper(request: WallpaperRequest): Boolean {
                        lastWallpaperSetMs = System.currentTimeMillis()
                        if (!OverlaySettings.wallpaper(OverlaySettings.prefs(this@GhostOverlayService))) {
                            Log.i(TAG, "壁紙の変更はアプリの設定で許していません")
                            return false
                        }
                        lifecycleScope.launch {
                            val ok = withContext(Dispatchers.Default) {
                                runCatching {
                                    val metrics = resources.displayMetrics
                                    val bitmap = Wallpapers.compose(request, metrics.widthPixels, metrics.heightPixels) ?: return@runCatching false
                                    android.app.WallpaperManager.getInstance(this@GhostOverlayService)
                                        .setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                                    true
                                }.getOrDefault(false)
                            }
                            if (!ok) toast("壁紙を変えられませんでした")
                        }
                        return true
                    }

                    /** 書き換えてよいという許可を付けて、そのファイル1つだけ渡す */
                    override fun editFile(file: File, line: Int?): Boolean {
                        if (!OverlaySettings.developer(OverlaySettings.prefs(this@GhostOverlayService))) return false
                        val uri = runCatching { FileProvider.getUriForFile(this@GhostOverlayService, "$packageName.files", file) }.getOrNull() ?: return false
                        val intent = Intent(Intent.ACTION_EDIT).setDataAndType(uri, "text/plain")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        if (line != null) intent.putExtra("line", line)
                        return try {
                            startActivity(intent)
                            true
                        } catch (e: ActivityNotFoundException) {
                            toast("書き換えられるアプリが見つかりません")
                            false
                        }
                    }

                    override fun setTrayText(text: String) {
                        trayText = text.takeIf { it.isNotBlank() }
                        refreshForeground()
                    }

                    override fun openAiGraph(title: String, graphs: List<net.pono.baseware.ghost.AiGraph>, logo: net.pono.baseware.ghost.AiGraphLogo?): Boolean {
                        net.pono.droid.AiGraphActivity.start(this@GhostOverlayService, graphs, logo)
                        return true
                    }

                    override fun copyText(text: String): Boolean = runCatching {
                        getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText(controller?.ghost?.name ?: "pono-droid", text))
                    }.isSuccess

                    override fun hostName(): String? =
                        runCatching { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME) }.getOrNull()
                            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

                    override fun showHanayu(graph: net.pono.baseware.ghost.HanayuGraph): Boolean {
                        net.pono.droid.AiGraphActivity.startHanayu(this@GhostOverlayService, graph)
                        return true
                    }

                    override fun hideHanayu(): Boolean = net.pono.droid.AiGraphActivity.hideHanayu()

                    override fun setTrayIcon(file: File?) {
                        trayIcon = file
                        refreshForeground()
                    }

                    override fun windowGeometry(scope: Int): WindowGeometry? =
                        windows.firstOrNull { it.scopeId == scope }?.geometry(characterScale.toDouble(), balloonScale.toDouble())

                    /** アプリの置き場（内部）と、端末の共有の置き場 */
                    override fun diskInfo(): List<DiskInfo> = listOfNotNull(filesDir, getExternalFilesDir(null)).mapNotNull { dir ->
                        runCatching {
                            val stat = android.os.StatFs(dir.path)
                            val external = dir != filesDir
                            DiskInfo(
                                mountpoint = if (external) android.os.Environment.getExternalStorageDirectory().path else android.os.Environment.getDataDirectory().path,
                                type = if (external && android.os.Environment.isExternalStorageRemovable(dir)) "removable" else "fixed",
                                totalMb = stat.totalBytes / (1024 * 1024),
                                freeMb = stat.availableBytes / (1024 * 1024),
                            )
                        }.getOrNull()
                    }.distinctBy { it.mountpoint }

                    override fun soundStatus(): List<SoundStatus> = sound.statuses()

                    override fun imageMainColor(file: File): Int? = BalloonColors.mainColor(file)

                    override fun voiceRecognitionEnabled(): Boolean = voiceAllowed()

                    override fun mailAccounts(): List<net.pono.baseware.net.MailAccount> = runCatching { MailAccounts.load(this@GhostOverlayService) }.getOrDefault(emptyList())

                    override fun mailCheckMinutes(): Int = MailAccounts.intervalMinutes(this@GhostOverlayService)

                    override fun speechEnabled(): Boolean = OverlaySettings.speech(OverlaySettings.prefs(this@GhostOverlayService))

                    override fun speak(scope: Int, text: String) = speech.speak(scope, text)

                    override suspend fun waitSound() = sound.await()

                    override fun onShellChanged(shell: Shell) {
                        controller?.let { rebuildWindows(it) }
                    }

                    override fun onBalloonChanged(balloon: Balloon?) {
                        controller?.let { rebuildWindows(it) }
                    }

                    /** ウェブのページだけ開く。ゴーストのスクリプトに他のアプリ（電話や決済など）を起動させない */
                    override fun openUrl(url: String) {
                        val uri = Uri.parse(url)
                        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
                            Log.w(TAG, "unsupported url scheme: $url")
                            return
                        }
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: ActivityNotFoundException) {
                            toast("ブラウザが見つかりません")
                        }
                    }

                    /** このゴーストが終わった。ほかに動いているゴーストが無ければサービスも止める */
                    override fun onQuit() = endSession(this@GhostSession)

                    /** いまのゴーストを閉じて、サービスは止めずに次のゴーストを（同じ SHIORI のプロセスで）起こす */
                    override fun switchGhost(dirName: String, previous: PreviousGhost) {
                        val next = GhostStore.repository(this@GhostOverlayService).find(dirName)
                        if (next == null) {
                            onQuit()
                            return
                        }
                        // 閉じる処理の途中で呼ばれるので、落ち着いてから入れ替える
                        lifecycleScope.launch { replaceSession(this@GhostSession, next, previous) }
                    }

                    /** ほかのゴーストを、このゴーストと一緒に起動する */
                    override fun callGhost(dirName: String, caller: PreviousGhost): Boolean = this@GhostOverlayService.callGhost(dirName, caller)

                    // ゴーストとのやりとり（ユーザーの操作も含む）はデバッグビルドでだけ残す
                    override fun log(message: String) {
                        if (debuggable) Log.i(TAG, message)
                    }

                    override fun onError(message: String) {
                        Log.w(TAG, message)
                        toast(message)
                    }
                },
            )
            controller = c
            publish()
            afterSessionsChanged()
            watchUsb()
            watchLoad()
            c.setScreenOn(getSystemService(PowerManager::class.java).isInteractive)
            createWindows(c)
            watchPositions(c)
            watchWindowState(c)
            // pono-droid の画面が前にある間は、キャラクターがボタンに重なっても押せるようにタッチを通す。
            // ただし入力の箱を出している間は、箱に触れられるように通さない
            touchJob = lifecycleScope.launch {
                combine(appInFront, c.state.map { it.input != null }) { inFront, typing -> inFront && !typing }
                    .distinctUntilChanged()
                    .collect { pass ->
                        passThrough = pass
                        windows.forEach { it.setTouchable(!pass) }
                    }
            }
            // 入力の箱は \0 のバルーンに出す。開いている間だけ、そのウィンドウがキーボードの入力を受ける
            inputJob = lifecycleScope.launch {
                c.state.map { it.input?.kind }.distinctUntilChanged().collect { applyInput(c) }
            }
            c.start()
            // SHIORI を読み終わるまで、アプリの一覧にも読み込み中と出す（start で読み込み中になる）
            loadingJob = lifecycleScope.launch {
                c.state.first { it.loading == null }
                _loading.value = null
            }
        }

        /** いまのシェルとバルーン（キャラクターのウィンドウで共有する） */
        private var renderer: SurfaceRenderer? = null
        private var balloonSkin: BalloonSkin? = null

        /**
         * キャラクターのウィンドウを、いまのシェルとバルーンで作る。\0 と \1 のほか、3人目以降のキャラクター（\p[2] など）にも作り、
         * スクリプトが新しい \p[N] に話しかけたら、そのときに足す
         */
        private fun createWindows(c: GhostController) {
            renderer = SurfaceRenderer(c.shell)
            synchronized(faceIcons) { faceIcons.clear() }
            balloonSkin = c.balloon?.let(::BalloonSkin)
            scopesJob?.cancel()
            scopesJob = lifecycleScope.launch {
                c.state.map { it.characters.keys }.distinctUntilChanged().collect { scopes -> ensureWindows(c, scopes) }
            }
        }

        /** \![move]・\![set,position]・\4 / \5・\![set,alignmenttodesktop] を、キャラクターのウィンドウに伝える */
        private fun watchPositions(c: GhostController) {
            handledMoves.clear()
            handledNudges.clear()
            positionsJob?.cancel()
            positionsJob = lifecycleScope.launch {
                c.state.map { s ->
                    s.characters.mapValues { (_, ch) -> listOf(ch.move?.first, ch.pinned, ch.nudge?.first, ch.alignment, ch.balloonAlign, ch.balloonOffset, ch.balloonOffsetRelative, ch.balloonOffsets, ch.balloonDrag) }
                }
                    .distinctUntilChanged()
                    .collect { applyPositions(c) }
            }
        }

        private fun applyPositions(c: GhostController) {
            for ((scope, ch) in c.state.value.characters) {
                val window = windows.firstOrNull { it.scopeId == scope } ?: continue
                window.setPinned(ch.pinned)
                window.setAlignment(ch.alignment)
                window.refreshBalloon()
                ch.nudge?.let { (serial, away) ->
                    if (handledNudges[scope] == serial) return@let
                    handledNudges[scope] = serial
                    // 相手は、\0 なら \1、ほかのキャラクターなら \0
                    val partner = windows.firstOrNull { it.scopeId == if (scope == 0) 1 else 0 } ?: return@let
                    window.nudge(away, partner.screenRect())
                }
                val (serial, move) = ch.move ?: continue
                if (handledMoves[scope] == serial) continue
                handledMoves[scope] = serial
                window.move(move) { base ->
                    when (base) {
                        is MoveCommand.Base.Scope -> windows.firstOrNull { it.scopeId == base.id }
                        is MoveCommand.Base.Ghost -> otherGhostWindow(base.sakuraName, base.id)
                        else -> null
                    }
                }
            }
        }

        /** 最小化・重なり順・位置の初期化・最初の位置を、キャラクターのウィンドウに伝える */
        private fun watchWindowState(c: GhostController) {
            windowJob?.cancel()
            windowJob = lifecycleScope.launch {
                var previous: WindowState? = null
                c.state.map { it.window }.distinctUntilChanged().collect { w ->
                    val before = previous
                    previous = w
                    if (w.minimized != before?.minimized) {
                        windows.forEach { it.setHidden(w.minimized) }
                        // 隠している間は、通知から出し直せるようにする
                        if (before != null) refreshForeground()
                    }
                    if (before != null && (w.zorder != before.zorder || w.frontRequests != before.frontRequests)) restack(w)
                    if (before != null && w.resetRequests != before.resetRequests) {
                        windows.forEach { it.resetPosition(w.defaultPositions[it.scopeId]) }
                    }
                    if (w.defaultPositions != before?.defaultPositions) {
                        windows.forEach { it.applyDefault(w.defaultPositions[it.scopeId]) }
                    }
                }
            }
        }

        /**
         * ウィンドウを付け直して重なり順を決める（後から付けたものほど上）。いつもの順は \1 → 3人目以降 → \0 で、
         * それぞれサーフェスの上にバルーン。\![set,zorder] の組があれば、組の中だけその順に並べ替える
         */
        private fun restack(w: WindowState) {
            val order = windows.map { it.scopeId }.sortedBy { if (it == 0) Int.MAX_VALUE else it }
                .flatMap { listOf("s$it", "b$it") }.toMutableList()
            for (group in w.zorder) {
                val slots = order.indices.filter { order[it] in group }
                // 組は手前から並べてあるので、下（先に付ける方）から埋める
                val members = group.filter { it in order }.reversed()
                slots.zip(members).forEach { (i, token) -> order[i] = token }
            }
            windows.forEach { it.detach() }
            for (token in order) {
                windows.firstOrNull { it.scopeId == token.drop(1).toInt() }?.attach(balloon = token[0] == 'b')
            }
            applyInput(controller ?: return)
        }

        /** sticky-window の組で、[scope] と一緒に動くキャラクターのウィンドウ */
        private fun stickyPartners(scope: Int): List<CharacterWindow> {
            val group = controller?.state?.value?.window?.stickyGroups?.firstOrNull { scope in it } ?: return emptyList()
            return windows.filter { it.scopeId != scope && it.scopeId in group }
        }

        /** 出ているキャラクター（サーフェスのあるもの）と、その画面の上の矩形 */
        fun shownRects(): List<Pair<Int, Rect>> {
            val c = controller ?: return emptyList()
            return windows.filter { (c.state.value.characters[it.scopeId]?.surface ?: -1) >= 0 }
                .map { it.scopeId to it.screenRect() }
                .filter { (_, r) -> !r.isEmpty }
        }

        fun screenBounds(): Rect? = windows.firstOrNull()?.screenBounds()

        fun window(scope: Int): CharacterWindow? = windows.firstOrNull { it.scopeId == scope }

        /** キャラクターの重なりと見切れを調べて、変わっていればゴーストに伝える（OnOverlap / OnOffscreen。ほかのゴーストとのものも） */
        private fun reportLayout() {
            val c = controller ?: return
            val shown = shownRects()
            val screen = screenBounds() ?: return
            val overlaps = shown.flatMapIndexed { i, (a, ra) ->
                shown.drop(i + 1).filter { (_, rb) -> Rect.intersects(ra, rb) }.map { (b, _) -> a to b }
            }
            val offscreen = shown.filter { (_, r) -> !screen.contains(r) }.map { it.first }
            c.onWindowLayout(overlaps, offscreen)
            reportOtherLayout()
        }

        /** 入力の箱を開いているかを \0 のウィンドウに伝える（作り直したウィンドウにも） */
        private fun applyInput(c: GhostController) {
            val kind = c.state.value.input?.kind
            windows.firstOrNull { it.scopeId == 0 }?.setInputOpen(kind != null, secure = kind == InputRequest.Kind.PASSWORD)
        }

        private fun ensureWindows(c: GhostController, scopes: Set<Int>) {
            val shown = windows.map { it.scopeId }.toSet()
            // 後から足したウィンドウほど上に重なるので、\1 → 3人目以降 → \0 の順に作る（\0 がいちばん上）
            val order = scopes.filter { it !in shown }.sortedBy { if (it == 0) Int.MAX_VALUE else it }
            val prefs = OverlaySettings.prefs(this@GhostOverlayService)
            val w = c.state.value.window
            for (scope in order) {
                // 前の版で覚えた位置（ゴーストを分けていない）は、最初の SHIORI のプロセスで動くゴーストだけが使う
                windows += CharacterWindow(this@GhostOverlayService, scope, c, renderer ?: return, balloonSkin, prefs, dirName, sharedPositionFallback = slot == 0).also {
                    it.onShift = { dx, dy -> stickyPartners(scope).forEach { p -> p.shiftBy(dx, dy) } }
                    it.onSettled = {
                        stickyPartners(scope).forEach(CharacterWindow::savePosition)
                        reportLayout()
                    }
                    it.show()
                    it.setTouchable(!passThrough)
                    it.setHidden(w.minimized)
                    it.applyDefault(w.defaultPositions[scope])
                }
            }
            if (order.isEmpty()) return
            if (w.zorder.isNotEmpty()) restack(w)
            // 後から出てきたキャラクターへの \![move] も、ウィンドウができてから動かす
            applyPositions(c)
        }

        /** シェルやバルーンが切り替わったら、ウィンドウを作り直す */
        private fun rebuildWindows(c: GhostController) {
            windows.forEach { it.dismiss() }
            windows.clear()
            createWindows(c)
        }

        /** 発言履歴の顔（サーフェスごと。切り出せなかったものは null）。シェルが替わったら作り直す */
        private val faceIcons = HashMap<Int, Bitmap?>()

        suspend fun faceIcon(surfaceId: Int, sizePx: Int): Bitmap? {
            val r = renderer ?: return null
            val shell = controller?.shell ?: return null
            synchronized(faceIcons) { if (surfaceId in faceIcons) return faceIcons[surfaceId] }
            val icon = withContext(Dispatchers.Default) {
                runCatching {
                    val full = r.render(surfaceId, emptyMap()) ?: return@runCatching null
                    val f = shell.faceRect(surfaceId, full.width, full.height) ?: return@runCatching null
                    val face = Bitmap.createBitmap(full, f.left, f.top, f.right - f.left, f.bottom - f.top)
                    Bitmap.createScaledBitmap(face, sizePx, sizePx, true)
                }.getOrNull()
            }
            synchronized(faceIcons) { faceIcons[surfaceId] = icon }
            return icon
        }

        /** このゴーストのウィンドウと見張りを片付ける。ゴーストがまだ動いていれば、何も送らずに止める（その片付けの Job を返す） */
        fun teardown(): Job? {
            startJob?.cancel()
            startJob = null
            loadingJob?.cancel()
            loadingJob = null
            if (_loading.value == dirName) _loading.value = null
            sound.stop()
            touchJob?.cancel()
            touchJob = null
            inputJob?.cancel()
            inputJob = null
            scopesJob?.cancel()
            scopesJob = null
            positionsJob?.cancel()
            positionsJob = null
            windowJob?.cancel()
            windowJob = null
            windows.forEach { it.dismiss() }
            windows.clear()
            val stopping = controller?.dispose()
            controller = null
            return stopping
        }
    }

    /** /proc/cpuinfo の Features（拡張命令。空白区切り）と、cpu0 のいちばん速いクロック（MHz）。読めなければ空と 0 */
    private val cpuFeatures: String by lazy {
        runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
            .firstOrNull { it.startsWith("Features") }?.substringAfter(':')?.trim().orEmpty()
    }
    private val cpuClockMhz: Int by lazy {
        runCatching { File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000 }.getOrDefault(0)
    }

    /** 声を聞く（設定で選び、マイクを許可したときだけ。画面が消えている間は聞かない） */
    private var voice: VoiceListener? = null

    private fun voiceAllowed(): Boolean = OverlaySettings.voice(OverlaySettings.prefs(this)) &&
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** つないでいる USB 機器（機器のファイル名ごと）。設定で選んだときだけ、画面がついている間に少しずつ見て変わったら伝える */
    private var usbDevices: Map<String, ConnectedDevice>? = null
    private var usbJob: Job? = null

    private fun watchUsb() {
        if (usbJob != null) return
        usbJob = lifecycleScope.launch {
            while (true) {
                val all = controllers
                if (!OverlaySettings.usb(OverlaySettings.prefs(this@GhostOverlayService))) {
                    // 選び直したときに、そのときつないであったものを知らせないよう、覚えていたものは捨てる
                    usbDevices = null
                } else if (all.isNotEmpty() && getSystemService(PowerManager::class.java)?.isInteractive != false) {
                    val now = runCatching {
                        getSystemService(android.hardware.usb.UsbManager::class.java)?.deviceList?.values?.associate { d ->
                            // 名前や会社は、機器を使ってよいと言われるまで読めない端末がある
                            val name = runCatching { d.productName }.getOrNull() ?: d.deviceName
                            val maker = runCatching { d.manufacturerName }.getOrNull().orEmpty()
                            d.deviceName to ConnectedDevice("usb", name, maker, d.deviceName)
                        }
                    }.getOrNull().orEmpty()
                    val before = usbDevices
                    // 起動したときにつないであったものは知らせない
                    if (before != null) {
                        val arrived = now.filterKeys { it !in before }.values.toList()
                        val removed = before.filterKeys { it !in now }.values.toList()
                        all.forEach {
                            it.onDevicesArrived(arrived)
                            it.onDevicesRemoved(removed)
                        }
                    }
                    usbDevices = now
                }
                kotlinx.coroutines.delay(USB_POLL_MS)
            }
        }
    }

    private fun updateVoice() {
        val any = controllers.isNotEmpty()
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (!any || !voiceAllowed() || !screenOn) {
            val wasListening = voice != null
            voice?.stop()
            voice = null
            // 聞くのをやめたら、通知の種類からマイクを外す
            if (wasListening && any && !voiceAllowed()) refreshForeground()
            return
        }
        if (voice != null) return
        // マイクを使う常駐の通知にし直してから聞く（Android 14 からは、通知の種類にマイクが要る）
        refreshForeground()
        // 名前を呼ばれたかどうかは、それぞれのゴーストが見る
        voice = VoiceListener(this) { text -> controllers.forEach { it.onVoiceRecognized(text) } }.also { it.start() }
    }

    /**
     * \![open,dialog,save] で選んだ保存先。ゴーストがゴーストのフォルダの [file] に書き終えたら（大きさが変わらなくなったら）、
     * 選ばれた場所 [uri] に写す。[SAVE_WAIT_MS] のうちに書かれなければやめる
     */
    private fun watchSave(file: File, uri: Uri) {
        lifecycleScope.launch {
            var lastSize = -1L
            val until = SystemClock.elapsedRealtime() + SAVE_WAIT_MS
            while (SystemClock.elapsedRealtime() < until) {
                kotlinx.coroutines.delay(1000)
                val size = if (file.isFile) file.length() else -1L
                if (size >= 0 && size == lastSize) {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } != null }.getOrDefault(false)
                    }
                    if (!ok) toast("保存できませんでした")
                    return@launch
                }
                lastSize = size
            }
        }
    }

    /** SSTP サーバー（設定で選んだときだけ。この端末の中のアプリからだけ受ける） */
    private var sstp: SstpServer? = null

    /** 設定とゴーストの起動に合わせて、SSTP サーバーを始める・やめる */
    private fun updateSstp() {
        val want = sessions.isNotEmpty() && OverlaySettings.sstp(OverlaySettings.prefs(this))
        if (!want) {
            sstp?.stop()
            sstp = null
            return
        }
        if (sstp != null) return
        // 宛先（ReceiverGhostName・IfGhost など）のゴーストに渡す。名指しのゴーストが動いていなければ 404
        val server = SstpServer(
            lifecycleScope,
            { req ->
                withContext(Dispatchers.Main) {
                    if (hub.running.isEmpty()) SstpResponse(503) else hub.sstpTarget(req)?.handleSstp(req) ?: SstpResponse(404)
                }
            },
            log = { Log.w(TAG, it) },
        )
        if (server.start()) sstp = server else toast("SSTP のポート ${SstpServer.DEFAULT_PORT} がほかのアプリに使われています")
    }

    /** 動いているゴーストをすべて、何も送らずに止める。それぞれの片付けの Job を返す */
    private fun teardown(): List<Job> {
        pendingStart?.cancel()
        pendingStart = null
        usbJob?.cancel()
        usbJob = null
        usbDevices = null
        loadJob?.cancel()
        loadJob = null
        sstp?.stop()
        sstp = null
        voice?.stop()
        voice = null
        val all = sessions.toList()
        sessions.clear()
        val stopping = all.mapNotNull { it.teardown() }
        _loading.value = null
        publish()
        return stopping
    }

    /** 画面が消えている間は、ゴーストのタイマーとアニメーションを止める。端末の電源を切るときは終わる */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val all = controllers.ifEmpty { return }
            when (intent.action) {
                // 端末の電源を切る（OnCloseAll の Reference0 は system）
                Intent.ACTION_SHUTDOWN -> all.forEach { it.close("system") }
                // Android にはパソコンのような「休止」が無いので、画面が消えたら休んだことにする（OnSysSuspend）
                Intent.ACTION_SCREEN_OFF -> {
                    for (c in all) {
                        c.setScreenOn(false)
                        c.onDisplayPower(false)
                        // 画面が消えたら、次につけたときはロック画面から（OnSessionLock）
                        c.onSessionLock()
                        c.onSystemSuspend()
                    }
                    updateVoice()
                }
                Intent.ACTION_SCREEN_ON -> {
                    // ロック画面の上にはキャラクターが出ないので、ロックがあれば解いてから OnSysResume
                    val unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked
                    for (c in all) {
                        c.setScreenOn(true)
                        c.onDisplayPower(true)
                        if (unlocked) {
                            c.onSessionUnlock()
                            c.onSystemResume()
                        }
                    }
                    updateVoice()
                }
                Intent.ACTION_USER_PRESENT -> all.forEach {
                    it.onSessionUnlock()
                    it.onSystemResume()
                }
                // Android のスクリーンセーバー
                Intent.ACTION_DREAMING_STARTED -> all.forEach { it.onScreenSaver(true) }
                Intent.ACTION_DREAMING_STOPPED -> all.forEach { it.onScreenSaver(false) }
            }
        }
    }

    /** 既定のネットワークの今の様子。コールバックで届いたものを覚えておく */
    private var networkCaps: NetworkCapabilities? = null
    private var networkLink: LinkProperties? = null

    /** ネットワークが変わったらゴーストに伝える（OnNetworkStatusChange。送るかどうかは controller が見る） */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networkCaps = caps
            pushNetwork()
        }

        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
            networkLink = link
            pushNetwork()
        }

        override fun onLost(network: Network) {
            networkCaps = null
            networkLink = null
            pushNetwork()
        }

        // コールバックは別のスレッドで来るので、ゴーストには画面のスレッドから伝える
        private fun pushNetwork() {
            val status = networkStatus(networkCaps, networkLink)
            lifecycleScope.launch { controllers.forEach { it.onNetworkChanged(status) } }
        }
    }

    /** SSP の OnNetworkStatusChange の形にする。従量制かどうかしか分からないので、従量制なら fixed（上限のある定額）とする */
    private fun networkStatus(caps: NetworkCapabilities?, link: LinkProperties?): NetworkStatus {
        if (caps == null) return NetworkStatus(online = false, type = "none", metering = "unknown")
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        // IPv6 の「%wlan0」のようなインターフェース名は落とす
        val addresses = link?.linkAddresses.orEmpty()
            .mapNotNull { it.address?.takeIf { a -> !a.isLoopbackAddress }?.hostAddress?.substringBefore('%') }
        val mbps = caps.linkDownstreamBandwidthKbps / 8000.0
        return NetworkStatus(
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            addresses = addresses,
            type = type,
            maxMbps = mbps,
            effectiveMbps = mbps,
            metering = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "unrestricted" else "fixed",
        )
    }

    /** 電池の状態はゴーストに伝える（OnBatteryNotify。変わったときだけ送るかは controller が見る） */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val info = batteryInfo(intent)
            controllers.forEach { it.onBatteryChanged(info) }
        }
    }

    private fun batteryInfo(intent: Intent): BatteryInfo {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        return BatteryInfo(
            percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
        )
    }

    /** 端末で鳴っている曲や動画を見張る。設定で選んでいて、通知へのアクセスがあるときだけ */
    private var mediaWatcher: MediaWatcher? = null

    private fun updateMedia() {
        if (OverlaySettings.media(OverlaySettings.prefs(this)) && MediaWatcher.hasAccess(this)) {
            val w = mediaWatcher ?: MediaWatcher(this) { p -> controllers.forEach { it.onMediaPlay(p.video, p.title, p.artist, p.extras) } }
            mediaWatcher = w
            w.start()
        } else {
            mediaWatcher?.stop()
        }
    }

    /** アプリの画面で変えたキャラクターの大きさ。変わったらゴーストに伝える（OnShellScaling） */
    private var characterScale = 1f
    /** バルーンの大きさ（OnBalloonScaling） */
    private var balloonScale = 1f
    private val scaleListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        when (key) {
            OverlaySettings.KEY_CHARACTER_SCALE -> {
                val old = characterScale
                characterScale = OverlaySettings.characterScale(p)
                controllers.forEach { it.onShellScaling((characterScale * 100).roundToInt(), (old * 100).roundToInt()) }
            }
            OverlaySettings.KEY_BALLOON_SCALE -> {
                val old = balloonScale
                balloonScale = OverlaySettings.balloonScale(p)
                controllers.forEach { it.onBalloonScaling((balloonScale * 100).roundToInt(), (old * 100).roundToInt()) }
            }
            OverlaySettings.KEY_SSTP -> updateSstp()
            OverlaySettings.KEY_MEDIA -> updateMedia()
            OverlaySettings.KEY_ALIGNMENT_POLICY -> controllers.forEach { it.onAlignmentPolicyChanged() }
            OverlaySettings.KEY_VOICE -> {
                updateVoice()
                controllers.forEach { it.onVoiceRecognitionChanged(voiceAllowed()) }
            }
            OverlaySettings.KEY_SPEECH -> {
                val on = OverlaySettings.speech(p)
                if (!on) speech.stop()
                controllers.forEach { it.onSpeechSettingChanged(on) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        OverlaySettings.prefs(this).let {
            characterScale = OverlaySettings.characterScale(it)
            balloonScale = OverlaySettings.balloonScale(it)
            it.registerOnSharedPreferenceChangeListener(scaleListener)
        }
        updateMedia()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
                addAction(Intent.ACTION_SHUTDOWN)
            },
        )
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            runCatching { android.app.WallpaperManager.getInstance(this).addOnColorsChangedListener(wallpaperListener, mainHandler) }
        }
    }

    /** \![set,wallpaper] で pono-droid が壁紙を変えた時刻（その直後の変化は self） */
    @Volatile private var lastWallpaperSetMs = 0L

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 端末の壁紙が変わった（OnDesktopWallpaperChange）。色が変わったという知らせで分かる。ロック画面だけの変化は見ない */
    private val wallpaperListener = android.app.WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and android.app.WallpaperManager.FLAG_SYSTEM == 0) return@OnColorsChangedListener
        val self = System.currentTimeMillis() - lastWallpaperSetMs < WALLPAPER_SELF_MS
        controllers.forEach { it.onDesktopWallpaperChanged(self) }
    }

    /** 画面の向き（OnDisplayChange）・ダークモード（OnDarkTheme）・言語（OnLanguageChange）が変わった */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        val info = systemInfo()
        controllers.forEach {
            it.onDisplayChanged(metrics.widthPixels, metrics.heightPixels)
            it.onSystemInfoChanged(info)
        }
    }

    /** 端末の見た目・言語・フォント・更新。言語名と ID は SSP（Windows）と同じ形にする */
    /** 端末のフォント。入れ替わることはまず無いので、1回だけ調べる */
    private val systemFonts: List<String> by lazy {
        val fonts = if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                android.graphics.fonts.SystemFonts.getAvailableFonts().mapNotNull { it.file?.nameWithoutExtension }.distinct().sorted().take(MAX_FONTS)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        fonts.ifEmpty { listOf("sans-serif", "serif", "monospace") }
    }

    private fun systemInfo(): SystemInfo {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val locale = resources.configuration.locales[0]
        val filter = getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        return SystemInfo(
            darkTheme = night,
            languageName = locale.getDisplayLanguage(java.util.Locale.ENGLISH),
            languageId = windowsLanguageId(locale),
            fonts = systemFonts,
            osUpdated = Build.TIME.takeIf { it > 0 },
            osUpdateTitle = "Android ${Build.VERSION.RELEASE} (${Build.VERSION.SECURITY_PATCH})",
            country = locale.country,
            doNotDisturb = filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
        )
    }

    /** Windows の言語 ID（LANGID）。よく使うものだけ */
    private fun windowsLanguageId(locale: java.util.Locale): Int = when (locale.language) {
        "ja" -> 1041
        "en" -> if (locale.country == "GB") 2057 else 1033
        "zh" -> if (locale.country in setOf("TW", "HK", "MO") || locale.script == "Hant") 1028 else 2052
        "ko" -> 1042
        "fr" -> 1036
        "de" -> 1031
        "es" -> 3082
        "it" -> 1040
        "pt" -> if (locale.country == "BR") 1046 else 2070
        "ru" -> 1049
        else -> 0
    }

    /**
     * 端末の負荷を 5 秒ごとに測って、ゴーストに伝える（OnCPULoadHigh / OnMemoryLoadHigh など）。
     * Android 8 からはほかのアプリの CPU の使い方は読めないので、読める端末だけ /proc/stat（だめなら /proc/loadavg）から求める
     */
    private fun watchLoad() {
        if (loadJob != null) return
        loadJob = lifecycleScope.launch {
            var previous: Pair<Long, Long>? = null
            while (true) {
                kotlinx.coroutines.delay(5_000)
                if (!getSystemService(PowerManager::class.java).isInteractive) continue
                val (cpu, sample) = withContext(Dispatchers.IO) { cpuLoad(previous) }
                previous = sample
                val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                val used = if (memory.totalMem > 0) ((memory.totalMem - memory.availMem) * 100 / memory.totalMem).toInt() else null
                controllers.forEach { it.onLoad(cpu, used) }
            }
        }
    }

    /** CPU の負荷（パーセント）と、次に差を取るための値（全体の時間・休んでいた時間）。読めなければ null */
    private fun cpuLoad(previous: Pair<Long, Long>?): Pair<Int?, Pair<Long, Long>?> {
        val stat = runCatching { File("/proc/stat").useLines { it.firstOrNull() } }.getOrNull()
        if (stat != null && stat.startsWith("cpu ")) {
            val v = stat.split(WHITESPACE).drop(1).mapNotNull { it.toLongOrNull() }
            if (v.size >= 4) {
                val idle = v[3] + (v.getOrNull(4) ?: 0)
                val total = v.sum()
                val load = previous?.let { (t, i) -> if (total > t) (100 - (idle - i) * 100 / (total - t)).toInt() else null }
                return load to (total to idle)
            }
        }
        val avg = runCatching { File("/proc/loadavg").readText().split(' ').firstOrNull()?.toDoubleOrNull() }.getOrNull()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return avg?.let { (it * 100 / cores).toInt().coerceIn(0, 100) } to null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        OverlaySettings.prefs(this).unregisterOnSharedPreferenceChangeListener(scaleListener)
        mediaWatcher?.stop()
        unregisterReceiver(screenReceiver)
        unregisterReceiver(batteryReceiver)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            runCatching { android.app.WallpaperManager.getInstance(this).removeOnColorsChangedListener(wallpaperListener) }
        }
        teardown()
        speech.shutdown()
        super.onDestroy()
    }

    /** \![set,tasktrayicon] で決めた、常駐の通知に出す文（null なら出さない） */
    private var trayText: String? = null

    /** \![set,tasktrayicon] で決めた、常駐の通知のアイコン（null ならゴーストの descript.txt の icon） */
    private var trayIcon: File? = null

    /** 常駐の通知のアイコンの絵（読み直さないよう、ファイルごとに覚えておく） */
    private var trayIconCache: Pair<File, Bitmap?>? = null

    /**
     * 常駐の通知に出すアイコン。\![set,tasktrayicon] のファイルか、先頭のゴーストの icon（隠している間は icon.minimize）。
     * .ico も Android で読める。読めなければ null（アイコンは出さない）
     */
    private fun trayIconBitmap(minimized: Boolean): Bitmap? {
        val ghost = sessions.firstOrNull()?.controller?.ghost ?: return null
        val file = trayIcon ?: (if (minimized) ghost.minimizedIconFile() else ghost.iconFile()) ?: return null
        trayIconCache?.takeIf { it.first == file }?.let { return it.second }
        val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
            ?.takeIf { it.width in 1..MAX_TRAY_ICON && it.height in 1..MAX_TRAY_ICON }
        trayIconCache = file to bitmap
        return bitmap
    }

    /**
     * \![set,trayballoon]。常駐の通知とは別の通知で、ゴーストからのお知らせを出す。触れたら OnTrayBalloonClick、
     * 消したら OnTrayBalloonTimeout（次のお知らせは前のものを置き換える）
     */
    private fun showGhostMessage(title: String, text: String, dirName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(MESSAGE_CHANNEL, "ゴーストからのお知らせ", NotificationManager.IMPORTANCE_DEFAULT))
        fun action(action: String, code: Int) = PendingIntent.getService(
            this, code,
            Intent(this, GhostOverlayService::class.java).setAction(action).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_GHOST, dirName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = sessions.firstOrNull { it.dirName == dirName }?.controller?.ghost?.name.orEmpty()
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.ifEmpty { name })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(action(ACTION_TRAY_CLICK, 10))
            .setDeleteIntent(action(ACTION_TRAY_DISMISS, 11))
            .setAutoCancel(true)
            .build()
        nm.notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    /** 常駐の通知を出す（出し直す）。[minimized] ならキャラクターを隠しているので、「表示」で出し直せるようにする */
    private fun startInForeground(minimized: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        // 重要度が「低」だと「サイレント」欄に折りたたまれて、終了などのボタンが見えない。
        // 「標準」にして、音とバイブレーションだけ切る（チャンネルの重要度は後から変えられないので作り直す）
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "常駐中のゴースト", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, GhostOverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val update = PendingIntent.getService(
            this, 1, Intent(this, GhostOverlayService::class.java).setAction(ACTION_UPDATE), PendingIntent.FLAG_IMMUTABLE,
        )
        val show = PendingIntent.getService(
            this, 2, Intent(this, GhostOverlayService::class.java).setAction(ACTION_SHOW), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = if (minimized) "ゴーストを隠しています" else "ゴーストが起動しています"
        // 通知は先頭のもの以外は折りたたまれ、標準のボタン（addAction）は隠れてしまう。
        // 折りたたまれた1行の中にもボタンを置いておく
        val icon = trayIconBitmap(minimized)
        val collapsed = RemoteViews(packageName, R.layout.notification_ghost).apply {
            // ゴーストのアイコン（descript.txt の icon）があれば、題の左に出す
            if (icon != null) {
                setImageViewBitmap(R.id.notification_icon, icon)
                setViewVisibility(R.id.notification_icon, android.view.View.VISIBLE)
            }
            setTextViewText(R.id.notification_title, title)
            setViewVisibility(R.id.notification_show, if (minimized) android.view.View.VISIBLE else android.view.View.GONE)
            setOnClickPendingIntent(R.id.notification_show, show)
            setOnClickPendingIntent(R.id.notification_update, update)
            setOnClickPendingIntent(R.id.notification_stop, stop)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(trayText)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed)
            .setLargeIcon(icon)
            // 隠している間は、通知に触れたら出し直す
            .setContentIntent(if (minimized) show else open)
        if (minimized) builder.addAction(0, "表示", show)
        val notification: Notification = builder
            .addAction(0, "ネットワーク更新", update)
            .addAction(0, "終了", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        // 声を聞いている（聞く設定でマイクの許可もある）ときだけ、通知の種類にマイクを足す
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            if (Build.VERSION.SDK_INT >= 30 && voiceAllowed()) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun writePng(bitmap: Bitmap?, file: File): Boolean {
        bitmap ?: return false
        return runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
    }

    companion object {
        /** 壁紙を変えてから、変化の知らせを self とみなす時間 */
        private const val WALLPAPER_SELF_MS = 10_000L

        private const val TAG = "pono"
        private const val CHANNEL = "ghost_running"
        private const val OLD_CHANNEL = "ghost"
        private const val NOTIFICATION_ID = 1
        private const val MESSAGE_CHANNEL = "ghost_message"

        /** 1日に読む端末の予定の数 */
        private const val MAX_DEVICE_EVENTS = 100
        private const val MESSAGE_NOTIFICATION_ID = 2
        private const val ACTION_TRAY_CLICK = "net.pono.droid.TRAY_CLICK"
        private const val ACTION_TRAY_DISMISS = "net.pono.droid.TRAY_DISMISS"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val ACTION_START = "net.pono.droid.START"
        private const val ACTION_START_BESIDE = "net.pono.droid.START_BESIDE"
        private const val ACTION_STOP = "net.pono.droid.STOP"
        private const val ACTION_UPDATE = "net.pono.droid.UPDATE"
        private const val EXTRA_EXPLORER = "explorer"
        private const val ACTION_SHOW = "net.pono.droid.SHOW"

        /** OnNotifyFontInfo で伝えるフォントの数の上限 */
        private const val MAX_FONTS = 200

        private val WHITESPACE = Regex("[ \t]+")
        private const val EXTRA_GHOST = "ghost"

        /** 常駐の通知のアイコンとして読む絵の大きさの上限（px） */
        private const val MAX_TRAY_ICON = 512

        /** \![execute,dumpsurface] で1つのアニメーションから書き出すコマの数 */
        private const val MAX_DUMP_FRAMES = 1000

        private val _appInFront = MutableStateFlow(false)

        /**
         * pono-droid の画面が前にあるか。その間はゴーストのウィンドウがタッチを奪わないようにする
         * （キャラクターがアプリのボタンに重なって押せなくなるため）。
         */
        val appInFront: StateFlow<Boolean> = _appInFront.asStateFlow()

        fun setAppInFront(inFront: Boolean) {
            _appInFront.value = inFront
        }

        /** 動いているサービス（\![open,dialog,save] の保存先を見張るため） */
        private var instance: GhostOverlayService? = null

        /** 通知へのアクセスを許された・取り消された（[MediaListenerService] から） */
        fun onMediaAccessChanged() {
            instance?.updateMedia()
        }

        /**
         * 発言履歴の顔。[controller] のゴーストの [surfaceId] の絵から顔を切り出し、[sizePx] 四方にしたもの
         * （ゴーストが動いていない・切り出せなければ null）
         */
        suspend fun faceIcon(controller: GhostController?, surfaceId: Int, sizePx: Int): Bitmap? {
            if (surfaceId < 0 || controller == null) return null
            return instance?.sessions?.firstOrNull { it.controller === controller }?.faceIcon(surfaceId, sizePx)
        }

        /** [file] にゴーストが書いたら [uri] に写す（ゴーストが動いていなければ何もしない） */
        fun watchSave(file: File, uri: Uri) {
            instance?.watchSave(file, uri)
        }

        private const val SAVE_WAIT_MS = 120_000L
        private const val USB_POLL_MS = 10_000L

        /** 起動し直すとき、前のゴーストの SHIORI を下ろし終えるのを待つ長さ */
        private const val STOP_WAIT_MS = 5_000L

        private val _controller = MutableStateFlow<GhostController?>(null)

        /** 起動中のゴースト（いくつか動いていれば、最初に起動したもの） */
        val controller: StateFlow<GhostController?> = _controller.asStateFlow()

        private val _controllers = MutableStateFlow<List<GhostController>>(emptyList())

        /** 動いているゴーストすべて（起動した順） */
        val controllers: StateFlow<List<GhostController>> = _controllers.asStateFlow()

        private val _runningGhosts = MutableStateFlow<List<String>>(emptyList())

        /** 動いているゴーストのディレクトリ名すべて（起動した順） */
        val runningGhosts: StateFlow<List<String>> = _runningGhosts.asStateFlow()

        private val _running = MutableStateFlow<String?>(null)

        /** 起動中のゴーストのディレクトリ名（いくつか動いていれば最初のもの。起動していなければ null） */
        val running: StateFlow<String?> = _running.asStateFlow()

        private val _loading = MutableStateFlow<String?>(null)

        /** 起動しようとして、シェルや SHIORI を読み込んでいる最中のゴースト（ディレクトリ名） */
        val loading: StateFlow<String?> = _loading.asStateFlow()

        fun start(context: Context, ghostDirName: String) {
            context.startForegroundService(
                Intent(context, GhostOverlayService::class.java).setAction(ACTION_START).putExtra(EXTRA_GHOST, ghostDirName),
            )
        }

        /** 動いているゴーストはそのままにして、もう1体起動する（[ShioriService.SLOTS] 体まで） */
        fun startBeside(context: Context, ghostDirName: String) {
            context.startForegroundService(
                Intent(context, GhostOverlayService::class.java).setAction(ACTION_START_BESIDE).putExtra(EXTRA_GHOST, ghostDirName),
            )
        }

        /** 同時に動かせるゴーストの数 */
        const val MAX_GHOSTS = ShioriService.SLOTS

        fun stop(context: Context) {
            context.startService(Intent(context, GhostOverlayService::class.java).setAction(ACTION_STOP))
        }

        /** 起動中のゴースト（最初に起動したもの）のネットワーク更新。アプリのゴースト一覧からなら [explorer] */
        fun update(context: Context, explorer: Boolean = false) {
            context.startService(Intent(context, GhostOverlayService::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_EXPLORER, explorer))
        }
    }
}
