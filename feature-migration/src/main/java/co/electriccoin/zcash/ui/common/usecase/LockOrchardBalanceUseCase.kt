package co.electriccoin.zcash.ui.common.usecase

/**
 * Marks the dust balance left behind in Orchard after migration as unspendable. Thin wrapper
 * around [OrchardMigrationSdk.lockRemainingOrchardBalance], now backed by real Rust-side note
 * locking — see its kdoc for details.
 */
class LockOrchardBalanceUseCase(
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
) {
    suspend operator fun invoke(accountKeyId: String, canLock: suspend () -> Boolean): Boolean {
        val sdk = checkNotNull(getOrchardMigrationSdk(accountKeyId)) { "Migration account is unavailable" }
        if (!canLock()) return false
        sdk.lockRemainingOrchardBalance()
        return true
    }
}
