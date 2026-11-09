package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.appbar.ZashiMainTopAppBarState
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.design.component.*
import co.electriccoin.zcash.ui.design.util.*
import co.electriccoin.zcash.ui.screen.swap.quote.*
import co.electriccoin.zcash.ui.screen.transactiondetail.*
import co.electriccoin.zcash.ui.screen.transactiondetail.info.SendSwapState

/** Inert synthetic scenes for review; no wallet/network dependencies or approval actions. */
@Composable
internal fun SwapSprintScene(screen: String, onBack: () -> Unit, appBar: ZashiMainTopAppBarState): Boolean {
    val zec = SwapTokenAmountState(stringRes("0.01"), stringRes("$0.40"), stringRes("ZEC"), stringRes("Zcash"), imageRes(R.drawable.ic_zec_round_full), imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_shielded))
    val btc = SwapTokenAmountState(stringRes("0.000004"), stringRes("$0.39"), stringRes("BTC"), stringRes("Bitcoin"), imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc), imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc))
    when (screen) {
        "sprint-mapper-crosspay", "sprint-mapper-missing" -> {
            val now = kotlin.time.Clock.System.now()
            val account = co.electriccoin.zcash.ui.common.model.ZodlAccount(
                sdkAccount = cash.z.ecc.android.sdk.model.Account.new(cash.z.ecc.android.sdk.model.AccountUuid.new(ByteArray(16))),
                unifiedAddress = "synthetic", transparentAddress = "synthetic", saplingAddress = "synthetic",
                orchardBalance = null, saplingBalance = null, ironwoodBalance = null, transparentBalance = null, isSelected = true,
            )
            val origin = co.electriccoin.zcash.ui.common.model.near.NearSwapAsset(
                tokenTicker = "ZEC", tokenName = stringRes("Zcash"), tokenIcon = zec.bigIcon,
                usdPrice = java.math.BigDecimal("100"), assetId = "synthetic-zec", decimals = 8,
                blockchain = co.electriccoin.zcash.ui.common.model.SwapBlockchain("zec", stringRes("Zcash"), zec.smallIcon),
            )
            val destination = origin.copy(tokenTicker = "BTC", tokenName = stringRes("Bitcoin"), tokenIcon = btc.bigIcon,
                assetId = "synthetic-btc", blockchain = co.electriccoin.zcash.ui.common.model.SwapBlockchain("btc", stringRes("Bitcoin"), btc.smallIcon))
            val quote = object : co.electriccoin.zcash.ui.common.model.SwapQuote {
                override val originAsset = origin
                override val destinationAsset = destination
                override val depositAddress = co.electriccoin.zcash.ui.common.model.DynamicSwapAddress("Synthetic deposit")
                override val destinationAddress = co.electriccoin.zcash.ui.common.model.DynamicSwapAddress("Synthetic destination")
                override val refundAddress = depositAddress
                override val provider = "synthetic"
                override val mode = co.electriccoin.zcash.ui.common.model.SwapMode.EXACT_OUTPUT
                override val amountIn = java.math.BigDecimal("10000000")
                override val amountInFormatted = java.math.BigDecimal("0.1")
                override val amountInUsd = java.math.BigDecimal("10")
                override val amountOut = java.math.BigDecimal("13157")
                override val amountOutFormatted = java.math.BigDecimal("0.00013157")
                override val amountOutUsd = if (screen == "sprint-mapper-missing") java.math.BigDecimal.ZERO else java.math.BigDecimal("9.85")
                override val inputAllowance = java.math.BigDecimal("0.0005")
                override val affiliateFee = java.math.BigDecimal.ZERO
                override val affiliateFeeUsd = java.math.BigDecimal.ZERO
                override val timestamp = now
                override val deadline = now + kotlin.time.Duration.parse("3m")
                override val slippage = java.math.BigDecimal("0.5")
            }
            SwapQuoteView(SwapQuoteVMMapper().createState(SwapQuoteInternalState(null, quote, account), onBack, {}, {}))
        }
        "sprint-incoming", "sprint-missing", "sprint-long" -> {
            val incoming = screen == "sprint-incoming"
            val missing = screen == "sprint-missing"
            val long = screen == "sprint-long"
            val from = if (incoming) btc.copy(fiatAmount = stringRes("$0.40")) else zec
            val to = if (incoming) zec.copy(fiatAmount = stringRes("$0.39")) else btc
            SwapQuoteView(SwapQuoteState.Success(
                title = stringRes("Review quote"), rotateIcon = false,
                from = if (long) from.copy(amount = stringRes("123456.12345678"), fiatAmount = stringRes("$4,938,244.94")) else from,
                to = to.copy(
                    amount = if (long) stringRes("47.12345678") else to.amount,
                    fiatAmount = if (missing) stringRes("—") else if (long) stringRes("$4,888,862.49") else to.fiatAmount,
                    valueDifference = if (missing) null else QuoteValueDifferenceState(
                        stringRes(if (long) "-1.00%" else if (incoming) "-2.50%" else "-3.47%"),
                        stringRes(if (incoming) R.string.private_value_difference_external else R.string.private_value_difference_known), true,
                    ),
                ),
                items = if (incoming) emptyList() else listOf(
                    SwapQuoteInfoItem(stringRes("Pay to"), styledStringResource("Synthetic destination")),
                    SwapQuoteInfoItem(stringRes(R.string.private_wallet_transaction_fee), styledStringResource("0.0001 ZEC")),
                ),
                amount = SwapQuoteInfoItem(stringRes(if (incoming) "Amount to send" else "Total ZEC to spend"), styledStringResource(if (incoming) "0.000004 BTC" else if (long) "123456.12355678 ZEC" else "0.0101 ZEC")),
                primaryButton = ButtonState(stringRes("Confirm"), onClick = {}),
                infoText = if (incoming) stringRes("Minimum received: 0.0097 ZEC") else null,
                onBack = onBack,
            ))
        }
        "sprint-refund", "sprint-no-refund", "sprint-full-refund" -> {
            val refunded = screen == "sprint-full-refund"
            TransactionDetailView(
                state = TransactionDetailState(
                    onBack = onBack, bookmarkButton = null,
                    header = TransactionDetailHeaderState(stringRes(if (refunded) "Refunded" else "Paid"), stringRes("0.0101 ZEC"), listOf(imageRes(R.drawable.ic_transaction_received))),
                    info = SendSwapState(
                        status = if (refunded) SwapStatus.REFUNDED else SwapStatus.SUCCESS,
                        message = null, quoteHeader = SwapQuoteHeaderState(
                            if (screen == "sprint-refund") zec.copy(amount = stringRes("0.0098"), fiatAmount = stringRes("$0.392")) else zec,
                            btc,
                        ),
                        depositAddress = stringRes("Synthetic deposit").withStyle(), totalFees = stringRes("0.0001 ZEC"),
                        recipientAddress = stringRes("Synthetic recipient").withStyle(), transactionId = stringRes("abab…abab"),
                        refundedAmount = if (screen == "sprint-no-refund") null else stringRes(if (refunded) "0.01 ZEC" else "0.0002 ZEC"),
                        onTransactionIdClick = {}, onDepositAddressClick = {}, onRecipientAddressClick = {},
                        maxSlippage = stringRes("0.5%"), note = null, isSlippageRealized = true,
                        isPending = false, completedTimestamp = stringRes("8 November 2026, 12:00"),
                    ),
                    errorFooter = null, infoFooter = null, primaryButton = null, secondaryButton = null,
                    explorerLinks = listOf(
                        TransactionExplorerLink(stringRes("ZEC deposit"), "ab".repeat(32), {}, {}),
                        TransactionExplorerLink(stringRes("Bitcoin payment"), "cd".repeat(32), {}, {}),
                    ),
                ), mainAppBarState = appBar,
            )
        }
        "sprint-send" -> TransactionDetailView(
            state = TransactionDetailState(
                onBack = onBack, bookmarkButton = null,
                header = TransactionDetailHeaderState(stringRes("Sent"), stringRes("0.01"), listOf(imageRes(R.drawable.ic_transaction_received))),
                info = SendShieldStateFixture.new().copy(completedTimestamp = stringRes("8 November 2026, 12:00")),
                errorFooter = null, infoFooter = null, primaryButton = null, secondaryButton = null,
                explorerLinks = listOf(TransactionExplorerLink(stringRes("Zcash transaction"), "ab".repeat(32), {}, {})),
            ), mainAppBarState = appBar,
        )
        else -> return false
    }
    return true
}
