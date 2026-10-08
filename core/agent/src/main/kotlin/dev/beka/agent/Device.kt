package dev.beka.agent

/** The screen as the model sees it. [text] lists elements as `[n12] Button "Kirim" (click)` lines. */
class Observation(
    val packageName: String,
    val appLabel: String,
    val text: String,
    val jpeg: ByteArray? = null,
) {
    /** Changes whenever anything visible changes; used to detect actions that did nothing. */
    val fingerprint: Int get() = text.hashCode()

    fun toPrompt(): String = "<screen>\nApp: $appLabel ($packageName)\n$text\n</screen>"
}

/** The phone, as the agent can touch it. Implemented over the accessibility service. */
interface Device {
    /** Reads the current screen and makes its element ids the ones later actions refer to. Null if unreadable. */
    suspend fun observe(screenshot: Boolean): Observation?

    /** The visible label of an element from the latest observation, or null if unknown or unlabeled. */
    fun label(nodeId: String): String?

    /** The package [Action.OpenApp] would launch, or null if no app matches. */
    fun resolveApp(name: String): String?

    /**
     * Performs the action and waits for the screen to settle.
     * @return a short outcome for the model, e.g. "Opened WhatsApp".
     * @throws ActionFailed when the action could not be done.
     */
    suspend fun perform(action: Action): String
}

class ActionFailed(message: String) : Exception(message)
