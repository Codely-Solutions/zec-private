package co.electriccoin.zcash.ui.screen.tor.settings

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateAndTorUseCase
import kotlinx.coroutines.flow.map
import co.electriccoin.zcash.ui.design.util.stringRes

class TorSettingsVM(
    isTorEnabledUseCase: IsTorEnabledUseCase,
    private val navigationRouter: NavigationRouter,
    private val optInExchangeRateAndTor: OptInExchangeRateAndTorUseCase,
    private val errorMapper: ErrorMapperUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val save = mutableLce<Unit>()

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
    }

    val state =
        isTorEnabledUseCase
            .observe()
            .map { isEnabled ->
                isEnabled?.let {
                    TorSettingsState(
                        isOptedIn = it,
                        onSaveClick = ::onOptInClick,
                        onDismiss = ::onBack,
                    )
                }
            }.withLce(save) { error ->
                errorMapper.mapToState(
                    error,
                    message = stringRes(R.string.tor_protection_save_failed),
                    supportWithoutDiagnostics = true,
                )
            }.stateIn(this)

    private fun onBack() = navigationRouter.back()

    private fun onOptInClick(optIn: Boolean) = save.guardLoading {
        save.execute { optInExchangeRateAndTor(optIn, sourceEntryId = sourceEntryId) }
    }
}
