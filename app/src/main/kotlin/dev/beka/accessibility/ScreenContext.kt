package dev.beka.accessibility

/** What the assistant knows about the screen at one moment. */
data class ScreenContext(
    val packageName: String,
    val appLabel: String,
    val tree: String,
    val nodeCount: Int,
    val capturedAt: Long,
    val screenshotJpeg: ByteArray? = null,
) {
    fun toPrompt(): String = buildString {
        append("App: ").append(appLabel).append(" (").append(packageName).append(")\n")
        append("Visible elements (").append(nodeCount).append("):\n")
        append(tree.ifBlank { "(no readable elements — the app may draw its own UI; rely on the screenshot if present)" })
    }
}
