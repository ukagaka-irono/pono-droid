package net.pono.baseware.ghost

import java.io.File

/**
 * AI グラフ1枚（getaistate / getaistateex の答え）。表示値群 [1] ラベル群 [1] 加算値群 [1] 最大値群（後ろの3つは省ける）。
 * [labels] が無ければ Neuron などの既定、[added] が無ければ 0、[max] が無ければ（表示値＋加算値）の最大をすべての軸に
 */
data class AiGraph(val values: List<Double>, val labels: List<String>, val added: List<Double>, val max: List<Double>) {
    companion object {
        /** バイト値1で区切った答えを読む。数でないものは 0 */
        fun parse(raw: String): AiGraph {
            val parts = raw.split('\u0001')
            fun numbers(s: String?) = s?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { it.toDoubleOrNull() ?: 0.0 }.orEmpty()
            val values = numbers(parts.getOrNull(0)).take(MAX_AXES)
            val labels = parts.getOrNull(1)?.split(',')?.map { it.trim() }?.takeIf { l -> l.any { it.isNotEmpty() } } ?: DEFAULT_LABELS
            val added = numbers(parts.getOrNull(2)).let { a -> values.indices.map { a.getOrElse(it) { 0.0 } } }
            val written = numbers(parts.getOrNull(3))
            val top = values.indices.maxOfOrNull { values[it] + added[it] } ?: 0.0
            val max = values.indices.map { i -> written.getOrNull(i)?.takeIf { it > 0 } ?: top }
            return AiGraph(values, values.indices.map { labels.getOrNull(it)?.takeIf(String::isNotEmpty) ?: (it + 1).toString() }, added, max)
        }

        /** ラベルを省いたときの名前（SSP と同じ） */
        val DEFAULT_LABELS = listOf("Neuron", "NeuronM", "NeuronK", "NeuronD", "NeuronE", "Synapse")

        /** 1枚のグラフの軸の数の上限 */
        const val MAX_AXES = 64
    }
}

/**
 * hanayu（花柚）のグラフ。[kind] は usetime（7日分の起動時間の棒）・line・bar・radar、[points] はラベルと値、
 * [background] は hanayu.txt の background.filename（無ければ null）
 */
data class HanayuGraph(val kind: String, val title: String, val points: List<Pair<String, Double>>, val background: File?)

/**
 * AI グラフの背景の画像（descript.txt の shiori.logo.*）。[files] はグラフごとの画像（無ければ null）、
 * [align] は lefttop / leftbottom / righttop / rightbottom、[x] / [y] はそこからの位置（288×288 の描く面のピクセル）
 */
data class AiGraphLogo(val files: List<File?>, val align: String, val x: Int, val y: Int) {
    companion object {
        val ALIGNS = setOf("lefttop", "leftbottom", "righttop", "rightbottom")
    }
}
