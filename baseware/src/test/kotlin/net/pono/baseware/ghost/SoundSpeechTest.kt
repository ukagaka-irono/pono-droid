package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.sakura.SoundOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** 音のオプションと止める・待つ・ループのイベント、読み上げ（\__v）と発言履歴 */
class SoundSpeechTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun soundOptionsAreParsedLikeSsp() {
        assertEquals(
            SoundOptions(volume = 100, balance = -100, rate = 150, seekMs = 90_500),
            SoundOptions.parse(listOf("--volume=150", "--balance=-300", "--rate=150", "--seektime=1:30.5", "--window=true")),
        )
        assertEquals(90_500L, SoundOptions.seekTime("90500"))
        assertEquals(90_500L, SoundOptions.seekTime("0:01:30.5"))
        assertEquals(3_723_004L, SoundOptions.seekTime("1:02:03.0045"))
        assertEquals(0L, SoundOptions.seekTime("x"))
        assertEquals(0L, SoundOptions.seekTime("-5"))
        assertEquals(SoundOptions(seekMs = -2000, seekRelative = true), SoundOptions.parse(listOf("--seektime=@-2000")))
    }

    private class SoundPlatform(val inner: RecordingPlatform = RecordingPlatform(), var speech: Boolean = false) : GhostPlatform by inner {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val spoken: MutableList<Pair<Int, String>> = Collections.synchronizedList(mutableListOf())

        override fun playSound(file: File, loop: Boolean, options: SoundOptions) {
            calls += "play:${file.name}:$loop:$options"
        }

        override fun soundControl(command: String, file: File?, options: SoundOptions) {
            calls += "$command:${file?.name}:$options"
        }

        override fun stopSound(file: File?) {
            calls += "stop:${file?.name}"
        }

        override fun stopSound() {
            calls += "stopall"
        }

        override fun speechEnabled() = speech

        override fun speak(scope: Int, text: String) {
            spoken += scope to text
        }
    }

    private fun run(script: String, speech: Boolean = false, block: suspend (GhostController, ScriptedShiori, SoundPlatform, Ghost) -> Unit) = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to script))
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost()
            for (name in listOf("a.wav", "b.ogg")) File(ghost.masterDir, name).writeBytes(byteArrayOf(1))
            val platform = SoundPlatform(speech = speech)
            val c = testController(ghost, shiori, scope, platform)
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            block(c, shiori, platform, ghost)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun <T> awaitSize(list: List<T>, n: Int) = withTimeout(5000) { while (list.size < n) delay(10) }

    @Test
    fun soundCommandsReachThePlatformPerFile() = run(
        """\0\![sound,loop,a.wav,--volume=30]\![sound,load,b.ogg,--seektime=500]\![sound,option,a.wav,--balance=20]""" +
            """\![sound,pause]\![sound,resume,a.wav]\![sound,stop,b.ogg]\![sound,stop]\![sound,cdplay,1]\![sound,play,missing.wav]\e""",
    ) { _, _, platform, _ ->
        awaitSize(platform.calls, 7)
        assertEquals(
            listOf(
                "play:a.wav:true:${SoundOptions(volume = 30)}",
                "load:b.ogg:${SoundOptions(seekMs = 500)}",
                "option:a.wav:${SoundOptions(balance = 20)}",
                "pause:null:${SoundOptions()}",
                "resume:a.wav:${SoundOptions()}",
                "stop:b.ogg",
                "stopall",
            ),
            platform.calls.toList(),
        )
        // missing.wav はスクリプトの最後なので、記録されるまで待つ
        withTimeout(5000) { while (platform.inner.logs.none { "音が見つかりません: missing.wav" in it }) delay(10) }
    }

    @Test
    fun soundEventsUseTheNameTheGhostWrote() = run("""\0\_v[a.wav]\e""") { c, shiori, _, ghost ->
        val file = File(ghost.masterDir, "a.wav").canonicalFile
        c.onSoundLooped(file)
        assertEquals(listOf("a.wav"), shiori.awaitEvent("OnSoundLoop"))
        c.onSoundStopped(file)
        assertEquals(listOf("a.wav", "end"), shiori.awaitEvent("OnSoundStop"))
        c.onSoundError(file, 1, "error 1/0")
        assertEquals(listOf("play", "1", "a.wav", "error 1/0"), shiori.awaitEvent("OnSoundError"))
    }

    @Test
    fun speechFollowsVTagsAndFillsTheBacklog() = run(
        """\0こんにちは\__v[disable]読まない\__v\1相方です\__v[alternate,あいかた]相方\__vだよ\0%username\e""",
        speech = true,
    ) { c, shiori, platform, _ ->
        assertEquals(listOf("1"), shiori.awaitEvent("OnSpeechSynthesisStatus"))
        awaitSize(platform.spoken, 3)
        assertEquals(listOf(0 to "こんにちは", 1 to "相方ですあいかただよ", 0 to "ユーザ"), platform.spoken.toList())
        assertEquals(listOf(0 to "こんにちは", 1 to "相方ですあいかただよ", 0 to "ユーザ"), c.backlog.map { it.scope to it.text })
        c.onSpeechSettingChanged(false)
        withTimeout(5000) { while (shiori.count("OnSpeechSynthesisStatus") < 2) delay(10) }
        assertEquals(listOf("0"), shiori.awaitEvent("OnSpeechSynthesisStatus"))
    }

    @Test
    fun noSpeechWhenItIsOff() = run("""\0こんにちは\e""") { c, shiori, platform, _ ->
        assertEquals(listOf("0"), shiori.awaitEvent("OnSpeechSynthesisStatus"))
        withTimeout(5000) { while (c.backlog.isEmpty()) delay(10) }
        assertTrue(platform.spoken.isEmpty())
    }
}
