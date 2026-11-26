package co.electriccoin.zcash.ui.common.usecase

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import co.electriccoin.zcash.spackle.getSystemService

/**
 * Reads the primary clip as text, for an explicit, user-initiated paste.
 */
class ReadClipboardTextUseCase(
    private val context: Context
) {
    @Suppress("TooGenericExceptionCaught") // A foreground ownership change can deny a clipboard read.
    internal operator fun invoke(): ClipboardTextSample? =
        try {
            context.getSystemService<ClipboardManager>().primaryClip?.readSample(context)
        } catch (_: RuntimeException) {
            null
        }
}

private fun ClipData.readSample(context: Context): ClipboardTextSample? =
    takeIf { itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }?.let {
        ClipboardTextSample(it, plainTextIdentity())
    }

internal class ClipboardTextSample(val text: String, val ownership: ClipboardTextIdentity?)

/** Evidence for deleting a single plain-text item. It is kept in process memory only. */
internal class ClipboardTextIdentity(
    private val timestamp: Long,
    private val label: String?,
    private val text: String,
) {
    fun matches(other: ClipboardTextIdentity): Boolean =
        timestamp == other.timestamp && label == other.label && text == other.text
}

internal fun ClipData.plainTextIdentity(): ClipboardTextIdentity? {
    val description = description
    val plainText = description.mimeTypeCount == 1 && description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
    if (itemCount != 1 || description.timestamp <= 0 || !plainText) return null
    val item = getItemAt(0)
    return (item.text as? String)
        ?.takeIf { item.intent == null && item.uri == null && item.htmlText == null }
        ?.let { ClipboardTextIdentity(description.timestamp, description.label?.toString(), it) }
}
