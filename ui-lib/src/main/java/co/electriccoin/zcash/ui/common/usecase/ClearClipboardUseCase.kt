package co.electriccoin.zcash.ui.common.usecase

import android.content.ClipboardManager
import android.content.Context
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.spackle.getSystemService

/**
 * Best-effort cleanup of the single sensitive clipboard item that supplied an explicit paste.
 */
class ClearClipboardUseCase(
    private val context: Context
) {
    @Suppress("TooGenericExceptionCaught")
    internal operator fun invoke(ownership: ClipboardTextIdentity) {
        try {
            val clipboard = context.getSystemService<ClipboardManager>()
            val current = clipboard.primaryClip?.plainTextIdentity()
            if (current != null && ownership.matches(current)) {
                clearRecoveryClipboard(clipboard)
            }
        } catch (e: RuntimeException) {
            Twig.warn { "Clearing the clipboard failed: ${e::class.simpleName}" }
        }
    }
}
