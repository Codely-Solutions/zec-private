package co.electriccoin.zcash.ui.screen.tor.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateAndTorUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TorSettingsVM(
    isTorEnabledUseCase: IsTorEnabledUseCase,
    private val navigationRouter: NavigationRouter,
    private val optInExchangeRateAndTor: OptInExchangeRateAndTorUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
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
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private fun onBack() = navigationRouter.back()

    private fun onOptInClick(optIn: Boolean) = viewModelScope.launch {
        optInExchangeRateAndTor(optIn, sourceEntryId = sourceEntryId)
    }
}
