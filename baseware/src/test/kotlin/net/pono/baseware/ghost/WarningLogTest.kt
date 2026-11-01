package net.pono.baseware.ghost

import net.pono.baseware.text.Messages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 開発者向けのログのうち、エラーログにも Warning として残すものの見分け。どの言語でも、日本語のときと同じものが Warning になる */
class WarningLogTest {
    @After
    fun reset() {
        Messages.language = "ja"
    }

    /** 前の見分け方（日本語の文に警告の言葉が入っているか） */
    private fun warnedInJapanese(key: String) =
        listOf("未対応", "できません", "ありません", "見つかりません", "失敗").any { it in Messages.table("ja").getValue(key) }

    @Test
    fun sameLogsAreWarningsInEveryLanguage() {
        val keys = Messages.table("ja").keys.filter { it.startsWith("log.") || it.startsWith("error.") }
        for (language in Messages.LANGUAGES) {
            Messages.language = language
            for (key in keys) {
                assertEquals("$language の $key", warnedInJapanese(key), GhostController.isWarning(Messages.get(key, "a", "b", "c", "d")))
            }
        }
    }

    @Test
    fun translatedLogs() {
        Messages.language = "en"
        assertFalse(GhostController.isWarning(Messages.get("log.reload.shell", "master")))
        // 「読み込めませんでした」は前から Warning の言葉を含まない（onError で Error として残る）
        assertFalse(GhostController.isWarning(Messages.get("error.shiori.load", "Emily")))
        assertTrue(GhostController.isWarning(Messages.get("error.shiori.unsupported", "Emily", "MISAKA")))
        assertTrue(GhostController.isWarning(Messages.get("log.sound.notfound", "a.wav")))
        // ゴーストや SHIORI が出す日本語のログは、言語によらず言葉で見分ける
        assertTrue(GhostController.isWarning("辞書の読み込みに失敗しました"))
        assertFalse(GhostController.isWarning("SHIORI version: 1.0"))
    }
}
