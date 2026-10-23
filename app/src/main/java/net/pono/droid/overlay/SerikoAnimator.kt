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

    /** ずらす前のコマ */
    private var raw: Map<Int, PatternFrame> = emptyMap()

    /** \![anim,offset,ID,x,y] のずれ（アニメーション番号ごと） */
    private val offsets = HashMap<Int, Pair<Int, Int>>()

    private var current: SurfaceDefinition? = null
    private var currentBinds: Set<Int> = emptySet()
    private val jobs = mutableListOf<Job>()
    private val running = HashMap<Int, Job>()

    /** @param binds 着せ替えで身に着けているもの（interval が bind のアニメーション番号） */
    fun show(def: SurfaceDefinition?, binds: Set<Int> = emptySet()) {
        if (def === current && binds == currentBinds) return
        stop()
        current = def
        currentBinds = binds
        def ?: return
        // 着せ替えは動かさず、パターンのコマを全部重ねて出しておく
        setRaw(def.bindFrames(binds).mapIndexed { i, f -> BIND_LAYER_BASE + i to f }.toMap())
        for (anim in def.animations.values) {
            if (anim.frames.all { it.isControl && it.method !in STARTERS }) continue
            jobs += when (anim.interval.kind) {
                IntervalKind.ALWAYS -> scope.launch { while (isActive) playOnce(anim) }
                IntervalKind.RUNONCE -> scope.launch { playOnce(anim) }
                IntervalKind.SOMETIMES -> everySecond(anim, 1.0 / 2)
                IntervalKind.RARELY -> everySecond(anim, 1.0 / 4)
                IntervalKind.RANDOM -> everySecond(anim, 1.0 / maxOf(anim.interval.arg, 1))
                IntervalKind.PERIODIC -> scope.launch {
                    while (isActive) {
                        delay(maxOf(anim.interval.arg, 1) * 1000L)
                        playOnce(anim)
                    }
                }
                else -> continue
            }
        }
    }

    /** `\i[n]` やパターンの start から呼ぶ */
    fun start(id: Int) {
        val anim = current?.animations?.get(id) ?: return
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

    /** `\![anim,offset,ID,x,y]`。表示中のコマも、これから出すコマもずらす */
    fun setOffset(id: Int, x: Int, y: Int) {
        if (x == 0 && y == 0) offsets.remove(id) else offsets[id] = x to y
        publish()
    }

    /** `\![anim,clear,ID]` とパターンの stop。そのアニメーションを止めて、出していたコマを消す */
    fun clear(id: Int) {
        running.remove(id)?.cancel()
        setRaw(raw - id)
    }

    /** `\![anim,stop]`。\i などで動かしているアニメーションを止める（出ているコマはそのまま） */
    fun stopRunning() {
        running.values.forEach { it.cancel() }
        running.clear()
    }

    fun stop() {
        current = null
        currentBinds = emptySet()
        jobs.forEach { it.cancel() }
        jobs.clear()
        running.values.forEach { it.cancel() }
        running.clear()
        offsets.clear()
        setRaw(emptyMap())
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
            if (Random.nextDouble() < chance) playOnce(anim)
        }
    }

    /** パターンを最初から最後まで1回再生する。最後のコマは残す（-1 で消すのが普通）。 */
    private suspend fun playOnce(anim: Animation) {
        var drew = false
        for (frame in anim.frames) {
            when (frame.method) {
                "start" -> frame.targets.firstOrNull()?.let(::start)
                "alternativestart" -> frame.targets.randomOrNull()?.let(::start)
                "parallelstart" -> frame.targets.forEach(::start)
                "stop", "alternativestop", "parallelstop" -> frame.targets.forEach(::clear)
                "insert" -> {} // 着せ替えの中でだけ意味がある（bindFrames で扱う）
                else -> {
                    setRaw(raw + (anim.id to frame))
                    val wait = if (frame.waitMaxMs > frame.waitMinMs) Random.nextInt(frame.waitMinMs, frame.waitMaxMs + 1) else frame.waitMinMs
                    delay(maxOf(wait, 16).toLong())
                    drew = true
                }
            }
        }
        // start だけのパターンを always で回すと空回りするので、少し待つ
        if (!drew) delay(1000)
    }

    private companion object {
        /** 着せ替えのコマは、普通のアニメーション（番号 = アニメーション番号）より下に重ねる */
        const val BIND_LAYER_BASE = -1_000_000

        private val STARTERS = setOf("start", "alternativestart", "parallelstart")
    }
}
