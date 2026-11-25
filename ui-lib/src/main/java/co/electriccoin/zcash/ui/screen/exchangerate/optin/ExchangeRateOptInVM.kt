package co.electriccoin.zcash.ui.screen.exchangerate.optin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectFiatCurrencyUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByFiatDisplayName
import co.electriccoin.zcash.ui.screen.exchangerate.settings.CurrencyFieldState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExchangeRateOptInVM(
    private val optInExchangeRate: OptInExchangeRateUseCase,
    private val navigationRouter: NavigationRouter,
    private val preferredFiatProvider: PreferredFiatProvider,
    private val navigateToSelectFiatCurrency: NavigateToSelectFiatCurrencyUseCase,
    private val errorMapper: ErrorMapperUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val selectedCurrency = MutableStateFlow<FiatCurrency?>(null)
    private val preference = mutableLce<Unit>()

    val state =
        selectedCurrency
            .map { currency -> currency?.let(::createState) }
            .withLce(preference) { error ->
                errorMapper.mapToState(error, message = stringRes(R.string.currencyConversion_preferenceUnavailable))
            }.stateIn(this)

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
        preference.execute {
            selectedCurrency.update { preferredFiatProvider.get() ?: FiatCurrency.USD }
        }
    }

    private fun createState(currency: FiatCurrency) =
        ExchangeRateOptInState(
            currencyField =
                if (VersionInfo.IS_CMC_AVAILABLE) {
                    CurrencyFieldState(
                        code = stringRes(currency.code),
                        name = stringResByFiatDisplayName(currency),
                        onClick = ::onSelectCurrencyClick
                    )
                } else {
                    null
                },
            onBack = ::onBack,
            onEnableClick = ::optInExchangeRateUsd,
            onSkipClick = ::onSkipClick
        )

    private fun onSkipClick() = save(optIn = false)

    private fun optInExchangeRateUsd() = save(optIn = true)

    private fun save(optIn: Boolean) = preference.guardLoading {
        val currency = selectedCurrency.value.takeIf { optIn }
        preference.execute { optInExchangeRate(optIn, currency, sourceEntryId = sourceEntryId) }
    }

    fun onBack() = navigationRouter.back()

    private fun onSelectCurrencyClick() =
        viewModelScope.launch {
            navigateToSelectFiatCurrency(selectedCurrency.value)?.let { picked ->
                selectedCurrency.update { picked }
            }
        }
}
