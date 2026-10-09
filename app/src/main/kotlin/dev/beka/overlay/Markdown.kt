package dev.beka.overlay

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * The little Markdown models write in chat replies, rendered instead of shown raw: **bold**,
 * *italic* / _italic_, `code`, "#" headings (as bold lines) and "-"/"*" bullets (as "•").
 * Anything else, including a marker still open mid-stream, stays as typed.
 */
fun markdown(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        val heading = HEADING.matchEntire(raw)
        val bullet = BULLET.matchEntire(raw)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(heading.groupValues[1], codeBackground) }
            bullet != null -> {
                append(bullet.groupValues[1])
                append("• ")
                inline(bullet.groupValues[2], codeBackground)
            }
            else -> inline(raw, codeBackground)
        }
    }
}

private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*)$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val INLINE = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""")

private fun AnnotatedString.Builder.inline(line: String, codeBackground: Color) {
    var at = 0
    for (m in INLINE.findAll(line)) {
        append(line, at, m.range.first)
        val (bold, bold2, code, italic, italic2) = m.destructured
        when {
            bold.isNotEmpty() || bold2.isNotEmpty() ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.ifEmpty { bold2 }) }
            code.isNotEmpty() ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(code) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic.ifEmpty { italic2 }) }
        }
        at = m.range.last + 1
    }
    append(line, at, line.length)
}
