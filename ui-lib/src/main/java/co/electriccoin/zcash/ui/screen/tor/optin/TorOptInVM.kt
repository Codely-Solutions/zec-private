package co.electriccoin.zcash.ui.screen.tor.optin

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateAndTorUseCase
import co.electriccoin.zcash.ui.screen.tor.settings.torSaveErrorMessage
import kotlinx.coroutines.flow.flowOf

class TorOptInVM(
    private val optInExchangeRateAndTor: OptInExchangeRateAndTorUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorMapper: ErrorMapperUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val save = mutableLce<Unit>()

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
    }

    val state =
        flowOf(
            TorOptInState(
                onBack = ::onBack,
                onEnableClick = ::onOptInClick,
                onSkipClick = ::onSkipClick
            )
        ).withLce(save) { error ->
            errorMapper.mapToState(
                error,
                message = torSaveErrorMessage(error.cause),
                supportWithoutDiagnostics = true,
            )
        }.stateIn(this)

    private fun onSkipClick() = saveChoice(false)

    private fun onOptInClick() = saveChoice(true)

    private fun saveChoice(optIn: Boolean) = save.guardLoading {
        save.execute { optInExchangeRateAndTor(optIn, sourceEntryId = sourceEntryId) }
    }

    private fun onBack() = navigationRouter.back()
}
