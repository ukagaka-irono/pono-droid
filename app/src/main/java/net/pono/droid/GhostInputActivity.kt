package net.pono.droid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.pono.droid.overlay.GhostOverlayService

/** ユーザーが開いている間だけキーとゲームパッドを受け取る。画面外のアプリの入力は取らない。 */
class GhostInputActivity : ComponentActivity() {
    private var scopeId = 0
    private val ghost get() = intent.getStringExtra("ghost").orEmpty()
    private val controller get() = GhostOverlayService.findController(ghost)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                var selectedScope by rememberSaveable { mutableIntStateOf(0) }
                var modifiers by rememberSaveable { mutableStateOf("") }
                val running by GhostOverlayService.runningGhosts.collectAsState()
                val c = controller
                val available = ghost in running && c != null
                val scopes = c?.state?.collectAsState()?.value?.characters?.keys?.sorted().orEmpty()
                SideEffect { scopeId = selectedScope }
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.input_title), style = MaterialTheme.typography.headlineSmall)
                        Text(c?.ghost?.name ?: stringResource(R.string.input_stopped))
                        Text(stringResource(R.string.input_help))
                        Text(stringResource(R.string.input_scope))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            scopes.forEach { id -> FilterChip(selectedScope == id, { selectedScope = id }, label = { Text("\\p[$id]") }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("shift", "ctrl", "alt", "win").forEach { name ->
                                val selected = name in modifiers.split(',')
                                FilterChip(selected, { modifiers = (if (selected) modifiers.split(',').filter { it != name } else modifiers.split(',') + name).filter { it.isNotEmpty() }.joinToString(",") }, label = { Text(name) })
                            }
                        }
                        fun send(code: Int, label: String = WindowsKeys.name(code)) {
                            controller?.onKeyPress(label, code, scopeId = selectedScope, modifiers = modifiers.split(',').toSet())
                        }
                        for (row in listOf((0x70..0x7b).toList(), "1234567890".map { it.code }, "QWERTYUIOP".map { it.code }, "ASDFGHJKL".map { it.code }, "ZXCVBNM".map { it.code })) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { code -> OutlinedButton(onClick = { send(code) }, enabled = available, contentPadding = PaddingValues(10.dp)) { Text(WindowsKeys.name(code)) } }
                            }
                        }
                        WindowsKeys.names.entries.chunked(4).forEach { row ->
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { (code, label) -> OutlinedButton(onClick = { send(code, label) }, enabled = available) { Text(label) } }
                            }
                        }
                        Button(onClick = { finish() }) { Text(stringResource(R.string.input_close)) }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (GhostOverlayService.gamepadKey(event)) return true
        if (event.isSystem) return super.dispatchKeyEvent(event)
        val code = WindowsKeys.code(event.keyCode) ?: return super.dispatchKeyEvent(event)
        val c = controller ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val modifiers = buildSet {
                if (event.isShiftPressed) add("shift"); if (event.isCtrlPressed) add("ctrl")
                if (event.isAltPressed) add("alt"); if (event.isMetaPressed) add("win")
            }
            c.onKeyPress(WindowsKeys.name(code, event.displayLabel), code, event.repeatCount, scopeId, modifiers)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = GhostOverlayService.gamepadMotion(event) || super.dispatchGenericMotionEvent(event)
    override fun onResume() { super.onResume(); GhostOverlayService.setAppInFront(true) }
    override fun onPause() {
        GhostOverlayService.releaseGamepadInputs()
        GhostOverlayService.setAppInFront(false)
        super.onPause()
    }

    companion object {
        fun start(context: Context, ghost: String) = context.startActivity(Intent(context, GhostInputActivity::class.java).putExtra("ghost", ghost).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
