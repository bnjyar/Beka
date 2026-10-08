package dev.beka.agent

enum class Risk { SAFE, MEDIUM, HIGH }

object RiskPolicy {
    /** Indonesian roots match with any suffix ("kirimkan", "bayarnya"); English words match whole. */
    private val RISKY_ID = Regex(
        """\b(kirim|bayar|hapus|beli|transfer|konfirmasi|pesan sekarang|unggah|bagikan|keluar|copot|tarik|langganan|setuju|lanjutkan pembayaran|top ?up)\w*""",
        RegexOption.IGNORE_CASE,
    )
    private val RISKY_EN = Regex(
        """\b(send|pay|payment|delete|remove|buy|checkout|check out|confirm|place order|order now|post|publish|upload|share|submit|sign out|log ?out|uninstall|withdraw|subscribe|accept)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Longer labels are content (a chat row, a message), not a command button. */
    private const val MAX_BUTTON_LABEL = 40

    fun isRiskyLabel(label: String?): Boolean {
        val text = label?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_BUTTON_LABEL) return false
        return RISKY_ID.containsMatchIn(text) || RISKY_EN.containsMatchIn(text)
    }

    fun classify(action: Action, targetLabel: String?): Risk = when (action) {
        is Action.Tap -> if (isRiskyLabel(targetLabel)) Risk.HIGH else Risk.MEDIUM
        is Action.Type, is Action.OpenLink -> Risk.MEDIUM
        is Action.Scroll, is Action.Global, is Action.OpenApp, is Action.ReadScreen, is Action.Media -> Risk.SAFE
    }
}

/** Apps the agent must not act in: banking, e-wallets, password managers and authenticators. */
object Blocklist {
    private val PACKAGES = setOf(
        "com.bca", "com.bca.mybca.omni.android", "com.bcadigital.blu", "id.bmri.livin", "id.co.bri.brimo",
        "src.com.bni", "id.co.bni.wondr", "com.jago.digitalBanking", "id.co.bankbkemobile.digitalbank",
        "id.dana", "ovo.id", "com.gojek.gopay", "com.shopeepay.id", "com.telkom.mwallet",
        "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.lastpass.lpandroid", "com.dashlane",
        "com.google.android.apps.authenticator2", "com.authy.authy", "com.azure.authenticator",
    )
    private val KEYWORDS = Regex("""bank|wallet|bitwarden|password|authenticator|keepass""", RegexOption.IGNORE_CASE)

    fun isBlocked(packageName: String?): Boolean {
        val pkg = packageName ?: return false
        return pkg in PACKAGES || KEYWORDS.containsMatchIn(pkg)
    }
}
