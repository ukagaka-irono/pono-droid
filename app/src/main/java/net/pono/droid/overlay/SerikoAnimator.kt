package net.pono.droid.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.pono.baseware.shell.Animation
import net.pono.baseware.shell.IntervalKind
import net.pono.baseware.shell.PatternFrame
import net.pono.baseware.shell.SurfaceDefinition
import kotlin.random.Random

/** 1人分の SERIKO アニメーション。サーフェスが変わるたびに [show] を呼ぶ。 */
class SerikoAnimator(private val scope: CoroutineScope) {
    private val _layers = MutableStateFlow<Map<Int, PatternFrame>>(emptyMap())
    val layers: StateFlow<Map<Int, PatternFrame>> = _layers.asStateFlow()

    private var current: SurfaceDefinition? = null
    private val jobs = mutableListOf<Job>()
    private val running = HashMap<Int, Job>()

    fun show(def: SurfaceDefinition?) {
        if (def === current) return
        stop()
        current = def
        def ?: return
        for (anim in def.animations.values) {
            if (anim.frames.all { it.method in controlMethods }) continue
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

    /** `\i[n]` */
    fun start(id: Int) {
        val anim = current?.animations?.get(id) ?: return
        running[id]?.cancel()
        running[id] = scope.launch { playOnce(anim) }
    }

    fun stop() {
        current = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        running.values.forEach { it.cancel() }
        running.clear()
        _layers.value = emptyMap()
    }

    private fun everySecond(anim: Animation, chance: Double) = scope.launch {
        while (isActive) {
            delay(1000)
            if (Random.nextDouble() < chance) playOnce(anim)
        }
    }

    /** パターンを最初から最後まで1回再生する。最後のコマは残す（-1 で消すのが普通）。 */
    private suspend fun playOnce(anim: Animation) {
        for (frame in anim.frames) {
            // 他のアニメーションを操作するコマはまだ扱わない
            if (frame.method in controlMethods) continue
            _layers.update { it + (anim.id to frame) }
            val wait = if (frame.waitMaxMs > frame.waitMinMs) Random.nextInt(frame.waitMinMs, frame.waitMaxMs + 1) else frame.waitMinMs
            delay(maxOf(wait, 16).toLong())
        }
    }

    private companion object {
        val controlMethods = setOf("start", "alternativestart", "stop", "alternativestop", "insert")
    }
}
