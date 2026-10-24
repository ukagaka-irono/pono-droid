package net.pono.baseware.ghost

/**
 * キャラクターのウィンドウ全体にかかること（SSP の \![set,zorder]・\![set,sticky-window]・最小化など）。
 * 置き方は表示する側が決め、ここには頼まれたことだけを持つ
 */
data class WindowState(
    /** \![set,windowstate,minimize] とメニューの「隠す」。隠している間はキャラクターもバルーンも出さない */
    val minimized: Boolean = false,
    /**
     * \![set,zorder] の組。1組は前に出すものから順に、"s0"（\0 のサーフェス）・"b0"（\0 のバルーン）のように並べたもの。
     * 組の中だけ順を決め、組に入っていないウィンドウはいつもの順（\1 → 3人目以降 → \0）のまま
     */
    val zorder: List<List<String>> = emptyList(),
    /** \![set,sticky-window]。一緒に動くキャラクターの組 */
    val stickyGroups: List<Set<Int>> = emptyList(),
    /** \v で手前に出すよう頼まれた回数（増えたら出し直す） */
    val frontRequests: Int = 0,
    /** \![execute,resetwindowpos] とメニューの「位置を戻す」で、位置を初期化するよう頼まれた回数 */
    val resetRequests: Int = 0,
    /** SHIORI リソース（と descript.txt）の sakura.defaultx などで決めた、キャラクターごとの最初の位置 */
    val defaultPositions: Map<Int, DefaultPosition> = emptyMap(),
)

/**
 * 画面の座標の1つ。[fromEnd] なら右端（下端）からの距離（SSP の負の値。-0 は端そのもの）
 */
data class ScreenCoordinate(val value: Int, val fromEnd: Boolean) {
    /** 幅（高さ）[size] の画面での座標 */
    fun resolve(size: Int): Int = if (fromEnd) size - value else value

    companion object {
        /** SSP の書き方を読む。"-100" は端から 100、"-0" は端、"--100" は本当に -100 */
        fun parse(text: String?): ScreenCoordinate? {
            val s = text?.trim().orEmpty()
            if (s.isEmpty()) return null
            return when {
                s.startsWith("--") -> s.substring(2).toIntOrNull()?.let { ScreenCoordinate(-it, fromEnd = false) }
                s.startsWith("-") -> s.substring(1).toIntOrNull()?.let { ScreenCoordinate(it, fromEnd = true) }
                else -> s.toIntOrNull()?.let { ScreenCoordinate(it, fromEnd = false) }
            }
        }
    }
}

/**
 * キャラクターの最初の位置（SSP の sakura.defaultx / defaulty / defaultleft / defaulttop）。位置を覚えていないときに使う。
 * [x] / [y] はサーフェスの基準点（中央下）、[left] / [top] はウィンドウの左上。同じ向きなら x・y が優先
 */
data class DefaultPosition(
    val x: ScreenCoordinate? = null,
    val y: ScreenCoordinate? = null,
    val left: ScreenCoordinate? = null,
    val top: ScreenCoordinate? = null,
) {
    val isEmpty: Boolean get() = x == null && y == null && left == null && top == null

    /**
     * 大きさ [width] × [height] のウィンドウの左上を、幅 [screenWidth]・高さ [screenHeight] の画面に置く位置。
     * [baseX] / [baseY] はウィンドウの中の基準点（point.basepos。既定は中央・下）。決まっていない向きは null（いつもの置き方のまま）
     */
    fun windowLeft(width: Int, screenWidth: Int, baseX: Int = width / 2): Int? =
        x?.let { it.resolve(screenWidth) - baseX }
            ?: left?.let { if (it.fromEnd) screenWidth - it.value - width else it.value }

    fun windowTop(height: Int, screenHeight: Int, baseY: Int = height): Int? =
        y?.let { it.resolve(screenHeight) - baseY }
            ?: top?.let { if (it.fromEnd) screenHeight - it.value - height else it.value }
}
