package dev.beka.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CalculatorTest {
    private fun calc(expression: String) = Calculator.evaluate(expression)

    @Test
    fun `precedence and parentheses`() {
        assertEquals("14", calc("2 + 3 * 4"))
        assertEquals("20", calc("(2 + 3) * 4"))
        assertEquals("1", calc("10 - 4 - 5"))
        assertEquals("2", calc("16 / 4 / 2"))
        assertEquals("6", calc("((1 + 2)) * (4 - 2)"))
    }

    @Test
    fun `power is right associative`() {
        assertEquals("512", calc("2 ^ 3 ^ 2"))
        assertEquals("1024", calc("2^10"))
        assertEquals("0.5", calc("2 ^ -1"))
        assertEquals("2", calc("4 ^ 0.5"))
    }

    @Test
    fun `unary minus`() {
        assertEquals("-3", calc("-3"))
        assertEquals("5", calc("--5"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("-6", calc("2 * -3"))
        assertEquals("8", calc("5 - -3"))
    }

    @Test
    fun `percent and remainder`() {
        assertEquals("0.5", calc("50%"))
        assertEquals("15", calc("200 * 7.5%"))
        assertEquals("1", calc("10 % 3"))
        assertEquals("2", calc("17 % 5"))
    }

    @Test
    fun `functions`() {
        assertEquals("4", calc("sqrt(16)"))
        assertEquals("1.4142135624", calc("sqrt(2)"))
        assertEquals("3", calc("round(2.5)"))
        assertEquals("-3", calc("round(-2.5)"))
        assertEquals("7", calc("abs(-7)"))
        assertEquals("5", calc("sqrt(9) + abs(-2)"))
    }

    @Test
    fun `decimals and comma decimal`() {
        assertEquals("3.75", calc("1.25 * 3"))
        assertEquals("5", calc("2,5 * 2"))
        assertEquals("0.5", calc(".5"))
        assertEquals("1500.5", calc("1,500.5"))
    }

    @Test
    fun `formatting is clean`() {
        assertEquals("0.3", calc("0.1 + 0.2"))
        assertEquals("0.3333333333", calc("1 / 3"))
        assertEquals("3", calc("6 / 2"))
        assertEquals("0", calc("0.00000000001"))
        assertEquals("1219326311126352690", calc("1234567890 * 987654321"))
        assertEquals("100000", calc("1000 * 100"))
    }

    @Test
    fun `division by zero is an error`() {
        val e = assertFailsWith<IllegalArgumentException> { calc("1 / 0") }
        assertTrue(e.message.orEmpty().contains("division by zero"))
        assertFailsWith<IllegalArgumentException> { calc("5 % 0") }
        assertFailsWith<IllegalArgumentException> { calc("0 ^ -1") }
    }

    @Test
    fun `garbage is an error`() {
        for (bad in listOf("", "   ", "abc", "2 +", "(1 + 2", "1 + 2)", "2 $ 3", "foo(3)", "1..2 + 3", "sqrt(-1)", "sqrt 4")) {
            assertFailsWith<IllegalArgumentException>(bad) { calc(bad) }
        }
    }
}
