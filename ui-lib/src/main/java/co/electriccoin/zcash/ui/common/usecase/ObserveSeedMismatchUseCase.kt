package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * Runs [RecoverFromSeedMismatchUseCase] whenever [SynchronizerProvider.isSeedMismatch] flips to `true`.
 * Collects forever and is launched once from the application class, so recovery does not depend on the
 * UI lifecycle. A terminal [SeedMismatchRecoveryException] is routed to the error screen; other failures
 * are logged and the collector keeps running.
 */
class ObserveSeedMismatchUseCase(
    private val synchronizerProvider: SynchronizerProvider,
    private val recoverFromSeedMismatch: RecoverFromSeedMismatchUseCase,
    private val navigateToError: NavigateToErrorUseCase,
    private val launchNavigation: LaunchNavigationRequests,
    private val walletWriteAccess: WalletWriteAccess,
) {
    suspend operator fun invoke() {
        // The SDK clears the flag as it enters the recovery lockout.
        synchronizerProvider.isSeedMismatch
            .collect { if (it) runRecovery() }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runRecovery() {
        val token = walletWriteAccess.token()
        val stillApplies = { walletWriteAccess.permits(token) }
        if (!stillApplies()) return
        try {
            recoverFromSeedMismatch(token)
            launchNavigation.updateSeedRecoveryError(null, stillApplies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SeedMismatchRecoveryException) {
            Twig.error(e) { "Seed-mismatch recovery failed, retries exhausted" }
            if (stillApplies()) {
                val route = navigateToError.stage(ErrorArgs.SeedRecoveryFailed)
                launchNavigation.updateSeedRecoveryError(route, stillApplies)
            }
        } catch (e: Exception) {
            Twig.error(e) { "Seed-mismatch recovery failed" }
        }
    }
}
