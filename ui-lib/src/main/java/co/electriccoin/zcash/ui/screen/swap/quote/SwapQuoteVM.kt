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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

internal class SwapQuoteVM(
    observeProposal: ObserveProposalUseCase,
    observeSelectedWalletAccount: ObserveSelectedWalletAccountUseCase,
    applicationStateProvider: ApplicationStateProvider,
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
    private val clock = flow {
        while (true) {
            emit(Clock.System.now().toEpochMilliseconds())
            delay(PRICE_CLOCK_INTERVAL_MILLIS)
        }
    }

    val state: StateFlow<SwapQuoteState?> =
        combine(
            swapRepository.quote.filterNotNull(),
            observeProposal.observeNullable(),
            observeSelectedWalletAccount.require(),
            priceCheck,
            clock,
        ) { quote, proposal, account, check, now ->
            when (quote) {
                SwapQuoteData.Loading -> null
                is SwapQuoteData.Error -> createErrorState(quote)
                is SwapQuoteData.Success -> createState(proposal, quote, account, check, now)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(ANDROID_STATE_FLOW_TIMEOUT),
            initialValue = null
        )

    init {
        viewModelScope.launch {
            combine(swapRepository.quote, observeProposal.observeNullable()) { quote, proposal -> quote to proposal }
                .collectLatest { (result, proposal) ->
                    val quote = (result as? SwapQuoteData.Success)?.quote
                    if (quote == null) {
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
        applicationStateProvider
            .observeOnForeground()
            .onEach {
                val quote = (swapRepository.quote.value as? SwapQuoteData.Success)?.quote ?: return@onEach

                if ((Clock.System.now() - quote.timestamp) >= 3.minutes) {
                    onBack()
                }
            }.launchIn(viewModelScope)
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
            val currentQuote = (swapRepository.quote.value as? SwapQuoteData.Success)?.quote
            val current = priceCheck.value.current(quote.quote, fee, Clock.System.now().toEpochMilliseconds())
            if (isClosing || currentQuote !== quote.quote) return
            if (current != assessment || current == IndependentPriceCheck.Checking) return
            action()
        }
        return swapQuoteSuccessMapper.createState(
            state = SwapQuoteInternalState(proposal as? SwapTransactionProposal, quote.quote, account),
            onBack = ::onBack,
            onSubmitQuoteClick = { reviewedAction { onSubmitQuoteClick() } },
            onNavigateToOnRampSwap = { reviewedAction { onNavigateToOnRampSwap() } },
            priceCheck = assessment,
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
                    onClick = ::onEditPaymentClick
                ),
            onBack = ::onBackDuringError
        )
    }

    private fun onEditPaymentClick() = onBack()

    private fun onBack() = closeQuote { cancelSwapQuote() }

    private fun closeQuote(action: () -> Unit) {
        if (isClosing) return
        isClosing = true
        action()
    }

    private fun onBackDuringError() = onBack()

    private fun onSubmitQuoteClick() = viewModelScope.launch { submitProposal() }

    private fun onCancelPaymentClick() = closeQuote { cancelSwap() }
}

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
