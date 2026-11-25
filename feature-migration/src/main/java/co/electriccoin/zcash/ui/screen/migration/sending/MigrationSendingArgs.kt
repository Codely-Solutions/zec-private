package co.electriccoin.zcash.ui.screen.migration.sending

import kotlinx.serialization.Serializable

@Serializable
data class MigrationSendingArgs(
    val accountKeyId: String,
    val retryTransferId: Long = UNSET,
    val retryScheduledHeight: Long = UNSET,
    val retryAnchorBoundaryHeight: Long = UNSET,
) {
    val isRecoveryRetry: Boolean get() = retryTransferId != UNSET

    companion object {
        const val UNSET = -1L
    }
}
