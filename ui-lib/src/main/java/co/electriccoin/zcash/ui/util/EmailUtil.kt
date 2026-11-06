package co.electriccoin.zcash.ui.util

import android.content.Intent

object EmailUtil {
    internal fun formatMessage(
        prefix: String? = null,
        body: String? = null,
        supportInfo: String? = null,
        suffix: String? = null,
    ): String =
        buildString {
            if (!prefix.isNullOrEmpty()) {
                appendLine(prefix)
                appendLine()
            }
            if (!body.isNullOrEmpty()) {
                appendLine(body)
                appendLine()
            }
            if (supportInfo != null) {
                appendLine(supportInfo)
                appendLine()
            }
            if (!suffix.isNullOrEmpty()) {
                appendLine(suffix)
            }
        }

    internal fun newTextShareIntent(
        subject: String,
        messageBody: String
    ): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, messageBody)
        }
}
