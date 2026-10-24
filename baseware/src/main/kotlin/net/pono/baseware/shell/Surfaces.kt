package net.pono.baseware.shell

import kotlin.random.Random

/** surfaces.txt の定義。座標はすべて元画像のピクセル単位。 */

data class SurfaceElement(val id: Int, val method: String, val file: String, val x: Int, val y: Int)

/** 当たり判定の形（collisionex の rect / ellipse / circle / polygon。collision は rect） */
enum class CollisionShape { RECT, ELLIPSE, CIRCLE, POLYGON }

/**
 * 当たり判定。[left] から [bottom] は形を囲む矩形。[points] は polygon の頂点（x, y, x, y ...）
 */
data class Collision(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val id: String,
    val shape: CollisionShape = CollisionShape.RECT,
    val points: List<Int> = emptyList(),
) {
    fun contains(x: Int, y: Int): Boolean {
        if (x !in left..right || y !in top..bottom) return false
        return when (shape) {
            CollisionShape.RECT -> true
            CollisionShape.ELLIPSE, CollisionShape.CIRCLE -> {
                val rx = (right - left) / 2.0
                val ry = (bottom - top) / 2.0
                if (rx <= 0 || ry <= 0) return false
                val dx = (x - (left + rx)) / rx
                val dy = (y - (top + ry)) / ry
                dx * dx + dy * dy <= 1.0
            }
            // 頂点を順につないだ多角形。辺が交差していてもよい（偶奇で内側を決める）
            CollisionShape.POLYGON -> {
                var inside = false
                val n = points.size / 2
                var j = n - 1
                for (i in 0 until n) {
                    val xi = points[i * 2].toDouble()
                    val yi = points[i * 2 + 1].toDouble()
                    val xj = points[j * 2].toDouble()
                    val yj = points[j * 2 + 1].toDouble()
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
                inside
            }
        }
    }
}

enum class IntervalKind { ALWAYS, SOMETIMES, RARELY, RANDOM, PERIODIC, RUNONCE, NEVER, BIND, TALK, YEN_E, STARTTALK, ENDTALK, UNKNOWN }

/**
 * animation*.interval。SSP では bind+runonce+random,5 のように + でつないで組み合わせられる（引数は最後に1つだけ）
 */
data class AnimationInterval(val kinds: Set<IntervalKind>, val arg: Int = 0) {
    constructor(kind: IntervalKind, arg: Int = 0) : this(setOf(kind), arg)

    /** 着せ替え以外で、いちばん初めに書いたもの（着せ替えだけなら BIND） */
    val kind: IntervalKind get() = kinds.firstOrNull { it != IntervalKind.BIND } ?: kinds.firstOrNull() ?: IntervalKind.NEVER

    /** 動かさずに着せ替えとして重ねるだけのもの（bind だけ。bind+always などは着せ替えている間だけ動く） */
    val isStaticBind: Boolean get() = kinds == setOf(IntervalKind.BIND)

    operator fun contains(kind: IntervalKind) = kind in kinds

    companion object {
        fun parse(value: String): AnimationInterval {
            val parts = value.split(',').map { it.trim() }
            val arg = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val kinds = parts[0].split('+').map { name ->
                when (name.trim().lowercase()) {
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
                    "starttalk" -> IntervalKind.STARTTALK
                    "endtalk" -> IntervalKind.ENDTALK
                    else -> IntervalKind.UNKNOWN
                }
            }.toCollection(LinkedHashSet())
            return AnimationInterval(kinds, arg)
        }
    }
}

/**
 * パターンの1コマ。[surface] が -1 ならそのレイヤーを消す。
 * start / stop / alternativestart / alternativestop のコマは、操作するアニメーションの番号を [targets] に持つ。
 * [scaleX] / [scaleY] は scaling のコマの倍率（パーセント。小数も書ける）、[file] は import のコマの APNG / GIF / WebP
 */
data class PatternFrame(
    val method: String,
    val surface: Int,
    val waitMinMs: Int,
    val waitMaxMs: Int,
    val x: Int,
    val y: Int,
    val targets: List<Int> = emptyList(),
    val scaleX: Float = 100f,
    val scaleY: Float = 100f,
    val file: String? = null,
) {
    val isControl: Boolean get() = method in CONTROL_METHODS

    companion object {
        val CONTROL_METHODS = setOf("start", "stop", "alternativestart", "alternativestop", "parallelstart", "parallelstop", "insert")
    }
}

class Animation(val id: Int) {
    var interval: AnimationInterval = AnimationInterval(IntervalKind.NEVER)
    val patterns = sortedMapOf<Int, PatternFrame>()

    /** animation*.name（SSP 2.8.24）。\i[名前] などで番号の代わりに使える */
    var name: String? = null

    /** option,exclusive。動いている間、ほかのアニメーション（[exclusiveOf] があればそれだけ）を止める */
    var exclusive: Boolean = false
    var exclusiveOf: Set<Int> = emptySet()

    /** option,background。土台のサーフェスの裏に重ねる */
    var background: Boolean = false

    /** option,shared-index。サーフェスを替えても、同じ番号のアニメーションを続きのコマから動かす */
    var sharedIndex: Boolean = false

    /** animation*.collision* / collisionex*。このアニメーションのコマが出ている間だけの当たり判定（書いた順） */
    val collisions = LinkedHashMap<Int, Collision>()

    val frames: List<PatternFrame> get() = patterns.values.toList()

    /** コマ数の上限を超えたら捨てる */
    fun putPattern(index: Int, frame: PatternFrame) {
        if (index in patterns || patterns.size < SurfaceDefinition.MAX_PATTERNS) patterns[index] = frame
    }
}

/** surfaces.txt の descript ブレス（collision-sort / animation-sort）の並べ方 */
enum class SortOrder { NONE, ASCEND, DESCEND }

class SurfaceDefinition(val id: Int) {
    /** `name,定義名`（SSP 2.8.24）。\s[定義名] で、番号の代わりにこの名前で呼べる */
    var name: String? = null

    val elements = sortedMapOf<Int, SurfaceElement>()
    val animations = sortedMapOf<Int, Animation>()

    /** 当たり判定。先に書いたものほど手前（collision-sort で ID の順にもできる） */
    val collisions = LinkedHashMap<Int, Collision>()

    /** point.basepos.x / y（位置を決めるときの基準点）。null なら中央・下 */
    var baseposX: Int? = null
    var baseposY: Int? = null

    /** point.centerx / centery と point.kinoko.centerx / centery。null ならサーフェスの中心 */
    var centerX: Int? = null
    var centerY: Int? = null
    var kinokoX: Int? = null
    var kinokoY: Int? = null

    /** descript ブレスの collision-sort / animation-sort */
    var collisionSort: SortOrder = SortOrder.NONE
    var animationSort: SortOrder = SortOrder.DESCEND

    /** 上限を超えたら新しい番号は受け付けない（null）。細工した surfaces.txt でアニメーションを無数に作らせないため */
    fun animation(id: Int): Animation? =
        animations[id] ?: if (animations.size < MAX_PARTS) Animation(id).also { animations[id] = it } else null

    /** \i[名前] / \![anim,...,名前]。番号か animation*.name の名前を、アニメーション番号にする */
    fun animationId(key: String): Int? {
        val k = key.trim()
        return k.toIntOrNull() ?: animations.values.firstOrNull { it.name == k }?.id
    }

    /**
     * 話した文字の数が [from] から [to] に増えたときに動かす、interval が talk,N のアニメーション（SSP と同じく N 文字ごと）。
     * 一度にたくさん出たとき（\_q など）でも、1つのアニメーションは1回だけ動かす
     */
    fun talkAnimations(from: Int, to: Int): List<Animation> {
        if (to <= from) return emptyList()
        return animations.values.filter { anim ->
            if (IntervalKind.TALK !in anim.interval) return@filter false
            val every = anim.interval.arg.coerceAtLeast(1)
            to / every > from / every
        }
    }

    /** スクリプトを話し終えた（\e まで来た）ときに動かす、interval が yen-e のアニメーション */
    val yenEAnimations: List<Animation> get() = animations.values.filter { IntervalKind.YEN_E in it.interval }

    /** そのキャラクターが話し始めた・話し終えた（スコープを外れた）ときに動かす、starttalk / endtalk のアニメーション */
    val startTalkAnimations: List<Animation> get() = animations.values.filter { IntervalKind.STARTTALK in it.interval }
    val endTalkAnimations: List<Animation> get() = animations.values.filter { IntervalKind.ENDTALK in it.interval }

    /**
     * 身に着けている着せ替え（interval が bind だけのもの）のコマを、重ねる順（下から）に並べる。
     * `insert,ID` の位置には着せ替え ID のコマを差し込む（入れ子も可）。差し込まれる着せ替えは、自分の番号の位置には出さない。
     * ID を身に着けていなければ何も出さない（SSP と同じ）。bind+always などは動くアニメーションなので、ここには入れない
     */
    fun bindFrames(binds: Set<Int>): List<PatternFrame> {
        val bindAnims = animations.values.filter { it.interval.isStaticBind }
        val inserted = bindAnims.flatMap { a -> a.frames.filter { it.method == "insert" }.flatMap { it.targets } }.toSet()
        val out = mutableListOf<PatternFrame>()
        fun expand(anim: Animation, visiting: Set<Int>) {
            for ((i, f) in anim.frames.withIndex()) {
                if (f.method == "insert") {
                    for (id in f.targets) {
                        if (id in visiting || id !in binds) continue
                        val target = animations[id]?.takeIf { it.interval.isStaticBind } ?: continue
                        expand(target, visiting + id)
                    }
                } else if (!f.isControl) {
                    // 着せ替えの base は最初のコマにしか使えない（それ以外は overlay として重ねる）
                    out += if (f.method == "base" && i > 0) f.copy(method = "overlay") else f
                }
            }
        }
        for (anim in bindAnims) {
            if (anim.id in binds && anim.id !in inserted) expand(anim, setOf(anim.id))
        }
        return out
    }

    companion object {
        /** 1つのサーフェスが持てる element / collision / animation の数 */
        const val MAX_PARTS = 1024

        /** 1つのアニメーションのコマ数 */
        const val MAX_PATTERNS = 4096
    }

    /** 当たり判定を、手前から並べたもの（collision-sort に従う） */
    fun orderedCollisions(): List<Collision> = when (collisionSort) {
        SortOrder.NONE -> collisions.values.toList()
        SortOrder.ASCEND -> collisions.toSortedMap().values.toList()
        SortOrder.DESCEND -> collisions.toSortedMap().values.reversed()
    }

    /**
     * 指が触れた当たり判定。[active] はいまコマを出しているアニメーション（着せ替えも）で、その animation*.collision を先に見る。
     * アニメーションは animation-sort（既定は番号の大きいものが手前）の順、その後に土台の当たり判定を見る
     */
    fun hitTest(x: Int, y: Int, active: Collection<Int> = emptyList()): String? {
        val shown = active.mapNotNull { animations[it] }.filter { it.collisions.isNotEmpty() }
        val order = when (animationSort) {
            SortOrder.ASCEND -> shown.sortedBy { it.id }
            else -> shown.sortedByDescending { it.id }
        }
        for (anim in order) anim.collisions.values.firstOrNull { it.contains(x, y) }?.let { return it.id }
        return orderedCollisions().firstOrNull { it.contains(x, y) }?.id
    }
}

/**
 * @param aliases キャラクター（0 が sakura、1 が kero、2 以降が charN）ごとの surface.alias。別名 → サーフェス番号の候補
 * @param tooltips キャラクターごとの sakura.tooltips などのブレス。当たり判定の名前 → 出す文字
 */
class SurfaceTable(
    val definitions: Map<Int, SurfaceDefinition>,
    val aliases: Map<Int, Map<String, List<Int>>> = emptyMap(),
    val tooltips: Map<Int, Map<String, String>> = emptyMap(),
) {
    operator fun get(id: Int): SurfaceDefinition? = definitions[id]

    /**
     * \s[...] の指定（番号・surface.alias の別名・`name,定義名`）を、そのキャラクターのサーフェス番号にする。
     * 別名に候補が複数あれば1つを選ぶ（SSP と同じくランダム）。番号でも別名があれば別名を使う。わからなければ null
     */
    fun resolve(scope: Int, key: String, random: Random = Random.Default): Int? {
        val k = key.trim()
        aliases[scope]?.get(k)?.takeIf { it.isNotEmpty() }?.let { return it.random(random) }
        k.toIntOrNull()?.let { return it }
        return definitions.values.firstOrNull { it.name == k }?.id
    }

    companion object {
        val EMPTY = SurfaceTable(emptyMap())

        private val surfaceRange = Regex("""^(!?)surface(\.append)?(\d+)(?:-(\d+))?$""")
        /** surface の後ろに続く番号だけの項目。グループの並びは [surfaceRange] と同じ */
        private val bareRange = Regex("""^(!?)()(\d+)(?:-(\d+))?$""")
        private val newAnimation = Regex("""^animation(\d+)\.(interval|pattern(\d+)|option|name|collision(\d+)|collisionex(\d+))$""")
        private val oldInterval = Regex("""^(\d+)interval$""")
        private val oldPattern = Regex("""^(\d+)pattern(\d+)$""")
        private val oldOption = Regex("""^(\d+)option$""")
        private val element = Regex("""^element(\d+)$""")
        private val collision = Regex("""^collision(\d+)$""")
        private val collisionEx = Regex("""^collisionex(\d+)$""")

        /** ブレスの種類（surface / surface.alias / tooltips / descript） */
        private sealed interface Block {
            data class Surfaces(val ids: List<Int>) : Block
            data class Alias(val scope: Int) : Block
            data class Tooltips(val scope: Int) : Block
            data object Descript : Block
            data object Other : Block
        }

        /**
         * @param existing surfaceN.png があるサーフェスの番号。surface.append はこれと、先に surface ブレスで書いたものにだけ足す
         */
        fun parse(text: String, existing: Set<Int> = emptySet()): SurfaceTable {
            val defs = sortedMapOf<Int, SurfaceDefinition>()
            val aliases = HashMap<Int, MutableMap<String, List<Int>>>()
            val tooltips = HashMap<Int, MutableMap<String, String>>()
            var collisionSort = SortOrder.NONE
            var animationSort = SortOrder.DESCEND
            var header = StringBuilder()
            var block: Block? = null

            fun apply(b: Block, line: String) {
                when (b) {
                    is Block.Surfaces -> for (id in b.ids) applyLine(defs.getOrPut(id) { SurfaceDefinition(id) }, line)
                    is Block.Alias -> addAlias(aliases.getOrPut(b.scope) { LinkedHashMap() }, line)
                    is Block.Tooltips -> {
                        val into = tooltips.getOrPut(b.scope) { LinkedHashMap() }
                        val key = line.substringBefore(',').trim()
                        if (key.isNotEmpty() && (key in into || into.size < SurfaceDefinition.MAX_PARTS)) {
                            into[key] = line.substringAfter(',', "").trim().take(MAX_TOOLTIP)
                        }
                    }
                    Block.Descript -> {
                        val value = line.substringAfter(',', "").trim().lowercase()
                        val order = when (value) {
                            "ascend" -> SortOrder.ASCEND
                            "descend" -> SortOrder.DESCEND
                            else -> SortOrder.NONE
                        }
                        when (line.substringBefore(',').trim().lowercase()) {
                            "collision-sort" -> collisionSort = order
                            "animation-sort" -> animationSort = if (order == SortOrder.ASCEND) SortOrder.ASCEND else SortOrder.DESCEND
                        }
                    }
                    Block.Other -> {}
                }
            }

            for (raw in text.lineSequence()) {
                var line = raw.substringBefore("//").trim()
                if (line.isEmpty()) continue
                if (block == null) {
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
                    block = block(header.toString(), defs, existing)
                    header = StringBuilder()
                    line = line.substring(brace + 1).trim()
                    if (line.isEmpty()) continue
                }
                if (line.startsWith("}")) {
                    block = null
                    continue
                }
                // "surface0 { element0,... }" のように同じ行で閉じることもある
                val closes = line.endsWith("}")
                if (closes) line = line.removeSuffix("}").trim()
                if (line.isNotEmpty()) apply(block, line)
                if (closes) block = null
            }
            for (def in defs.values) {
                def.collisionSort = collisionSort
                def.animationSort = animationSort
            }
            return SurfaceTable(defs, aliases, tooltips)
        }

        private val scopedHeader = Regex("""^(sakura|kero|char(\d+))\.(surface\.alias|tooltips)$""", RegexOption.IGNORE_CASE)

        /**
         * ヘッダの種類。"sakura.surface.alias" / "kero.tooltips" ならそのキャラクター、"descript" なら descript ブレス。
         * ブロックの前の行（"charset,UTF-8" など）もヘッダにつながってくるので、区切った項目の最後の方から見る
         */
        private fun block(header: String, defs: Map<Int, SurfaceDefinition>, existing: Set<Int>): Block {
            val tokens = header.split(',').map { it.trim() }
            if (tokens.lastOrNull()?.lowercase() == "descript") return Block.Descript
            tokens.asReversed().firstNotNullOfOrNull { scopedHeader.matchEntire(it) }?.let { m ->
                val scope = when (m.groupValues[1].lowercase()) {
                    "sakura" -> 0
                    "kero" -> 1
                    else -> m.groupValues[2].toIntOrNull() ?: return Block.Other
                }
                return if (m.groupValues[3].lowercase() == "tooltips") Block.Tooltips(scope) else Block.Alias(scope)
            }
            val ids = surfaceIds(tokens, defs, existing)
            return if (ids.isEmpty()) Block.Other else Block.Surfaces(ids)
        }

        /** "照れ,[1,101,201]" を読む（SSP と同じく区切りは . でもよい）。別名の数と候補の数には上限を置く */
        private fun addAlias(into: MutableMap<String, List<Int>>, line: String) {
            val key = line.substringBefore(',').trim()
            if (key.isEmpty() || (key !in into && into.size >= SurfaceDefinition.MAX_PARTS)) return
            val ids = Regex("""-?\d+""").findAll(line.substringAfter(',', ""))
                .mapNotNull { it.value.toIntOrNull() }.take(SurfaceDefinition.MAX_PARTS).toList()
            if (ids.isNotEmpty()) into[key] = ids
        }

        /**
         * "surface0,surface2-5,!surface3" を ID の一覧にする。surface 以外のブロックなら空。
         * "surface.append0,6,7" のように、surface の後ろの番号だけの項目（範囲も）は同じ surface の続きとして読む（SSP と同じ）。
         * surface.append の番号は、すでにある（画像があるか、先に surface ブレスで書いた）サーフェスだけにする
         */
        private fun surfaceIds(tokens: List<String>, defs: Map<Int, SurfaceDefinition>, existing: Set<Int>): List<Int> {
            val include = linkedSetOf<Int>()
            val exclude = mutableSetOf<Int>()
            var afterSurface = false
            var append = false
            for (token in tokens) {
                val full = surfaceRange.matchEntire(token)
                val m = full
                    ?: bareRange.matchEntire(token)?.takeIf { afterSurface }
                    ?: run {
                        afterSurface = false
                        null
                    }
                    ?: continue
                // 番号だけの項目は、前の surface / surface.append の続き
                if (full != null) append = full.groupValues[2].isNotEmpty()
                afterSurface = true
                // 桁あふれの番号は飛ばす。"surface0-999999999" のような範囲でメモリを食いつぶさないよう、幅と総数に上限を置く
                val from = m.groupValues[3].toIntOrNull() ?: continue
                val to = m.groupValues[4].toIntOrNull() ?: from
                val low = minOf(from, to)
                val high = maxOf(from, to)
                if (high.toLong() - low > MAX_RANGE_SPAN) continue
                val set = if (m.groupValues[1] == "!") exclude else include
                for (i in low..high) {
                    if (set.size >= MAX_IDS_PER_HEADER) break
                    if (append && set === include && i !in defs && i !in existing) continue
                    set += i
                }
            }
            return (include - exclude).toList()
        }

        private const val MAX_RANGE_SPAN = 10_000
        private const val MAX_IDS_PER_HEADER = 20_000

        /** ツールチップの文字の長さの上限 */
        private const val MAX_TOOLTIP = 500

        private fun applyLine(def: SurfaceDefinition, line: String) {
            val key = line.substringBefore(',').trim().lowercase()
            val args = line.substringAfter(',', "").split(',').map { it.trim() }

            when (key) {
                "name" -> {
                    def.name = line.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }
                    return
                }
                "point.basepos.x" -> def.baseposX = args[0].toIntOrNull()
                "point.basepos.y" -> def.baseposY = args[0].toIntOrNull()
                "point.centerx" -> def.centerX = args[0].toIntOrNull()
                "point.centery" -> def.centerY = args[0].toIntOrNull()
                "point.kinoko.centerx" -> def.kinokoX = args[0].toIntOrNull()
                "point.kinoko.centery" -> def.kinokoY = args[0].toIntOrNull()
            }

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
                parseCollision(args)?.let { if (id in def.collisions || def.collisions.size < SurfaceDefinition.MAX_PARTS) def.collisions[id] = it }
                return
            }
            collisionEx.matchEntire(key)?.let { m ->
                val id = m.groupValues[1].toIntOrNull() ?: return
                parseCollisionEx(args)?.let { if (id in def.collisions || def.collisions.size < SurfaceDefinition.MAX_PARTS) def.collisions[id] = it }
                return
            }
            newAnimation.matchEntire(key)?.let { m ->
                val anim = m.groupValues[1].toIntOrNull()?.let(def::animation) ?: return
                when {
                    m.groupValues[2] == "interval" -> anim.interval = AnimationInterval.parse(args.joinToString(","))
                    m.groupValues[2] == "option" -> applyOption(anim, args)
                    m.groupValues[2] == "name" -> anim.name = line.substringAfter(',', "").trim().takeIf { it.isNotEmpty() }
                    m.groupValues[3].isNotEmpty() -> m.groupValues[3].toIntOrNull()?.let { index ->
                        parseNewPattern(args)?.let { anim.putPattern(index, it) }
                    }
                    m.groupValues[4].isNotEmpty() -> m.groupValues[4].toIntOrNull()?.let { id ->
                        parseCollision(args)?.let { if (id in anim.collisions || anim.collisions.size < SurfaceDefinition.MAX_PARTS) anim.collisions[id] = it }
                    }
                    m.groupValues[5].isNotEmpty() -> m.groupValues[5].toIntOrNull()?.let { id ->
                        parseCollisionEx(args)?.let { if (id in anim.collisions || anim.collisions.size < SurfaceDefinition.MAX_PARTS) anim.collisions[id] = it }
                    }
                }
                return
            }
            oldInterval.matchEntire(key)?.let { m ->
                val anim = m.groupValues[1].toIntOrNull()?.let(def::animation) ?: return
                anim.interval = AnimationInterval.parse(args.joinToString(","))
                return
            }
            oldOption.matchEntire(key)?.let { m ->
                val anim = m.groupValues[1].toIntOrNull()?.let(def::animation) ?: return
                applyOption(anim, args)
                return
            }
            oldPattern.matchEntire(key)?.let { m ->
                val anim = m.groupValues[1].toIntOrNull()?.let(def::animation) ?: return
                val index = m.groupValues[2].toIntOrNull() ?: return
                parseOldPattern(args)?.let { anim.putPattern(index, it) }
                return
            }
        }

        /** option,exclusive / exclusive,(1,3,5) / background / shared-index（+ でつないでもよい） */
        private fun applyOption(anim: Animation, args: List<String>) {
            for (name in args.firstOrNull().orEmpty().split('+').map { it.trim().lowercase() }) {
                when (name) {
                    "exclusive" -> {
                        anim.exclusive = true
                        anim.exclusiveOf = Regex("""\d+""").findAll(args.drop(1).joinToString(",")).mapNotNull { it.value.toIntOrNull() }.toSet()
                    }
                    "background" -> anim.background = true
                    "shared-index" -> anim.sharedIndex = true
                }
            }
        }

        /** collision*,左,上,右,下,ID */
        private fun parseCollision(args: List<String>): Collision? {
            val n = args.take(4).map { it.toIntOrNull() }
            if (args.size < 5 || n.any { it == null }) return null
            return Collision(minOf(n[0]!!, n[2]!!), minOf(n[1]!!, n[3]!!), maxOf(n[0]!!, n[2]!!), maxOf(n[1]!!, n[3]!!), args[4])
        }

        /** collisionex*,ID,rect|ellipse|circle|polygon,座標... */
        private fun parseCollisionEx(args: List<String>): Collision? {
            if (args.size < 3) return null
            val id = args[0]
            val coords = args.drop(2).mapNotNull { it.toIntOrNull() }.take(MAX_POLYGON_COORDS)
            return when (args[1].lowercase()) {
                "circle" -> {
                    if (coords.size < 3) return null
                    val (cx, cy, r) = coords
                    Collision(cx - r, cy - r, cx + r, cy + r, id, CollisionShape.CIRCLE)
                }
                "ellipse", "rect" -> {
                    if (coords.size < 4) return null
                    val shape = if (args[1].lowercase() == "ellipse") CollisionShape.ELLIPSE else CollisionShape.RECT
                    Collision(minOf(coords[0], coords[2]), minOf(coords[1], coords[3]), maxOf(coords[0], coords[2]), maxOf(coords[1], coords[3]), id, shape)
                }
                "polygon" -> {
                    if (coords.size < 6) return null
                    val points = coords.take(coords.size / 2 * 2)
                    val xs = points.filterIndexed { i, _ -> i % 2 == 0 }
                    val ys = points.filterIndexed { i, _ -> i % 2 == 1 }
                    Collision(xs.min(), ys.min(), xs.max(), ys.max(), id, CollisionShape.POLYGON, points)
                }
                else -> null
            }
        }

        private const val MAX_POLYGON_COORDS = 2048

        /** 新形式: method,surfaceID,wait,x,y（wait はミリ秒。"50-100" の範囲指定あり） */
        private fun parseNewPattern(args: List<String>): PatternFrame? {
            if (args.isEmpty()) return null
            val method = args[0].lowercase()
            if (method in PatternFrame.CONTROL_METHODS) {
                // start,3 / alternativestart,[3.5.7] / alternativestart,(3,5) など。数字をすべて拾う
                val targets = Regex("""\d+""").findAll(args.drop(1).joinToString(",")).mapNotNull { it.value.toIntOrNull() }.toList()
                return PatternFrame(method, -1, 0, 0, 0, 0, targets)
            }
            // import,ファイル名,待ち時間,x,y（APNG などのアニメーション画像を、待ってから流す）
            if (method == "import") {
                val file = args.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
                val (min, max) = parseWait(args.getOrNull(2), 1)
                return PatternFrame(method, -1, min, max, args.getOrNull(3)?.toIntOrNull() ?: 0, args.getOrNull(4)?.toIntOrNull() ?: 0, file = file)
            }
            val surface = args.getOrNull(1)?.toIntOrNull() ?: -1
            val (min, max) = parseWait(args.getOrNull(2), 1)
            // scaling のコマだけ、倍率に小数を書ける
            val fx = args.getOrNull(3)?.toFloatOrNull() ?: 0f
            val fy = args.getOrNull(4)?.toFloatOrNull() ?: fx
            if (method == "scaling") return PatternFrame(method, surface, min, max, fx.toInt(), fy.toInt(), scaleX = fx, scaleY = fy)
            return PatternFrame(method, surface, min, max, fx.toInt(), fy.toInt())
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
