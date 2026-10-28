package net.pono.droid.overlay

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.pono.baseware.ghost.InputRequest
import kotlin.math.roundToInt

private val SimpleBorder = Color(0xFF8D6E63)
private val SimpleText = Color(0xFF3E2723)

/**
 * ゴーストの入力の箱（\![open,inputbox,...] など）。SSP と同じく \0 のバルーンの位置（話している文の下）に出す。
 * バルーンに入力の箱の画像（balloonc0.png、複数行は balloonc5.png）があればそれを敷き、communicatebox の範囲に入力欄を置く。
 * 無ければ簡易バルーンと同じ見た目の箱にする。年月日・時分秒・スライダー・IP アドレスは数を入れる欄が要るので、いつも簡易な見た目。
 * 戻るボタンは取り消し。答えや取り消しは [onSubmit] / [onCancel] に箱の通し番号と一緒に返す。
 */
@Composable
fun InputBalloon(request: InputRequest, skin: BalloonSkin?, onSubmit: (Int, String) -> Unit, onCancel: (Int) -> Unit) {
    val password = request.kind == InputRequest.Kind.PASSWORD
    // 複数行は textareabox だけ（SSP のコミュニケートボックスも1行）
    val multiLine = request.kind == InputRequest.Kind.TEXTAREA
    // 開き直されたら（通し番号が変わったら）最初の文字からやり直す。最初の文字は選んでおき、そのまま打ち直せるようにする
    val textState: MutableState<TextFieldValue> =
        remember(request.serial) { mutableStateOf(TextFieldValue(request.text, TextRange(0, request.text.length))) }
    var text by textState
    // 年月日・時分秒・IP アドレス・スライダーの値
    val numbers = remember(request.serial) { request.values.map { mutableStateOf(it.toString()) } }
    var slider by remember(request.serial) { mutableStateOf(request.values.firstOrNull()?.toFloat() ?: 0f) }
    val focus = remember(request.serial) { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        val answer = when (request.kind) {
            InputRequest.Kind.DATE, InputRequest.Kind.TIME -> numbers.joinToString(",") { it.value.trim().toIntOrNull()?.toString() ?: "0" }
            // IP アドレスは4つの数を「.」でつなぐ（空の欄は 0、それぞれ 0〜255 に収める）
            InputRequest.Kind.IP -> numbers.joinToString(".") { ((it.value.trim().toIntOrNull() ?: 0).coerceIn(0, 255)).toString() }
            InputRequest.Kind.SLIDER -> slider.roundToInt().toString()
            else -> text.text
        }
        onSubmit(request.serial, answer)
    }
    val cancel = { onCancel(request.serial) }
    val numeric = request.isNumeric
    val image by produceState<BalloonSkin.Image?>(null, skin, multiLine, numeric) {
        value = if (skin == null || numeric) null else withContext(Dispatchers.IO) { skin.communicate(multiLine, request.balloonId) }
    }

    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier
            .padding(bottom = 4.dp)
            // 戻るボタンで取り消す（キーボードが出ていれば、まずキーボードが閉じる）
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Back && e.type == KeyEventType.KeyUp) {
                    cancel()
                    true
                } else {
                    false
                }
            },
    ) {
        val skinImage = image
        if (skinImage != null) {
            SkinInputBox(skinImage, skin!!, text, { text = it.limitedTo(request.limit) }, request, password, multiLine, focus, submit)
            Suggestions(
                request, text.text, { text = it.limitedTo(request.limit) },
                Modifier.width(balloonMaxWidth()).background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(12.dp)),
            )
            if (password) PasswordNote(Modifier.width(balloonMaxWidth()))
            InputButtons(cancel, submit, Modifier.background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(12.dp)))
        } else {
            SimpleInputBox(request, textState, numbers, slider, { slider = it }, password, multiLine, focus, submit, cancel)
        }
    }
    LaunchedEffect(request.serial) {
        // ウィンドウがキーボードを受けられるようになるのを少し待ってから欄を選び、キーボードを出す
        delay(150)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
}

/** バルーンの入力の箱の画像を敷き、communicatebox の範囲に1行（か複数行）の入力欄を置く */
@Composable
private fun SkinInputBox(
    image: BalloonSkin.Image,
    skin: BalloonSkin,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    request: InputRequest,
    password: Boolean,
    multiLine: Boolean,
    focus: FocusRequester,
    submit: () -> Unit,
) {
    val density = LocalDensity.current
    val maxWidthPx = with(density) { balloonMaxWidth().toPx() }
    // バルーンの dpi（作った画面の DPI）の分も掛ける
    val scale = minOf(density.density * skin.balloon.dpiFactor, maxWidthPx / image.bitmap.width)
    fun px(v: Int) = with(density) { (v * scale).toDp() }
    val area = image.area
    val balloon = skin.balloon
    val color = balloon.communicateFontColor?.toColor() ?: balloon.fontColor?.toColor() ?: SimpleText
    // 1行の欄は高さに合わせた文字の大きさにする（小さすぎると読めないので 12sp は残す）
    val fontSize = if (multiLine) {
        maxOf(balloon.fontHeight * scale / density.density, 12f)
    } else {
        (area.height * scale / density.density * 0.6f).coerceIn(12f, 18f)
    }
    Box(Modifier.size(px(image.bitmap.width), px(image.bitmap.height))) {
        Image(image.bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = !multiLine,
            // communicatebox.font.name（無ければ font.name）
            textStyle = TextStyle(color = color, fontSize = fontSize.sp, fontFamily = BalloonFonts.family(balloon.communicateFontNames)),
            cursorBrush = SolidColor(color),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions(request, password),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .offset(px(area.left), px(area.top))
                .size(px(area.width), px(area.height))
                .then(balloon.communicateBackground?.let { Modifier.background(it.toColor()) } ?: Modifier)
                .focusRequester(focus),
            decorationBox = { field ->
                Box(Modifier.fillMaxSize().padding(horizontal = 2.dp), contentAlignment = if (multiLine) Alignment.TopStart else Alignment.CenterStart) {
                    // --placeholder の案内文。空のときだけ薄く出す（communicatebox.placeholder.color、無ければ文字の色を薄めたもの）
                    if (value.text.isEmpty()) request.placeholder?.let { hint ->
                        Text(
                            hint,
                            color = balloon.placeholderColor?.toColor() ?: color.copy(alpha = 0.45f),
                            fontSize = fontSize.sp,
                            fontFamily = BalloonFonts.family(balloon.communicateFontNames),
                            maxLines = if (multiLine) Int.MAX_VALUE else 1,
                        )
                    }
                    field()
                }
            },
        )
    }
}

/** バルーンに入力の箱の画像が無いとき（と数を入れる箱）。簡易バルーンと同じ見た目の箱 */
@Composable
private fun SimpleInputBox(
    request: InputRequest,
    textState: MutableState<TextFieldValue>,
    numbers: List<MutableState<String>>,
    slider: Float,
    onSlider: (Float) -> Unit,
    password: Boolean,
    multiLine: Boolean,
    focus: FocusRequester,
    submit: () -> Unit,
    cancel: () -> Unit,
) {
    var text by textState
    // 年月日・時分秒は欄が3つ、IP アドレスは4つ並ぶので、バルーンより広く取る（画面には収める）
    val fieldRow = request.kind == InputRequest.Kind.DATE || request.kind == InputRequest.Kind.TIME || request.kind == InputRequest.Kind.IP
    val width = if (fieldRow) (LocalConfiguration.current.screenWidthDp - 32).coerceIn(160, 320).dp else balloonMaxWidth()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier.width(width).border(1.5.dp, SimpleBorder, RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            val fieldModifier = Modifier.padding(end = 8.dp)
            when (request.kind) {
                InputRequest.Kind.DATE, InputRequest.Kind.TIME, InputRequest.Kind.IP -> {
                    val labels = when (request.kind) {
                        InputRequest.Kind.DATE -> listOf("年", "月", "日")
                        InputRequest.Kind.TIME -> listOf("時", "分", "秒")
                        else -> listOf("1", "2", "3", "4")
                    }
                    // IP アドレスの欄は 0〜255 なので3桁まで
                    val maxDigits = if (request.kind == InputRequest.Kind.IP) 3 else 6
                    Row(fieldModifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        numbers.forEachIndexed { i, state ->
                            OutlinedTextField(
                                value = state.value,
                                onValueChange = { v -> state.value = v.filter { it.isDigit() }.take(maxDigits) },
                                label = { Text(labels.getOrElse(i) { "" }, fontSize = 11.sp) },
                                singleLine = true,
                                textStyle = TextStyle(color = SimpleText, fontSize = 14.sp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (i == numbers.lastIndex) ImeAction.Done else ImeAction.Next),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                // 年は4桁あるので、月日より広く取る
                                modifier = Modifier.weight(if (request.kind == InputRequest.Kind.DATE && i == 0) 1.6f else 1f)
                                    .then(if (i == 0) Modifier.focusRequester(focus) else Modifier),
                            )
                        }
                    }
                }
                InputRequest.Kind.SLIDER -> {
                    val min = request.values.getOrNull(1) ?: 0
                    val max = request.values.getOrNull(2) ?: 100
                    Text("${slider.roundToInt()}（$min〜$max）", color = SimpleText, fontSize = 14.sp)
                    Slider(
                        value = slider,
                        onValueChange = onSlider,
                        valueRange = min.toFloat()..max.toFloat(),
                        modifier = fieldModifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                else -> OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.limitedTo(request.limit) },
                    label = { Text(request.label(), fontSize = 11.sp) },
                    singleLine = !multiLine,
                    minLines = if (request.kind == InputRequest.Kind.TEXTAREA) 3 else 1,
                    maxLines = if (multiLine) 5 else 1,
                    textStyle = TextStyle(color = SimpleText, fontSize = 14.sp),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = keyboardOptions(request, password),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = fieldModifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Suggestions(request, text.text, { text = it.limitedTo(request.limit) }, fieldModifier.fillMaxWidth())
            if (password) PasswordNote(fieldModifier.padding(top = 4.dp))
            InputButtons(cancel, submit, Modifier.align(Alignment.End))
        }
    }
}

/**
 * inputbox.autocomplete の候補のうち、打った文字で始まるもの（大文字小文字は区別しない）。欄が空のときと、打った文字と同じものは出さない。
 * 押すとその文字にする
 */
@Composable
private fun Suggestions(request: InputRequest, typed: String, onPick: (TextFieldValue) -> Unit, modifier: Modifier) {
    if (typed.isEmpty() || request.suggestions.isEmpty()) return
    val matches = request.suggestions.filter { it.startsWith(typed, ignoreCase = true) && it != typed }.take(MAX_SUGGESTIONS)
    if (matches.isEmpty()) return
    Column(modifier) {
        for (s in matches) {
            Text(
                s,
                color = SimpleText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(TextFieldValue(s, TextRange(s.length))) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

/** 入力欄の下に出す候補の数 */
private const val MAX_SUGGESTIONS = 5

/** パスワードの箱に添える注意。入れたものはゴーストのスクリプトに渡る */
@Composable
private fun PasswordNote(modifier: Modifier) {
    Text(
        "入力した内容はゴーストのスクリプトに渡されます。他のサービスのパスワードは入れないでください。",
        color = SimpleText.copy(alpha = 0.7f),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        modifier = modifier,
    )
}

@Composable
private fun InputButtons(cancel: () -> Unit, submit: () -> Unit, modifier: Modifier) {
    Row(modifier) {
        TextButton(onClick = cancel) { Text("キャンセル", color = SimpleBorder) }
        TextButton(onClick = submit) { Text("OK", color = SimpleBorder) }
    }
}

private fun keyboardOptions(request: InputRequest, password: Boolean) = KeyboardOptions(
    keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
    // 複数行の箱（textareabox）は改行を入れられるようにし、OK ボタンで送る
    imeAction = if (request.kind == InputRequest.Kind.TEXTAREA) ImeAction.Default else ImeAction.Done,
)

private fun InputRequest.label() = when (kind) {
    InputRequest.Kind.INPUT -> "入力"
    InputRequest.Kind.PASSWORD -> "パスワード"
    InputRequest.Kind.COMMUNICATE -> "話しかける"
    InputRequest.Kind.TEACH -> "教える"
    InputRequest.Kind.DATE -> "日付"
    InputRequest.Kind.TIME -> "時刻"
    InputRequest.Kind.IP -> "IP アドレス"
    InputRequest.Kind.SLIDER -> "値"
    InputRequest.Kind.TEXTAREA -> "入力（複数行）"
}

/** --limit= の文字数で切る（textareabox は改行も 1 文字）。[limit] が null なら切らない */
private fun TextFieldValue.limitedTo(limit: Int?): TextFieldValue =
    if (limit == null || text.length <= limit) this else copy(text = text.take(limit), selection = TextRange(minOf(selection.start, limit), minOf(selection.end, limit)))
