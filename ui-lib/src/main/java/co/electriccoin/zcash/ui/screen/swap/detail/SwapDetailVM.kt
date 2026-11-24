package co.electriccoin.zcash.ui.screen.swap.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.mapper.SwapSupportMapper
import co.electriccoin.zcash.ui.common.mapper.originalQuoteSupportButton
import co.electriccoin.zcash.ui.common.model.positiveRefund
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.SwapStatus.EXPIRED
import co.electriccoin.zcash.ui.common.model.SwapStatus.FAILED
import co.electriccoin.zcash.ui.common.model.SwapStatus.INCOMPLETE_DEPOSIT
import co.electriccoin.zcash.ui.common.model.SwapStatus.PENDING
import co.electriccoin.zcash.ui.common.model.SwapStatus.PROCESSING
import co.electriccoin.zcash.ui.common.model.SwapStatus.REFUNDED
import co.electriccoin.zcash.ui.common.model.SwapStatus.SUCCESS
import co.electriccoin.zcash.ui.common.usecase.CopyToClipboardUseCase
import co.electriccoin.zcash.ui.common.usecase.GetReloadableSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.SwapData
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.loadingImageRes
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByDateTime
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.swap.detail.support.SwapSupportArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.CommonTransactionDetailMapper
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailHeaderState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoRowState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRowState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.toDetailStatusRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import co.electriccoin.zcash.ui.util.CURRENCY_TICKER
import java.time.ZoneId

class SwapDetailVM(
    getReloadableSwapQuote: GetReloadableSwapQuoteUseCase,
    private val args: SwapDetailArgs,
    private val navigationRouter: NavigationRouter,
    private val copyToClipboard: CopyToClipboardUseCase,
    private val mapper: CommonTransactionDetailMapper,
    private val getSwapMessage: SwapSupportMapper,
) : ViewModel() {
    val state: StateFlow<SwapDetailState?> =
        getReloadableSwapQuote
            .observe(args.depositAddress)
            .map { swapData ->
                val hasDetails = swapData.status != null || swapData.savedMetadata != null
                SwapDetailState(
                    transactionHeader = createTransactionHeaderState(swapData),
                    quoteHeader =
                        mapper
                            .createTransactionDetailQuoteHeaderState(
                                swap = swapData.status,
                                originAsset = swapData.status?.originAsset,
                                destinationAsset = swapData.status?.destinationAsset,
                                savedMetadata = swapData.savedMetadata,
                            ),
                    status = swapData.data.toDetailStatusRow(mode = swapData.statusRowMode),
                    depositTo =
                        TransactionDetailInfoRowState(
                            title = stringRes(R.string.private_swap_deposit_reference),
                            message = stringResByAddress(args.depositAddress),
                        ),
                    recipient = createRecipientState(swapData),
                    totalFees = createQuotedOutputState(swapData),
                    maxSlippage = createSlippageState(swapData),
                    timestamp =
                        TransactionDetailInfoRowState(
                            title = stringRes(
                                if (swapData.status == null && hasDetails) {
                                    R.string.private_swap_created_at
                                } else {
                                    R.string.transactionHistory_timestamp
                                }
                            ),
                            message =
                                (swapData.status?.timestamp ?: swapData.savedMetadata?.lastUpdated)
                                    ?.atZone(ZoneId.systemDefault())
                                    ?.let {
                                        stringResByDateTime(
                                            zonedDateTime = it,
                                            useFullFormat = true
                                        )
                                    }?.withStyle(),
                        ),
                    message = getSwapMessage.getMessage(swapData.status),
                    errorFooter = mapper.createTransactionDetailErrorFooter(swapData.error).takeUnless { hasDetails },
                    infoFooter = when {
                        hasDetails && swapData.error != null -> stringRes(R.string.private_swap_refresh_unavailable)
                        hasDetails && swapData.isLoading -> stringRes(R.string.private_swap_refreshing)
                        swapData.status?.status == PENDING -> stringRes(R.string.deposits_info)
                        else -> null
                    },
                    primaryButton = createPrimaryButtonState(swapData, swapData.error),
                    supportButton = if (swapData.error != null) {
                        swapData.savedMetadata?.originalQuoteSupportButton(::onContactSupport)
                    } else {
                        null
                    },
                    onBack = ::onBack,
                    showLiveDetails = swapData.status != null || !hasDetails && swapData.error == null,
                    explorerLinks = swapData.status?.let { status ->
                        val testnet = co.electriccoin.zcash.ui.common.model.VersionInfo.NETWORK ==
                            cash.z.ecc.android.sdk.model.ZcashNetwork.Testnet
                        co.electriccoin.zcash.ui.screen.transactiondetail.explorerLinks(
                            chain = status.originAsset.blockchain.chainTicker,
                            ids = status.originTransactionIds,
                            label = stringRes(R.string.private_explorer_deposit, status.originAsset.chainName),
                            testnet = testnet,
                            router = navigationRouter
                        ) + co.electriccoin.zcash.ui.screen.transactiondetail.explorerLinks(
                            chain = status.destinationAsset.blockchain.chainTicker,
                            ids = status.destinationTransactionIds,
                            label = stringRes(R.string.private_explorer_destination, status.destinationAsset.chainName),
                            testnet = testnet,
                            router = navigationRouter
                        )
                    }.orEmpty(),
                    refundedAmount = swapData.status?.positiveRefund?.let { refund ->
                        TransactionDetailInfoRowState(
                            title = stringRes(R.string.swapAndPay_refundedAmount),
                            message = stringResByCurrencyNumber(
                                amount = refund, ticker = swapData.status.originAsset.tokenTicker
                            ).withStyle(),
                        )
                    },
                    donationBasisZec = swapData.status?.takeIf {
                        !swapData.isOutgoing && it.status == SUCCESS && it.destinationAsset.tokenTicker == "ZEC"
                    }?.executedAmountOut?.takeIf { it.signum() > 0 }?.toPlainString(),
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
                initialValue = null
            )

    private fun onContactSupport(depositAddress: String) {
        navigationRouter.forward(SwapSupportArgs(depositAddress))
    }

    private val SwapData.isOutgoing: Boolean
        get() = (status?.mode ?: savedMetadata?.mode)?.let { it != FLEX_INPUT } == true

    private val SwapData.statusRowMode: TransactionDetailSwapStatusRowState.Mode
        get() = if (isOutgoing) {
            TransactionDetailSwapStatusRowState.Mode.SWAP_FROM_ZEC
        } else {
            TransactionDetailSwapStatusRowState.Mode.SWAP_INTO_ZEC
        }

    private fun createQuotedOutputState(swapData: SwapData): TransactionDetailInfoRowState =
        TransactionDetailInfoRowState(
            title = stringRes(R.string.private_quoted_output),
            message =
                (swapData.savedMetadata?.quotedAmountOut ?: swapData.status?.quotedAmountOut)?.let {
                    val ticker = swapData.savedMetadata?.destination?.tokenTicker
                        ?: swapData.status?.destinationAsset?.tokenTicker ?: CURRENCY_TICKER
                    stringResByCurrencyNumber(amount = it, ticker = ticker).withStyle()
                }
        )

    private fun createRecipientState(swapData: SwapData): TransactionDetailInfoRowState {
        val destinationAddress =
            swapData.status
                ?.destinationAddress
                ?.address
        return TransactionDetailInfoRowState(
            title = stringRes(R.string.swapAndPay_recipient),
            message = destinationAddress?.let { stringResByAddress(it) },
            trailingIcon = R.drawable.ic_transaction_detail_info_copy,
            onClick =
                if (destinationAddress != null) {
                    { onCopyRecipientAddressClick(destinationAddress) }
                } else {
                    null
                }
        )
    }

    private fun createSlippageState(swapData: SwapData): TransactionDetailInfoRowState =
        TransactionDetailInfoRowState(
            title =
                if (swapData.status?.isSlippageRealized == true) {
                    stringRes(R.string.swapAndPay_executedSlippage)
                } else {
                    stringRes(R.string.swapAndPay_maxSlippageTitle)
                },
            message =
                swapData.status
                    ?.maxSlippage
                    ?.let {
                        stringResByNumber(it, 0) + stringRes("%")
                    }?.withStyle(),
        )

    private fun createPrimaryButtonState(
        swapData: SwapData,
        error: Exception?
    ): ButtonState? {
        if (swapData.error != null && (swapData.status != null || swapData.savedMetadata != null)) {
            return ButtonState(
                text = stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain),
                onClick = { swapData.handle.requestReload() },
                style = ButtonStyle.SECONDARY,
            )
        }
        val supportButton =
            getSwapMessage.getButton(swapData.status) {
                onContactSupport(it)
            }
        return when {
            supportButton != null -> {
                supportButton
            }

            swapData.error != null && swapData.status == null -> {
                mapper.createTransactionDetailErrorButtonState(
                    error = error,
                    reloadHandle = swapData.handle
                )
            }

            else -> {
                swapData.savedMetadata?.originalQuoteSupportButton(::onContactSupport)
            }
        }
    }

    private fun createTransactionHeaderState(swapData: SwapData): TransactionDetailHeaderState =
        TransactionDetailHeaderState(
            title = createHeaderTitle(swapData),
            amount =
                (swapData.status?.executedAmountOut
                    ?: swapData.savedMetadata?.executedAmountOut
                    ?: swapData.savedMetadata?.legacySuccessfulOutput
                    ?: swapData.status?.amountOutFormatted
                    ?: swapData.savedMetadata?.amountOutFormatted?.takeIf { it.signum() > 0 })
                    ?.let { stringResByNumber(it) },
            icons =
                listOf(
                    swapData.status
                        ?.originAsset
                        ?.tokenIcon ?: swapData.savedMetadata?.origin?.tokenIcon ?: loadingImageRes(),
                    imageRes(
                        if (swapData.isOutgoing) R.drawable.ic_transaction_sent else R.drawable.ic_transaction_received
                    ),
                    swapData.status?.destinationAsset?.tokenIcon ?: swapData.savedMetadata?.destination?.tokenIcon
                        ?: imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                ),
        )

    private fun createHeaderTitle(swapData: SwapData): StringResource? =
        when (swapData.status?.status ?: swapData.savedMetadata?.status) {
            EXPIRED -> stringRes(R.string.swapStatus_swapExpired)
            PENDING -> stringRes(
                if (swapData.isOutgoing) R.string.private_swap_funding_unconfirmed
                else R.string.swapAndPay_status_pendingDeposit
            )
            INCOMPLETE_DEPOSIT -> stringRes(R.string.swapStatus_swapIncomplete)
            PROCESSING -> stringRes(R.string.swapToZec_swapProcessing)
            SUCCESS -> createSuccessfulOutputTitle(swapData)
            REFUNDED -> stringRes(R.string.swapStatus_swapRefunded)
            FAILED -> stringRes(R.string.swapStatus_swapFailed)
            null -> null
        }

    private fun createSuccessfulOutputTitle(swapData: SwapData): StringResource =
        stringRes(
            when {
                swapData.status?.executedAmountOut != null -> R.string.private_provider_output
                swapData.savedMetadata?.executedAmountOut != null -> R.string.private_saved_provider_output
                swapData.savedMetadata?.legacySuccessfulOutput != null -> R.string.private_swap_saved_output
                swapData.status != null || swapData.savedMetadata?.quotedAmountOut != null ->
                    R.string.private_estimated_output
                else -> R.string.private_swap_saved_output
            }
        )

    private fun onBack() = navigationRouter.back()

    private fun onCopyRecipientAddressClick(recipient: String) = copyToClipboard(recipient)
}
