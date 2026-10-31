package net.pono.baseware.shell

import kotlin.random.Random

/** surfaces.txt の定義。座標はすべて元画像のピクセル単位。 */

/**
 * element*。[clip] は --clipping（その矩形だけ使う）、[alpha] は --alpha（0〜255）、[scaling] は --scaling、
 * [source] は --source（PSD のレイヤー名など、画像の中のどれを使うか。SourceImages）。[alphaGiven] は --alpha を書いたか
 * （書いていなければ PSD のレイヤーの不透明度を使う）
 */
data class SurfaceElement(
    val id: Int,
    val method: String,
    val file: String,
    val x: Int,
    val y: Int,
    val clip: SurfaceRect? = null,
    val alpha: Int = 255,
    val scaling: ElementScaling? = null,
    val source: String? = null,
    val alphaGiven: Boolean = false,
)

/**
 * element の --scaling。横・縦（数字はピクセル、% を付けると元の大きさに対する割合、0 なら縦横比を保つ）か、
 * サイズと基準辺（long / short に max か min を付けてもよい）
 */
sealed class ElementScaling {
    data class Size(val width: Float, val widthPercent: Boolean, val height: Float, val heightPercent: Boolean) : ElementScaling()
    data class Edge(val size: Int, val long: Boolean, val onlyShrink: Boolean, val onlyGrow: Boolean) : ElementScaling()

    /** 元の大きさ [w] x [h] を拡大縮小した大きさ。負の数はその向きに裏返す */
    fun resize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        fun clamp(v: Float): Int {
            val n = Math.round(kotlin.math.abs(v)).coerceIn(1, MAX)
            return if (v < 0) -n else n
        }
        return when (this) {
            is Size -> {
                val tw = if (widthPercent) w * width / 100f else width
                val th = if (heightPercent) h * height / 100f else height
                when {
                    tw == 0f && th == 0f -> w to h
                    tw == 0f -> clamp(w * kotlin.math.abs(th) / h) to clamp(th)
                    th == 0f -> clamp(tw) to clamp(h * kotlin.math.abs(tw) / w)
                    else -> clamp(tw) to clamp(th)
                }
            }
            is Edge -> {
                val edge = if (long) maxOf(w, h) else minOf(w, h)
                var factor = kotlin.math.abs(size).toFloat() / edge
                if (onlyShrink && factor > 1f) factor = 1f
                if (onlyGrow && factor < 1f) factor = 1f
                val sign = if (size < 0) -1f else 1f
                clamp(w * factor * sign) to clamp(h * factor * sign)
            }
        }
    }

    companion object {
        const val MAX = 4096

        fun parse(a: String, b: String): ElementScaling? {
            val edge = b.trim().lowercase()
            Regex("""^(long|short)?(max|min)?$""").matchEntire(edge)?.takeIf { edge.isNotEmpty() }?.let { m ->
                val size = a.trim().toIntOrNull() ?: return null
                return Edge(size, m.groupValues[1] != "short", m.groupValues[2] == "max", m.groupValues[2] == "min")
            }
            fun dim(s: String): Pair<Float, Boolean>? {
                val t = s.trim().lowercase()
                return when {
                    t.endsWith("%") -> t.dropLast(1).toFloatOrNull()?.let { it to true }
                    t.endsWith("px") -> t.dropLast(2).toFloatOrNull()?.let { it to false }
                    else -> t.toFloatOrNull()?.let { it to false }
                }
            }
            val (w, wp) = dim(a) ?: return null
            val (h, hp) = dim(b) ?: return null
            return Size(w, wp, h, hp)
        }
    }
}

/** 当たり判定の形（collisionex の rect / ellipse / circle / polygon / region。collision は rect） */
enum class CollisionShape { RECT, ELLIPSE, CIRCLE, POLYGON, REGION }

/** collisionex の region。画像 [file] の色 [rgb] の所（[invert] なら、その色でない所）が当たり判定 */
data class CollisionRegion(val file: String, val rgb: Int, val invert: Boolean)

/** region の画像の、指定した色の所の地図（[width] x [height]、横に並べたもの） */
class RegionMask(val width: Int, val height: Int, private val bits: java.util.BitSet) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && bits[y * width + x]

    companion object {
        /** 画像の画素（0xAARRGGBB、横に並べたもの）から作る。透明度は見ない */
        fun of(width: Int, height: Int, pixels: IntArray, region: CollisionRegion): RegionMask {
            val bits = java.util.BitSet(width * height)
            for (i in 0 until minOf(pixels.size, width * height)) {
                if (((pixels[i] and 0xFFFFFF) == region.rgb) != region.invert) bits.set(i)
            }
            return RegionMask(width, height, bits)
        }
    }
}

/** サーフェスの中の矩形（右と下は含まない） */
data class SurfaceRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

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
    val region: CollisionRegion? = null,
) {
    /** @param masks region の画像の地図を返す（まだ読めていなければ null。そのときは当たらない） */
    fun contains(x: Int, y: Int, masks: (CollisionRegion) -> RegionMask? = { null }): Boolean {
        if (x !in left..right || y !in top..bottom) return false
        return when (shape) {
            CollisionShape.RECT -> true
            CollisionShape.REGION -> region?.let(masks)?.get(x, y) == true
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
 * パターンの1コマ。[surface] が -1 ならそのレイヤーを消し、-2 ならほかの動いているアニメーションを全部止める。
 * start / stop / alternativestart / alternativestop のコマは、操作するアニメーションの番号を [targets] に持つ
 * （surfaces.txt に名前で書いたものは [targetKeys] に残し、読み終えてから番号にする）。
 * [scaleX] / [scaleY] は scaling のコマの倍率（パーセント。小数も書ける）、[file] は import のコマの APNG / GIF / WebP、
 * [alpha] は --alpha（0〜255）、[surfaceKey] はサーフェス番号の代わりに書いた別名（読み終えてから [surface] にする）、
 * [layerOf] は着せ替えのコマのもとのアニメーション番号（重ねる順に使う）
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
    val alpha: Int = 255,
    val targetKeys: List<String> = emptyList(),
    val surfaceKey: String? = null,
    val layerOf: Int? = null,
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

    /** exclusive,(…) に名前で書いたもの（読み終えてから [exclusiveOf] に足す） */
    var exclusiveKeys: List<String> = emptyList()

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

    /** icon.rect（発言履歴の顔を切り出す矩形）。null なら当たり判定の face / head から決める */
    var iconRect: SurfaceRect? = null

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
                    out += (if (f.method == "base" && i > 0) f.copy(method = "overlay") else f).copy(layerOf = anim.id)
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
    fun hitTest(
        x: Int,
        y: Int,
        active: Collection<Int> = emptyList(),
        masks: (CollisionRegion) -> RegionMask? = { null },
        /** base のコマで土台を差し替えているなら、その土台のサーフェス（土台の当たり判定はそちら、アニメーションのものはこのサーフェス） */
        base: SurfaceDefinition? = null,
    ): String? {
        val shown = active.mapNotNull { animations[it] }.filter { it.collisions.isNotEmpty() }
        val order = when (animationSort) {
            SortOrder.ASCEND -> shown.sortedBy { it.id }
            else -> shown.sortedByDescending { it.id }
        }
        // option,background のアニメーションは土台の裏にあるので、土台の当たり判定の後に見る
        val (back, front) = order.partition { it.background }
        for (anim in front) anim.collisions.values.firstOrNull { it.contains(x, y, masks) }?.let { return it.id }
        (base ?: this).orderedCollisions().firstOrNull { it.contains(x, y, masks) }?.let { return it.id }
        for (anim in back) anim.collisions.values.firstOrNull { it.contains(x, y, masks) }?.let { return it.id }
        return null
    }
}

/**
 * @param aliases キャラクター（0 が sakura、1 が kero、2 以降が charN）ごとの surface.alias。別名 → サーフェス番号の候補
 * @param tooltips キャラクターごとの sakura.tooltips などのブレス。当たり判定の名前 → 出す文字
 * @param cursors キャラクターごとの sakura.cursor などのブレス（当たり判定ごとのマウスカーソル）
 */
class SurfaceTable(
    val definitions: Map<Int, SurfaceDefinition>,
    val aliases: Map<Int, Map<String, List<Int>>> = emptyMap(),
    val tooltips: Map<Int, Map<String, String>> = emptyMap(),
    val cursors: Map<Int, List<CursorRule>> = emptyMap(),
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

        /** ブレスの種類（surface / surface.alias / tooltips / cursor / descript） */
        private sealed interface Block {
            data class Surfaces(val ids: List<Int>) : Block
            data class Alias(val scope: Int) : Block
            data class Tooltips(val scope: Int) : Block
            data class Cursor(val scope: Int) : Block
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
            val cursors = HashMap<Int, MutableList<CursorRule>>()
            var collisionSort = SortOrder.NONE
            var animationSort = SortOrder.DESCEND
            var header = StringBuilder()
            var block: Block? = null
            // descript ブレスはファイルごと。そのファイルで書いたサーフェスにだけ効く
            val touched = HashSet<Int>()
            // 前のファイルで書いたサーフェスは、そのファイルの descript のまま（surface.append で足しても変えない）
            val settled = HashSet<Int>()
            fun endFile() {
                for (id in touched) defs[id]?.let {
                    it.collisionSort = collisionSort
                    it.animationSort = animationSort
                }
                settled += touched
                touched.clear()
                collisionSort = SortOrder.NONE
                animationSort = SortOrder.DESCEND
            }

            // 範囲のヘッダは 1 行を何万ものサーフェスに写すので、写した行の合計にも上限を置く（数 KB のファイルで食いつぶさない）
            var applied = 0
            fun apply(b: Block, line: String) {
                when (b) {
                    is Block.Surfaces -> for (id in b.ids) {
                        if (applied >= MAX_APPLIED_LINES) break
                        applied++
                        if (id !in settled) touched += id
                        applyLine(defs.getOrPut(id) { SurfaceDefinition(id) }, line)
                    }
                    is Block.Alias -> addAlias(aliases.getOrPut(b.scope) { LinkedHashMap() }, line)
                    is Block.Tooltips -> {
                        val into = tooltips.getOrPut(b.scope) { LinkedHashMap() }
                        val key = line.substringBefore(',').trim()
                        if (key.isNotEmpty() && (key in into || into.size < SurfaceDefinition.MAX_PARTS)) {
                            into[key] = line.substringAfter(',', "").trim().take(MAX_TOOLTIP)
                        }
                    }
                    is Block.Cursor -> Cursors.parseRule(line)?.let { rule ->
                        val into = cursors.getOrPut(b.scope) { ArrayList() }
                        if (into.size < SurfaceDefinition.MAX_PARTS) into += rule
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
                if (raw == FILE_BREAK) {
                    endFile()
                    block = null
                    header = StringBuilder()
                    continue
                }
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
            endFile()
            resolveNames(defs, aliases)
            return SurfaceTable(defs, aliases, tooltips, cursors)
        }

        /** surfaces*.txt を何個かつないで読むときの、ファイルの境目の行（descript ブレスをファイルごとにする） */
        const val FILE_BREAK = "\u0000surfaces-file-break"

        /**
         * 名前で書いたものを番号にする。start / stop などの相手（animation*.name）、exclusive,(…)、コマのサーフェス番号の別名
         * （name,定義名 か、どれかのキャラクターの surface.alias）
         */
        private fun resolveNames(defs: Map<Int, SurfaceDefinition>, aliases: Map<Int, Map<String, List<Int>>>) {
            fun surfaceOf(key: String): Int? =
                defs.values.firstOrNull { it.name == key }?.id ?: aliases.values.firstNotNullOfOrNull { it[key]?.firstOrNull() }
            for (def in defs.values) {
                for (anim in def.animations.values) {
                    if (anim.exclusiveKeys.isNotEmpty()) anim.exclusiveOf = anim.exclusiveOf + anim.exclusiveKeys.mapNotNull(def::animationId)
                    for ((i, f) in anim.patterns.toList()) {
                        var g = f
                        if (f.targetKeys.isNotEmpty()) g = g.copy(targets = f.targetKeys.mapNotNull(def::animationId))
                        f.surfaceKey?.let { key -> g = g.copy(surface = surfaceOf(key) ?: -1) }
                        if (g !== f) anim.patterns[i] = g
                    }
                }
            }
        }

        /** start,3 / alternativestart,[3.5.7] / alternativestart,(3,5) / start,名前 の相手（数字か名前） */
        private fun targetKeys(args: List<String>): List<String> =
            args.joinToString(",").split(',', '.', '[', ']', '(', ')').map { it.trim() }.filter { it.isNotEmpty() }

        private val scopedHeader = Regex("""^(sakura|kero|char(\d+))\.(surface\.alias|tooltips|cursor)$""", RegexOption.IGNORE_CASE)

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
                return when (m.groupValues[3].lowercase()) {
                    "tooltips" -> Block.Tooltips(scope)
                    "cursor" -> Block.Cursor(scope)
                    else -> Block.Alias(scope)
                }
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

        /** surfaces.txt 全体で、サーフェスに写す行の数の上限 */
        private const val MAX_APPLIED_LINES = 100_000

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
                "icon.rect" -> {
                    val v = args.take(4).map { it.toIntOrNull() }
                    if (v.size == 4 && v.all { it != null }) def.iconRect = SurfaceRect(v[0]!!, v[1]!!, v[2]!!, v[3]!!)
                }
            }

            // 番号が桁あふれしていれば（細工された surfaces.txt）その行は無視する。数にも上限を置く
            element.matchEntire(key)?.let { m ->
                val id = m.groupValues[1].toIntOrNull() ?: return
                if (args.size >= 4 && (id in def.elements || def.elements.size < SurfaceDefinition.MAX_PARTS)) {
                    def.elements[id] = elementOf(id, args)
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
                        val keys = targetKeys(args.drop(1))
                        anim.exclusiveOf = keys.mapNotNull { it.toIntOrNull() }.toSet()
                        anim.exclusiveKeys = keys.filter { it.toIntOrNull() == null }
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

        /** collisionex*,ID,rect|ellipse|circle|polygon,座標... / collisionex*,ID,region,ファイル名,R,G,B(,領域反転) */
        private fun parseCollisionEx(args: List<String>): Collision? {
            if (args.size < 3) return null
            val id = args[0]
            if (args[1].trim().lowercase() == "region") {
                if (args.size < 6) return null
                val c = args.subList(3, 6).map { it.trim().toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return null }
                val region = CollisionRegion(args[2].trim(), (c[0] shl 16) or (c[1] shl 8) or c[2], args.getOrNull(6)?.trim()?.lowercase() == "true")
                return Collision(0, 0, Int.MAX_VALUE, Int.MAX_VALUE, id, CollisionShape.REGION, region = region)
            }
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
            if (method in PatternFrame.CONTROL_METHODS) return controlFrame(method, args.drop(1))
            // import,ファイル名,待ち時間,x,y（APNG などのアニメーション画像を、待ってから流す）
            if (method == "import") {
                val file = args.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
                val (min, max) = parseWait(args.getOrNull(2), 1)
                return PatternFrame(method, -1, min, max, args.getOrNull(3)?.toIntOrNull() ?: 0, args.getOrNull(4)?.toIntOrNull() ?: 0, file = file)
            }
            // サーフェス番号の代わりに別名も書ける（SSP 2.8.25）
            val surfaceArg = args.getOrNull(1)?.trim().orEmpty()
            val surface = surfaceArg.toIntOrNull() ?: -1
            val surfaceKey = surfaceArg.takeIf { it.isNotEmpty() && it.toIntOrNull() == null }
            val (min, max) = parseWait(args.getOrNull(2), 1)
            // scaling のコマだけ、倍率に小数を書ける
            val fx = args.getOrNull(3)?.toFloatOrNull() ?: 0f
            val fy = args.getOrNull(4)?.toFloatOrNull() ?: fx
            val alpha = alphaOption(args.drop(5))
            if (method == "scaling") return PatternFrame(method, surface, min, max, fx.toInt(), fy.toInt(), scaleX = fx, scaleY = fy, alpha = alpha)
            return PatternFrame(method, surface, min, max, fx.toInt(), fy.toInt(), alpha = alpha, surfaceKey = surfaceKey)
        }

        /** element*,描画メソッド,ファイル名,X,Y(,--clipping,左,上,右,下,--alpha,値,--scaling,横,縦,--source,値) */
        private fun elementOf(id: Int, args: List<String>): SurfaceElement {
            val options = args.drop(4).map { it.trim() }
            fun after(name: String, n: Int): List<String>? {
                val i = options.indexOfFirst { it.lowercase() == name }
                if (i < 0) return null
                return options.drop(i + 1).take(n).takeIf { it.size == n }
            }
            val clip = after("--clipping", 4)?.map { it.toIntOrNull() }?.takeIf { v -> v.all { it != null } }?.let { v ->
                SurfaceRect(v[0]!!, v[1]!!, v[2]!!, v[3]!!).takeIf { it.right > it.left && it.bottom > it.top }
            }
            return SurfaceElement(
                id,
                args[0].lowercase(),
                args[1],
                args[2].toIntOrNull() ?: 0,
                args[3].toIntOrNull() ?: 0,
                clip = clip,
                alpha = alphaOption(options),
                scaling = after("--scaling", 2)?.let { ElementScaling.parse(it[0], it[1]) },
                source = after("--source", 1)?.single()?.takeIf { it.isNotEmpty() },
                alphaGiven = options.any { it.lowercase() == "--alpha" },
            )
        }

        /** start / stop などのコマ。相手は数字なら番号、名前なら読み終えてから番号にする */
        private fun controlFrame(method: String, args: List<String>): PatternFrame {
            val keys = targetKeys(args)
            return PatternFrame(method, -1, 0, 0, 0, 0, keys.mapNotNull { it.toIntOrNull() }, targetKeys = keys.takeIf { k -> k.any { it.toIntOrNull() == null } }.orEmpty())
        }

        /** --alpha,値（0〜255）。無ければ 255 */
        private fun alphaOption(options: List<String>): Int {
            val i = options.indexOfFirst { it.trim().lowercase() == "--alpha" }
            return options.getOrNull(i + 1)?.trim()?.toIntOrNull()?.coerceIn(0, 255)?.takeIf { i >= 0 } ?: 255
        }

        /** 旧形式: surfaceID,wait,method,x,y（wait は 10 ミリ秒単位） */
        private fun parseOldPattern(args: List<String>): PatternFrame? {
            if (args.size < 3) return null
            val method = args[2].lowercase()
            // 0pattern0,0,0,alternativestart,[1,2] のように、相手は描画メソッドの後ろ
            if (method in PatternFrame.CONTROL_METHODS) return controlFrame(method, args.drop(3))
            val surface = args[0].toIntOrNull() ?: return null
            val (min, max) = parseWait(args[1], 10)
            return PatternFrame(method, surface, min, max, args.getOrNull(3)?.toIntOrNull() ?: 0, args.getOrNull(4)?.toIntOrNull() ?: 0, alpha = alphaOption(args.drop(5)))
        }

        private fun parseWait(s: String?, unit: Int): Pair<Int, Int> {
            if (s == null) return 0 to 0
            val parts = s.split('-')
            // 単位を掛けてあふれたり、選ぶときに +1 であふれたりしないよう、ありうる範囲（0〜10 分）に丸める
            fun ms(v: String?) = v?.trim()?.toLongOrNull()?.let { (it * unit).coerceIn(0L, MAX_WAIT_MS.toLong()).toInt() }
            val min = ms(parts[0]) ?: 0
            val max = ms(parts.getOrNull(1)) ?: min
            return min to maxOf(min, max)
        }

        private const val MAX_WAIT_MS = 600_000
    }
}
