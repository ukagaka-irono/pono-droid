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
        // ipinput は数が無ければ 0.0.0.0 から
        assertEquals(listOf(0, 0, 0, 0), InputRequest.fromOpenTag("ipinput", listOf("x"), 5)!!.values)
        // 日付・時刻・スライダー・複数行。--option は読み飛ばす
        assertEquals(listOf(2012, 12, 23), InputRequest.fromOpenTag("dateinput", listOf("D", "30000", "2012", "12", "23"), 6)?.values)
        assertEquals(listOf(23, 45, 12), InputRequest.fromOpenTag("timeinput", listOf("T", "0", "23", "45", "12", "--option=x"), 7)?.values)
        assertEquals(listOf(50, 0, 100), InputRequest.fromOpenTag("sliderinput", listOf("S", "0", "50", "0", "100"), 8)?.values)
        assertEquals(InputRequest(InputRequest.Kind.TEXTAREA, "A", "1行目\n2行目", 0, 9), InputRequest.fromOpenTag("textareabox", listOf("A", "0", "1行目\u00012行目"), 9))
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

    @Test
    fun readsInputBoxOptions() {
        // --timeout= / --text= は位置引数の代わり。数を入れる箱の --text= はカンマ区切り
        val box = InputRequest.fromOpenTag("inputbox", listOf("インプットテスト", "--timeout=10000", "--text=テスト入力"), 1)!!
        assertEquals("テスト入力", box.text)
        assertEquals(10000L, box.timeoutMs)
        assertEquals(listOf(50, 0, 100), InputRequest.fromOpenTag("sliderinput", listOf("S", "--timeout=30000", "--text=50,0,100"), 2)?.values)
        assertEquals(listOf(2012, 12, 23), InputRequest.fromOpenTag("dateinput", listOf("D", "--text=2012,12,23"), 3)?.values)
        assertEquals(listOf(1, 2, 3), InputRequest.fromOpenTag("timeinput", listOf("T", "--text=1,2,3"), 4)?.values)
        assertEquals(listOf(192, 168, 0, 1), InputRequest.fromOpenTag("ipinput", listOf("I", "--text=192.168.0.1"), 5)?.values)
        assertEquals("テスト", InputRequest.fromOpenTag("communicatebox", listOf("--text=テスト"), 6)?.text)
        assertEquals("テスト", InputRequest.fromOpenTag("communicatebox", listOf("テスト"), 7)?.text)
        // ほかのオプション
        val r = InputRequest.fromOpenTag(
            "textareabox", listOf("OnDiary", "--limit=4", "--text=12345", "--balloon=7", "--option=noclose", "--option=noclear", "--reference=a", "--reference=b"), 8,
        )!!
        assertEquals("1234", r.text)
        assertEquals(4, r.limit)
        assertEquals(7, r.balloonId)
        assertEquals(true, r.noClose && r.noClear)
        assertEquals(listOf("a", "b"), r.references)
        // 数を入れる箱に案内文は出さない
        assertNull(InputRequest.fromOpenTag("sliderinput", listOf("S", "--placeholder=x"), 9)?.placeholder)
    }

    @Test
    fun referencesNoCloseAndTimeoutFallback() = runBlocking {
        val shiori = ScriptedShiori(
            mapOf(
                "OnFirstBoot" to """\0\![open,inputbox,Box,0,--reference=r1,--reference=r2,--option=noclose,--option=noclear]\e""",
                "OnSlide" to """\0\![open,sliderinput,OnS,0,5,1,9,--reference=x]\e""",
                "OnLate" to """\0\![open,inputbox,Late,100]\e""",
                "OnBar" to """\0\![open,addressbar]\e""",
            ),
        )
        val scope = testScope()
        val controller = testController(tmp.newTestGhost(), shiori, scope)
        suspend fun awaitInput(after: Int = 0) = withTimeout(5000) { controller.state.first { (it.input?.serial ?: 0) > after } }.input!!
        try {
            controller.start()
            // OnUserInput の Reference2 は補足（空）、Reference3 から --reference=
            val box = awaitInput()
            controller.onInputSubmit(box.serial, "こんにちは")
            assertEquals(listOf("Box", "こんにちは", "", "r1", "r2"), shiori.awaitEvent("OnUserInput"))
            // noclose なら出し続け、noclear なら打った文字を残す
            val again = awaitInput(box.serial)
            assertEquals("Box", again.id)
            assertEquals("こんにちは", again.text)
            controller.onInputCancel(again.serial)

            // On で始まる ID は Reference1 が補足（スライダーの最小,最大）、その後ろが --reference=
            controller.raise("OnSlide", emptyList())
            val slider = awaitInput(again.serial)
            controller.onInputSubmit(slider.serial, "7")
            assertEquals(listOf("7", "1,9", "x"), shiori.awaitEvent("OnS"))

            // 時間切れで OnUserInputCancel に答えが無ければ、"timeout" の OnUserInput
            controller.raise("OnLate", emptyList())
            withTimeout(5000) {
                while (shiori.events.lastOrNull { it.first == "OnUserInputCancel" }?.second != listOf("Late", "timeout")) delay(20)
            }
            withTimeout(5000) {
                while (shiori.events.lastOrNull { it.first == "OnUserInput" }?.second != listOf("Late", "timeout")) delay(20)
            }

            // アドレスバーで打った文字は、共有で渡したのと同じ（URL でなければ OnTextDrop）。取り消してもゴーストには知らせない
            controller.raise("OnBar", emptyList())
            val bar = awaitInput(slider.serial)
            assertEquals("URL", bar.placeholder)
            controller.onInputSubmit(bar.serial, "メモ")
            assertEquals(listOf("メモ", "0"), shiori.awaitEvent("OnTextDrop"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun autocompleteSuggestionsComeWithTheBox() = runBlocking {
        val shiori = ScriptedShiori(
            mapOf(
                "OnFirstBoot" to """\0\![open,inputbox,OnName,0]\e""",
                "inputbox.autocomplete" to "りんご\u0001りす\u0001 \u0001りんご",
                "OnTeachIt" to """\0\![open,teachbox]\e""",
            ),
        )
        val scope = testScope()
        val controller = testController(tmp.newTestGhost(), shiori, scope)
        try {
            controller.start()
            // 空と重なりは除く
            val box = withTimeout(5000) { controller.state.first { it.input?.suggestions?.isNotEmpty() == true } }.input!!
            assertEquals(listOf("りんご", "りす"), box.suggestions)
            assertEquals(listOf("inputbox", "OnName"), shiori.awaitEvent("inputbox.autocomplete"))
            controller.raise("OnTeachIt", emptyList())
            withTimeout(5000) { while (shiori.count("inputbox.autocomplete") < 2) delay(10) }
            assertEquals(listOf("teachbox"), shiori.awaitEvent("inputbox.autocomplete"))
        } finally {
            scope.cancel()
        }
    }
}
