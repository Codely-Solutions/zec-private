package co.electriccoin.zcash.ui.screen.exchangerateunavailable

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateUseCase
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByFiatDisplayName
import kotlinx.coroutines.flow.flowOf

class ExchangeRateUnavailableVM(
    private val navigationRouter: NavigationRouter,
    private val optInExchangeRate: OptInExchangeRateUseCase,
    exchangeRateRepository: ExchangeRateRepository,
    errorMapper: ErrorMapperUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private val expectedCurrency =
        (exchangeRateRepository.state.value as? ExchangeRateState.Data)?.expectedCurrency ?: FiatCurrency.USD

    private val preference = mutableLce<Unit>()
    private val content = createState()
    val state = flowOf(content).withLce(preference) { error ->
        errorMapper.mapToState(error, message = stringRes(R.string.currencyConversion_preferenceUnavailable))
    }.stateIn(this, LceState(content = content))

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
    }

    private fun createState() =
        ExchangeRateUnavailableState(
            title = stringRes(R.string.send_currencyUnavailable_title, expectedCurrency.code),
            subtitle =
                stringRes(
                    R.string.send_currencyUnavailable_desc,
                    stringResByFiatDisplayName(expectedCurrency)
                ),
            switchToUsdButton =
                ButtonState(
                    text = stringRes(R.string.send_currencyUnavailable_switchToUSD),
                    style = ButtonStyle.PRIMARY,
                    onClick = ::onSwitchToUsd
                ),
            continueInZecButton =
                ButtonState(
                    text = stringRes(R.string.send_currencyUnavailable_continueInZEC),
                    style = ButtonStyle.SECONDARY,
                    onClick = ::onContinueInZec
                ),
            onBack = ::onContinueInZec,
        )

    private fun onSwitchToUsd() = preference.guardLoading {
        preference.execute {
            optInExchangeRate(optIn = true, fiatCurrency = FiatCurrency.USD, sourceEntryId = sourceEntryId)
        }
    }

    private fun onContinueInZec() = navigationRouter.back()
}
