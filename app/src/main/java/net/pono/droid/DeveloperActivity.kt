package net.pono.droid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.pono.baseware.ghost.DeveloperLog
import net.pono.baseware.ghost.GhostController
import net.pono.droid.overlay.GhostOverlayService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 開発用の画面（発言履歴・ログ・SHIORI リクエスト・サーフェステスト・開発用パレット）。起動中のゴーストを見る */
class DeveloperActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val first = intent.getStringExtra(EXTRA_PAGE)?.takeIf { it in TITLES } ?: PAGE_DEVELOPER
        setContent {
            MaterialTheme {
                var page by rememberSaveable { mutableStateOf(first) }
                // パレットから開いたページは、戻るでパレットに戻る
                BackHandler(enabled = page != first) { page = first }
                val controller by GhostOverlayService.controller.collectAsState()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(TITLES.getValue(page), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                if (page != first) TextButton(onClick = { page = first }) { Text("戻る") }
                            },
                            actions = { TextButton(onClick = { finish() }) { Text("閉じる") } },
                        )
                    },
                ) { padding ->
                    val c = controller
                    if (c == null) {
                        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
                            Text("ゴーストが起動していません", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        when (page) {
                            PAGE_BACKLOG -> BacklogPage(c, Modifier.padding(padding))
                            PAGE_LOG -> LogPage(c, Modifier.padding(padding))
                            PAGE_SHIORI_REQUEST -> ShioriRequestPage(c, Modifier.padding(padding))
                            PAGE_SURFACE_TEST -> SurfaceTestPage(c, Modifier.padding(padding))
                            else -> DeveloperPage(c, Modifier.padding(padding), onOpen = { page = it })
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PAGE = "page"
        const val PAGE_BACKLOG = "backlog"
        const val PAGE_LOG = "log"
        const val PAGE_SHIORI_REQUEST = "shiorirequest"
        const val PAGE_SURFACE_TEST = "surfacetest"
        const val PAGE_DEVELOPER = "developer"

        private val TITLES = mapOf(
            PAGE_BACKLOG to "発言履歴",
            PAGE_LOG to "ログ",
            PAGE_SHIORI_REQUEST to "SHIORI リクエスト",
            PAGE_SURFACE_TEST to "サーフェステスト",
            PAGE_DEVELOPER to "開発用パレット",
        )

        fun start(context: Context, page: String) {
            context.startActivity(
                Intent(context, DeveloperActivity::class.java)
                    .putExtra(EXTRA_PAGE, page)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun formatTime(time: Long): String = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()).format(Date(time))

/** キャラクターの呼び名。名前が無ければ \p[N] */
private fun scopeLabel(controller: GhostController, scope: Int): String {
    val ghost = controller.ghost
    val name = when (scope) {
        0 -> ghost.sakuraName
        1 -> ghost.keroName
        else -> ghost.descript["char$scope.name"].orEmpty()
    }
    return name.ifEmpty { "\\p[$scope]" }
}

/** 発言履歴。開いたときのものを、新しいものから */
@Composable
private fun BacklogPage(controller: GhostController, modifier: Modifier) {
    val entries = remember(controller) { controller.backlog.asReversed() }
    Column(modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entries.isEmpty()) {
            Text("発言はまだありません", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(scopeLabel(controller, entry.scope), style = MaterialTheme.typography.titleMedium)
                        Text(formatTime(entry.time), style = MaterialTheme.typography.bodySmall)
                        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private val LOG_LABELS = mapOf("script" to "スクリプト", "error" to "エラー", "network" to "通信", "update" to "更新")

/** 開発用の記録。種類を1つずつ、新しいものから */
@Composable
private fun LogPage(controller: GhostController, modifier: Modifier) {
    var kind by rememberSaveable { mutableStateOf("error") }
    val entries = remember(controller, kind) { controller.developerLog.entries(kind) }
    Column(modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 4つ並ぶと狭い画面では入りきらないので、横にスクロールさせる
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in DeveloperLog.KINDS) {
                val label = LOG_LABELS[k] ?: k
                if (k == kind) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = { kind = k }) { Text(label) }
                }
            }
        }
        if (entries.isEmpty()) {
            Text("記録はありません", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.name.ifEmpty { entry.type }, style = MaterialTheme.typography.titleMedium)
                        Text("${formatTime(entry.time)}  ${entry.type}", style = MaterialTheme.typography.bodySmall)
                        Text(entry.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** イベントを SHIORI に送って、返ってきたスクリプトを見る */
@Composable
private fun ShioriRequestPage(controller: GhostController, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var event by rememberSaveable { mutableStateOf("") }
    var references by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    // 一度も送っていなければ結果は出さない
    var sent by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun send(play: Boolean) {
        scope.launch {
            sending = true
            failure = null
            // 空の行も Reference として送るが、最後の空行は入力の名残なので落とす
            val refs = references.lines().dropLastWhile { it.isEmpty() }
            try {
                result = controller.sendShioriRequest(event, refs, play)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result = null
                failure = "送れませんでした: ${e.message}"
            } finally {
                sending = false
                sent = true
            }
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = event,
            onValueChange = { event = it },
            label = { Text("イベント ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = references,
            onValueChange = { references = it },
            label = { Text("Reference（1行に1つ）") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canSend = !sending && event.isNotBlank()
            Button(onClick = { send(play = false) }, enabled = canSend) { Text("送る") }
            OutlinedButton(onClick = { send(play = true) }, enabled = canSend) { Text("送って再生") }
        }
        if (sending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("送信中…", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (failure != null) {
            // 失敗は結果の出る場所に出す（スナックバーにはしない）
            Text(failure.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        } else if (sent) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("返ってきたスクリプト", style = MaterialTheme.typography.titleMedium)
                    val script = result
                    if (script == null) {
                        Text("（答えなし）", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(script, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/** シェルにあるサーフェスを、選んだキャラクターに1つずつ出してみる */
@Composable
private fun SurfaceTestPage(controller: GhostController, modifier: Modifier) {
    val scopes = remember(controller) { controller.characterScopes }
    val surfaces = remember(controller) { controller.shell.surfaceIds() }
    var target by rememberSaveable { mutableStateOf(0) }
    Column(modifier.padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // キャラクターが多いゴーストもあるので、横にスクロールさせる
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in scopes) {
                val label = scopeLabel(controller, s)
                if (s == target) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = { target = s }) { Text(label) }
                }
            }
        }
        if (surfaces.isEmpty()) {
            Text("サーフェスがありません", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(surfaces, key = { it }) { id ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("サーフェス $id", style = MaterialTheme.typography.bodyLarge)
                        }
                        TextButton(onClick = { controller.testSurface(target, id) }) { Text("出す") }
                    }
                }
            }
        }
    }
}

/** 開発用パレット。今のゴーストのようすと、読み直し、ほかの開発用の画面への入り口 */
@Composable
private fun DeveloperPage(controller: GhostController, modifier: Modifier, onOpen: (String) -> Unit) {
    val status = remember(controller) { controller.property("currentghost.status") }
    val balloon = remember(controller) { controller.property("currentghost.balloon.name") }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(controller.ghost.name, style = MaterialTheme.typography.titleMedium)
                Text("SHIORI: ${controller.ghost.shioriName}", style = MaterialTheme.typography.bodySmall)
                Text("シェル: ${controller.shell.name}", style = MaterialTheme.typography.bodySmall)
                Text("バルーン: ${balloon ?: "なし"}", style = MaterialTheme.typography.bodySmall)
                Text("状態: ${status ?: "なし"}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("読み直す", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controller.reloadFromDeveloper("shiori") }) { Text("SHIORI") }
            OutlinedButton(onClick = { controller.reloadFromDeveloper("shell") }) { Text("シェル") }
            OutlinedButton(onClick = { controller.reloadFromDeveloper("ghost") }) { Text("ゴースト") }
        }
        Text("ほかの画面", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        PageLink("発言履歴", "ゴーストが話したことを、新しいものから", onClick = { onOpen(DeveloperActivity.PAGE_BACKLOG) })
        PageLink("ログ", "スクリプト・エラー・通信・更新の記録", onClick = { onOpen(DeveloperActivity.PAGE_LOG) })
        PageLink("SHIORI リクエスト", "イベントを送って、返ってきたスクリプトを見る", onClick = { onOpen(DeveloperActivity.PAGE_SHIORI_REQUEST) })
        PageLink("サーフェステスト", "シェルのサーフェスを1つずつ出してみる", onClick = { onOpen(DeveloperActivity.PAGE_SURFACE_TEST) })
    }
}

@Composable
private fun PageLink(title: String, description: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
