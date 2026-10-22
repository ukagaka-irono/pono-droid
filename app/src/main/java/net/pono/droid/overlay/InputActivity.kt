package net.pono.droid.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.pono.baseware.ghost.GhostController
import net.pono.baseware.ghost.InputRequest

/**
 * ゴーストの入力の箱（\![open,inputbox,...] など）。
 * オーバーレイのウィンドウはキーボードの入力を受けられないので、透明な Activity の上にダイアログを出す。
 * ゴーストが箱を閉じたら（答えた・取り消した・\![close,inputbox] されたら）自分も閉じる。
 * ゴーストは好きなときに好きな名前で箱を開けるので、pono-droid の箱だとわかる見た目にする（他のアプリの画面に見せかけさせない）。
 */
class InputActivity : ComponentActivity() {
    /** いま表示している箱の通し番号。答えずに閉じたら取り消しを送る */
    private var shownSerial: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = GhostOverlayService.controller.value ?: return finish()
        lifecycleScope.launch {
            controller.state.collect { state ->
                val input = state.input ?: return@collect finish()
                // パスワードはスクリーンショットや画面の記録に写さない
                if (input.kind == InputRequest.Kind.PASSWORD) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
        setContent {
            MaterialTheme {
                val state by controller.state.collectAsState()
                val request = state.input ?: return@MaterialTheme
                shownSerial = request.serial
                InputDialog(controller.ghost.name, request, controller)
            }
        }
    }

    override fun onDestroy() {
        // 戻るボタンなどで答えずに閉じた。もう閉じた箱なら controller が無視する
        if (isFinishing) {
            shownSerial?.let { serial -> GhostOverlayService.controller.value?.onInputCancel(serial) }
        }
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, InputActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

@Composable
private fun InputDialog(ghostName: String, request: InputRequest, controller: GhostController) {
    val password = request.kind == InputRequest.Kind.PASSWORD
    // 開き直されたら（通し番号が変わったら）最初の文字からやり直す。最初の文字は選んでおき、そのまま打ち直せるようにする。
    // パスワードは画面の保存状態にも残さない
    val initial = { mutableStateOf(TextFieldValue(request.text, TextRange(0, request.text.length))) }
    val textState: MutableState<TextFieldValue> =
        if (password) remember(request.serial, initial) else rememberSaveable(request.serial, stateSaver = TextFieldValue.Saver, init = initial)
    var text by textState
    val focus = remember(request.serial) { FocusRequester() }
    val submit = { controller.onInputSubmit(request.serial, text.text) }
    // 別のウィンドウ（Dialog）にするとキーボードが自動で出ないので、この Activity の窓の中にダイアログ風に描く
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).imePadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 480.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text("pono-droid", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "ゴースト「${displayName(ghostName)}」が入力を求めています",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (password) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "入力した内容はこのゴーストのスクリプトに渡されます。他のサービスのパスワードは入れないでください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(request.label()) },
                    singleLine = request.kind != InputRequest.Kind.COMMUNICATE,
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = { controller.onInputCancel(request.serial) }) { Text("キャンセル") }
                    TextButton(onClick = submit) { Text("OK") }
                }
            }
        }
    }
    LaunchedEffect(request.serial) { focus.requestFocus() }
}

/** ゴーストの名前は descript.txt に書かれたまま（信用しない）。制御文字を落とし、長さも抑える */
private fun displayName(name: String): String =
    name.filterNot { it.isISOControl() }.trim().take(40).ifEmpty { "(名前なし)" }

private fun InputRequest.label() = when (kind) {
    InputRequest.Kind.INPUT -> "入力"
    InputRequest.Kind.PASSWORD -> "パスワード"
    InputRequest.Kind.COMMUNICATE -> "話しかける"
    InputRequest.Kind.TEACH -> "教える"
}
