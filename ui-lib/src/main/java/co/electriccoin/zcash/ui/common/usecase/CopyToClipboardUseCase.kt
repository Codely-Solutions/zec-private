package co.electriccoin.zcash.ui.common.usecase

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.UserManager
import android.widget.Toast
import co.electriccoin.zcash.spackle.AndroidApiVersion
import co.electriccoin.zcash.spackle.getSystemService
import co.electriccoin.zcash.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class CopyToClipboardUseCase(
    private val context: Context
) {
    private val extraIsSensitive: String
        get() =
            if (AndroidApiVersion.isAtLeastTiramisu) {
                ClipDescription.EXTRA_IS_SENSITIVE
            } else {
                "android.content.extra.IS_SENSITIVE"
            }

    // The system clipboard confirmation chip (API 33+) is only shown to the admin/primary user (API 34+).
    // Non-admin profiles never receive it — this is an upstream AOSP limitation across all OEMs.
    private val needsManualClipboardToast: Boolean
        get() =
            !AndroidApiVersion.isAtLeastTiramisu ||
                !AndroidApiVersion.isAtLeastUpsideDownCake ||
                !context.getSystemService<UserManager>().isAdminUser

    operator fun invoke(value: String) = copyWithMetadata(value, PersistableBundle())

    internal fun copyWithMetadata(value: String, metadata: PersistableBundle) {
        val clipboardManager = context.getSystemService<ClipboardManager>()
        val data =
            ClipData
                .newPlainText("", value)
                .apply {
                    description.extras = PersistableBundle(metadata).apply { putBoolean(extraIsSensitive, true) }
                }
        if (AndroidApiVersion.isAtLeastTiramisu) {
            clipboardManager.setPrimaryClip(data)
        } else {
            // Blocking call is fine here, as we just moved to the IO thread to satisfy StrictMode on an older API
            runBlocking(Dispatchers.IO) {
                clipboardManager.setPrimaryClip(data)
            }
        }
        if (needsManualClipboardToast) {
            Toast.makeText(context, R.string.general_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
    }
}
