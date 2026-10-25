package net.pono.droid.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.pono.baseware.shell.Animation
import net.pono.baseware.shell.IntervalKind
import net.pono.baseware.shell.PatternFrame
import net.pono.baseware.shell.SurfaceDefinition
import kotlin.random.Random

/** 1人分の SERIKO アニメーション。サーフェスか着せ替えが変わるたびに [show] を呼ぶ。 */
class SerikoAnimator(private val scope: CoroutineScope) {
    /** レイヤーの番号 → いま表示しているコマ（\![anim,offset] のずれを足したもの）。番号の小さい順に重ねる */
    private val _layers = MutableStateFlow<Map<Int, PatternFrame>>(emptyMap())
    val layers: StateFlow<Map<Int, PatternFrame>> = _layers.asStateFlow()

    /** いまコマを進めているアニメーションの番号（\__w[animation,ID] などで待つのに使う） */
    private val _playing = MutableStateFlow<Set<Int>>(emptySet())
    val playing: StateFlow<Set<Int>> = _playing.asStateFlow()

    /** ずらす前のコマ */
    private var raw: Map<Int, PatternFrame> = emptyMap()

    /** \![anim,offset,ID,x,y] のずれ（アニメーション番号ごと） */
    private val offsets = HashMap<Int, Pair<Int, Int>>()

    private var current: SurfaceDefinition? = null
    private var currentBinds: Set<Int> = emptySet()
    private val jobs = mutableListOf<Job>()
    private val running = HashMap<Int, Job>()

    /** アニメーションごとの、いま出しているコマの番号（option,shared-index でサーフェスをまたいで続ける） */
    private val positions = HashMap<Int, Int>()

    /** option,exclusive のアニメーションが動いている間はそれ。ほかのアニメーション（exclusiveOf があればそれだけ）は動かさない */
    private var exclusive: Animation? = null

    /** \![anim,pause,ID] で止めているアニメーション */
    private val paused = HashSet<Int>()

    /** @param binds 着せ替えで身に着けているもの（interval が bind のアニメーション番号） */
    fun show(def: SurfaceDefinition?, binds: Set<Int> = emptySet()) {
        if (def === current && binds == currentBinds) return
        // shared-index のアニメーションは、替えた先にも同じ番号の shared-index があれば続きのコマから動かす
        val previous = current
        // \i や start で動かしたものだけでなく、always などで動いているものも続ける
        val carried = previous?.animations?.values
            ?.filter { it.sharedIndex && positions[it.id] != null && it.id in _playing.value }
            ?.associate { it.id to positions[it.id]!! }
            .orEmpty()
        stop()
        current = def
        currentBinds = binds
        def ?: return
        // 着せ替えは動かさず、パターンのコマを全部重ねて出しておく
        setRaw(bindLayers(def, binds))
        for (anim in def.animations.values) {
            if (anim.frames.all { it.isControl && it.method !in STARTERS }) continue
            val interval = anim.interval
            // bind+always などは、その着せ替えを身に着けているときだけ動く
            if (IntervalKind.BIND in interval && (interval.isStaticBind || anim.id !in binds)) continue
            val from = carried[anim.id]?.takeIf { anim.sharedIndex && it < anim.patterns.size }
            // always なら、その続きから回し始める。ほかは続きを1回流してから、いつもの間隔で
            if (from != null && IntervalKind.ALWAYS !in interval) running[anim.id] = scope.launch { playOnce(anim, from) }
            for (kind in interval.kinds) {
                jobs += when (kind) {
                    IntervalKind.ALWAYS -> scope.launch {
                        var start = from ?: 0
                        while (isActive) {
                            // stop や -2 で止めたら、また start するまで回さない
                            if (anim.id in halted) {
                                delay(50)
                                continue
                            }
                            playTimed(anim, start)
                            start = 0
                        }
                    }
                    IntervalKind.RUNONCE -> scope.launch { playTimed(anim) }
                    IntervalKind.SOMETIMES -> everySecond(anim, 1.0 / 2)
                    IntervalKind.RARELY -> everySecond(anim, 1.0 / 4)
                    IntervalKind.RANDOM -> everySecond(anim, 1.0 / maxOf(interval.arg, 1))
                    IntervalKind.PERIODIC -> scope.launch {
                        while (isActive) {
                            delay(maxOf(interval.arg, 1) * 1000L)
                            playTimed(anim)
                        }
                    }
                    else -> continue
                }
            }
        }
    }

    /** `\i[n]` やパターンの start から呼ぶ */
    fun start(id: Int) {
        val anim = current?.animations?.get(id) ?: return
        if (blocked(anim)) return
        // 止めていた always は、いつもの回し方に戻す
        if (halted.remove(id) && IntervalKind.ALWAYS in anim.interval) return
        running[id]?.cancel()
        running[id] = scope.launch { playOnce(anim) }
    }

    /** 話した文字の数が [from] から [to] に増えた。interval が talk,N のアニメーション（口パクなど）を動かす */
    fun talk(from: Int, to: Int) {
        current?.talkAnimations(from, to)?.forEach { start(it.id) }
    }

    /** スクリプトを話し終えた（\e まで来た）。interval が yen-e のアニメーションを動かす */
    fun scriptEnd() {
        current?.yenEAnimations?.forEach { start(it.id) }
    }

    /** このキャラクターが話し始めた・話し終えた。interval が starttalk / endtalk のアニメーションを動かす */
    fun startTalk() {
        current?.startTalkAnimations?.forEach { start(it.id) }
    }

    fun endTalk() {
        current?.endTalkAnimations?.forEach { start(it.id) }
    }

    /** `\![anim,offset,ID,x,y]`。表示中のコマも、これから出すコマもずらす */
    fun setOffset(id: Int, x: Int, y: Int) {
        if (x == 0 && y == 0) offsets.remove(id) else offsets[id] = x to y
        publish()
    }

    /** `\![anim,clear,ID]` とパターンの stop。そのアニメーションを止めて、出していたコマを消す */
    fun clear(id: Int) {
        running.remove(id)?.cancel()
        timedPlays.remove(id)?.cancel()
        halted += id
        markStopped(id)
        setRaw(raw - id)
    }

    /** `\![anim,stop]`。\i などで動かしているアニメーションを止める（出ているコマはそのまま） */
    fun stopRunning() {
        running.values.forEach { it.cancel() }
        running.clear()
        _playing.value = emptySet()
    }

    /**
     * `\![anim,add,...]`。サーフェスを替えるまで、ふつうのアニメーションより上に重ねる。
     * コマが2つ以上なら、それぞれの待ち時間ごとに順に出す（最後のコマは残す）
     */
    fun add(frames: List<PatternFrame>) {
        if (frames.isEmpty()) return
        // 重ねすぎたら古いものから上書きする（スクリプトに無数のレイヤーを作らせない）
        val layer = ADD_LAYER_BASE + addSerial++ % MAX_ADD_LAYERS
        if (frames.size == 1) {
            setRaw(raw + (layer to frames[0]))
            return
        }
        jobs += scope.launch {
            for (f in frames) {
                setRaw(raw + (layer to f))
                delay(maxOf(f.waitMinMs, 16).toLong())
            }
        }
    }

    private var addSerial = 0

    /** `\![anim,pause,ID]` / `\![anim,resume,ID]`。止めている間はコマを進めない（サーフェスを替えると解ける） */
    fun pause(id: Int) {
        paused += id
    }

    fun resume(id: Int) {
        paused -= id
    }

    fun stop() {
        current = null
        currentBinds = emptySet()
        jobs.forEach { it.cancel() }
        jobs.clear()
        running.values.forEach { it.cancel() }
        running.clear()
        timedPlays.clear()
        halted.clear()
        offsets.clear()
        positions.clear()
        paused.clear()
        exclusive = null
        _playing.value = emptySet()
        setRaw(emptyMap())
    }

    /** [except] 以外の、動いているアニメーションを止めてコマを消す（着せ替えと \![anim,add] のものは残す） */
    private fun stopOthers(except: Int) {
        val others = (running.keys + timedPlays.keys + _playing.value + raw.keys.filter { it in 0 until ADD_LAYER_BASE }).filter { it != except }.toSet()
        for (id in others) clear(id)
    }

    /** exclusive のアニメーションが動いている間、[anim] を動かさないか */
    private fun blocked(anim: Animation): Boolean {
        val ex = exclusive ?: return false
        return ex !== anim && (ex.exclusiveOf.isEmpty() || anim.id in ex.exclusiveOf)
    }

    private fun markStopped(id: Int) {
        _playing.value = _playing.value - id
        positions.remove(id)
    }

    private fun setRaw(layers: Map<Int, PatternFrame>) {
        raw = layers
        publish()
    }

    private fun publish() {
        _layers.value = if (offsets.isEmpty()) {
            raw
        } else {
            raw.mapValues { (id, frame) -> offsets[id]?.let { (x, y) -> frame.copy(x = frame.x + x, y = frame.y + y) } ?: frame }
        }
    }

    private fun everySecond(anim: Animation, chance: Double) = scope.launch {
        while (isActive) {
            delay(1000)
            if (Random.nextDouble() < chance) playTimed(anim)
        }
    }

    /** interval で回している1回分の再生。stop や -2 で、その回だけ止められる（interval はそのまま続く） */
    private val timedPlays = HashMap<Int, Job>()

    /** stop・\![anim,clear]・-2 で止めた always のアニメーション（start するかサーフェスを替えるまで回さない） */
    private val halted = HashSet<Int>()

    private suspend fun playTimed(anim: Animation, from: Int = 0) {
        val job = scope.launch { playOnce(anim, from) }
        timedPlays[anim.id] = job
        try {
            job.join()
        } finally {
            job.cancel()
            if (timedPlays[anim.id] === job) timedPlays.remove(anim.id)
        }
    }

    /**
     * パターンを [from] 番目のコマから最後まで1回再生する。最後のコマは残す（-1 で消すのが普通）。
     * exclusive なら、動いている間ほかのアニメーションを止める
     */
    private suspend fun playOnce(anim: Animation, from: Int = 0) {
        if (blocked(anim)) {
            delay(1000)
            return
        }
        if (anim.exclusive) {
            exclusive = anim
            // ほかに動いているもの（\i や start で動かしたもの）を止める
            for ((id, job) in running.toList()) {
                val other = current?.animations?.get(id) ?: continue
                if (other !== anim && blocked(other)) {
                    job.cancel()
                    running.remove(id)
                    markStopped(id)
                }
            }
        }
        _playing.value = _playing.value + anim.id
        var drew = false
        try {
            for ((index, frame) in anim.frames.withIndex()) {
                if (index < from) continue
                // \![anim,pause] の間と、ほかの exclusive が動いている間は止まって待つ
                while (anim.id in paused || blocked(anim)) delay(50)
                positions[anim.id] = index
                when (frame.method) {
                    "start" -> frame.targets.firstOrNull()?.let(::start)
                    "alternativestart" -> frame.targets.randomOrNull()?.let(::start)
                    "parallelstart" -> frame.targets.forEach(::start)
                    "stop", "alternativestop", "parallelstop" -> frame.targets.forEach(::clear)
                    "insert" -> {} // 着せ替えの中でだけ意味がある（bindFrames で扱う）
                    else -> if (frame.surface == STOP_OTHERS) {
                        // サーフェス番号 -2 は、ほかの動いているアニメーションを全部止める（このアニメーションは続ける）
                        stopOthers(anim.id)
                    } else {
                        setRaw(raw + (anim.id to frame))
                        val wait = if (frame.waitMaxMs > frame.waitMinMs) Random.nextInt(frame.waitMinMs, frame.waitMaxMs + 1) else frame.waitMinMs
                        delay(maxOf(wait, 16).toLong())
                        drew = true
                    }
                }
            }
        } finally {
            if (exclusive === anim) exclusive = null
            markStopped(anim.id)
        }
        // start だけのパターンを always で回すと空回りするので、少し待つ
        if (!drew) delay(1000)
    }

    companion object {
        /** 着せ替えのコマは、普通のアニメーション（番号 = アニメーション番号）より下に重ねる */
        private const val BIND_LAYER_BASE = -1_000_000

        /** 着せ替え [binds] を身に着けた [def] の、動かないコマ（レイヤーの番号ごと）。\![execute,dumpsurface] でも使う */
        fun bindLayers(def: SurfaceDefinition?, binds: Set<Int>): Map<Int, PatternFrame> =
            def?.bindFrames(binds).orEmpty().mapIndexed { i, f -> BIND_LAYER_BASE + i to f }.toMap()

        /** \![anim,add] のコマは、普通のアニメーションより上に重ねる */
        private const val ADD_LAYER_BASE = 1_000_000
        private const val MAX_ADD_LAYERS = 64

        private val STARTERS = setOf("start", "alternativestart", "parallelstart")

        /** パターンのサーフェス番号 -2 */
        private const val STOP_OTHERS = -2
    }
}
