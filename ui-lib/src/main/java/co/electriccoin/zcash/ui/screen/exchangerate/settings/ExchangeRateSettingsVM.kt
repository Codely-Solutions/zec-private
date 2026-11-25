package co.electriccoin.zcash.ui.screen.exchangerate.settings

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.IsExchangeRateEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.PreferredFiatProvider
import co.electriccoin.zcash.ui.common.provider.recoverableReads
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectFiatCurrencyUseCase
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByFiatDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ExchangeRateSettingsVM(
    isTorEnabledStorageProvider: IsTorEnabledStorageProvider,
    private val navigationRouter: NavigationRouter,
    private val optInExchangeRate: OptInExchangeRateUseCase,
    private val isExchangeRateEnabledStorageProvider: IsExchangeRateEnabledStorageProvider,
    private val preferredFiatProvider: PreferredFiatProvider,
    private val navigateToSelectFiatCurrency: NavigateToSelectFiatCurrencyUseCase,
    private val errorMapper: ErrorMapperUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    private var isOptedInOriginal = false

    private var selectedCurrencyOriginal: FiatCurrency? = null

    private val isOptedIn = MutableStateFlow(false)

    private val selectedCurrency = MutableStateFlow<FiatCurrency?>(null)
    private val preference = mutableLce<Unit>()
    private val torReads: Flow<Result<Boolean?>?> = isTorEnabledStorageProvider.observe().recoverableReads()

    val state =
        combine(
            isOptedIn,
            selectedCurrency,
            torReads.onStart { emit(null) },
        ) { isOptedIn, selectedCurrency, torPreference ->
            selectedCurrency?.let { createState(isOptedIn, it, torPreference) }
        }.withLce(preference) { error ->
            errorMapper.mapToState(error, message = stringRes(R.string.currencyConversion_preferenceUnavailable))
        }.stateIn(this)

    init {
        addCloseable(AutoCloseable { navigationRouter.discardResultFrom(sourceEntryId) })
        preference.execute {
            val storedOptIn = isExchangeRateEnabledStorageProvider.get() == true
            val storedCurrency = preferredFiatProvider.get() ?: FiatCurrency.USD
            isOptedInOriginal = storedOptIn
            selectedCurrencyOriginal = storedCurrency
            isOptedIn.update { isOptedInOriginal }
            selectedCurrency.update { selectedCurrencyOriginal }
        }
    }

    private fun createState(
        isOptedIn: Boolean,
        selectedCurrency: FiatCurrency,
        torPreference: Result<Boolean?>?
    ) = ExchangeRateSettingsState(
        isOptedIn = SimpleCheckboxState(isOptedIn, ::onOptInClick),
        isOptedOut = SimpleCheckboxState(!isOptedIn, ::onOptOutClick),
        currencyField =
            if (isOptedIn && VersionInfo.IS_CMC_AVAILABLE) {
                CurrencyFieldState(
                    code = stringRes(selectedCurrency.code),
                    name = stringResByFiatDisplayName(selectedCurrency),
                    onClick = ::onSelectCurrencyClick
                )
            } else {
                null
            },
        saveButton =
            ButtonState(
                stringRes(R.string.currencyConversion_saveBtn),
                onClick = ::onOptInExchangeRateUsdClick,
                isEnabled = isOptedIn != isOptedInOriginal || selectedCurrency != selectedCurrencyOriginal,
                hapticFeedbackType = HapticFeedbackType.Confirm
            ),
        onBack = ::onBack,
        info =
            when {
                !VersionInfo.IS_CMC_AVAILABLE || torPreference == null || torPreference.isFailure -> null
                torPreference.getOrNull() == true -> stringRes(R.string.currencyConversion_torOnInfo)
                else -> stringRes(R.string.currencyConversion_torOffInfo)
            }
    )

    fun onBack() = navigationRouter.back()

    private fun onOptInClick() = isOptedIn.update { true }

    private fun onOptInExchangeRateUsdClick() = preference.guardLoading {
        val optIn = isOptedIn.value
        val currency = selectedCurrency.value
        preference.execute { optInExchangeRate(optIn, currency, sourceEntryId = sourceEntryId) }
    }

    private fun onOptOutClick() = isOptedIn.update { false }

    private fun onSelectCurrencyClick() =
        viewModelScope.launch {
            navigateToSelectFiatCurrency(selectedCurrency.value)?.let { picked ->
                selectedCurrency.update { picked }
            }
        }
}
