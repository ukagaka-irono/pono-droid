package net.pono.baseware.shell

/** surfaces.txt の定義。座標はすべて元画像のピクセル単位。 */

data class SurfaceElement(val id: Int, val method: String, val file: String, val x: Int, val y: Int)

data class Collision(val left: Int, val top: Int, val right: Int, val bottom: Int, val id: String) {
    fun contains(x: Int, y: Int) = x in left..right && y in top..bottom
}

enum class IntervalKind { ALWAYS, SOMETIMES, RARELY, RANDOM, PERIODIC, RUNONCE, NEVER, BIND, TALK, YEN_E, UNKNOWN }

data class AnimationInterval(val kind: IntervalKind, val arg: Int = 0) {
    companion object {
        fun parse(value: String): AnimationInterval {
            val parts = value.split(',').map { it.trim() }
            // "sometimes+runonce" のような組み合わせは最初のものだけ見る
            val name = parts[0].substringBefore('+').lowercase()
            val arg = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val kind = when (name) {
                "always" -> IntervalKind.ALWAYS
                "sometimes" -> IntervalKind.SOMETIMES
                "rarely" -> IntervalKind.RARELY
                "random" -> IntervalKind.RANDOM
                "periodic" -> IntervalKind.PERIODIC
                "runonce" -> IntervalKind.RUNONCE
                "never" -> IntervalKind.NEVER
                "bind" -> IntervalKind.BIND
                "talk" -> IntervalKind.TALK
                "yen-e" -> IntervalKind.YEN_E
                else -> IntervalKind.UNKNOWN
            }
            return AnimationInterval(kind, arg)
        }
    }
}

/**
 * パターンの1コマ。[surface] が -1 ならそのレイヤーを消す。
 * start / stop / alternativestart / alternativestop のコマは、操作するアニメーションの番号を [targets] に持つ。
 */
data class PatternFrame(
    val method: String,
    val surface: Int,
    val waitMinMs: Int,
    val waitMaxMs: Int,
    val x: Int,
    val y: Int,
    val targets: List<Int> = emptyList(),
) {
    val isControl: Boolean get() = method in CONTROL_METHODS

    companion object {
        val CONTROL_METHODS = setOf("start", "stop", "alternativestart", "alternativestop", "insert")
    }
}

class Animation(val id: Int) {
    var interval: AnimationInterval = AnimationInterval(IntervalKind.NEVER)
    val patterns = sortedMapOf<Int, PatternFrame>()

    val frames: List<PatternFrame> get() = patterns.values.toList()

    /** コマ数の上限を超えたら捨てる */
    fun putPattern(index: Int, frame: PatternFrame) {
        if (index in patterns || patterns.size < SurfaceDefinition.MAX_PATTERNS) patterns[index] = frame
    }
}

class SurfaceDefinition(val id: Int) {
    val elements = sortedMapOf<Int, SurfaceElement>()
    val animations = sortedMapOf<Int, Animation>()
    val collisions = sortedMapOf<Int, Collision>()

    /** 上限を超えたら新しい番号は受け付けない（null）。細工した surfaces.txt でアニメーションを無数に作らせないため */
    fun animation(id: Int): Animation? =
        animations[id] ?: if (animations.size < MAX_PARTS) Animation(id).also { animations[id] = it } else null

    companion object {
        /** 1つのサーフェスが持てる element / collision / animation の数 */
        const val MAX_PARTS = 1024

        /** 1つのアニメーションのコマ数 */
        const val MAX_PATTERNS = 4096
    }

    fun hitTest(x: Int, y: Int): String? = collisions.values.firstOrNull { it.contains(x, y) }?.id
}

class SurfaceTable(val definitions: Map<Int, SurfaceDefinition>) {
    operator fun get(id: Int): SurfaceDefinition? = definitions[id]

    companion object {
        val EMPTY = SurfaceTable(emptyMap())

        private val surfaceRange = Regex("""^(!?)surface(?:\.append)?(\d+)(?:-(\d+))?$""")
        /** surface の後ろに続く番号だけの項目。グループの並びは [surfaceRange] と同じ */
        private val bareRange = Regex("""^(!?)(\d+)(?:-(\d+))?$""")
        private val newAnimation = Regex("""^animation(\d+)\.(interval|pattern(\d+)|option|collision\d+|collisionex\d+)$""")
        private val oldInterval = Regex("""^(\d+)interval$""")
        private val oldPattern = Regex("""^(\d+)pattern(\d+)$""")
        private val element = Regex("""^element(\d+)$""")
        private val collision = Regex("""^collision(\d+)$""")
        private val collisionEx = Regex("""^collisionex(\d+)$""")

        fun parse(text: String): SurfaceTable {
            val defs = sortedMapOf<Int, SurfaceDefinition>()
            var header = StringBuilder()
            var targets: List<Int>? = null

            for (raw in text.lineSequence()) {
                var line = raw.substringBefore("//").trim()
                if (line.isEmpty()) continue
                if (targets == null) {
                    // ブロックの前のヘッダ行。"surface0,surface2-5" と "{" が同じ行のこともある
                    val brace = line.indexOf('{')
                    if (brace < 0) {
                        if (header.isNotEmpty()) header.append(',')
                        header.append(line)
                        continue
                    }
                    if (brace > 0) {
                        if (header.isNotEmpty()) header.append(',')
                        header.append(line.substring(0, brace))
                    }
                    targets = surfaceIds(header.toString())
                    header = StringBuilder()
                    line = line.substring(brace + 1).trim()
                    if (line.isEmpty()) continue
                }
                if (line.startsWith("}")) {
                    targets = null
                    continue
                }
                // "surface0 { element0,... }" のように同じ行で閉じることもある
                val closes = line.endsWith("}")
                if (closes) line = line.removeSuffix("}").trim()
                if (line.isNotEmpty()) {
                    for (id in targets) {
                        applyLine(defs.getOrPut(id) { SurfaceDefinition(id) }, line)
                    }
                }
                if (closes) targets = null
            }
            return SurfaceTable(defs)
        }

        /**
         * "surface0,surface2-5,!surface3" を ID の一覧にする。surface 以外のブロックなら空。
         * "surface.append0,6,7" のように、surface の後ろの番号だけの項目（範囲も）は同じ surface の続きとして読む（SSP と同じ）
         */
        private fun surfaceIds(header: String): List<Int> {
            val include = linkedSetOf<Int>()
            val exclude = mutableSetOf<Int>()
            var afterSurface = false
            for (token in header.split(',').map { it.trim() }) {
                val m = surfaceRange.matchEntire(token)
                    ?: bareRange.matchEntire(token)?.takeIf { afterSurface }
                    ?: run {
                        afterSurface = false
                        null
                    }
                    ?: continue
                afterSurface = true
                // 桁あふれの番号は飛ばす。"surface0-999999999" のような範囲でメモリを食いつぶさないよう、幅と総数に上限を置く
                val from = m.groupValues[2].toIntOrNull() ?: continue
                val to = m.groupValues[3].toIntOrNull() ?: from
                val low = minOf(from, to)
                val high = maxOf(from, to)
                if (high.toLong() - low > MAX_RANGE_SPAN) continue
                val set = if (m.groupValues[1] == "!") exclude else include
                for (i in low..high) {
                    if (set.size >= MAX_IDS_PER_HEADER) break
                    set += i
                }
            }
            return (include - exclude).toList()
        }

        private const val MAX_RANGE_SPAN = 10_000
        private const val MAX_IDS_PER_HEADER = 20_000

        private fun applyLine(def: SurfaceDefinition, line: String) {
            val key = line.substringBefore(',').trim().lowercase()
            val args = line.substringAfter(',', "").split(',').map { it.trim() }

            // 番号が桁あふれしていれば（細工された surfaces.txt）その行は無視する。数にも上限を置く
            element.matchEntire(key)?.let { m ->
                val id = m.groupValues[1].toIntOrNull() ?: return
                if (args.size >= 4 && (id in def.elements || def.elements.size < SurfaceDefinition.MAX_PARTS)) {
                    def.elements[id] = SurfaceElement(id, args[0].lowercase(), args[1], args[2].toIntOrNull() ?: 0, args[3].toIntOrNull() ?: 0)
                }
                return
            }
            collision.matchEntire(key)?.let { m ->
                val id = m.groupValues[1].toIntOrNull() ?: return
                val n = args.take(4).map { it.toIntOrNull() }
                if (args.size >= 5 && n.all { it != null } && (id in def.collisions || def.collisions.size < SurfaceDefinition.MAX_PARTS)) {
                    def.collisions[id] = Collision(n[0]!!, n[1]!!, n[2]!!, n[3]!!, args[4])
                }
                return
            }
            collisionEx.matchEntire(key)?.let { m ->
                // collisionexN,ID,形状,座標... 当たり判定は外接矩形で近似する
                val id = m.groupValues[1].toIntOrNull() ?: return
                val coords = args.drop(2).mapNotNull { it.toIntOrNull() }
                if (args.size >= 3 && coords.size >= 4 && (id in def.collisions || def.collisions.size < SurfaceDefinition.MAX_PARTS)) {
                    val xs = coords.filterIndexed { i, _ -> i % 2 == 0 }
                    val ys = coords.filterIndexed { i, _ -> i % 2 == 1 }
                    def.collisions[id] = Collision(xs.min(), ys.min(), xs.max(), ys.max(), args[0])
                }
                return
            }
            newAnimation.matchEntire(key)?.let { m ->
                val anim = m.groupValues[1].toIntOrNull()?.let(def::animation) ?: return
                when {
                    m.groupValues[2] == "interval" -> anim.interval = AnimationInterval.parse(args.joinToString(","))
                    m.groupValues[3].isNotEmpty() -> m.groupValues[3].toIntOrNull()?.let { index ->
                        parseNewPattern(args)?.let { anim.putPattern(index, it) }
                    }
                }
                return
            }
            oldInterval.matchEntire(key)?.let { m ->
                val anim = m.groupValues[1].toIntOrNull()?.let(def::animation) ?: return
                anim.interval = AnimationInterval.parse(args.joinToString(","))
                return
            }
            oldPattern.matchEntire(key)?.let { m ->
                val anim = m.groupValues[1].toIntOrNull()?.let(def::animation) ?: return
                val index = m.groupValues[2].toIntOrNull() ?: return
                parseOldPattern(args)?.let { anim.putPattern(index, it) }
                return
            }
        }

        /** 新形式: method,surfaceID,wait,x,y（wait はミリ秒。"50-100" の範囲指定あり） */
        private fun parseNewPattern(args: List<String>): PatternFrame? {
            if (args.isEmpty()) return null
            val method = args[0].lowercase()
            if (method in PatternFrame.CONTROL_METHODS) {
                // start,3 / alternativestart,[3.5.7] / alternativestart,(3,5) など。数字をすべて拾う
                val targets = Regex("""\d+""").findAll(args.drop(1).joinToString(",")).mapNotNull { it.value.toIntOrNull() }.toList()
                return PatternFrame(method, -1, 0, 0, 0, 0, targets)
            }
            val surface = args.getOrNull(1)?.toIntOrNull() ?: -1
            val (min, max) = parseWait(args.getOrNull(2), 1)
            return PatternFrame(method, surface, min, max, args.getOrNull(3)?.toIntOrNull() ?: 0, args.getOrNull(4)?.toIntOrNull() ?: 0)
        }

        /** 旧形式: surfaceID,wait,method,x,y（wait は 10 ミリ秒単位） */
        private fun parseOldPattern(args: List<String>): PatternFrame? {
            if (args.size < 3) return null
            val surface = args[0].toIntOrNull() ?: return null
            val (min, max) = parseWait(args[1], 10)
            return PatternFrame(args[2].lowercase(), surface, min, max, args.getOrNull(3)?.toIntOrNull() ?: 0, args.getOrNull(4)?.toIntOrNull() ?: 0)
        }

        private fun parseWait(s: String?, unit: Int): Pair<Int, Int> {
            if (s == null) return 0 to 0
            val parts = s.split('-')
            val min = (parts[0].trim().toIntOrNull() ?: 0) * unit
            val max = (parts.getOrNull(1)?.trim()?.toIntOrNull()?.times(unit)) ?: min
            return min to maxOf(min, max)
        }
    }
}
