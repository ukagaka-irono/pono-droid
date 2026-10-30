package net.pono.droid

import android.provider.Settings
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import net.pono.baseware.shell.Shell
import android.content.Context
import android.net.Uri
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.GhostRepository
import net.pono.baseware.ghost.NarInstaller
import net.pono.baseware.balloon.Balloon
import net.pono.droid.overlay.GhostOverlayService
import java.io.File

/** アプリ内のゴースト置き場（filesDir/ghost）とバルーン置き場（filesDir/balloon）。 */
object GhostStore {
    fun repository(context: Context) = GhostRepository(File(context.filesDir, "ghost"))

    /**
     * SAF で選んだ .nar を一時ファイルに写してから展開する。ゴーストかバルーンかは .nar の install.txt で決まる。
     * ゴーストが起動していれば、そのゴーストに入れてもらう（OnInstallBegin などのイベントを送り、シェルや追加ファイルはそのゴーストに入る）
     */
    suspend fun install(context: Context, uri: Uri): NarInstaller.Result {
        val tmp = File.createTempFile("import", ".nar", context.cacheDir)
        try {
            // 共有からはどのアプリでも渡せるので、写す大きさに上限を置く
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_NAR_BYTES) error("ファイルが大きすぎます（1GB まで）")
                        out.write(buf, 0, n)
                    }
                }
            } ?: error("ファイルを開けません")
            val controller = GhostOverlayService.controller.value
            return controller?.installNar(tmp, fromUser = true)?.getOrThrow() ?: repository(context).installer().install(tmp)
        } finally {
            tmp.delete()
        }
    }

    /** 受け取る .nar の大きさの上限 */
    private const val MAX_NAR_BYTES = 1024L * 1024 * 1024

    /**
     * アプリの一覧の「.nar をインストール」。画面を回して作り直しても止まらず、二重にも始めないよう、画面ではなくアプリの中で動かす。
     * 結果の文は [installResults] に1回だけ流す（そのとき出ている一覧が受け取る）
     */
    fun installFromApp(context: Context, uri: Uri) {
        if (_installing.value) return
        _installing.value = true
        val app = context.applicationContext
        appScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { runCatching { install(app, uri) } }
            } finally {
                _installing.value = false
            }
            result.onSuccess { afterInstall(app, it) }
            _installResults.send(result.fold({ describe(it) }, { "インストールできませんでした: ${it.message}" }))
        }
    }

    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    private val _installing = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** アプリの一覧から入れている途中か */
    val installing: kotlinx.coroutines.flow.StateFlow<Boolean> = _installing

    private val _installResults = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)

    /** アプリの一覧から入れた結果の文 */
    val installResults: kotlinx.coroutines.flow.Flow<String> = _installResults.receiveAsFlow()

    /** インストールしたことを伝える文。同梱のバルーンなどがあれば、それも並べる */
    /** 入れたゴースト・バルーン・シェルのうち、最初に readme があるものの名前と中身。どれにも無ければ null */
    fun readme(result: NarInstaller.Result): Pair<String, String>? = result.installed.firstNotNullOfOrNull { item ->
        when (item.type) {
            NarInstaller.Type.GHOST -> Ghost(item.dir).let { g -> g.readmeText()?.let { g.name to it } }
            NarInstaller.Type.BALLOON -> Balloon(item.dir).let { b -> b.readmeText()?.let { b.name to it } }
            NarInstaller.Type.SHELL -> Shell.readmeOf(item.dir)?.let { Shell.nameOf(item.dir) to it }
            else -> null
        }
    }

    /**
     * インストールした後（アプリの画面と共有から）。readme があれば開き（SSP と同じくインストールしたときに見せる）、
     * package の bootghost があって、ゴーストが動いていなければそれを起動する
     */
    suspend fun afterInstall(context: Context, result: NarInstaller.Result) {
        withContext(Dispatchers.IO) { readme(result) }?.let { (title, text) -> TextViewerActivity.start(context, title, text) }
        val boot = result.bootGhost ?: return
        if (GhostOverlayService.runningGhosts.value.isEmpty() && Settings.canDrawOverlays(context)) GhostOverlayService.start(context, boot)
    }

    fun describe(result: NarInstaller.Result): String = result.installed.joinToString("、") { item ->
        when (item.type) {
            NarInstaller.Type.GHOST -> "ゴースト: ${Ghost(item.dir).name}"
            NarInstaller.Type.BALLOON -> "バルーン: ${Balloon(item.dir).name}"
            NarInstaller.Type.SHELL -> "シェル: ${item.name}"
            NarInstaller.Type.SUPPLEMENT -> "追加ファイル: ${item.name}"
        }
    } + " をインストールしました"
}
