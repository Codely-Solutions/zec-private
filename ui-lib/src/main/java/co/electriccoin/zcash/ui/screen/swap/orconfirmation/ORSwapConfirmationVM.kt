package co.electriccoin.zcash.ui.screen.swap.orconfirmation

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    internal var clock: Clock = Clock.System

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ORSwapConfirmationState?> =
        swapRepository.quote
            .transformLatest<SwapQuoteData?, SwapQuoteData.Success?> { publication ->
                emit(null)
                _dialogState.value = null
                if (publication is SwapQuoteData.Success) {
                    try {
                        saveORSwap.persistBeforeFunding(publication.quote)
                        if (swapRepository.quote.value === publication) emit(publication)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (swapRepository.quote.value === publication) showTrackingError()
                    }
                }
            }
            .combine(flow {
                while (true) {
                    emit(clock.now())
                    delay(FUNDING_CLOCK_INTERVAL_MILLIS)
                }
            }) { publication, _ -> publication }
            .map { publication ->
                if (publication == null || swapRepository.quote.value !== publication) return@map null
                val quote = publication.quote
                val fundingEnabled = canFund(publication)
                ORSwapConfirmationState(
                    fundingEnabled = fundingEnabled,
                    expiryMessage = if (fundingEnabled) null else stringRes(R.string.private_swap_funding_expired),
                    freshQuoteButton = if (fundingEnabled) null else ButtonState(
                        text = stringRes(R.string.private_swap_fresh_quote),
                        onClick = ::onCancelSwapClick,
                    ),
                    onBack = { onBack(publication) },
                    info =
                        IconButtonState(
                            icon = co.electriccoin.zcash.ui.design.R.drawable.ic_info,
                            onClick = ::onInfoClick,
                        ),
                    bigIcon = quote.originAsset.tokenIcon,
                    smallIcon = quote.originAsset.chainIcon,
                    amount = stringResByNumber(quote.amountInFormatted),
                    amountFiat = stringResByDynamicCurrencyNumber(quote.amountInUsd, FiatCurrency.USD.symbol),
                    onAmountClick = {
                        if (canFund(publication)) copyToClipboard(quote.amountInFormatted.toPlainString())
                    },
                    qr = quote.depositAddress.address,
                    address = stringResByAddress(quote.depositAddress.address),
                    copyButton =
                        BigIconButtonState(
                            text = stringRes(co.electriccoin.zcash.ui.design.R.string.receive_copy),
                            icon = R.drawable.ic_copy,
                            onClick = { if (canFund(publication)) copyToClipboard(quote.depositAddress.address) }
                        ),
                    shareButton =
                        BigIconButtonState(
                            text = stringRes(co.electriccoin.zcash.ui.design.R.string.swapToZec_shareQR),
                            icon = R.drawable.ic_qr_code_other,
                            onClick = { onShareClick(publication) }
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
                            onClick = { onSentFundsClick(publication) }
                        ),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT, replayExpiration = kotlin.time.Duration.ZERO),
                initialValue = null
            )

    private fun showTrackingError() {
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
    }

    private var onSentFundsClickJob: Job? = null

    private fun canFund(publication: SwapQuoteData.Success): Boolean =
        swapRepository.quote.value === publication && clock.now() < publication.quote.deadline

    private fun onBack(publication: SwapQuoteData.Success) {
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
                            onClick = { onSentFundsClick(publication) }
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

    private fun onSentFundsClick(publication: SwapQuoteData.Success) {
        if (onSentFundsClickJob?.isActive == true || swapRepository.quote.value !== publication) return
        _dialogState.value = null
        onSentFundsClickJob = viewModelScope.launch { saveORSwap(publication) }
    }

    private fun onInfoClick() = navigationRouter.forward(DepositSwapInfoArgs)

    private fun onShareClick(publication: SwapQuoteData.Success) = viewModelScope.launch {
        if (!canFund(publication)) return@launch
        val quote = publication.quote
        shareQR(
            qrData = quote.depositAddress.address,
            shareText =
                stringRes(
                    R.string.swap_to_zec_share_text,
                    stringResByNumber(quote.amountInFormatted),
                    quote.originAsset.tokenTicker.uppercase(),
                    quote.originAsset.chainName,
                    CURRENCY_TICKER
                ),
            sharePickerText = stringRes("Swap Deposit Address"),
            filenamePrefix = "swap_deposit_address_",
            isAllowed = { canFund(publication) },
        )
    }
}

private const val FUNDING_CLOCK_INTERVAL_MILLIS = 1_000L
