package net.pono.droid

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.balloon.BalloonRepository
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.NarInstaller
import net.pono.baseware.shell.Shell
import net.pono.droid.overlay.GhostOverlayService
import net.pono.droid.overlay.OverlaySettings
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                var showLicenses by rememberSaveable { mutableStateOf(false) }
                if (showLicenses) {
                    LicensesScreen(onBack = { showLicenses = false })
                } else {
                    GhostListScreen(onShowLicenses = { showLicenses = true })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GhostListScreen(onShowLicenses: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var ghosts by remember { mutableStateOf<List<GhostItem>>(emptyList()) }
    var balloons by remember { mutableStateOf<List<Balloon>>(emptyList()) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val running by GhostOverlayService.running.collectAsState()
    val loading by GhostOverlayService.loading.collectAsState()
    // 一覧を読み終わるまでは「ゴーストがありません」と言わない
    var listed by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    val version = remember { AppVersion.of(context) }

    suspend fun reload() {
        val repo = GhostStore.repository(context)
        // 壊れたゴーストやバルーンがあっても一覧は出す（出なければ消すこともできない）
        ghosts = withContext(Dispatchers.IO) {
            runCatching { repo.list() }.getOrDefault(emptyList()).map { ghost ->
                runCatching { GhostItem(ghost, repo.balloons) }.getOrElse { GhostItem(ghost, emptyList(), null, emptyList(), null) }
            }
        }
        balloons = withContext(Dispatchers.IO) { runCatching { repo.balloons.list() }.getOrDefault(emptyList()) }
        listed = true
    }

    LaunchedEffect(Unit) { reload() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canOverlay = Settings.canDrawOverlays(context)
        GhostOverlayService.setAppInFront(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { GhostOverlayService.setAppInFront(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val importNar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // 大きな .nar は展開に時間がかかるので、終わるまでボタンを止めて進んでいることを出す
            installing = true
            val result = try {
                withContext(Dispatchers.IO) { runCatching { GhostStore.install(context, uri) } }
            } finally {
                installing = false
            }
            result.onSuccess { reload() }
            snackbar.showSnackbar(result.fold({ GhostStore.describe(it) }, { "インストールできませんでした: ${it.message}" }))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("pono-droid") },
                actions = { TextButton(onClick = onShowLicenses) { Text("ライセンス") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!canOverlay) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ゴーストを他のアプリの上に表示するには、「他のアプリの上に重ねて表示」を許可してください。")
                        Button(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                            )
                        }) { Text("許可する") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { importNar.launch(arrayOf("*/*")) }, enabled = !installing) {
                    Text(if (installing) "インストール中…" else ".nar をインストール")
                }
                OutlinedButton(onClick = { GhostOverlayService.update(context) }) { Text("更新") }
                OutlinedButton(onClick = { GhostOverlayService.stop(context) }) { Text("終了") }
            }
            if (installing) LinearProgressIndicator(Modifier.fillMaxWidth())
            CharacterScaleSlider()
            BalloonScaleSlider()
            if (!listed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("ゴーストを読み込み中…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (ghosts.isEmpty()) {
                Text("ゴーストがありません。.nar ファイルをインストールしてください。", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ghosts, key = { it.ghost.dir.name }) { item ->
                    val dirName = item.ghost.dir.name
                    val isRunning = running == dirName
                    GhostRow(
                        item = item,
                        running = isRunning,
                        loading = loading == dirName,
                        enabled = canOverlay,
                        onStart = { GhostOverlayService.start(context, dirName) },
                        onSelectShell = { shell ->
                            scope.launch {
                                withContext(Dispatchers.IO) { item.ghost.selectShell(shell) }
                                reload()
                                // 起動中ならすぐ着替える（起動し直す）
                                if (isRunning) GhostOverlayService.start(context, dirName)
                            }
                        },
                        onSelectBalloon = { balloon ->
                            scope.launch {
                                withContext(Dispatchers.IO) { item.ghost.selectBalloon(balloon) }
                                reload()
                                if (isRunning) GhostOverlayService.start(context, dirName)
                            }
                        },
                        onUninstall = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { GhostStore.repository(context).uninstall(item.ghost) }
                                reload()
                                snackbar.showSnackbar(if (ok) "削除しました: ${item.ghost.name}" else "削除できませんでした: ${item.ghost.name}")
                            }
                        },
                    )
                }
                if (balloons.isNotEmpty()) {
                    item(key = "balloons-header") {
                        Text("バルーン", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                // 起動中のゴーストが使っているバルーンは、画像を読んでいる途中かもしれないので消させない
                val inUse = ghosts.firstOrNull { it.ghost.dir.name == running }?.selectedBalloon
                items(balloons, key = { "balloon:" + it.dir.name }) { balloon ->
                    BalloonRow(
                        balloon = balloon,
                        inUse = balloon.dir.name == inUse,
                        onUninstall = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { GhostStore.repository(context).balloons.uninstall(balloon) }
                                reload()
                                snackbar.showSnackbar(if (ok) "削除しました: ${balloon.name}" else "削除できませんでした: ${balloon.name}")
                            }
                        },
                    )
                }
                item(key = "version") {
                    Text(
                        "pono-droid バージョン $version",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

/** キャラクターの大きさ。起動中のゴーストにもすぐ反映される（OnShellScaling）。 */
@Composable
private fun CharacterScaleSlider() = ScaleSlider(
    label = "キャラクターの大きさ",
    key = OverlaySettings.KEY_CHARACTER_SCALE,
    read = OverlaySettings::characterScale,
    range = OverlaySettings.MIN_CHARACTER_SCALE..OverlaySettings.MAX_CHARACTER_SCALE,
)

/** バルーンの大きさ。起動中のゴーストにもすぐ反映される（OnBalloonScaling）。 */
@Composable
private fun BalloonScaleSlider() = ScaleSlider(
    label = "バルーンの大きさ",
    key = OverlaySettings.KEY_BALLOON_SCALE,
    read = OverlaySettings::balloonScale,
    range = OverlaySettings.MIN_BALLOON_SCALE..OverlaySettings.MAX_BALLOON_SCALE,
)

/** 倍率の設定。0.1 刻みで、指を離したときに保存する */
@Composable
private fun ScaleSlider(label: String, key: String, read: (SharedPreferences) -> Float, range: ClosedFloatingPointRange<Float>) {
    val context = LocalContext.current
    val prefs = remember { OverlaySettings.prefs(context) }
    var scale by remember { mutableFloatStateOf(read(prefs)) }
    Column {
        Text("$label: ${"%.1f".format(scale)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = scale,
            onValueChange = { scale = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { prefs.edit().putFloat(key, scale).apply() },
            valueRange = range,
        )
    }
}

/** 一覧の1行に出すもの。シェルやバルーンの名前を読むのにファイルを開くので、まとめて IO スレッドで作る */
private data class GhostItem(
    val ghost: Ghost,
    /** shell/ の下のディレクトリ名 → シェルの名前 */
    val shells: List<Pair<String, String>>,
    val selectedShell: String?,
    /** バルーン置き場のディレクトリ名 → バルーンの名前。null は pono-droid の簡易バルーン */
    val balloons: List<Pair<String?, String>>,
    val selectedBalloon: String?,
)

private fun GhostItem(ghost: Ghost, balloons: BalloonRepository) = GhostItem(
    ghost = ghost,
    shells = ghost.shellDirs.map { dir -> dir.name to runCatching { Shell(dir).name }.getOrDefault(dir.name) },
    selectedShell = ghost.shellDir()?.name,
    balloons = listOf<Pair<String?, String>>(null to "簡易バルーン") + balloons.list().map { it.dir.name to it.name },
    selectedBalloon = ghost.balloon(balloons)?.dir?.name,
)

/** インストール済みのバルーン1つ。削除は確かめてから */
@Composable
private fun BalloonRow(balloon: Balloon, inUse: Boolean, onUninstall: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(balloon.name + if (inUse) "（使用中）" else "", style = MaterialTheme.typography.bodyLarge)
                balloon.descript["craftmanw"]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(onClick = { confirm = true }, enabled = !inUse) { Text("削除") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("バルーンを削除") },
            text = { Text("「${balloon.name}」を削除しますか？ このバルーンを選んでいたゴーストは、ゴーストの指定のバルーンか簡易バルーンに戻ります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onUninstall()
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("やめる") } },
        )
    }
}

/** 「シェル: 名前」のようなボタンと、選ぶためのメニュー。選べるものが1つしか無ければ押せない */
@Composable
private fun <T> SelectButton(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: "なし"
    Box(modifier) {
        TextButton(onClick = { open = true }, enabled = options.size > 1) { Text("$label: $current") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, name) in options) {
                DropdownMenuItem(
                    text = { Text(if (value == selected) "✓ $name" else name) },
                    onClick = {
                        open = false
                        if (value != selected) onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun GhostRow(
    item: GhostItem,
    running: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onSelectShell: (String) -> Unit,
    onSelectBalloon: (String?) -> Unit,
    onUninstall: () -> Unit,
) {
    val ghost = item.ghost
    var confirmUninstall by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ghost.name + when {
                            loading -> "（読み込み中）"
                            running -> "（起動中）"
                            else -> ""
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        listOf(ghost.sakuraName, ghost.keroName).filter { it.isNotEmpty() }.joinToString(" / ") + "  (${ghost.shioriName})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (loading) {
                    // 読み込んでいる間は起動し直させない（押しても最初からやり直しになるだけ）
                    CircularProgressIndicator(Modifier.padding(horizontal = 24.dp).size(24.dp), strokeWidth = 2.dp)
                } else {
                    Button(onClick = onStart, enabled = enabled) { Text(if (running) "再起動" else "起動") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectButton("シェル", item.shells, item.selectedShell, { it?.let(onSelectShell) }, Modifier.weight(1f))
                // 起動中に消すと、動いているゴーストのファイルが無くなるので先に終了してもらう
                TextButton(onClick = { confirmUninstall = true }, enabled = !running && !loading) { Text("削除") }
            }
            SelectButton("バルーン", item.balloons, item.selectedBalloon, onSelectBalloon)
        }
    }
    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("ゴーストを削除") },
            text = { Text("「${ghost.name}」を削除しますか？ ゴーストが保存していた内容も消え、元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    onUninstall()
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("やめる") } },
        )
    }
}
