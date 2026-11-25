package co.electriccoin.zcash.ui.screen.migration.torfailure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MigrationTorFailureVM(
    private val navigationRouter: NavigationRouter,
    private val args: MigrationTorFailureArgs,
    private val pendingMigrationTorFailureDecisionRepository: PendingMigrationTorFailureDecisionRepository,
    private val isMigrationTorEnabledStorageProvider: IsMigrationTorEnabledStorageProvider,
) : ViewModel() {
    private var continuing: Job? = null

    val state: StateFlow<MigrationTorFailureState?> =
        flowOf(
            MigrationTorFailureState(
                onContinueWithoutTor = ::onContinueWithoutTor,
                onTryAgain = ::onTryAgain,
                onBack = ::onBack,
            )
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null,
        )

    @Suppress("TooGenericExceptionCaught") // Keep the choice available after an unsuccessful preference write.
    private fun onContinueWithoutTor() {
        if (continuing?.isActive == true) return
        continuing = viewModelScope.launch {
            try {
                isMigrationTorEnabledStorageProvider.store(args.accountKeyId, false)
                pendingMigrationTorFailureDecisionRepository.set(args.accountKeyId, useTor = false)
                navigationRouter.back()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                migrationLog("Migration Tor choice unavailable (${e.javaClass.simpleName}).")
            }
        }
    }

    // "Try again" is NOT an in-place Tor retry (that would loop forever on a persistent Tor
    // outage) — it just dismisses this sheet and sends the user to the standard missed/overdue
    // transfer resolution screen (Reschedule or Send Now), per spec §6.5.4/§9.1. No decision is
    // recorded on PendingMigrationTorFailureDecisionRepository: navigating straight to
    // MigrationProgressArgs (rather than relying on back() to reveal a live listener somewhere on
    // the stack) works identically regardless of whether this sheet was reached interactively or
    // via CheckMigrationRecoveryUseCase's background-failure routing.
    private fun onTryAgain() {
        if (continuing?.isActive != true) {
            navigationRouter.replaceAll(MigrationProgressArgs(args.accountKeyId))
        }
    }

    private fun onBack() {
        if (continuing?.isActive != true) navigationRouter.back()
    }
}
