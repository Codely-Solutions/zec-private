package co.electriccoin.zcash.ui.screen.migration.howitworks

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationPrivacyOrReviewDestinationUseCase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class MigrationHowItWorksVM(
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val getMigrationPrivacyOrReviewDestination: GetMigrationPrivacyOrReviewDestinationUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }
    private val lce = mutableLce<Unit>()

    val state: StateFlow<LceState<MigrationHowItWorksState>> =
        flowOf(
            MigrationHowItWorksState(
                onContinue = ::onContinue,
                onBack = ::onBack,
            )
        ).withLce(lce, errorStateMapper::mapToState)
            .stateIn(this)

    private fun onContinue() = lce.guardLoading {
        lce.execute {
            val destination = getMigrationPrivacyOrReviewDestination(MigrationMode.AUTOMATIC)
            navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.Forward(listOf(destination)) }
        }
    }

    private fun onBack() = navigationRouter.back()
}
