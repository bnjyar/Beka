package dev.beka.agent

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Arithmetic for the `calculate` tool: + - * / ^ %, parentheses, unary minus, decimals and the
 * functions sqrt, round, abs (plus the constant pi). `^` is right-associative and binds tighter
 * than unary minus (-2^2 = -4). `%` is a percentage after a number ("50%" = 0.5) and a remainder
 * between two operands ("10 % 3" = 1). A comma is the decimal separator when the expression has
 * no dot ("2,5 * 2"); otherwise commas are dropped as thousands separators.
 */
object Calculator {
    private val MC = MathContext.DECIMAL128
    private const val MAX_DECIMALS = 10
    private const val MAX_INT_EXPONENT = 10_000

    /** @throws IllegalArgumentException with a message for the model when the expression is not valid. */
    fun evaluate(expression: String): String {
        val cleaned = normalize(expression)
        require(cleaned.isNotBlank()) { "Empty expression" }
        val value = try {
            Parser(cleaned).parse()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("Result is undefined or too large: ${e.message}")
        }
        return format(value)
    }

    private fun normalize(expression: String): String {
        val s = expression.trim()
            .replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
        return if ('.' in s) s.replace(",", "") else s.replace(',', '.')
    }

    fun format(value: BigDecimal): String {
        val rounded = value.setScale(MAX_DECIMALS, RoundingMode.HALF_UP)
        if (rounded.signum() == 0) return "0"
        return rounded.stripTrailingZeros().toPlainString()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): BigDecimal {
            val value = expr()
            skipSpaces()
            if (pos < s.length) fail("Unexpected '${s[pos]}' at position ${pos + 1}")
            return value
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("Cannot calculate \"$s\": $message")

        private fun skipSpaces() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipSpaces()
            return s.getOrNull(pos)
        }

        private fun startsOperand(c: Char?): Boolean = c != null && (c.isDigit() || c == '.' || c == '(' || c.isLetter())

        private fun expr(): BigDecimal {
            var left = term()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; left = left.add(term(), MC) }
                    '-' -> { pos++; left = left.subtract(term(), MC) }
                    else -> return left
                }
            }
        }

        private fun term(): BigDecimal {
            var left = unary()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; left = left.multiply(unary(), MC) }
                    '/' -> {
                        pos++
                        val right = unary()
                        if (right.signum() == 0) fail("division by zero")
                        left = left.divide(right, MC)
                    }
                    '%' -> {
                        pos++
                        val right = unary()
                        if (right.signum() == 0) fail("division by zero")
                        left = left.remainder(right, MC)
                    }
                    else -> return left
                }
            }
        }

        private fun unary(): BigDecimal = when (peek()) {
            '-' -> { pos++; unary().negate() }
            '+' -> { pos++; unary() }
            else -> power()
        }

        private fun power(): BigDecimal {
            val base = postfix()
            if (peek() != '^') return base
            pos++
            return pow(base, unary())
        }

        /** "50%" is 0.5, but "10 % 3" is a remainder: a percent sign followed by an operand is left to [term]. */
        private fun postfix(): BigDecimal {
            var value = atom()
            while (peek() == '%') {
                val save = pos
                pos++
                if (startsOperand(peek())) { pos = save; break }
                value = value.divide(BigDecimal(100), MC)
            }
            return value
        }

        private fun pow(base: BigDecimal, exp: BigDecimal): BigDecimal {
            val asInt = runCatching { exp.intValueExact() }.getOrNull()
            if (asInt != null) {
                if (kotlin.math.abs(asInt) > MAX_INT_EXPONENT) fail("exponent too large")
                if (asInt >= 0) return base.pow(asInt, MC)
                if (base.signum() == 0) fail("division by zero")
                return BigDecimal.ONE.divide(base.pow(-asInt, MC), MC)
            }
            if (base.signum() < 0) fail("a negative number cannot have a fractional power")
            if (base.signum() == 0) return if (exp.signum() > 0) BigDecimal.ZERO else fail("division by zero")
            val result = Math.pow(base.toDouble(), exp.toDouble())
            if (result.isNaN() || result.isInfinite()) fail("result is too large")
            return BigDecimal(result, MC)
        }

        private fun atom(): BigDecimal {
            val c = peek() ?: fail("expression ends unexpectedly")
            return when {
                c == '(' -> {
                    pos++
                    val value = expr()
                    if (peek() != ')') fail("missing closing parenthesis")
                    pos++
                    value
                }
                c.isDigit() || c == '.' -> number()
                c.isLetter() -> named()
                else -> fail("unexpected '$c' at position ${pos + 1}")
            }
        }

        private fun number(): BigDecimal {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            return runCatching { BigDecimal(s.substring(start, pos)) }
                .getOrElse { fail("\"${s.substring(start, pos)}\" is not a number") }
        }

        private fun named(): BigDecimal {
            val start = pos
            while (pos < s.length && s[pos].isLetter()) pos++
            val name = s.substring(start, pos).lowercase()
            if (name == "pi") return BigDecimal("3.14159265358979323846")
            if (peek() != '(') fail("unknown name \"$name\" (functions: sqrt, round, abs)")
            pos++
            val arg = expr()
            if (peek() != ')') fail("missing closing parenthesis after $name(")
            pos++
            return when (name) {
                "sqrt" -> {
                    if (arg.signum() < 0) fail("square root of a negative number")
                    // BigDecimal.sqrt needs Android 13; a double is precise enough for 10 shown decimals.
                    BigDecimal.valueOf(Math.sqrt(arg.toDouble()))
                }
                "abs" -> arg.abs()
                "round" -> arg.setScale(0, RoundingMode.HALF_UP)
                else -> fail("unknown function \"$name\" (functions: sqrt, round, abs)")
            }
        }
    }
}
