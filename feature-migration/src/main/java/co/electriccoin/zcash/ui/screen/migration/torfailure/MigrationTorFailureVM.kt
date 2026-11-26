package co.electriccoin.zcash.ui.screen.migration.torfailure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.repository.PendingMigrationTorFailureDecisionRepository
import co.electriccoin.zcash.ui.screen.migration.progress.MigrationProgressArgs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

class MigrationTorFailureVM(
    private val navigationRouter: NavigationRouter,
    private val args: MigrationTorFailureArgs,
    private val pendingMigrationTorFailureDecisionRepository: PendingMigrationTorFailureDecisionRepository,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }
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

    private fun onContinueWithoutTor() {
        val attempt = args.authorizationId ?: return
        val foreground = { navigationRouter.isForegroundAtEntry(sourceEntryId) }
        navigationRouter.stageResultFrom(sourceEntryId, foreground) {
            pendingMigrationTorFailureDecisionRepository.set(
                args.accountKeyId,
                useTor = false,
                authorizationId = attempt,
            )
            NavigationCommand.Back
        }
    }

    // Return to live progress so a new attempt requires an explicit choice on the current transfer.
    // Keep the saved Tor preference and leave the decision repository untouched.
    private fun onTryAgain() {
        navigationRouter.stageResultFrom(sourceEntryId, { navigationRouter.isForegroundAtEntry(sourceEntryId) }) {
            NavigationCommand.ReplaceAll(listOf(MigrationProgressArgs(args.accountKeyId, offerRetry = true)))
        }
    }

    private fun onBack() {
        navigationRouter.stageResultFrom(sourceEntryId, { navigationRouter.isForegroundAtEntry(sourceEntryId) }) {
            NavigationCommand.Back
        }
    }
}
