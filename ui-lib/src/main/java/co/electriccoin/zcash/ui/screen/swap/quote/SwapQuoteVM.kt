package co.electriccoin.zcash.ui.screen.swap.quote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.ANDROID_STATE_FLOW_TIMEOUT
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.GiftCardAddressNotAllowedException
import co.electriccoin.zcash.ui.common.datasource.QuoteLowAmountException
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.IndependentPriceCheck
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.zcash.getTotal
import co.electriccoin.zcash.ui.common.model.zcash.getTotalFeesUsd
import co.electriccoin.zcash.ui.common.model.zcash.getTotalFeesZatoshi
import co.electriccoin.zcash.ui.common.model.zcash.getTotalUsd
import co.electriccoin.zcash.ui.common.provider.IndependentPriceProvider
import co.electriccoin.zcash.ui.common.provider.ApplicationStateProvider
import co.electriccoin.zcash.ui.common.provider.ResponseWithNearErrorException
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.usecase.CancelSwapQuoteUseCase
import co.electriccoin.zcash.ui.common.usecase.CancelSwapUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationArgs
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import kotlin.time.Clock

internal class SwapQuoteVM(
    observeProposal: ObserveProposalUseCase,
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    @Suppress("UNUSED_PARAMETER") applicationStateProvider: ApplicationStateProvider,
    private val swapRepository: SwapRepository,
    private val cancelSwapQuote: CancelSwapQuoteUseCase,
    private val cancelSwap: CancelSwapUseCase,
    private val swapQuoteSuccessMapper: SwapQuoteVMMapper,
    private val submitProposal: SubmitProposalUseCase,
    private val navigationRouter: NavigationRouter,
    private val independentPrices: IndependentPriceProvider,
) : ViewModel() {
    private companion object {
        const val PRICE_CLOCK_INTERVAL_MILLIS = 1_000L
        const val PRICE_REVIEW_BUDGET_MILLIS = 8_000L
    }

    private var isClosing = false
    private val priceCheck = MutableStateFlow<BoundPriceCheck?>(null)
    private val proposals = observeProposal.observeNullable().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val clock = flow {
        while (true) {
            emit(Clock.System.now().toEpochMilliseconds())
            delay(PRICE_CLOCK_INTERVAL_MILLIS)
        }
    }

    val state: StateFlow<SwapQuoteState?> =
        combine(
            swapRepository.quote,
            proposals,
            observeSelectedWalletAccount(),
            priceCheck,
            clock,
        ) { quote, proposal, account, check, now ->
            when (quote) {
                null -> unavailableState()
                SwapQuoteData.Loading -> null
                is SwapQuoteData.Error -> createErrorState(quote)
                is SwapQuoteData.Success -> if (account == null || quote.quote.requiresLocalProposal &&
                    !proposal.matchesFunding(quote.quote, account)) unavailableState()
                else createState(proposal, quote, account, check, now)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = if (swapRepository.quote.value == null) unavailableState() else null
        )

    init {
        viewModelScope.launch {
            combine(swapRepository.quote, proposals) { quote, proposal -> quote to proposal }
                .collectLatest { (result, proposal) ->
                    val quote = (result as? SwapQuoteData.Success)?.quote
                    if (quote == null || quote.requiresLocalProposal &&
                        (proposal as? SwapTransactionProposal)?.quote !== quote) {
                        priceCheck.value = null
                    } else {
                        val fee = quote.getTotalFeesZatoshi((proposal as? SwapTransactionProposal)?.proposal)
                            .convertZatoshiToZec()
                        priceCheck.value = BoundPriceCheck(quote, fee, IndependentPriceCheck.Checking)
                        // The transport can finish cancellation after the review deadline.
                        // Cancel it without making the review wait for its cleanup.
                        val request = viewModelScope.async { independentPrices.check(quote, fee) }
                        val checked = try {
                            withTimeoutOrNull(PRICE_REVIEW_BUDGET_MILLIS) { request.await() }
                                ?: IndependentPriceCheck.Unavailable
                        } finally {
                            request.cancel()
                        }
                        priceCheck.value = BoundPriceCheck(quote, fee, checked)
                    }
                }
        }
    }

    private fun createState(
        proposal: TransactionProposal?,
        quote: SwapQuoteData.Success,
        account: WalletAccount,
        check: BoundPriceCheck?,
        nowMillis: Long,
    ): SwapQuoteState.Success {
        val fee = quote.quote.getTotalFeesZatoshi((proposal as? SwapTransactionProposal)?.proposal)
            .convertZatoshiToZec()
        val assessment = check.current(quote.quote, fee, nowMillis)
        fun reviewedAction(action: () -> Unit) {
            val currentPublication = swapRepository.quote.value
            val now = Clock.System.now()
            val proposalChanged = quote.quote.requiresLocalProposal && proposals.value !== proposal
            val invalidReview = currentPublication !== quote || !quote.canFund(now) || proposalChanged
            if (isClosing || invalidReview) return
            val current = priceCheck.value.current(quote.quote, fee, now.toEpochMilliseconds())
            if (current != assessment || current == IndependentPriceCheck.Checking) return
            action()
        }
        return swapQuoteSuccessMapper.createState(
            state = SwapQuoteInternalState(proposal as? SwapTransactionProposal, quote.quote, account),
            onBack = { if (swapRepository.quote.value === quote) onBack() },
            onSubmitQuoteClick = { reviewedAction { onSubmitQuoteClick(proposal, quote) } },
            onNavigateToOnRampSwap = { reviewedAction { onNavigateToOnRampSwap() } },
            priceCheck = assessment,
            canFund = { quote.canFund(Clock.System.now()) },
        )
    }

    private fun onNavigateToOnRampSwap() = navigationRouter.forward(ORSwapConfirmationArgs)

    private fun createErrorState(quote: SwapQuoteData.Error): SwapQuoteState.Error {
        val message =
            when (quote.exception) {
                is GiftCardAddressNotAllowedException -> {
                    stringRes(R.string.swap_error_giftCardLink)
                }

                is QuoteLowAmountException if quote.exception.amountFormatted != null -> {
                    stringRes(
                        R.string.swap_quote_error_too_low_try_at_least,
                        stringResByCurrencyNumber(
                            amount = quote.exception.amountFormatted,
                            ticker = quote.exception.asset.tokenTicker,
                            minDecimals = 0,
                        )
                    )
                }

                is QuoteLowAmountException -> {
                    stringRes(R.string.swap_quote_error_too_low_try_higher)
                }

                is ResponseWithNearErrorException if !quote.exception.error.message
                    .contains("failed to get quote", ignoreCase = true) -> {
                    stringRes(quote.exception.error.message)
                }

                else -> {
                    when (quote.mode) {
                        EXACT_INPUT, FLEX_INPUT -> stringRes(R.string.swap_quoteUnavailableSwap)
                        EXACT_OUTPUT -> stringRes(R.string.swap_quoteUnavailable)
                    }
                }
            }

        return SwapQuoteState.Error(
            icon = imageRes(R.drawable.ic_swap_quote_error),
            title = stringRes(R.string.swapAndPay_quoteUnavailable),
            subtitle = message,
            negativeButton =
                ButtonState(
                    text =
                        when (quote.mode) {
                            EXACT_INPUT, FLEX_INPUT -> stringRes(R.string.swapAndPay_cancelSwap)
                            EXACT_OUTPUT -> stringRes(R.string.swapAndPay_cancelPayment)
                        },
                    onClick = ::onCancelPaymentClick
                ),
            positiveButton =
                ButtonState(
                    text =
                        when (quote.mode) {
                            EXACT_INPUT, FLEX_INPUT -> stringRes(R.string.swapAndPay_editSwap)
                            EXACT_OUTPUT -> stringRes(R.string.swapAndPay_editPayment)
                        },
                    onClick = ::onBack
                ),
            onBack = ::onBack
        )
    }

    internal fun unavailableState() = SwapQuoteState.Error(
        icon = imageRes(R.drawable.ic_swap_quote_error),
        title = stringRes(R.string.swapAndPay_quoteUnavailable),
        subtitle = stringRes(R.string.private_quote_unavailable_message),
        negativeButton = null,
        positiveButton = ButtonState(text = stringRes(R.string.general_back), onClick = ::dismissUnavailable),
        onBack = ::dismissUnavailable,
    )

    private fun dismissUnavailable() = closeQuote { navigationRouter.back() }

    internal fun onBack() = closeQuote { cancelSwapQuote() }

    private fun closeQuote(action: () -> Unit) {
        if (isClosing) return
        isClosing = true
        action()
    }

    private fun onSubmitQuoteClick(proposal: TransactionProposal?, quote: SwapQuoteData.Success) =
        viewModelScope.launch { submitProposal(proposal, quote) }

    private fun onCancelPaymentClick() = closeQuote { cancelSwap() }
}

private fun TransactionProposal?.matchesFunding(quote: SwapQuote, account: WalletAccount): Boolean =
    this is SwapTransactionProposal && this.quote === quote &&
        fundingAccount.sdkAccount.accountUuid == account.sdkAccount.accountUuid

private val SwapQuote.requiresLocalProposal: Boolean
    get() = co.electriccoin.zcash.ui.common.model.ZecSwapComposition.funding(mode) ==
        co.electriccoin.zcash.wallet.foundation.FundingCapability.LOCAL_TRANSACTION

internal data class SwapQuoteInternalState(
    val proposal: SwapTransactionProposal?,
    val quote: SwapQuote,
    val account: WalletAccount,
) {
    val total: BigDecimal = quote.getTotal(proposal?.proposal)
    val totalUsd: BigDecimal = quote.getTotalUsd(proposal?.proposal)
    val totalFeesZatoshi: Zatoshi = quote.getTotalFeesZatoshi(proposal?.proposal)
    val totalFeesUsd: BigDecimal = quote.getTotalFeesUsd(proposal?.proposal)
}

private data class BoundPriceCheck(
    val quote: SwapQuote,
    val feeZec: BigDecimal,
    val assessment: IndependentPriceCheck,
)

private fun BoundPriceCheck?.current(quote: SwapQuote, fee: BigDecimal, nowMillis: Long): IndependentPriceCheck {
    if (this == null || this.quote !== quote || feeZec.compareTo(fee) != 0) return IndependentPriceCheck.Checking
    val checked = assessment
    return if (checked is IndependentPriceCheck.Compared && checked.validUntilMillis <= nowMillis) {
        IndependentPriceCheck.Unavailable
    } else {
        checked
    }
}
