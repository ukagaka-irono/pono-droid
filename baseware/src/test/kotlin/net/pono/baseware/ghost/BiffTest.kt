package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.pono.baseware.net.MailAccount
import net.pono.baseware.net.MailChecker
import net.pono.baseware.net.MailItem
import net.pono.baseware.net.MailResult
import net.pono.baseware.sakura.ScriptPlayer
import net.pono.baseware.shell.Shell
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** \![biff] と OnBIFFBegin / Complete / BIFF2Complete / Failure */
class BiffTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class MailPlatform(val inner: RecordingPlatform = RecordingPlatform()) : GhostPlatform by inner {
        override fun mailAccounts() = listOf(MailAccount("メイン", "imap", "mail.test", 993, "me", "pw"), MailAccount("壊れた", "pop3", "bad.test", 995, "me", "pw"))
    }

    @Test
    fun checksEveryAccountAndReportsIncreases() = runBlocking<Unit> {
        val shiori = ScriptedShiori(mapOf("OnFirstBoot" to """\0\![biff]\e"""))
        val scope = testScope()
        var count = 1
        val checker = MailChecker { a ->
            if (a.name == "壊れた") {
                MailResult.Failure("timeout")
            } else {
                MailResult.Ok((1..count).map { MailItem("$it", "u$it", 10, "from$it", "subject$it", listOf("From: from$it", "Subject: subject$it")) }, count, 10L * count)
            }
        }
        try {
            val ghost = tmp.newTestGhost()
            val c = GhostController(
                ghost = ghost, shell = Shell(ghost.shellDir()!!), shioriFactory = { _, _ -> shiori }, scope = scope,
                platform = MailPlatform(), player = ScriptPlayer(charWaitMs = 0), mailChecker = checker,
            )
            c.start()
            // アカウントごとに OnBIFFBegin が来る。どちらが先に届くかは決まっていない
            withTimeout(5000) { while (shiori.count("OnBIFFBegin") < 2) delay(10) }
            assertEquals(
                setOf(listOf("", "", "メイン"), listOf("", "", "壊れた")),
                synchronized(shiori.events) { shiori.events.filter { it.first == "OnBIFFBegin" }.map { it.second }.toSet() },
            )
            val first = shiori.awaitEvent("OnBIFFComplete")
            assertEquals(listOf("1", "10", "メイン", "0"), first.take(4))
            assertEquals("from1\u0001subject1", first[7])
            assertEquals(listOf("timeout", "", "壊れた"), shiori.awaitEvent("OnBIFFFailure"))
            // 増えたら OnBIFF2Complete（OnBIFFComplete に答えが無いとき）
            count = 3
            withTimeout(5000) { while (c.state.value.talking) delay(10) }
            delay(100)
            c.checkMail("メイン")
            withTimeout(5000) { while (shiori.count("OnBIFF2Complete") == 0) delay(10) }
            assertEquals(listOf("3", "30", "メイン"), shiori.awaitEvent("OnBIFF2Complete").take(3))
            assertEquals("2", shiori.awaitEvent("OnBIFFComplete")[3])
        } finally {
            scope.cancel()
        }
    }
}
