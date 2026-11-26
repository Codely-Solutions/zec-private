package co.electriccoin.zcash.ui.screen.tor.settings

import androidx.lifecycle.ViewModel
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.common.datasource.GiftWalletRestartRequiredException
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateAndTorUseCase
import co.electriccoin.zcash.ui.common.usecase.TorChangeWalletBusyException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import co.electriccoin.zcash.ui.design.util.stringRes

class TorSettingsVM(
    isTorEnabledUseCase: IsTorEnabledUseCase,
    private val navigationRouter: NavigationRouter,
    private val optInExchangeRateAndTor: OptInExchangeRateAndTorUseCase,
    private val errorMapper: ErrorMapperUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val save = mutableLce<Unit>()
    private val readAttempts = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    internal val preference = readAttempts.flatMapLatest {
        isTorEnabledUseCase.observe().map<Boolean?, TorPreferenceRead> { enabled ->
            if (enabled == null) TorPreferenceRead.Unavailable else TorPreferenceRead.Available(enabled)
        }.onStart { emit(TorPreferenceRead.Loading) }
    }.stateIn(this, TorPreferenceRead.Loading)

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
    }

    val state =
        preference
            .map { read ->
                (read as? TorPreferenceRead.Available)?.let {
                    TorSettingsState(
                        isOptedIn = it.enabled,
                        onSaveClick = { optedIn -> onOptInClick(it, optedIn) },
                        onDismiss = ::onBack,
                    )
                }
            }.withLce(save) { error ->
                errorMapper.mapToState(
                    error,
                    message = torSaveErrorMessage(error.cause),
                    supportWithoutDiagnostics = true,
                )
            }.stateIn(this)

    fun onBack() = navigationRouter.back()

    fun retryRead() {
        if (preference.value == TorPreferenceRead.Unavailable) readAttempts.update { it + 1 }
    }

    private fun onOptInClick(displayed: TorPreferenceRead.Available, optIn: Boolean) = save.guardLoading {
        if (preference.value === displayed && displayed.enabled != optIn) {
            save.execute { optInExchangeRateAndTor(optIn, sourceEntryId = sourceEntryId) }
        }
    }
}

internal sealed interface TorPreferenceRead {
    data object Loading : TorPreferenceRead
    data object Unavailable : TorPreferenceRead
    data class Available(val enabled: Boolean) : TorPreferenceRead
}

internal fun torSaveErrorMessage(error: Throwable?) = stringRes(
    when (error) {
        is TorChangeWalletBusyException -> R.string.tor_protection_wallet_busy
        is GiftWalletRestartRequiredException -> R.string.tor_protection_gift_restart
        else -> R.string.tor_protection_save_failed
    }
)
