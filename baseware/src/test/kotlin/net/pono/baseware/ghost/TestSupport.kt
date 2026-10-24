package net.pono.baseware.ghost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import net.pono.baseware.shiori.Shiori
import net.pono.baseware.update.FetchResult
import net.pono.baseware.update.HttpFetcher
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** 送られたイベントを覚えておき、決めておいたスクリプトを返す SHIORI */
class ScriptedShiori(val replies: Map<String, String>) : Shiori {
    val events: MutableList<Pair<String, List<String>>> = Collections.synchronizedList(mutableListOf())

    override fun load(dir: File) = true

    override fun request(request: ByteArray): ByteArray {
        val headers = request.toString(Charsets.UTF_8).split("\r\n")
            .mapNotNull { line -> line.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        val id = headers["ID"].orEmpty()
        events += id to generateSequence(0) { it + 1 }.map { headers["Reference$it"] }.takeWhile { it != null }.filterNotNull().toList()
        val value = replies[id]?.let { "Value: $it\r\n" }.orEmpty()
        return "SHIORI/3.0 200 OK\r\nCharset: UTF-8\r\n$value\r\n".toByteArray()
    }

    override fun unload() {}

    fun count(id: String): Int = synchronized(events) { events.count { it.first == id } }

    /** [id] のイベントが届くまで待ち、最後に届いたものの References を返す */
    suspend fun awaitEvent(id: String, timeoutMs: Long = 5000): List<String> = withTimeout(timeoutMs) {
        while (count(id) == 0) delay(10)
        synchronized(events) { events.last { it.first == id }.second }
    }
}

/** 頼まれたことを記録するだけのプラットフォーム */
class RecordingPlatform : GhostPlatform {
    val urls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val errors: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val texts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    var settingsOpened = 0
    var device: DeviceInfo? = null
    var battery: BatteryInfo? = null
    var network: NetworkStatus? = null
    var system: SystemInfo? = null
    val sounds: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val shellChanges: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val balloonChanges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    override fun playSound(file: File, loop: Boolean, volume: Int?) {
        sounds += "${file.name}/$loop/$volume"
    }

    override fun stopSound() {
        sounds += "stop"
    }

    override fun onShellChanged(shell: net.pono.baseware.shell.Shell) {
        shellChanges += shell.dir.name
    }

    override fun onBalloonChanged(balloon: net.pono.baseware.balloon.Balloon?) {
        balloonChanges += balloon?.dir?.name
    }

    override fun openUrl(url: String) {
        urls += url
    }

    override fun deviceInfo(): DeviceInfo? = device

    override fun batteryInfo(): BatteryInfo? = battery

    override fun networkStatus(): NetworkStatus? = network

    override fun systemInfo(): SystemInfo? = system

    override fun showText(title: String, text: String) {
        texts += title to text
    }

    override fun openSettings() {
        settingsOpened++
    }

    override fun onQuit() {}

    override fun log(message: String) {
        logs += message
    }

    override fun onError(message: String) {
        errors += message
    }
}

/** 最小のゴースト（descript.txt と空のシェル）を作る */
fun TemporaryFolder.newTestGhost(name: String = "ghost", extraDescript: String = ""): Ghost {
    val ghostDir = newFolder(name)
    File(ghostDir, "ghost/master").mkdirs()
    File(ghostDir, "ghost/master/descript.txt").writeText("charset,UTF-8\r\nname,テスト\r\nshiori,yaya.dll\r\n$extraDescript")
    val shellDir = File(ghostDir, "shell/master").apply { mkdirs() }
    File(shellDir, "descript.txt").writeText("charset,UTF-8\r\nname,master\r\n")
    return Ghost(ghostDir)
}

/** アプリと同じく1本のスレッドで動かす（状態の更新が競合しないように） */
fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

fun testController(
    ghost: Ghost,
    shiori: Shiori,
    scope: CoroutineScope,
    platform: GhostPlatform = RecordingPlatform(),
    fetcher: HttpFetcher = HttpFetcher { _, _, _ -> FetchResult.HttpError(404) },
) = GhostController(
    ghost = ghost,
    shell = Shell(ghost.shellDir()!!),
    shioriFactory = { _, _ -> shiori },
    scope = scope,
    platform = platform,
    player = ScriptPlayer(charWaitMs = 0),
    updateFetcher = fetcher,
)
