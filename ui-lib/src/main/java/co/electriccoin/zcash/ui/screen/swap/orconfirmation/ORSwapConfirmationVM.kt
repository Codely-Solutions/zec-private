package co.electriccoin.zcash.ui.screen.swap.orconfirmation

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.SaveORSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ShareQRUseCase
import co.electriccoin.zcash.ui.design.component.BigIconButtonState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.IconButtonState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.styledStringResource
import co.electriccoin.zcash.ui.screen.error.ErrorState
import co.electriccoin.zcash.ui.screen.swap.info.DepositSwapInfoArgs
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import kotlin.time.Clock

class ORSwapConfirmationVM(
    private val swapRepository: SwapRepository,
    private val cancelSwapQuote: CancelSwapQuoteUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val saveORSwap: SaveORSwapUseCase,
    private val shareQR: ShareQRUseCase,
) : ViewModel() {
    private val _dialogState = MutableStateFlow<ErrorState?>(null)
    val dialogState = _dialogState.asStateFlow()

    val state: StateFlow<ORSwapConfirmationState?> =
        swapRepository.quote
            .filterIsInstance<SwapQuoteData.Success>()
            .map { it.quote }
            .map { quote ->
                saveORSwap.persistBeforeFunding(quote)
                quote
            }
            .combine(flow {
                while (true) {
                    emit(Clock.System.now())
                    delay(FUNDING_CLOCK_INTERVAL_MILLIS)
                }
            }) { quote, now -> quote to (now < quote.deadline) }
            .map<Pair<SwapQuote, Boolean>, ORSwapConfirmationState?> { (quote, fundingEnabled) ->
                ORSwapConfirmationState(
                    fundingEnabled = fundingEnabled,
                    expiryMessage = if (fundingEnabled) null else stringRes(R.string.private_swap_funding_expired),
                    freshQuoteButton = if (fundingEnabled) null else ButtonState(
                        text = stringRes(R.string.private_swap_fresh_quote),
                        onClick = ::onCancelSwapClick,
                    ),
                    onBack = ::onBack,
                    info =
                        IconButtonState(
                            icon = co.electriccoin.zcash.ui.design.R.drawable.ic_info,
                            onClick = ::onInfoClick,
                        ),
                    bigIcon = quote.originAsset.tokenIcon,
                    smallIcon = quote.originAsset.chainIcon,
                    amount = stringResByNumber(quote.amountInFormatted),
                    amountFiat = stringResByDynamicCurrencyNumber(quote.amountInUsd, FiatCurrency.USD.symbol),
                    onAmountClick = { onAmountClick(quote.amountInFormatted, quote.depositAddress.address) },
                    qr = quote.depositAddress.address,
                    address = stringResByAddress(quote.depositAddress.address),
                    copyButton =
                        BigIconButtonState(
                            text = stringRes(co.electriccoin.zcash.ui.design.R.string.receive_copy),
                            icon = R.drawable.ic_copy,
                            onClick = { onCopyAddressClick(quote.depositAddress.address) }
                        ),
                    shareButton =
                        BigIconButtonState(
                            text = stringRes(co.electriccoin.zcash.ui.design.R.string.swapToZec_shareQR),
                            icon = R.drawable.ic_qr_code_other,
                            onClick = {
                                onShareClick(
                                    qrData = quote.depositAddress.address,
                                    amount = quote.amountInFormatted,
                                    tokenTicker = quote.originAsset.tokenTicker,
                                    chainName = quote.originAsset.chainName
                                )
                            }
                        ),
                    footer =
                        styledStringResource(
                            R.string.swap_to_zec_footer,
                            styledStringResource(
                                resource = R.string.swapToZec_info2,
                                fontWeight = FontWeight.Bold,
                                quote.originAsset.tokenTicker,
                                quote.originAsset.chainName
                            )
                        ),
                    primaryButton =
                        ButtonState(
                            text = stringRes(R.string.swapToZec_sentTheFunds),
                            style = if (fundingEnabled) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY,
                            hapticFeedbackType = HapticFeedbackType.Confirm,
                            onClick = ::onSentFundsClick
                        ),
                )
            }.catch { error ->
                if (error is CancellationException) throw error
                emit(null)
                _dialogState.value = ErrorState(
                    title = stringRes(R.string.private_swap_tracking_failed_title),
                    message = stringRes(R.string.private_swap_tracking_failed),
                    positive = ButtonState(
                        text = stringRes(R.string.private_swap_fresh_quote),
                        onClick = ::onCancelSwapClick,
                    ),
                    negative = ButtonState(
                        text = stringRes(R.string.swapAndPay_cancelSwap),
                        onClick = ::onCancelSwapClick,
                    ),
                    onBack = ::onCancelSwapClick,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private var onSentFundsClickJob: Job? = null

    private fun canFund(depositAddress: String): Boolean =
        (swapRepository.quote.value as? SwapQuoteData.Success)?.quote?.let {
            it.depositAddress.address == depositAddress && Clock.System.now() < it.deadline
        } == true

    private fun onAmountClick(amountInFormatted: BigDecimal, depositAddress: String) {
        if (canFund(depositAddress)) copyToClipboard(amountInFormatted.toPlainString())
    }

    private fun onCopyAddressClick(depositAddress: String) {
        if (canFund(depositAddress)) copyToClipboard(depositAddress)
    }

    private fun onBack() {
        if (onSentFundsClickJob?.isActive == true) return
        if (_dialogState.value != null) {
            _dialogState.value = null
        } else {
            _dialogState.value =
                ErrorState(
                    title = stringRes(R.string.depositFunds_alert_title),
                    message = stringRes(R.string.depositFunds_alert_message),
                    positive =
                        ButtonState(
                            text = stringRes(R.string.swapToZec_sentTheFunds),
                            onClick = ::onSentFundsClick
                        ),
                    negative =
                        ButtonState(
                            text = stringRes(R.string.swapAndPay_cancelSwap),
                            onClick = ::onCancelSwapClick
                        ),
                    onBack = { _dialogState.value = null }
                )
        }
    }

    private fun onCancelSwapClick() {
        _dialogState.value = null
        cancelSwapQuote()
    }

    private fun onSentFundsClick() {
        if (onSentFundsClickJob?.isActive == true) return
        _dialogState.value = null
        onSentFundsClickJob = viewModelScope.launch { saveORSwap() }
    }

    private fun onInfoClick() = navigationRouter.forward(DepositSwapInfoArgs)

    private fun onShareClick(
        qrData: String,
        amount: BigDecimal,
        tokenTicker: String,
        chainName: StringResource
    ) = viewModelScope.launch {
        if (!canFund(qrData)) return@launch
        shareQR(
            qrData = qrData,
            shareText =
                stringRes(
                    R.string.swap_to_zec_share_text,
                    stringResByNumber(amount),
                    tokenTicker.uppercase(),
                    chainName,
                    CURRENCY_TICKER
                ),
            sharePickerText = stringRes("Swap Deposit Address"),
            filenamePrefix = "swap_deposit_address_"
        )
    }
}

private const val FUNDING_CLOCK_INTERVAL_MILLIS = 1_000L
