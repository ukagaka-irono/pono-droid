package net.pono.droid

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import net.pono.baseware.ghost.Ghost
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
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    val running by GhostOverlayService.running.collectAsState()

    suspend fun reload() {
        ghosts = withContext(Dispatchers.IO) { GhostStore.repository(context).list().map(::GhostItem) }
    }

    LaunchedEffect(Unit) { reload() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canOverlay = Settings.canDrawOverlays(context) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val importNar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { GhostStore.install(context, uri) } }
            result.onSuccess { reload() }
            snackbar.showSnackbar(result.fold({ "インストールしました: ${it.name}" }, { "インストールできませんでした: ${it.message}" }))
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
                Button(onClick = { importNar.launch(arrayOf("*/*")) }) { Text(".nar をインストール") }
                OutlinedButton(onClick = { GhostOverlayService.update(context) }) { Text("更新") }
                OutlinedButton(onClick = { GhostOverlayService.stop(context) }) { Text("終了") }
            }
            CharacterScaleSlider()
            if (ghosts.isEmpty()) {
                Text("ゴーストがありません。.nar ファイルをインストールしてください。", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ghosts, key = { it.ghost.dir.name }) { item ->
                    val dirName = item.ghost.dir.name
                    val isRunning = running == dirName
                    GhostRow(
                        item = item,
                        running = isRunning,
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
                        onUninstall = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { GhostStore.repository(context).uninstall(item.ghost) }
                                reload()
                                snackbar.showSnackbar(if (ok) "削除しました: ${item.ghost.name}" else "削除できませんでした: ${item.ghost.name}")
                            }
                        },
                    )
                }
            }
        }
    }
}

/** キャラクターの大きさ。起動中のゴーストにもすぐ反映される。 */
@Composable
private fun CharacterScaleSlider() {
    val context = LocalContext.current
    val prefs = remember { OverlaySettings.prefs(context) }
    var scale by remember { mutableFloatStateOf(OverlaySettings.characterScale(prefs)) }
    Column {
        Text("キャラクターの大きさ: ${"%.1f".format(scale)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = scale,
            onValueChange = { scale = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { prefs.edit().putFloat(OverlaySettings.KEY_CHARACTER_SCALE, scale).apply() },
            valueRange = OverlaySettings.MIN_CHARACTER_SCALE..OverlaySettings.MAX_CHARACTER_SCALE,
        )
    }
}

/** 一覧の1行に出すもの。シェルの名前を読むのにファイルを開くので、まとめて IO スレッドで作る */
private data class GhostItem(
    val ghost: Ghost,
    /** shell/ の下のディレクトリ名 → シェルの名前 */
    val shells: List<Pair<String, String>>,
    val selectedShell: String?,
)

private fun GhostItem(ghost: Ghost) = GhostItem(
    ghost = ghost,
    shells = ghost.shellDirs.map { it.name to Shell(it).name },
    selectedShell = ghost.shellDir()?.name,
)

@Composable
private fun GhostRow(
    item: GhostItem,
    running: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onSelectShell: (String) -> Unit,
    onUninstall: () -> Unit,
) {
    val ghost = item.ghost
    var confirmUninstall by remember { mutableStateOf(false) }
    var shellMenu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ghost.name + if (running) "（起動中）" else "", style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOf(ghost.sakuraName, ghost.keroName).filter { it.isNotEmpty() }.joinToString(" / ") + "  (${ghost.shioriName})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onStart, enabled = enabled) { Text(if (running) "再起動" else "起動") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shellName = item.shells.firstOrNull { it.first == item.selectedShell }?.second ?: "なし"
                Box(Modifier.weight(1f)) {
                    // シェルが1つしか無ければ選ぶものが無いので、名前を出すだけにする
                    TextButton(onClick = { shellMenu = true }, enabled = item.shells.size > 1) { Text("シェル: $shellName") }
                    DropdownMenu(expanded = shellMenu, onDismissRequest = { shellMenu = false }) {
                        for ((dir, name) in item.shells) {
                            DropdownMenuItem(
                                text = { Text(if (dir == item.selectedShell) "✓ $name" else name) },
                                onClick = {
                                    shellMenu = false
                                    if (dir != item.selectedShell) onSelectShell(dir)
                                },
                            )
                        }
                    }
                }
                // 起動中に消すと、動いているゴーストのファイルが無くなるので先に終了してもらう
                TextButton(onClick = { confirmUninstall = true }, enabled = !running) { Text("削除") }
            }
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
