package net.pono.baseware.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.StringReader

/** POP3 / IMAP の受け答えと、件名の MIME */
class MailCheckTest {
    private val account = MailAccount("メイン", "pop3", "mail.test", 995, "me", "secret")

    @Test
    fun pop3ReadsCountsAndHeaders() {
        val server = listOf(
            "+OK ready", "+OK user", "+OK pass", "+OK 2 300",
            "+OK list", "1 100", "2 200", ".",
            "+OK uidl", "1 aaa", "2 bbb", ".",
            "+OK top", "From: =?UTF-8?B?44Gf44KN44GG?= <t@example.test>", "Subject: =?UTF-8?Q?=E3=81=93=E3=82=93?=", " =?UTF-8?B?44Gr44Gh44Gv?=", ".",
            "+OK top", "From: b@example.test", "Subject: plain", ".",
            "+OK bye",
        ).joinToString("\r\n", postfix = "\r\n")
        val out = ByteArrayOutputStream()
        val r = TlsMailChecker.Pop3(BufferedReader(StringReader(server)), out, 50).check(account) as MailResult.Ok
        assertEquals(2, r.count)
        assertEquals(300L, r.bytes)
        assertEquals(listOf("たろう <t@example.test>" to "こんにちは", "b@example.test" to "plain"), r.items.map { it.from to it.subject })
        assertEquals(listOf("aaa", "bbb"), r.items.map { it.uid })
        val sent = out.toString(Charsets.UTF_8)
        assertTrue(sent.startsWith("USER me\r\nPASS secret\r\nSTAT\r\nLIST\r\nUIDL\r\nTOP 1 0\r\nTOP 2 0\r\nQUIT"))
    }

    @Test
    fun pop3RefusedLoginIsKick() {
        val server = "+OK ready\r\n+OK user\r\n-ERR bad password\r\n"
        assertEquals(MailResult.Failure("kick"), TlsMailChecker.Pop3(BufferedReader(StringReader(server)), ByteArrayOutputStream(), 50).check(account))
    }

    @Test
    fun imapReadsTheNewestHeaders() {
        val header = "From: a@example.test\r\nSubject: hi\r\n\r\n"
        val server = listOf(
            "* OK ready", "a1 OK logged in",
            "* 3 EXISTS", "* 0 RECENT", "a2 OK [READ-ONLY] done",
            "* 3 FETCH (UID 33 RFC822.SIZE 120 BODY[HEADER.FIELDS (FROM SUBJECT DATE)] {${header.length}}",
        ).joinToString("\r\n", postfix = "\r\n") + header + ")\r\na3 OK fetched\r\na4 OK bye\r\n"
        val out = ByteArrayOutputStream()
        val r = TlsMailChecker.Imap(BufferedReader(StringReader(server)), out, 1).check(account.copy(protocol = "imap")) as MailResult.Ok
        assertEquals(3, r.count)
        assertEquals(listOf(Triple("33", "a@example.test", "hi")), r.items.map { Triple(it.uid, it.from, it.subject) })
        assertTrue(out.toString(Charsets.UTF_8).contains("a3 FETCH 3:3 (UID RFC822.SIZE"))
    }

    @Test
    fun imapRefusedLoginIsKick() {
        val server = "* OK ready\r\na1 NO [AUTHENTICATIONFAILED] nope\r\n"
        assertEquals(MailResult.Failure("kick"), TlsMailChecker.Imap(BufferedReader(StringReader(server)), ByteArrayOutputStream(), 50).check(account))
    }

    @Test
    fun passwordIsNotInTheAccountsText() {
        assertTrue("secret" !in account.toString())
    }
}
