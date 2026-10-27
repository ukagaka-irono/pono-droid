package net.pono.baseware.sakura

/**
 * `\![move,...]` / `\![moveasync,...]`。キャラクターのウィンドウを動かす。座標は画面のピクセル。
 * [x] / [y] が null（省略か fix）ならその向きは動かさない。
 */
data class MoveCommand(
    val x: Int?,
    val y: Int?,
    val timeMs: Long = 0,
    val base: Base = Base.Screen,
    val baseOffset: Anchor = Anchor.LEFT_TOP,
    val moveOffset: Anchor = Anchor.LEFT_TOP,
    /** --option=ignore-sticky-window。sticky-window の組の相手を一緒に動かさない */
    val ignoreSticky: Boolean = false,
) {
    /** 座標の原点 */
    sealed interface Base {
        /** 画面の左上 */
        data object Screen : Base

        /** 動かすキャラクター自身 */
        data object Me : Base

        /** \p[id] のキャラクター */
        data class Scope(val id: Int) : Base

        /** 一緒に動いているほかのゴースト（本体側の名前が [sakuraName]）の \p[id] のキャラクター */
        data class Ghost(val sakuraName: String, val id: Int) : Base
    }

    /** ウィンドウのどこを基準にするか。BASE は surfaces.txt の point.basepos（無ければ中央・下） */
    enum class Edge { START, CENTER, END, BASE }

    data class Anchor(val h: Edge, val v: Edge) {
        companion object {
            val LEFT_TOP = Anchor(Edge.START, Edge.START)

            /** "right.bottom" / "center.base" など。読めない方は left / top */
            fun parse(s: String): Anchor {
                val parts = s.trim().lowercase().split('.')
                fun edge(p: String?, start: String, end: String) = when (p) {
                    end -> Edge.END
                    "center" -> Edge.CENTER
                    "base" -> Edge.BASE
                    else -> Edge.START
                }
                return Anchor(edge(parts.getOrNull(0), "left", "right"), edge(parts.getOrNull(1), "top", "bottom"))
            }
        }
    }

    companion object {
        /** その場にとどまる（\![moveasync,cancel] で、動いている途中のものを止める） */
        val STAY = MoveCommand(null, null, 0, Base.Me)

        /** 動かすのにかける時間の上限。スクリプトがいつまでも止まらないように */
        const val MAX_TIME_MS = 10_000L

        /**
         * 新しい書式（--X=10,--time=500 ...、順不同・大小文字を問わない）と、古い書式（X,Y,時間,基準,基準位置,動かす位置）を読む。
         * 他のゴーストを基準にするなど、扱えない基準なら null
         */
        fun parse(args: List<String>): MoveCommand? {
            val named = args.any { it.trim().startsWith("--") }
            val values = HashMap<String, String>()
            if (named) {
                for (arg in args) {
                    val t = arg.trim()
                    if (!t.startsWith("--")) continue
                    val name = t.substring(2).substringBefore('=').lowercase()
                    values[name] = t.substringAfter('=', "")
                }
            } else {
                listOf("x", "y", "time", "base", "base-offset", "move-offset").zip(args).forEach { (k, v) -> values[k] = v }
            }
            fun coord(name: String) = values[name]?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() != "fix" }?.toIntOrNull()
            val rawBase = values["base"]?.trim().orEmpty()
            val base = when (val b = rawBase.lowercase()) {
                "", "screen", "primaryscreen", "global" -> Base.Screen
                "me" -> Base.Me
                // 本体側の名前/ID。名前に / があってもよいように、最後の / で分ける
                else -> if ('/' in rawBase) {
                    val id = rawBase.substringAfterLast('/').trim().toIntOrNull()?.takeIf { it in 0..ScriptPlayer.MAX_SCOPE } ?: return null
                    Base.Ghost(rawBase.substringBeforeLast('/').trim().takeIf { it.isNotEmpty() } ?: return null, id)
                } else {
                    Base.Scope(b.toIntOrNull()?.takeIf { it in 0..ScriptPlayer.MAX_SCOPE } ?: return null)
                }
            }
            return MoveCommand(
                x = coord("x"),
                y = coord("y"),
                timeMs = (values["time"]?.trim()?.toLongOrNull() ?: 0).coerceIn(0, MAX_TIME_MS),
                base = base,
                baseOffset = values["base-offset"]?.let(Anchor::parse) ?: Anchor.LEFT_TOP,
                moveOffset = values["move-offset"]?.let(Anchor::parse) ?: Anchor.LEFT_TOP,
                ignoreSticky = args.any { it.trim().lowercase() == "--option=ignore-sticky-window" },
            )
        }
    }
}
