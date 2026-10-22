package net.pono.baseware.ghost

import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InputBoxTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun parsesOpenTags() {
        assertEquals(
            InputRequest(InputRequest.Kind.INPUT, "OnInputName", "ぽの", 5000, 1),
            InputRequest.fromOpenTag("inputbox", listOf("OnInputName", "5000", "ぽの"), 1),
        )
        assertEquals(InputRequest.Kind.PASSWORD, InputRequest.fromOpenTag("PasswordInput", listOf("pw"), 2)?.kind)
        assertEquals(InputRequest(InputRequest.Kind.COMMUNICATE, "", "", 0, 3), InputRequest.fromOpenTag("communicatebox", emptyList(), 3))
        // ID の無い inputbox と知らない種類は扱わない
        assertNull(InputRequest.fromOpenTag("inputbox", emptyList(), 4))
        assertNull(InputRequest.fromOpenTag("dateinput", listOf("x"), 5))
    }

    @Test
    fun answersAndCancelsSendSspEvents() = runBlocking {
        val shiori = ScriptedShiori(
            mapOf(
                "OnFirstBoot" to """\0\![open,inputbox,OnInputName,0,ぽの]\e""",
                "OnInputName" to """\0\![open,communicatebox]\e""",
                "OnCommunicateInputCancel" to """\0\![open,inputbox,MyBox,200]\e""",
                "OnUserInputCancel" to """\0\![open,teachbox]\![close,teachbox]\e""",
            ),
        )
        val scope = testScope()
        val controller = testController(tmp.newTestGhost(), shiori, scope)
        suspend fun awaitInput() = withTimeout(5000) { controller.state.first { it.input != null } }.input!!
        try {
            controller.start()

            // 名前の入力。ID が On で始まるので、そのイベントに文字だけを渡す
            val name = awaitInput()
            assertEquals(InputRequest(InputRequest.Kind.INPUT, "OnInputName", "ぽの", 0, name.serial), name)
            controller.onInputSubmit(name.serial, "たろう")
            assertEquals(listOf("たろう"), shiori.awaitEvent("OnInputName"))

            // 話しかける箱を取り消す
            val talk = awaitInput()
            assertEquals(InputRequest.Kind.COMMUNICATE, talk.kind)
            // 古い箱への答えは無視される
            controller.onInputSubmit(name.serial, "古い")
            controller.onInputCancel(talk.serial)
            assertEquals(listOf("", "cancel"), shiori.awaitEvent("OnCommunicateInputCancel"))

            // 時間切れ
            awaitInput()
            assertEquals(listOf("MyBox", "timeout"), shiori.awaitEvent("OnUserInputCancel"))

            // スクリプトの中で開いて閉じた箱は残らない
            delay(300)
            assertNull(controller.state.value.input)
            assertEquals(emptyList<String>(), shiori.events.map { it.first }.filter { it == "OnUserInput" || it == "OnCommunicate" })
        } finally {
            scope.cancel()
        }
    }
}
