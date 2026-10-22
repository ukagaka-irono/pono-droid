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

/** パターンの1コマ。[surface] が -1 ならそのレイヤーを消す。 */
data class PatternFrame(
    val method: String,
    val surface: Int,
    val waitMinMs: Int,
    val waitMaxMs: Int,
    val x: Int,
    val y: Int,
)

class Animation(val id: Int) {
    var interval: AnimationInterval = AnimationInterval(IntervalKind.NEVER)
    val patterns = sortedMapOf<Int, PatternFrame>()

    val frames: List<PatternFrame> get() = patterns.values.toList()
}

class SurfaceDefinition(val id: Int) {
    val elements = sortedMapOf<Int, SurfaceElement>()
    val animations = sortedMapOf<Int, Animation>()
    val collisions = sortedMapOf<Int, Collision>()

    fun animation(id: Int) = animations.getOrPut(id) { Animation(id) }

    fun hitTest(x: Int, y: Int): String? = collisions.values.firstOrNull { it.contains(x, y) }?.id
}

class SurfaceTable(val definitions: Map<Int, SurfaceDefinition>) {
    operator fun get(id: Int): SurfaceDefinition? = definitions[id]

    companion object {
        val EMPTY = SurfaceTable(emptyMap())

        private val surfaceRange = Regex("""^(!?)surface(?:\.append)?(\d+)(?:-(\d+))?$""")
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
                for (id in targets) {
                    applyLine(defs.getOrPut(id) { SurfaceDefinition(id) }, line)
                }
            }
            return SurfaceTable(defs)
        }

        /** "surface0,surface2-5,!surface3" を ID の一覧にする。surface 以外のブロックなら空。 */
        private fun surfaceIds(header: String): List<Int> {
            val include = linkedSetOf<Int>()
            val exclude = mutableSetOf<Int>()
            for (token in header.split(',').map { it.trim() }) {
                val m = surfaceRange.matchEntire(token) ?: continue
                val from = m.groupValues[2].toInt()
                val to = m.groupValues[3].toIntOrNull() ?: from
                val set = if (m.groupValues[1] == "!") exclude else include
                for (i in minOf(from, to)..maxOf(from, to)) set += i
            }
            return (include - exclude).toList()
        }

        private fun applyLine(def: SurfaceDefinition, line: String) {
            val key = line.substringBefore(',').trim().lowercase()
            val args = line.substringAfter(',', "").split(',').map { it.trim() }

            element.matchEntire(key)?.let { m ->
                if (args.size >= 4) {
                    def.elements[m.groupValues[1].toInt()] = SurfaceElement(
                        m.groupValues[1].toInt(), args[0].lowercase(), args[1], args[2].toIntOrNull() ?: 0, args[3].toIntOrNull() ?: 0,
                    )
                }
                return
            }
            collision.matchEntire(key)?.let { m ->
                val n = args.take(4).map { it.toIntOrNull() }
                if (args.size >= 5 && n.all { it != null }) {
                    def.collisions[m.groupValues[1].toInt()] = Collision(n[0]!!, n[1]!!, n[2]!!, n[3]!!, args[4])
                }
                return
            }
            collisionEx.matchEntire(key)?.let { m ->
                // collisionexN,ID,形状,座標... 当たり判定は外接矩形で近似する
                val coords = args.drop(2).mapNotNull { it.toIntOrNull() }
                if (args.size >= 3 && coords.size >= 4) {
                    val xs = coords.filterIndexed { i, _ -> i % 2 == 0 }
                    val ys = coords.filterIndexed { i, _ -> i % 2 == 1 }
                    def.collisions[m.groupValues[1].toInt()] = Collision(xs.min(), ys.min(), xs.max(), ys.max(), args[0])
                }
                return
            }
            newAnimation.matchEntire(key)?.let { m ->
                val anim = def.animation(m.groupValues[1].toInt())
                when {
                    m.groupValues[2] == "interval" -> anim.interval = AnimationInterval.parse(args.joinToString(","))
                    m.groupValues[3].isNotEmpty() -> parseNewPattern(args)?.let { anim.patterns[m.groupValues[3].toInt()] = it }
                }
                return
            }
            oldInterval.matchEntire(key)?.let { m ->
                def.animation(m.groupValues[1].toInt()).interval = AnimationInterval.parse(args.joinToString(","))
                return
            }
            oldPattern.matchEntire(key)?.let { m ->
                parseOldPattern(args)?.let { def.animation(m.groupValues[1].toInt()).patterns[m.groupValues[2].toInt()] = it }
                return
            }
        }

        /** 新形式: method,surfaceID,wait,x,y（wait はミリ秒。"50-100" の範囲指定あり） */
        private fun parseNewPattern(args: List<String>): PatternFrame? {
            if (args.isEmpty()) return null
            val method = args[0].lowercase()
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
