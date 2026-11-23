package co.electriccoin.zcash.ui.screen.request.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrencyConversion
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.zcash.MAX_ZCASH_SUPPLY
import co.electriccoin.zcash.ui.common.provider.GetZcashCurrencyProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.ObserveAccountIconUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.common.usecase.Zip321BuildUriUseCase
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.common.wallet.eligibleConversion
import co.electriccoin.zcash.ui.common.wallet.isRateExpired
import co.electriccoin.zcash.ui.design.util.StringResource.Companion.NUMBER_FORMAT_LOCALE
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.qrcode.ext.fromReceiveAddressType
import co.electriccoin.zcash.ui.screen.receive.ReceiveAddressType
import co.electriccoin.zcash.ui.screen.request.ext.toBigDecimalLocalized
import co.electriccoin.zcash.ui.screen.request.model.AmountState
import co.electriccoin.zcash.ui.screen.request.model.MemoState
import co.electriccoin.zcash.ui.screen.request.model.OnAmount
import co.electriccoin.zcash.ui.screen.request.model.QrCodeState
import co.electriccoin.zcash.ui.screen.request.model.Request
import co.electriccoin.zcash.ui.screen.request.model.RequestCurrency
import co.electriccoin.zcash.ui.screen.request.model.RequestStage
import co.electriccoin.zcash.ui.screen.request.model.RequestState
import co.electriccoin.zcash.ui.screen.request.model.formatRequestZec
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormatSymbols

@Suppress("TooManyFunctions")
class RequestVM(
    private val addressTypeOrdinal: Int,
    private val application: Application,
    private val exchangeRateRepository: ExchangeRateRepository,
    getZcashCurrency: GetZcashCurrencyProvider,
    zip321BuildUriUseCase: Zip321BuildUriUseCase,
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
    private val shareQR: ShareQRUseCase,
    private val observeAccountIcon: ObserveAccountIconUseCase,
) : ViewModel() {
    companion object {
        private const val DEFAULT_MEMO = ""
        private const val DEFAULT_URI = ""
    }

    private val decimalFormatSymbols: DecimalFormatSymbols
        get() = DecimalFormatSymbols(NUMBER_FORMAT_LOCALE)

    private val decimal: String
        get() = Regex.escape(decimalFormatSymbols.decimalSeparator.toString())

    private val grouping: String
        get() = Regex.escape(decimalFormatSymbols.groupingSeparator.toString())

    private val defaultAmount = application.getString(R.string.request_amount_empty)

    internal val request =
        MutableStateFlow(
            Request(
                amountState =
                    AmountState(
                        amount = defaultAmount,
                        currency = RequestCurrency.ZEC,
                        isValid = null
                    ),
                memoState = MemoState.Valid(DEFAULT_MEMO, 0, defaultAmount),
                qrCodeState = QrCodeState(DEFAULT_URI, defaultAmount, DEFAULT_MEMO),
            )
        )

    private class Step(val stage: RequestStage)

    private val step = MutableStateFlow(Step(RequestStage.AMOUNT))

    internal val state =
        combine(
            request,
            step,
            exchangeRateRepository.state,
            observeSelectedWalletAccount.require(),
            observeAccountIcon.observeAll()
        ) { draft, currentStep, exchangeRate, account, colors ->
            val walletAddress =
                account.fromReceiveAddressType(ReceiveAddressType.fromOrdinal(addressTypeOrdinal))
                    ?: return@combine RequestState.Loading

            when (currentStep.stage) {
                RequestStage.AMOUNT -> {
                    val amount = draft.amountState
                    val rate = displayRate(amount, exchangeRate)
                    val zecAmount = amount.validZecAmount(resolveExchangeRateValue(rate))
                    RequestState.Amount(
                        request = draft,
                        exchangeRateState = rate,
                        zcashCurrency = getZcashCurrency(),
                        onAmount = { edit ->
                            if (step.value === currentStep) onAmount(edit)
                        },
                        onSwitch = { currency ->
                            if (step.value === currentStep && request.value.amountState == amount) {
                                onSwitch(currentConversion(amount), currency)
                            }
                        },
                        onBack = { if (step.value === currentStep) onBack() },
                    ) {
                        if (step.value === currentStep) {
                            onNextClick(walletAddress, zip321BuildUriUseCase, amount, zecAmount)
                        }
                    }
                }

                RequestStage.MEMO -> {
                    RequestState.Memo(
                        icon =
                            if (account is ZodlAccount) {
                                imageRes(R.drawable.ic_zec_round_full)
                            } else {
                                observeAccountIcon.iconOf(colors, account)
                            },
                        walletAddress = walletAddress,
                        request = draft,
                        onMemo = { if (step.value === currentStep) onMemo(it) },
                        onDone = {
                            if (step.value === currentStep) {
                                onMemoDone(walletAddress.address, zip321BuildUriUseCase)
                            }
                        },
                        onBack = { if (step.value === currentStep) onBack() },
                        zcashCurrency = getZcashCurrency(),
                    )
                }

                RequestStage.QR_CODE -> {
                    RequestState.QrCode(
                        icon =
                            if (walletAddress is WalletAddress.Transparent) {
                                R.drawable.ic_zec_qr_transparent
                            } else {
                                R.drawable.ic_zec_qr_shielded
                            },
                        walletAddress = walletAddress,
                        request = draft,
                        onQrCodeShare = { _, _, uri ->
                            viewModelScope.launch {
                                shareQR(
                                    qrData = uri,
                                    shareText = stringRes(R.string.request_qr_code_share_chooser_text, CURRENCY_TICKER),
                                    sharePickerText =
                                        stringRes(R.string.request_qr_code_share_chooser_title, CURRENCY_TICKER),
                                    filenamePrefix = TEMP_FILE_NAME_PREFIX,
                                    centerIcon =
                                        if (walletAddress is WalletAddress.Transparent) {
                                            R.drawable.ic_zec_qr_transparent
                                        } else {
                                            R.drawable.ic_zec_qr_shielded
                                        }
                                )
                            }
                        },
                        onBack = { if (step.value === currentStep) onBack() },
                        onClose = { if (step.value === currentStep) onClose() },
                        zcashCurrency = getZcashCurrency(),
                    )
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = RequestState.Loading
        )

    private fun onNextClick(
        walletAddress: WalletAddress,
        zip321BuildUriUseCase: Zip321BuildUriUseCase,
        displayedAmount: AmountState,
        displayedZecAmount: BigDecimal?,
    ) {
        val amount = request.value.amountState
        val represented = displayedZecAmount ?: return
        if (amount != displayedAmount || represented != amount.validZecAmount(currentConversion(amount))) return
        val zecAmount = when (amount.currency) {
            RequestCurrency.ZEC -> amount.amount
            RequestCurrency.FIAT -> represented.formatRequestZec(application)
        }
        when (walletAddress) {
            is WalletAddress.Transparent -> {
                onAmountAndMemoDone(
                    walletAddress.address,
                    zip321BuildUriUseCase,
                    zecAmount
                )
            }

            is WalletAddress.Unified, is WalletAddress.Sapling -> {
                onAmountDone(zecAmount)
            }

            else -> {
                error("Unexpected address type")
            }
        }
    }

    private fun resolveExchangeRateValue(state: ExchangeRateState): FiatCurrencyConversion? =
        (state as? ExchangeRateState.Data)?.eligibleConversion()

    private fun currentConversion(amount: AmountState): FiatCurrencyConversion? =
        resolveExchangeRateValue(displayRate(amount, exchangeRateRepository.state.value))

    private fun displayRate(amount: AmountState, state: ExchangeRateState): ExchangeRateState {
        val conversion = resolveExchangeRateValue(state)
        if (amount.currency == RequestCurrency.ZEC) {
            return (state as? ExchangeRateState.Data)?.let {
                it.copy(currencyConversion = conversion, isStale = it.isRateExpired())
            } ?: state
        }
        val currency = amount.fiatCurrency
        val data = state as? ExchangeRateState.Data
            ?: ExchangeRateState.Data(isLoading = false, isRefreshEnabled = false, onRefresh = {})
        return if (currency == null) {
            ExchangeRateState.OptedOut
        } else {
            data.copy(
                expectedCurrency = currency,
                currencyConversion = conversion?.takeIf { it.fiatCurrency == currency },
                isStale = data.expectedCurrency == currency && data.isRateExpired(),
            )
        }
    }

    private fun onAmount(onAmount: OnAmount) {
        val conversion = currentConversion(request.value.amountState)
        request.update {
            val newState =
                when (onAmount) {
                    is OnAmount.Number -> {
                        if (it.amountState.amount == defaultAmount) {
                            // Special case with current value only zero
                            validateAmountState(conversion, onAmount.number.toString())
                        } else {
                            // Adding new number to the result string
                            validateAmountState(
                                conversion,
                                it.amountState.amount + onAmount.number
                            )
                        }
                    }

                    is OnAmount.Delete -> {
                        if (it.amountState.amount.length == 1) {
                            // Deleting up to the last character
                            AmountState(
                                amount = defaultAmount,
                                currency = it.amountState.currency,
                                fiatCurrency = it.amountState.fiatCurrency,
                                isValid = null
                            )
                        } else {
                            validateAmountState(
                                conversion,
                                it.amountState.amount.dropLast(1)
                            )
                        }
                    }

                    is OnAmount.Separator -> {
                        if (it.amountState.amount.contains(onAmount.separator)) {
                            // Separator already present
                            validateAmountState(conversion, it.amountState.amount)
                        } else {
                            validateAmountState(
                                conversion,
                                it.amountState.amount + onAmount.separator
                            )
                        }
                    }
                }

            it.copy(amountState = newState)
        }
    }

    // Validates only zeros and decimal separator
    private val defaultAmountValidationRegex = "^[${defaultAmount}$decimal]*$".toRegex()

    // Validates only numbers the properly use grouping and decimal separators
    // Note that this regex aligns with the one from ZcashSDK (sdk-incubator-lib/src/main/res/values/strings-regex.xml)
    // It only adds check for 0-8 digits after the decimal separator at maximum
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length")
    private val allowedNumberFormatValidationRegex =
        "^([0-9]*([0-9]+([$grouping]\$|[$grouping][0-9]+))*([$decimal]\$|[$decimal][0-9]{0,8})?)?\$".toRegex()

    private fun validateAmountState(
        conversion: FiatCurrencyConversion?,
        resultAmount: String,
    ): AmountState {
        val newAmount =
            if (resultAmount.contains(defaultAmountValidationRegex)) {
                AmountState(
                    // Check for the max decimals in the default (i.e. 0.000) number, too
                    amount =
                        if (!resultAmount.contains(allowedNumberFormatValidationRegex)) {
                            request.value.amountState.amount
                        } else {
                            resultAmount
                        },
                    currency = request.value.amountState.currency,
                    isValid = null
                )
            } else if (!resultAmount.contains(allowedNumberFormatValidationRegex)) {
                AmountState(
                    amount = request.value.amountState.amount,
                    currency = request.value.amountState.currency,
                    isValid = true
                )
            } else {
                AmountState(
                    amount = resultAmount,
                    currency = request.value.amountState.currency,
                    isValid = true
                )
            }

        val boundAmount = newAmount.copy(fiatCurrency = request.value.amountState.fiatCurrency)
        // A missing fiat conversion never changes the unit of the input.
        if (newAmount.currency == RequestCurrency.FIAT &&
            (conversion == null || conversion.fiatCurrency != boundAmount.fiatCurrency)
        ) return boundAmount
        return boundAmount.amount
            .toBigDecimalLocalized()
            ?.let { currentValue ->
                val zecValue =
                    if (newAmount.currency == RequestCurrency.FIAT && conversion != null) {
                        currentValue.divide(conversion.priceOfZec.toBigDecimal(), MathContext.DECIMAL128)
                    } else {
                        currentValue
                    }
                if (zecValue > MAX_ZCASH_SUPPLY.toBigDecimal()) {
                    boundAmount.copy(amount = request.value.amountState.amount)
                } else {
                    boundAmount
                }
            } ?: boundAmount
    }

    internal fun onBack() {
        when (step.value.stage) {
            RequestStage.AMOUNT -> {
                navigationRouter.back()
            }

            RequestStage.MEMO -> {
                step.value = Step(RequestStage.AMOUNT)
            }

            RequestStage.QR_CODE -> {
                when (ReceiveAddressType.fromOrdinal(addressTypeOrdinal)) {
                    ReceiveAddressType.Transparent -> step.value = Step(RequestStage.AMOUNT)
                    ReceiveAddressType.Unified, ReceiveAddressType.Sapling -> step.value = Step(RequestStage.MEMO)
                }
            }
        }
    }

    private fun onClose() = navigationRouter.back()

    private fun onAmountDone(zecAmount: String) {
        request.update { it.copy(memoState = MemoState.new(DEFAULT_MEMO, zecAmount)) }
        step.value = Step(RequestStage.MEMO)
    }

    private fun onMemoDone(address: String, zip321BuildUriUseCase: Zip321BuildUriUseCase) {
        if (!request.value.memoState.isValid()) return
        request.update {
            it.copy(
                qrCodeState =
                    QrCodeState(
                        requestUri =
                            createZip321Uri(
                                address = address,
                                amount = it.memoState.zecAmount,
                                memo = it.memoState.text,
                                zip321BuildUriUseCase = zip321BuildUriUseCase
                            ),
                        zecAmount = it.memoState.zecAmount,
                        memo = it.memoState.text,
                    )
            )
        }
        step.value = Step(RequestStage.QR_CODE)
    }

    private fun onAmountAndMemoDone(
        address: String,
        zip321BuildUriUseCase: Zip321BuildUriUseCase,
        qrCodeAmount: String,
    ) {
        request.update {
            it.copy(
                qrCodeState =
                    QrCodeState(
                        requestUri =
                            createZip321Uri(
                                address = address,
                                amount = qrCodeAmount,
                                memo = DEFAULT_MEMO,
                                zip321BuildUriUseCase = zip321BuildUriUseCase
                            ),
                        zecAmount = qrCodeAmount,
                        memo = DEFAULT_MEMO,
                    )
            )
        }

        step.value = Step(RequestStage.QR_CODE)
    }

    private fun onSwitch(
        conversion: FiatCurrencyConversion?,
        onSwitchTo: RequestCurrency
    ) {
        val amount = request.value.amountState
        if (conversion == null || onSwitchTo == amount.currency) return

        request.update {
            val newAmount =
                when (onSwitchTo) {
                    RequestCurrency.FIAT -> {
                        it.amountState.toFiatString(
                            context = application.applicationContext,
                            conversion = conversion
                        )
                    }

                    RequestCurrency.ZEC -> {
                        it.amountState.validZecAmount(conversion)?.formatRequestZec(application)
                            ?: defaultAmount
                    }
                }

            it.copy(
                amountState =
                    if (newAmount.contains(defaultAmountValidationRegex)) {
                        it.amountState.copy(
                            amount = defaultAmount, currency = onSwitchTo, fiatCurrency = conversion.fiatCurrency
                        )
                    } else {
                        it.amountState.copy(
                            amount = newAmount, currency = onSwitchTo, fiatCurrency = conversion.fiatCurrency
                        )
                    }
            )
        }
    }

    private fun onMemo(memoState: MemoState) = request.update {
        it.copy(memoState = MemoState.new(memoState.text, it.memoState.zecAmount))
    }

    private fun createZip321Uri(
        address: String,
        amount: String,
        memo: String,
        zip321BuildUriUseCase: Zip321BuildUriUseCase,
    ): String {
        val amountNumber = amount.toBigDecimalLocalized()
        return if (amountNumber == null) {
            Twig.error { "Unexpected amount state" }
            DEFAULT_URI
        } else {
            zip321BuildUriUseCase.invoke(
                address = address,
                amount = amountNumber,
                memo = memo
            )
        }
    }
}

private const val TEMP_FILE_NAME_PREFIX = "zip_321_request_qr_" // NON-NLS
