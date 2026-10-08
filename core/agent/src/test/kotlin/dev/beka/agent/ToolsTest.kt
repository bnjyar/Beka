package dev.beka.agent

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolsTest {
    @Test
    fun `ids are normalized`() {
        assertEquals(Action.Tap("n12"), Tools.parse("tap", """{"id":"12"}"""))
        assertEquals(Action.Tap("n12"), Tools.parse("tap", """{"id":"[n12]"}"""))
        assertEquals(Action.Tap("n12"), Tools.parse("tap", """{"id":"n12"}"""))
        assertEquals(Action.Tap("n12"), Tools.parse("tap", """{"id":"N12"}"""))
        assertEquals(Action.Tap("n12"), Tools.parse("tap", """{"id":12}"""))
        assertEquals("n7", Tools.normalizeId(" [7] "))
    }

    @Test
    fun `bad ids are rejected`() {
        assertFailsWith<IllegalArgumentException> { Tools.normalizeId("abc") }
        assertFailsWith<IllegalArgumentException> { Tools.normalizeId("") }
        assertFailsWith<IllegalArgumentException> { Tools.parse("tap", """{"id":"nx"}""") }
    }

    @Test
    fun `tap needs an id`() {
        val e = assertFailsWith<IllegalArgumentException> { Tools.parse("tap", "{}") }
        assertTrue(e.message.orEmpty().contains("id"))
    }

    @Test
    fun `long_press is a long tap`() {
        assertEquals(Action.Tap("n3", long = true), Tools.parse("long_press", """{"id":"n3"}"""))
    }

    @Test
    fun `read_screen parses screenshot flag`() {
        assertEquals(Action.ReadScreen(false), Tools.parse("read_screen", "{}"))
        assertEquals(Action.ReadScreen(true), Tools.parse("read_screen", """{"screenshot":true}"""))
    }

    @Test
    fun `type parses text and press_enter`() {
        assertEquals(
            Action.Type("n5", "halo", pressEnter = true),
            Tools.parse("type", """{"id":"5","text":"halo","press_enter":true}"""),
        )
        assertEquals(Action.Type("n5", "halo", false), Tools.parse("type", """{"id":"n5","text":"halo"}"""))
        assertFailsWith<IllegalArgumentException> { Tools.parse("type", """{"id":"n5"}""") }
    }

    @Test
    fun `scroll parses direction and optional id`() {
        assertEquals(Action.Scroll(Direction.DOWN), Tools.parse("scroll", """{"direction":"down"}"""))
        assertEquals(Action.Scroll(Direction.UP), Tools.parse("scroll", """{"direction":"UP"}"""))
        assertEquals(Action.Scroll(Direction.LEFT), Tools.parse("scroll", """{"direction":"left"}"""))
        assertEquals(
            Action.Scroll(Direction.RIGHT, "n5"),
            Tools.parse("scroll", """{"direction":"right","id":"[n5]"}"""),
        )
        assertEquals(Action.Scroll(Direction.DOWN, null), Tools.parse("scroll", """{"direction":"down","id":""}"""))
    }

    @Test
    fun `invalid or missing scroll direction throws`() {
        assertFailsWith<IllegalArgumentException> { Tools.parse("scroll", """{"direction":"sideways"}""") }
        assertFailsWith<IllegalArgumentException> { Tools.parse("scroll", "{}") }
    }

    @Test
    fun `global tools accept blank args`() {
        assertEquals(Action.Global(GlobalKind.BACK), Tools.parse("back", ""))
        assertEquals(Action.Global(GlobalKind.BACK), Tools.parse("back", "   "))
        assertEquals(Action.Global(GlobalKind.BACK), Tools.parse("back", "{}"))
        assertEquals(Action.Global(GlobalKind.HOME), Tools.parse("home", "{}"))
        assertEquals(Action.Global(GlobalKind.RECENTS), Tools.parse("recents", "{}"))
        assertEquals(Action.Global(GlobalKind.NOTIFICATIONS), Tools.parse("notifications", "{}"))
    }

    @Test
    fun `open_app and open_link need their argument`() {
        assertEquals(Action.OpenApp("WhatsApp"), Tools.parse("open_app", """{"name":"WhatsApp"}"""))
        assertEquals(Action.OpenLink("https://example.com"), Tools.parse("open_link", """{"uri":"https://example.com"}"""))
        assertFailsWith<IllegalArgumentException> { Tools.parse("open_app", "{}") }
        assertFailsWith<IllegalArgumentException> { Tools.parse("open_app", """{"name":"  "}""") }
        assertFailsWith<IllegalArgumentException> { Tools.parse("open_link", "{}") }
    }

    @Test
    fun `unknown tool throws`() {
        val e = assertFailsWith<IllegalArgumentException> { Tools.parse("fly", "{}") }
        assertTrue(e.message.orEmpty().contains("fly"))
    }

    @Test
    fun `non object arguments throw`() {
        assertFailsWith<IllegalArgumentException> { Tools.parse("tap", "not json") }
        assertFailsWith<IllegalArgumentException> { Tools.parse("tap", "[1,2]") }
    }

    @Test
    fun `every spec parses by name and names are unique`() {
        val names = Tools.specs.map { it.name }
        assertEquals(names.size, names.toSet().size)
        val expected = setOf(
            "read_screen", "tap", "long_press", "type", "scroll", "back", "home",
            "recents", "notifications", "open_app", "open_link",
        )
        assertEquals(expected, names.toSet())
    }

    @Test
    fun `every spec has an object schema`() {
        for (spec in Tools.specs) {
            assertEquals("object", spec.parameters["type"]?.jsonPrimitive?.content, spec.name)
            assertTrue(spec.description.isNotBlank(), spec.name)
        }
    }
}
