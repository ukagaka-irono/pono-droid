package net.pono.droid

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pono.baseware.ghost.SystemDialogRequest
import net.pono.droid.overlay.GhostOverlayService
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * \![open,dialog,open / save / folder / color] の答え口。ファイルやフォルダは Android の選ぶ画面を出し、
 * 選んだものをゴーストのフォルダに写してパスを返す。色だけはこの画面のダイアログで選ぶ。
 * 答えはどの場合も1回だけ返し、返したら閉じる（やめたときや選べるアプリが無いときは null）
 */
class SystemDialogActivity : ComponentActivity() {
    private var serial = 0
    private lateinit var request: SystemDialogRequest

    /** 選んだものを写している間。画面を作り直すと写すのが止まるので、作り直したら「やめた」にする */
    private var copying = false

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) done(null) else copy { SystemDialogFiles.copyIn(this, uri, request.dir) }
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri == null) return@registerForActivityResult done(null)
        val target = SystemDialogFiles.saveTarget(this, uri, request.dir, request.defaultName)
        GhostOverlayService.watchSave(target, uri)
        done(target.absolutePath)
    }

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) done(null) else copy { SystemDialogFiles.copyTreeIn(this, uri, request.dir) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serial = intent.getIntExtra(EXTRA_SERIAL, 0)
        // もう答えた（close された・アプリが作り直された）ものは何もせず閉じる
        val request = requests[serial] ?: return finish()
        this.request = request
        opened += this
        if (savedInstanceState?.getBoolean(STATE_COPYING) == true) return done(null)
        when (request.kind) {
            "color" -> setContent {
                MaterialTheme {
                    ColorDialog(
                        title = request.title.ifBlank { "色を選ぶ" },
                        initial = request.color ?: 0xFFFFFF,
                        onPick = { r, g, b -> done("$r,$g,$b") },
                        onDismiss = { done(null) },
                    )
                }
            }
            // 選ぶ画面は作り直しても残るので、出すのは最初の1回だけ
            else -> if (savedInstanceState == null) launchPicker(request)
        }
    }

    private fun launchPicker(request: SystemDialogRequest) {
        try {
            when (request.kind) {
                "open" -> openDocument.launch(arrayOf("*/*"))
                "save" -> createDocument.launch(request.defaultName.ifBlank { "file" })
                "folder" -> openTree.launch(null)
                else -> done(null)
            }
        } catch (e: ActivityNotFoundException) {
            // 選ぶアプリが入っていない
            done(null)
        }
    }

    /** 選んだものを IO スレッドで写し、写した先のパスを返す（写せなければ null） */
    private fun copy(block: () -> File?) {
        copying = true
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { block() }
            copying = false
            done(file?.absolutePath)
        }
    }

    private fun done(value: String?) {
        answer(serial, value)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_COPYING, copying)
    }

    /**
     * 色を選ぶ途中でホームやほかのアプリに移ったら取り消す（この画面は履歴に残さないので、戻ってこられない）。
     * 画面が消えただけなら待つ。ほかの種類は Android の選ぶ画面に隠れているだけなので、待つ
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::request.isInitialized && request.kind == "color") done(null)
    }

    override fun onDestroy() {
        opened -= this
        // 画面の向きが変わっただけなら作り直したほうが答える
        if (!isChangingConfigurations) answer(serial, null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SERIAL = "serial"
        private const val STATE_COPYING = "copying"

        /** まだ答えていないもの。答えたら消すので、ここに無ければ答え済み */
        private val requests = ConcurrentHashMap<Int, SystemDialogRequest>()

        /** 出ている画面。メインスレッドからだけ触る */
        private val opened = mutableListOf<SystemDialogActivity>()

        private val main = Handler(Looper.getMainLooper())

        fun start(context: Context, request: SystemDialogRequest) {
            requests[request.serial] = request
            context.startActivity(
                Intent(context, SystemDialogActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_SERIAL, request.serial),
            )
        }

        /** id が同じダイアログを閉じる（null ならすべて）。閉じたものは「やめた」と答える */
        fun close(id: String?) {
            main.post {
                val serials = requests.values.filter { id == null || it.id == id }.map { it.serial }
                for (serial in serials) answer(serial, null)
                opened.filter { it.serial in serials }.forEach { it.finish() }
            }
        }

        /** 答えはメインスレッドで、1つにつき1回だけ */
        private fun answer(serial: Int, value: String?) {
            requests.remove(serial) ?: return
            GhostOverlayService.controller.value?.onSystemDialogResult(serial, value)
        }
    }
}

/** 赤・緑・青を 0〜255 で選ぶダイアログ。上に選んでいる色を出す */
@Composable
private fun ColorDialog(title: String, initial: Int, onPick: (Int, Int, Int) -> Unit, onDismiss: () -> Unit) {
    var red by rememberSaveable { mutableIntStateOf(initial shr 16 and 0xFF) }
    var green by rememberSaveable { mutableIntStateOf(initial shr 8 and 0xFF) }
    var blue by rememberSaveable { mutableIntStateOf(initial and 0xFF) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).background(Color(red, green, blue), MaterialTheme.shapes.medium))
                ColorSlider("赤", red) { red = it }
                ColorSlider("緑", green) { green = it }
                ColorSlider("青", blue) { blue = it }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(red, green, blue) }) { Text("選ぶ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/** 色の1つ分。1 刻み */
@Composable
private fun ColorSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..255f,
        )
    }
}
