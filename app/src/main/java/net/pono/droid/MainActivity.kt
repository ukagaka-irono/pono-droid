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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import net.pono.droid.overlay.GhostOverlayService
import net.pono.droid.overlay.OverlaySettings
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                GhostListScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GhostListScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var ghosts by remember { mutableStateOf<List<Ghost>>(emptyList()) }
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    suspend fun reload() {
        ghosts = withContext(Dispatchers.IO) { GhostStore.repository(context).list() }
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
        topBar = { TopAppBar(title = { Text("pono-droid") }) },
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
                OutlinedButton(onClick = { GhostOverlayService.stop(context) }) { Text("終了") }
            }
            CharacterScaleSlider()
            if (ghosts.isEmpty()) {
                Text("ゴーストがありません。.nar ファイルをインストールしてください。", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ghosts, key = { it.dir.name }) { ghost ->
                    GhostRow(ghost, enabled = canOverlay) { GhostOverlayService.start(context, ghost.dir.name) }
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

@Composable
private fun GhostRow(ghost: Ghost, enabled: Boolean, onStart: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ghost.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(ghost.sakuraName, ghost.keroName).filter { it.isNotEmpty() }.joinToString(" / ") + "  (${ghost.shioriName})",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onStart, enabled = enabled) { Text("起動") }
        }
    }
}
