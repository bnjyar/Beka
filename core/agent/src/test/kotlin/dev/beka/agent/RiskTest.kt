package dev.beka.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RiskTest {
    private fun tap(label: String?) = RiskPolicy.classify(Action.Tap("n1"), label)

    @Test
    fun `risky tap labels are high`() {
        for (label in listOf("Kirim", "Kirimkan", "Send", "Bayar Sekarang", "Hapus")) {
            assertEquals(Risk.HIGH, tap(label), label)
        }
    }

    @Test
    fun `ordinary tap labels are medium`() {
        assertEquals(Risk.MEDIUM, tap("Cari"))
        assertEquals(Risk.MEDIUM, tap("Chat"))
        assertEquals(Risk.MEDIUM, tap(null))
        assertEquals(Risk.MEDIUM, tap(""))
    }

    @Test
    fun `long labels are content not buttons`() {
        val label = "kirim " + "a".repeat(54)
        assertEquals(60, label.length)
        assertEquals(Risk.MEDIUM, tap(label))
    }

    @Test
    fun `long press follows the same label rule`() {
        assertEquals(Risk.HIGH, RiskPolicy.classify(Action.Tap("n1", long = true), "Hapus"))
    }

    @Test
    fun `other actions have fixed risk`() {
        assertEquals(Risk.MEDIUM, RiskPolicy.classify(Action.Type("n1", "kirim"), "Kirim"))
        assertEquals(Risk.MEDIUM, RiskPolicy.classify(Action.OpenLink("https://example.com"), null))
        assertEquals(Risk.SAFE, RiskPolicy.classify(Action.Scroll(Direction.DOWN), null))
        assertEquals(Risk.SAFE, RiskPolicy.classify(Action.Global(GlobalKind.BACK), null))
        assertEquals(Risk.SAFE, RiskPolicy.classify(Action.OpenApp("WhatsApp"), null))
        assertEquals(Risk.SAFE, RiskPolicy.classify(Action.ReadScreen(), null))
    }

    @Test
    fun `blocklist blocks banking wallet and password apps`() {
        assertTrue(Blocklist.isBlocked("id.dana"))
        assertTrue(Blocklist.isBlocked("com.bca"))
        assertTrue(Blocklist.isBlocked("com.example.mybank"))
    }

    @Test
    fun `blocklist allows ordinary apps`() {
        assertFalse(Blocklist.isBlocked("com.whatsapp"))
        assertFalse(Blocklist.isBlocked(null))
    }

    @Test
    fun `tapping a text field is never high risk whatever its label`() {
        assertEquals(Risk.MEDIUM, RiskPolicy.classify(Action.Tap("n1"), "Kirim pesan ke Math", editable = true))
    }

    @Test
    fun `unlabeled tap right after typing is high risk`() {
        assertEquals(Risk.HIGH, RiskPolicy.classify(Action.Tap("n1"), null, afterTyping = true))
        assertEquals(Risk.MEDIUM, RiskPolicy.classify(Action.Tap("n1"), "Cari", afterTyping = true))
        assertEquals(Risk.MEDIUM, RiskPolicy.classify(Action.Tap("n1"), null))
    }
}
