package net.pono.droid.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pono.baseware.balloon.Balloon
import net.pono.baseware.balloon.BalloonColor
import net.pono.baseware.balloon.TextArea
import net.pono.baseware.ghost.BalloonItem
import net.pono.baseware.ghost.BalloonLayout
import net.pono.baseware.sakura.Choice

/** バルーンの中の文の色と大きさ */
data class BalloonTextStyle(
    val textColor: Color,
    val choiceColor: Color,
    val fontSize: TextUnit,
) {
    companion object {
        val Simple = BalloonTextStyle(Color(0xFF3E2723), Color(0xFF1565C0), 14.sp)
    }
}

private fun BalloonColor.toColor() = Color(r, g, b)

/**
 * \0 と \1 は画面の右端・左端から出るので、画面の半分から余白を引いた幅に収めれば2人が同時に話しても重ならない。
 * 幅は固定にする（文字が増えるたびにウィンドウの幅が変わらないように。高さは行が増えたときだけ変わる）
 */
@Composable
private fun balloonMaxWidth(): Dp = (LocalConfiguration.current.screenWidthDp / 2 - 24).coerceIn(120, 280).dp

/** 文字を出している間だけ最後まで追いかける。選択肢で終わるとき（メニューなど）は先頭の問いかけを見せたいので動かさない */
@Composable
private fun AutoScroll(items: List<BalloonItem>, scroll: ScrollState) {
    val last = items.lastOrNull()
    LaunchedEffect(items.size, (last as? BalloonItem.Text)?.text?.length) {
        if (last !is BalloonItem.ChoiceItem) scroll.animateScrollTo(scroll.maxValue)
    }
}

/** pono-droid の簡易バルーン（ゴーストのバルーン画像が無いとき） */
@Composable
fun SimpleBalloon(items: List<BalloonItem>, onClick: () -> Unit, onChoice: (Choice) -> Unit) {
    val scroll = rememberScrollState()
    AutoScroll(items, scroll)
    val maxHeight = (LocalConfiguration.current.screenHeightDp / 2).coerceIn(220, 400).dp
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .width(balloonMaxWidth())
            .border(1.5.dp, Color(0xFF8D6E63), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.heightIn(max = maxHeight).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
            BalloonBody(items, onChoice, BalloonTextStyle.Simple)
        }
    }
}

/**
 * ゴーストのバルーン画像で描く。画像は画面の半分の幅に収まるよう縮め、文字が収まるいちばん小さい大きさを選ぶ
 * （どれにも収まらなければいちばん大きい画像で、文字の範囲をスクロールする）。
 */
@Composable
fun SkinBalloon(items: List<BalloonItem>, skin: BalloonSkin, scopeId: Int, onClick: () -> Unit, onChoice: (Choice) -> Unit) {
    val images by produceState<List<BalloonSkin.Image>?>(null, skin, scopeId) {
        value = withContext(Dispatchers.IO) { skin.images(scopeId) }
    }
    val candidates = images ?: return
    if (candidates.isEmpty()) {
        SimpleBalloon(items, onClick, onChoice)
        return
    }
    val density = LocalDensity.current
    val maxWidthPx = with(density) { balloonMaxWidth().toPx() }
    // 1枚ごとに縮めると大きさで文字の大きさが変わるので、いちばん広い画像に合わせて同じ比率で縮める
    val scale = minOf(density.density, maxWidthPx / candidates.maxOf { it.bitmap.width })
    val style = skin.textStyle(scale / density.density)
    val scroll = rememberScrollState()
    AutoScroll(items, scroll)

    SubcomposeLayout(Modifier.clickable(onClick = onClick)) { _ ->
        // 文字の高さを測って、収まる画像を選ぶ
        val chosen = candidates.indices.firstOrNull { i ->
            val area = candidates[i].area
            val measured = subcompose("measure$i") { BalloonBody(items, onChoice, style) }
                .first().measure(Constraints(minWidth = 0, maxWidth = (area.width * scale).toInt()))
            measured.height <= area.height * scale
        } ?: candidates.lastIndex
        val image = candidates[chosen]
        val placeable = subcompose("balloon") {
            SkinFrame(image, scale, scroll) { BalloonBody(items, onChoice, style) }
        }.first().measure(Constraints())
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/** バルーン画像を敷き、文字の範囲に中身を置く */
@Composable
private fun SkinFrame(image: BalloonSkin.Image, scale: Float, scroll: ScrollState, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    fun px(v: Int) = with(density) { (v * scale).toDp() }
    Box(Modifier.size(px(image.bitmap.width), px(image.bitmap.height))) {
        Image(image.bitmap, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.size(px(image.bitmap.width), px(image.bitmap.height)))
        Box(
            Modifier
                .offset(px(image.area.left), px(image.area.top))
                .size(px(image.area.width), px(image.area.height))
                .verticalScroll(scroll),
        ) {
            content()
        }
    }
}

/** バルーンの中身。行ごとに文を並べ、\_l で分けた列は横に等分して並べる */
@Composable
fun BalloonBody(items: List<BalloonItem>, onChoice: (Choice) -> Unit, style: BalloonTextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in BalloonLayout.lines(items)) {
            if (line.columns.size == 1) {
                BalloonText(line.columns[0], onChoice, style)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in line.columns) {
                        BalloonText(column, onChoice, style, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 1列分の文。選択肢とリンクは文の中で押せるようにする（\![*] があれば印を付ける） */
@Composable
private fun BalloonText(runs: List<BalloonLayout.Run>, onChoice: (Choice) -> Unit, style: BalloonTextStyle, modifier: Modifier = Modifier) {
    val links = TextLinkStyles(
        style = SpanStyle(color = style.choiceColor, fontWeight = FontWeight.Medium),
        pressedStyle = SpanStyle(color = style.choiceColor, background = style.choiceColor.copy(alpha = 0.15f)),
    )
    val text = buildAnnotatedString {
        for (run in runs) {
            when (run) {
                is BalloonLayout.Run.Text -> append(run.text)
                is BalloonLayout.Run.ChoiceRun -> {
                    if (run.marked) append("▸ ")
                    withLink(LinkAnnotation.Clickable(tag = run.choice.id, styles = links) { onChoice(run.choice) }) {
                        append(run.choice.title)
                    }
                }
            }
        }
    }
    // 選択肢を含む行は指で押しやすいよう少し高さを取る
    val hasChoice = runs.any { it is BalloonLayout.Run.ChoiceRun }
    Text(
        text = text,
        fontSize = style.fontSize,
        lineHeight = style.fontSize * (if (hasChoice) 1.7f else 1.4f),
        color = style.textColor,
        modifier = modifier.padding(vertical = if (hasChoice) 2.dp else 0.dp),
    )
}

/** インストール済みのバルーンを、画面に出せる形にしたもの。画像は使うときに読み込んで覚えておく */
class BalloonSkin(val balloon: Balloon) {
    class Image(val bitmap: ImageBitmap, val area: TextArea)

    private val cache = HashMap<Int, List<Image>>()

    @Synchronized
    fun images(scope: Int): List<Image> = cache.getOrPut(scope) {
        balloon.images(scope).mapNotNull { img ->
            val bmp = ImageTransparency.load(img.file, balloon.pnaFor(img.file), selfAlpha = null) ?: return@mapNotNull null
            Image(bmp.asImageBitmap(), balloon.textArea(bmp.width, bmp.height))
        }
    }

    /** [dpPerPx] は画像の 1px が何 dp になるか。字が小さくなりすぎないよう 12sp は残す */
    fun textStyle(dpPerPx: Float): BalloonTextStyle {
        val simple = BalloonTextStyle.Simple
        return BalloonTextStyle(
            textColor = balloon.fontColor?.toColor() ?: simple.textColor,
            choiceColor = balloon.choiceColor?.toColor() ?: simple.choiceColor,
            fontSize = maxOf(balloon.fontHeight * dpPerPx, 12f).sp,
        )
    }
}

/** 伺かの画像の透過。PNG にアルファがあればそのまま、無ければ .pna か左上の色で抜く */
object ImageTransparency {
    /** @param selfAlpha true / false で決め打ち、null なら画像にアルファがあるかで決める */
    fun load(file: java.io.File, pna: java.io.File?, selfAlpha: Boolean?): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        if (selfAlpha ?: bmp.hasAlpha()) return bmp
        val pnaBmp = pna?.let { BitmapFactory.decodeFile(it.path) }
        if (pnaBmp != null && pnaBmp.width == bmp.width && pnaBmp.height == bmp.height) applyPna(bmp, pnaBmp) else applyColorKey(bmp)
        return bmp
    }

    private fun applyColorKey(bmp: Bitmap) {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val key = px[0] or 0xFF000000.toInt()
        for (i in px.indices) {
            if ((px[i] or 0xFF000000.toInt()) == key) px[i] = 0
        }
        bmp.setHasAlpha(true)
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }

    /** .pna はグレースケールで、明るさがそのままアルファになる。 */
    private fun applyPna(bmp: Bitmap, pna: Bitmap) {
        val px = IntArray(bmp.width * bmp.height)
        val alpha = IntArray(px.size)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        pna.getPixels(alpha, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) {
            px[i] = (px[i] and 0x00FFFFFF) or ((alpha[i] and 0xFF) shl 24)
        }
        bmp.setHasAlpha(true)
        bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    }
}
