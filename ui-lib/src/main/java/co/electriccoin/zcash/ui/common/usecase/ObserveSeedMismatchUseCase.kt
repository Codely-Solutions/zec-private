package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorRoute
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.error.SeedRecoveryPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Runs [RecoverFromSeedMismatchUseCase] whenever [SynchronizerProvider.isSeedMismatch] flips to `true`.
 * Collects forever and is launched once from the application class, so recovery does not depend on the
 * UI lifecycle. A delayed or failed recovery offers help for the initiating wallet.
 */
class ObserveSeedMismatchUseCase(
    private val synchronizerProvider: SynchronizerProvider,
    private val recoverFromSeedMismatch: RecoverFromSeedMismatchUseCase,
    private val navigateToError: NavigateToErrorUseCase,
    private val launchNavigation: LaunchNavigationRequests,
    private val walletWriteAccess: WalletWriteAccess,
) {
    suspend operator fun invoke(): Unit = coroutineScope {
        val signals = Channel<Long>(Channel.CONFLATED)
        launch {
            // Withdrawing the SDK input clears the old coordinator's signal during a recovery.
            synchronizerProvider.isSeedMismatch.collect { if (it) signals.trySend(walletWriteAccess.token()) }
        }
        for (token in signals) {
            if (synchronizerProvider.isSeedMismatch.value) runRecovery(token)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runRecovery(token: Long) {
        val stillApplies = { walletWriteAccess.permits(token) }
        if (!stillApplies()) return
        val phase = MutableStateFlow(SeedRecoveryPhase.WAITING)
        var noticeRoute: ErrorRoute? = null
        val offerWaiting = {
            if (stillApplies()) {
                val route = navigateToError.stage(ErrorArgs.SeedRecoveryNotice(phase))
                noticeRoute = route
                launchNavigation.updateSeedRecoveryError(route, stillApplies)
            }
        }
        try {
            recoverFromSeedMismatch(token, offerWaiting)
            finishNotice(phase, stillApplies)
        } catch (e: CancellationException) {
            finishNotice(phase, stillApplies)
            throw e
        } catch (e: Exception) {
            Twig.error { "Seed-mismatch recovery failed (${e.javaClass.simpleName})" }
            phase.value = SeedRecoveryPhase.FAILED
            if (noticeRoute?.let(navigateToError::argsFor) == null && stillApplies()) {
                val failure = navigateToError.stage(ErrorArgs.SeedRecoveryFailed)
                launchNavigation.updateSeedRecoveryError(failure, stillApplies)
            }
        }
    }

    private fun finishNotice(phase: MutableStateFlow<SeedRecoveryPhase>, stillApplies: () -> Boolean) {
        phase.value = SeedRecoveryPhase.FINISHED
        launchNavigation.updateSeedRecoveryError(null, stillApplies)
    }
}
