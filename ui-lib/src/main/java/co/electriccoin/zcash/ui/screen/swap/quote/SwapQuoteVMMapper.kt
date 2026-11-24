package co.electriccoin.zcash.ui.screen.swap.quote

import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.FiatCurrency
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_INPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
import co.electriccoin.zcash.ui.common.model.SwapMode.FLEX_INPUT
import co.electriccoin.zcash.ui.common.model.IndependentPriceCheck
import co.electriccoin.zcash.ui.common.model.isZCashAsset
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.SwapTokenAmountState
import co.electriccoin.zcash.ui.design.component.QuoteValueDifferenceState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.design.util.stringResByCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.transactiondetail.swapQuoteChainIcon
import co.electriccoin.zcash.ui.screen.transactiondetail.swapQuoteTokenIcon
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Instant

internal class SwapQuoteVMMapper {
    @Suppress("UseCheckOrError")
    fun createState(
        state: SwapQuoteInternalState,
        onBack: () -> Unit,
        onSubmitQuoteClick: () -> Unit,
        onNavigateToOnRampSwap: () -> Unit,
        priceCheck: IndependentPriceCheck? = null,
        assessmentTime: Instant = Clock.System.now(),
        canFund: () -> Boolean = { Clock.System.now() < state.quote.deadline },
    ): SwapQuoteState.Success =
        with(state) {
            val expired = !canFund()
            return SwapQuoteState.Success(
                title =
                    when {
                        quote.destinationAsset.isZCashAsset -> stringRes(R.string.swapToZec_review)
                        quote.mode in listOf(EXACT_INPUT, FLEX_INPUT) -> stringRes(R.string.swapAndPay_swapNow)
                        quote.mode == EXACT_OUTPUT -> stringRes(R.string.swapAndPay_payNow)
                        else -> throw IllegalStateException("Unknown swap mode")
                    },
                rotateIcon = quote.mode == EXACT_OUTPUT,
                from = createFromState(),
                to = createToState(),
                items = createItems(),
                amount = createTotalAmountState(assessmentTime),
                onBack = onBack,
                infoText = createInfoText(),
                priceCheck = priceCheck?.let(::mapPriceCheck),
                minimumDeposit = quote.minimumDepositInfo(),
                primaryButton =
                    ButtonState(
                        text = when {
                            expired -> stringRes(R.string.private_swap_fresh_quote)
                            priceCheck is IndependentPriceCheck.Compared && priceCheck.poor ->
                                stringRes(if (quote.mode == EXACT_OUTPUT) R.string.private_pay_anyway
                                else R.string.private_swap_anyway)
                            else -> stringRes(co.electriccoin.zcash.ui.design.R.string.general_confirm)
                        },
                        isEnabled =
                            expired || canReview(priceCheck),
                        onClick = {
                            if (canFund()) reviewAction(onNavigateToOnRampSwap, onSubmitQuoteClick) else onBack()
                        }
                    )
            )
        }

    private fun SwapQuoteInternalState.canReview(priceCheck: IndependentPriceCheck?): Boolean =
        (quote.destinationAsset.isZCashAsset || proposal?.quote === quote) &&
            priceCheck != IndependentPriceCheck.Checking

    private fun SwapQuoteInternalState.reviewAction(onIncoming: () -> Unit, onLocal: () -> Unit) {
        when {
            quote.destinationAsset.isZCashAsset -> onIncoming()
            proposal?.quote === quote -> onLocal()
        }
    }

    @Suppress("MagicNumber")
    private fun SwapQuoteInternalState.createInfoText(): StringResource? {
        if (quote.mode == EXACT_OUTPUT) return null
        val minimum = quote.minimumAmountOut?.let {
            stringRes(
                R.string.private_minimum_received,
                stringResByCurrencyNumber(it, quote.destinationAsset.tokenTicker, minDecimals = 0)
            )
        }
        val slippageUsd = quote.amountOutUsd.multiply(quote.slippage.divide(BigDecimal(100)))
        return minimum ?: stringRes(
            R.string.swapAndPay_swapQuoteSlippageWarn,
            stringResByDynamicCurrencyNumber(slippageUsd, FiatCurrency.USD.symbol),
            stringResByNumber(quote.slippage, minDecimals = 0) + stringRes("%")
        )
    }

    @Suppress("MagicNumber")
    private fun SwapQuoteInternalState.createItems(): List<SwapQuoteInfoItem> =
        listOfNotNull(
            SwapQuoteInfoItem(
                description =
                    when (quote.mode) {
                        EXACT_INPUT, FLEX_INPUT -> stringRes(R.string.swapAndPay_swapFrom)
                        EXACT_OUTPUT -> stringRes(R.string.swapAndPay_payFrom)
                    },
                title = account.name.withStyle(),
                subtitle = null
            ).takeIf { !quote.destinationAsset.isZCashAsset },
            SwapQuoteInfoItem(
                description =
                    when (quote.mode) {
                        EXACT_INPUT, FLEX_INPUT -> stringRes(R.string.swapAndPay_swapTo)
                        EXACT_OUTPUT -> stringRes(R.string.swapAndPay_payTo)
                    },
                title = stringResByAddress(quote.destinationAddress.address),
                subtitle = null
            ).takeIf { !quote.destinationAsset.isZCashAsset },
            SwapQuoteInfoItem(
                description = stringRes(R.string.private_wallet_transaction_fee),
                title = stringRes(totalFeesZatoshi).withStyle(),
                subtitle =
                    stringResByDynamicCurrencyNumber(totalFeesUsd, FiatCurrency.USD.symbol)
                        .takeIf {
                            quote.mode == EXACT_OUTPUT
                        }?.withStyle()
            ).takeIf { !quote.destinationAsset.isZCashAsset && totalFeesZatoshi.value > 0 },
            SwapQuoteInfoItem(
                description = stringRes(R.string.private_provider_costs),
                title = stringRes(R.string.private_provider_costs_included).withStyle(),
                subtitle = null,
                layout = SwapQuoteInfoLayout.STACKED
            ),
            if (quote.mode == EXACT_OUTPUT) {
                val slippage = quote.slippage.divide(BigDecimal("100"))
                val allowance = quote.inputAllowance ?: quote.amountInFormatted.multiply(slippage)
                val slippageUsd = quote.amountInUsd
                    .divide(quote.amountInFormatted, MathContext.DECIMAL128)
                    .multiply(allowance)
                val slippageZatoshi =
                    allowance.convertZecToZatoshi()

                SwapQuoteInfoItem(
                    description =
                        stringRes(
                            R.string.private_included_input_allowance,
                            stringResByNumber(quote.slippage, minDecimals = 0) + stringRes("%")
                        ),
                    title = stringRes(slippageZatoshi).withStyle(),
                    subtitle =
                        quoteFiatAmount(slippageUsd, quote.amountInUsd.signum() > 0).withStyle()
                )
            } else {
                null
            }
        )

    private fun SwapQuoteInternalState.createTotalAmountState(assessmentTime: Instant): SwapQuoteInfoItem =
        SwapQuoteInfoItem(
            description =
                if (quote.destinationAsset.isZCashAsset) {
                    stringRes(R.string.swapAndPay_totalAmount)
                } else {
                    stringRes(R.string.private_total_zec_to_spend)
                },
            title = stringResByCurrencyNumber(total, quote.originAsset.tokenTicker, minDecimals = 0).withStyle(),
            subtitle = quoteFiatAmount(totalUsd, quote.amountInUsd.signum() > 0).withStyle()
        )

    private fun SwapQuoteInternalState.createFromState(): SwapTokenAmountState =
        SwapTokenAmountState(
            bigIcon = quote.originAsset.swapQuoteTokenIcon(),
            smallIcon = quote.originAsset.swapQuoteChainIcon(isShielded = true),
            amount = stringResByNumber(quote.amountInFormatted, minDecimals = 0),
            fiatAmount = quoteFiatAmount(quote.amountInUsd),
            token = stringRes(quote.originAsset.tokenTicker),
            chain = quote.originAsset.chainName
        )

    private fun SwapQuoteInternalState.createToState(): SwapTokenAmountState =
        SwapTokenAmountState(
            bigIcon = quote.destinationAsset.swapQuoteTokenIcon(),
            smallIcon = quote.destinationAsset.swapQuoteChainIcon(isShielded = true),
            amount =
                stringResByNumber(
                    quote.amountOutFormatted.setScale(quote.destinationAsset.decimals, RoundingMode.DOWN),
                    minDecimals = 0,
                ),
            fiatAmount = quoteFiatAmount(quote.amountOutUsd),
            token = stringRes(quote.destinationAsset.tokenTicker),
            chain = quote.destinationAsset.chainName,
            valueDifference = createValueDifference(assessmentTime),
        )

    private fun SwapQuoteInternalState.createValueDifference(assessmentTime: Instant): QuoteValueDifferenceState? {
        val percentage = quoteValueDifference(
            totalSpendUsd = totalUsd,
            receivedUsd = quote.amountOutUsd,
            quotedAt = quote.timestamp,
            deadline = quote.deadline,
            now = assessmentTime,
        ) ?: return null
        val rounded = percentage.setScale(2, RoundingMode.HALF_UP)
        val signed = (if (rounded.signum() > 0) "+" else "") + rounded.toPlainString() + "%"
        val scope = when {
            quote.destinationAsset.isZCashAsset -> R.string.private_value_difference_external
            quote.mode == EXACT_OUTPUT -> R.string.private_value_difference_buffered
            else -> R.string.private_value_difference_known
        }
        return QuoteValueDifferenceState(
            percentage = stringRes(signed),
            explanation = stringRes(scope),
            isNegative = rounded.signum() < 0,
        )
    }

    private fun quoteFiatAmount(usd: BigDecimal, available: Boolean = usd.signum() > 0): StringResource =
        if (available) stringResByDynamicCurrencyNumber(usd, FiatCurrency.USD.symbol)
        else stringRes(R.string.private_quote_estimate_unavailable)
}

internal fun mapPriceCheck(check: IndependentPriceCheck): PriceCheckNoticeState = when (check) {
    IndependentPriceCheck.Checking -> PriceCheckNoticeState(
        stringRes(R.string.private_price_checking), stringRes(R.string.private_price_check_details)
    )
    IndependentPriceCheck.NoMarketReference -> PriceCheckNoticeState(
        stringRes(R.string.private_price_no_market),
        showInline = false,
    )
    IndependentPriceCheck.Unavailable -> PriceCheckNoticeState(
        stringRes(R.string.private_price_unavailable), stringRes(R.string.private_price_unavailable_details),
        showInline = false,
    )
    is IndependentPriceCheck.Compared -> if (check.poor) {
        PriceCheckNoticeState(
            label = stringRes(
                R.string.private_price_poor,
                check.differencePercent.abs().setScale(1, RoundingMode.HALF_UP).toPlainString(),
            ),
            explanation = stringRes(R.string.private_price_check_details),
            isWarning = true,
            detailItem = check.toMarketComparison(),
        )
    } else {
        PriceCheckNoticeState(
            label = stringRes(R.string.private_price_reference),
            showInline = false,
            detailItem = check.toMarketComparison(),
        )
    }
}

private fun IndependentPriceCheck.Compared.toMarketComparison(): SwapQuoteInfoItem {
    val rounded = differencePercent.setScale(2, RoundingMode.HALF_UP)
    val percentage = rounded.abs().toPlainString()
    val result = when (rounded.signum()) {
        -1 -> stringRes(R.string.private_price_less_value, percentage)
        1 -> stringRes(R.string.private_price_more_value, percentage)
        else -> stringRes(R.string.private_price_in_line)
    }
    return SwapQuoteInfoItem(
        description = stringRes(R.string.private_price_reference),
        title = result.withStyle(),
        help = SwapQuoteFieldHelp(
            label = stringRes(R.string.private_price_reference_help_label),
            text = stringRes(R.string.private_price_reference_help, sources.joinToString()),
        ),
    )
}
