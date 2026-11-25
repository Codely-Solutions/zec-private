package co.electriccoin.zcash.ui.screen.swap.orconfirmation

import android.os.SystemClock
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.FiatCurrency
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.forwardFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.SwapMode
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
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityHistoryArgs
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
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
import kotlin.time.Duration

class ORSwapConfirmationVM(
    private val swapRepository: SwapRepository,
    private val cancelSwapQuote: CancelSwapQuoteUseCase,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val navigationRouter: NavigationRouter,
    private val saveORSwap: SaveORSwapUseCase,
    private val shareQR: ShareQRUseCase,
    private val appAccessSession: AppAccessSession,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }

    private val _dialogState = MutableStateFlow<ErrorState?>(null)
    val dialogState = _dialogState.asStateFlow()

    private val retryTracking = MutableStateFlow(0)
    private val _saving = MutableStateFlow(
        (swapRepository.quote.value as? SwapQuoteData.Success)?.let(::isCurrent) == true
    )
    val saving = _saving.asStateFlow()
    private var fundingAcknowledgement: SaveORSwapUseCase.FundingAcknowledgement? = null
    internal var clock: Clock = Clock.System
    internal var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ORSwapConfirmationState?> =
        combine(swapRepository.quote, retryTracking) { publication, _ -> publication }
            .transformLatest<SwapQuoteData?, SaveORSwapUseCase.FundingAcknowledgement?> { publication ->
                _saving.value = publication is SwapQuoteData.Success && isCurrent(publication)
                emit(null)
                _dialogState.value = null
                if (publication is SwapQuoteData.Success && isCurrent(publication)) {
                    val acknowledgement = try {
                        fundingAcknowledgement?.takeIf { it.publication === publication }
                            ?: saveORSwap.acknowledgeFunding(publication)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (isCurrent(publication)) showTrackingError(publication)
                        null
                    } finally {
                        _saving.value = false
                    }
                    if (acknowledgement != null && isCurrent(publication)) {
                        fundingAcknowledgement = acknowledgement
                        emit(acknowledgement)
                    }
                }
            }
            .combine(flow {
                while (true) {
                    emit(Unit)
                    delay(FUNDING_CLOCK_INTERVAL_MILLIS)
                }
            }) { acknowledgement, _ -> acknowledgement }
            .combine(appAccessSession.grant, ::Pair)
            .map { (acknowledgement, grant) ->
                val publication = acknowledgement?.publication ?: return@map null
                if (!isCurrent(publication)) return@map null
                val quote = publication.quote
                val fundingEnabled = canFund(publication)
                ORSwapConfirmationState(
                    fundingEnabled = fundingEnabled,
                    expiryMessage = if (fundingEnabled) null else stringRes(R.string.private_swap_funding_expired),
                    freshQuoteButton = if (fundingEnabled) null else ButtonState(
                        text = stringRes(R.string.private_swap_fresh_quote),
                        onClick = { onCancelSwapClick(publication) },
                    ),
                    onBack = { onBack(acknowledgement) },
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
                            onClick = { onShareClick(publication, grant) }
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
                            onClick = { onSentFundsClick(acknowledgement) }
                        ),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(
                    stopTimeout = ANDROID_STATE_FLOW_TIMEOUT,
                    replayExpiration = Duration.ZERO,
                ),
                initialValue = null
            )

    private fun showTrackingError(publication: SwapQuoteData.Success) {
        _dialogState.value = ErrorState(
            title = stringRes(R.string.private_swap_tracking_failed_title),
            message = stringRes(R.string.private_swap_tracking_failed),
            positive = ButtonState(
                text = stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain),
                onClick = { if (isCurrent(publication)) retryTracking.value += 1 },
            ),
            negative = ButtonState(
                text = stringRes(R.string.swapAndPay_cancelSwap),
                onClick = { onCancelSwapClick(publication) },
            ),
            onBack = { onCancelSwapClick(publication) },
        )
    }

    private fun canFund(publication: SwapQuoteData.Success): Boolean =
        isCurrent(publication) && publication.canFund(clock.now(), elapsedRealtime())

    internal fun openActivity() {
        viewModelScope.launch {
            navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.ReplaceAll(listOf(ActivityHistoryArgs)) }
        }
    }

    private fun isCurrent(publication: SwapQuoteData.Success): Boolean =
        publication.quote.mode == SwapMode.FLEX_INPUT && swapRepository.quote.value === publication &&
            (publication.incomingOwner == null || saveORSwap.isWalletCurrent(publication))

    private fun onBack(acknowledgement: SaveORSwapUseCase.FundingAcknowledgement) {
        if (!isCurrent(acknowledgement.publication)) return
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
                            onClick = { onSentFundsClick(acknowledgement) }
                        ),
                    negative =
                        ButtonState(
                            text = stringRes(R.string.swapAndPay_cancelSwap),
                            onClick = { onCancelSwapClick(acknowledgement.publication) }
                        ),
                    onBack = { _dialogState.value = null }
                )
        }
    }

    private fun onCancelSwapClick(publication: SwapQuoteData.Success) {
        if (!isCurrent(publication)) return
        _dialogState.value = null
        if (sourceEntryId == null) {
            cancelSwapQuote()
        } else {
            viewModelScope.launch { cancelSwapQuote.cancelFrom(sourceEntryId) { isCurrent(publication) } }
        }
    }

    private fun onSentFundsClick(acknowledgement: SaveORSwapUseCase.FundingAcknowledgement) {
        if (!isCurrent(acknowledgement.publication)) return
        _dialogState.value = null
        if (sourceEntryId == null) {
            saveORSwap.finishFunding(acknowledgement)
        } else {
            viewModelScope.launch { saveORSwap.finishFunding(acknowledgement, sourceEntryId) }
        }
    }

    private fun onInfoClick() = navigationRouter.forwardFrom(sourceEntryId, { true }) { DepositSwapInfoArgs }

    private fun onShareClick(
        publication: SwapQuoteData.Success,
        grant: AppAccessSession.Grant?,
    ) = viewModelScope.launch {
        val isAllowed = { appAccessSession.permits(grant) && canFund(publication) }
        if (!isAllowed()) return@launch
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
            isAllowed = isAllowed,
        )
    }
}

private const val FUNDING_CLOCK_INTERVAL_MILLIS = 1_000L
