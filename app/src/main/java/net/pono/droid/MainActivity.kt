package net.pono.droid

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.balloon.BalloonRepository
import net.pono.baseware.ghost.Ghost
import net.pono.baseware.ghost.NarInstaller
import net.pono.baseware.net.MailAccount
import net.pono.baseware.shell.Shell
import net.pono.droid.overlay.GhostOverlayService
import net.pono.droid.overlay.MailAccounts
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
                item(key = "settings-header") {
                    Text("設定", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                item(key = "setting:developer") { SettingSwitch("開発者向けの機能", OverlaySettings.KEY_DEVELOPER, OverlaySettings::developer) }
                item(key = "setting:speech") { SettingSwitch("話した文字を読み上げる", OverlaySettings.KEY_SPEECH, OverlaySettings::speech) }
                item(key = "setting:sstp") { SettingSwitch("ほかのアプリから SSTP を受ける（この端末の中だけ）", OverlaySettings.KEY_SSTP, OverlaySettings::sstp) }
                item(key = "setting:wallpaper") {
                    SettingSwitch("ゴーストが壁紙を変えてよい（元の壁紙には戻せません）", OverlaySettings.KEY_WALLPAPER, OverlaySettings::wallpaper)
                }
                item(key = "setting:calendar") {
                    SettingSwitch(
                        "今日の予定をゴーストに伝える（端末のカレンダー）", OverlaySettings.KEY_CALENDAR, OverlaySettings::calendar,
                        permission = Manifest.permission.READ_CALENDAR,
                        onDenied = { scope.launch { snackbar.showSnackbar("カレンダーを読む許可がありません") } },
                    )
                }
                item(key = "setting:voice") {
                    SettingSwitch(
                        "名前を呼ぶと声を聞く（マイク）", OverlaySettings.KEY_VOICE, OverlaySettings::voice,
                        permission = Manifest.permission.RECORD_AUDIO,
                        onDenied = { scope.launch { snackbar.showSnackbar("マイクの許可がありません") } },
                    )
                }
                item(key = "setting:usb") { SettingSwitch("USB 機器をつないだり外したりしたらゴーストに伝える", OverlaySettings.KEY_USB, OverlaySettings::usb) }
                item(key = "setting:mail") { MailAccountRow(onMessage = { scope.launch { snackbar.showSnackbar(it) } }) }
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

/**
 * 入り切りの設定。切り替えたときに保存する。permission があれば、入れる前に許可を求め、許されなければ切ったままにする。
 * 後から端末の設定で許可を取り消されたら、設定が入っていても切れているように出す（画面に戻るたびに確かめる）
 */
@Composable
private fun SettingSwitch(
    label: String,
    key: String,
    read: (SharedPreferences) -> Boolean,
    permission: String? = null,
    onDenied: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { OverlaySettings.prefs(context) }
    var checked by remember { mutableStateOf(read(prefs)) }
    fun hasPermission() = permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasPermission()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasPermission() }
    fun save(value: Boolean) {
        checked = value
        prefs.edit().putBoolean(key, value).apply()
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) save(true) else onDenied()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked && granted,
            onCheckedChange = { on -> if (on && permission != null) request.launch(permission) else save(on) },
        )
    }
}

/** メールの確認（\![biff]）のアカウントと、確かめる間隔。アカウントは1つだけ */
@Composable
private fun MailAccountRow(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf<MailAccount?>(null) }
    // 読み終わるまでは編集させない（無いと思って保存すると、あったアカウントを消してしまう）
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var interval by remember { mutableStateOf(MailAccounts.intervalMinutes(context)) }
    LaunchedEffect(Unit) {
        // パスワードを鍵で戻すので IO スレッドで読む
        account = withContext(Dispatchers.IO) { runCatching { MailAccounts.load(context) }.getOrDefault(emptyList()) }.firstOrNull()
        loaded = true
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("メールのアカウント: ${account?.name ?: "なし"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = true }, enabled = loaded) { Text("編集") }
            TextButton(onClick = { confirmDelete = true }, enabled = account != null) { Text("削除") }
        }
        // 前に入れた値が選べるものに無くても、今の値として出す
        val minutes = listOf(0, 15, 30, 60).let { if (interval in it) it else (it + interval).sorted() }
        SelectButton("確かめる間隔", minutes.map { it to if (it == 0) "しない" else "$it 分" }, interval, { value ->
            interval = value
            MailAccounts.setIntervalMinutes(context, value)
        })
    }
    if (editing) {
        MailAccountDialog(
            account = account,
            onDismiss = { editing = false },
            onSave = { new ->
                editing = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { MailAccounts.save(context, listOf(new)) } }
                    result.onSuccess { account = new }
                    onMessage(result.fold({ "保存しました" }, { "保存できませんでした: ${it.message}" }))
                }
            },
        )
    }
    val current = account
    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("メールのアカウントを削除") },
            text = { Text("「${current.name}」を削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { MailAccounts.save(context, emptyList()) }.isSuccess }
                        if (ok) account = null
                        onMessage(if (ok) "削除しました: ${current.name}" else "削除できませんでした: ${current.name}")
                    }
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }
}

private fun defaultMailPort(protocol: String) = if (protocol == "pop3") 995 else 993

/** メールのアカウントを入れる。削除は一覧の行から（確かめてから） */
@Composable
private fun MailAccountDialog(account: MailAccount?, onDismiss: () -> Unit, onSave: (MailAccount) -> Unit) {
    var name by remember { mutableStateOf(account?.name ?: "") }
    var host by remember { mutableStateOf(account?.host ?: "") }
    var protocol by remember { mutableStateOf(account?.protocol ?: "imap") }
    var port by remember { mutableStateOf((account?.port ?: defaultMailPort(protocol)).toString()) }
    var user by remember { mutableStateOf(account?.user ?: "") }
    var password by remember { mutableStateOf(account?.password ?: "") }
    val portNumber = port.trim().toIntOrNull()
    val canSave = name.isNotBlank() && host.isNotBlank() && user.isNotBlank() && password.isNotEmpty() && portNumber != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("メールのアカウント") },
        text = {
            // 小さい画面では入りきらないので流せるようにする
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名前") }, singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("サーバー") }, singleLine = true)
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("ポート") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("ユーザー名") }, singleLine = true)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("パスワード") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Text("パスワードは端末の鍵で暗号にして保存します", style = MaterialTheme.typography.bodySmall)
                SelectButton("種類", listOf("imap" to "IMAP", "pop3" to "POP3"), protocol, { value ->
                    // ポートが前の種類の決まりのままなら、新しい種類の決まりに合わせる
                    if (port.trim() == defaultMailPort(protocol).toString()) port = defaultMailPort(value).toString()
                    protocol = value
                })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { portNumber?.let { onSave(MailAccount(name.trim(), protocol, host.trim(), it, user.trim(), password)) } },
                enabled = canSave,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
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
