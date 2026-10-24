package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 声を聞く（OnVoiceRecognitionWord / OnVoiceRecognitionStatus） */
class VoiceRecognitionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class VoicePlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        override fun voiceRecognitionEnabled() = true
    }

    @Test
    fun namesCallCharactersAndFollowingWordsGoToThem() = runBlocking<Unit> {
        val shiori = ScriptedShiori(emptyMap())
        val scope = testScope()
        try {
            val ghost = tmp.newTestGhost(extraDescript = "sakura.name,さくら\r\nkero.name,うにゅう\r\n")
            val c = testController(ghost, shiori, scope, VoicePlatform())
            c.start()
            shiori.awaitEvent("OnFirstBoot")
            assertEquals(listOf("1"), shiori.awaitEvent("OnVoiceRecognitionStatus"))
            // 誰も呼んでいない話し声は送らない
            c.onVoiceRecognized("今日は雨だね")
            c.onVoiceRecognized("うにゅう")
            c.onVoiceRecognized("おなかすいた")
            c.onVoiceRecognized("さくら、おはよう")
            withTimeout(5000) { while (shiori.count("OnVoiceRecognitionWord") < 4) delay(10) }
            val words = synchronized(shiori.events) { shiori.events.filter { it.first == "OnVoiceRecognitionWord" }.map { it.second } }
            assertEquals(listOf(listOf("1", "うにゅう"), listOf("1", "おなかすいた"), listOf("0", "さくら"), listOf("0", "おはよう")), words)
            c.onVoiceRecognitionChanged(false)
            withTimeout(5000) { while (shiori.count("OnVoiceRecognitionStatus") < 2) delay(10) }
            assertEquals(listOf("0"), shiori.awaitEvent("OnVoiceRecognitionStatus"))
        } finally {
            scope.cancel()
        }
    }
}
