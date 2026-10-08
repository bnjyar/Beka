package dev.beka.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Flattens an accessibility tree into compact, indented lines like
 * `[n4] Button "Kirim" (click)` so the model can read the screen cheaply.
 */
object ScreenSerializer {
    private const val MAX_NODES = 400
    private const val MAX_CHARS = 16_000
    private const val MAX_LABEL = 200
    private val SENSITIVE_ID = Regex("otp|pin|cvv|cvc|passcode|password", RegexOption.IGNORE_CASE)

    data class Result(val text: String, val count: Int)

    fun serialize(root: AccessibilityNodeInfo): Result {
        val out = StringBuilder()
        var count = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || out.length >= MAX_CHARS) return
            if (!node.isVisibleToUser) return
            var childDepth = depth
            describe(node)?.let { line ->
                count++
                repeat(depth.coerceAtMost(8)) { out.append("  ") }
                out.append("[n").append(count).append("] ").append(line).append('\n')
                childDepth = depth + 1
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, childDepth) }
            }
        }

        visit(root, 0)
        if (count >= MAX_NODES || out.length >= MAX_CHARS) out.append("… (truncated)\n")
        return Result(out.toString(), count)
    }

    private fun describe(node: AccessibilityNodeInfo): String? {
        val sensitive = node.isPassword || node.viewIdResourceName?.let(SENSITIVE_ID::containsMatchIn) == true
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = node.hintText?.toString()?.trim().orEmpty()

        val labels = buildList {
            if (text.isNotEmpty()) add(if (sensitive) "[REDACTED]" else quote(text))
            if (desc.isNotEmpty() && desc != text) add("desc=" + quote(desc))
            if (hint.isNotEmpty() && hint != text) add("hint=" + quote(hint))
        }
        val actions = buildList {
            if (node.isClickable) add("click")
            if (node.isLongClickable) add("long-click")
            if (node.isEditable) add("edit")
            if (node.isScrollable) add("scroll")
            if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
            if (node.isSelected) add("selected")
        }
        if (labels.isEmpty() && actions.isEmpty()) return null

        return buildString {
            append(role(node.className?.toString()))
            labels.forEach { append(' ').append(it) }
            if (actions.isNotEmpty()) append(" (").append(actions.joinToString(",")).append(')')
        }
    }

    private fun quote(s: String): String {
        val clipped = if (s.length > MAX_LABEL) s.take(MAX_LABEL) + "…" else s
        return "\"" + clipped.replace('\n', ' ') + "\""
    }

    private fun role(className: String?): String {
        val simple = className?.substringAfterLast('.') ?: return "View"
        return when {
            "EditText" in simple -> "Input"
            "Button" in simple -> "Button"
            "CheckBox" in simple || "Switch" in simple -> "Toggle"
            "Image" in simple -> "Image"
            "RecyclerView" in simple || "ListView" in simple || "ScrollView" in simple -> "List"
            "TextView" in simple -> "Text"
            "WebView" in simple -> "Web"
            else -> simple
        }
    }
}
