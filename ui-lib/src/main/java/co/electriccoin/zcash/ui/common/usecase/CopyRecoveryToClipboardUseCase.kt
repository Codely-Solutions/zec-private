package co.electriccoin.zcash.ui.common.usecase

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.SystemClock
import android.widget.Toast
import co.electriccoin.zcash.spackle.AndroidApiVersion
import co.electriccoin.zcash.spackle.getSystemService
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Application-scoped lease: leaving the recovery screen does not cancel clipboard cleanup. */
class CopyRecoveryToClipboardUseCase(
    context: Context,
    applicationStateProvider: ApplicationStateProvider,
    private val copyToClipboard: CopyToClipboardUseCase
) {
    private val applicationContext = context.applicationContext
    private val clipboard = applicationContext.getSystemService<ClipboardManager>()
    private val lease = RecoveryClipboardLease()
    private val revision = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            combine(applicationStateProvider.isInForeground, revision) { foreground, _ -> foreground }
                .collectLatest { foreground ->
                    val expiresAt = lease.expiresAt ?: return@collectLatest
                    if (foreground) {
                        delay((expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        // Focus can arrive after ON_START. A denied/null read retains the lease for the next return.
                        repeat(FOCUS_ATTEMPTS) {
                            if (tryClear()) return@collectLatest
                            delay(FOCUS_RETRY_MS)
                        }
                    }
                }
        }
    }

    operator fun invoke(value: String) {
        val owner = UUID.randomUUID().toString()
        val copied = copyRecoverySafely(
            write = {
                copyToClipboard.copyWithMetadata(
                    value,
                    PersistableBundle().apply { putString(OWNER_KEY, owner) }
                )
            },
            onDenied = {
                Toast.makeText(applicationContext, R.string.recovery_copy_failed, Toast.LENGTH_SHORT).show()
            }
        )
        if (!copied) return
        lease.start(owner, SystemClock.elapsedRealtime())
        revision.value += 1
    }

    @Suppress("TooGenericExceptionCaught")
    private fun tryClear(): Boolean =
        try {
            val description = clipboard.primaryClipDescription
            // Null can mean Android withheld access. Never infer ownership from the copied text.
            if (description == null) {
                false
            } else {
                lease.expire(SystemClock.elapsedRealtime(), description.extras?.getString(OWNER_KEY).orEmpty()) {
                    clearRecoveryClipboard(clipboard)
                }
            }
        } catch (_: RuntimeException) {
            false
        }

    private companion object {
        const val OWNER_KEY = "co.electriccoin.zcash.recovery.clipboard.OWNER"
        const val FOCUS_ATTEMPTS = 5
        const val FOCUS_RETRY_MS = 1_000L
    }
}

/** Holds only ownership metadata; clipboard text is never retained or read back. */
internal class RecoveryClipboardLease {
    var expiresAt: Long? = null
        private set
    private var owner: String? = null

    fun start(token: String, now: Long) {
        owner = token
        expiresAt = now + LEASE_MS
    }

    fun expire(now: Long, observedOwner: String?, clear: () -> Unit): Boolean {
        val expiry = expiresAt ?: return true
        if (now < expiry || observedOwner == null) return false
        if (owner == observedOwner) clear()
        owner = null
        expiresAt = null
        return true
    }

    private companion object {
        const val LEASE_MS = 60_000L
    }
}

internal fun clearRecoveryClipboard(clipboard: ClipboardManager) {
    if (AndroidApiVersion.isAtLeastP) {
        clipboard.clearPrimaryClip()
    } else {
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}

internal fun copyRecoverySafely(write: () -> Unit, onDenied: () -> Unit): Boolean =
    try {
        write()
        true
    } catch (_: SecurityException) {
        onDenied()
        false
    }
