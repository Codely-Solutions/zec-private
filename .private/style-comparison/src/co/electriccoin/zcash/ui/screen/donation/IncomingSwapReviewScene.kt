package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.SwapQuoteHeaderState
import co.electriccoin.zcash.ui.design.component.SwapTokenAmountState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByNumber
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailState
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailView
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailHeaderState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoRowState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRowState
import java.math.BigDecimal

/** Synthetic saved state with inert actions; production state transitions have JVM coverage. */
@Composable
internal fun IncomingSwapReviewScene(screen: String): Boolean {
    val title = when (screen) {
        "incoming-saved-pending" -> R.string.swapAndPay_status_pendingDeposit
        "incoming-saved-completed" -> R.string.private_swap_saved_output
        "incoming-success-quoted" -> R.string.private_estimated_output
        "incoming-success-retained-execution" -> R.string.private_saved_provider_output
        "incoming-input-without-usd" -> R.string.private_provider_output
        else -> return false
    }
    val completed = screen != "incoming-saved-pending"
    val live = screen.startsWith("incoming-success-") || screen == "incoming-input-without-usd"
    val amount = if (screen == "incoming-success-quoted") BigDecimal("0.075") else BigDecimal("0.07799797")
    val empty = TransactionDetailInfoRowState(stringRes(""), message = null)
    SwapDetailView(
        state = SwapDetailState(
            transactionHeader = TransactionDetailHeaderState(
                title = stringRes(title),
                amount = stringResByNumber(amount),
                icons = listOf(
                    imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                    imageRes(R.drawable.ic_transaction_received),
                    imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                ),
            ),
            quoteHeader = if (screen == "incoming-input-without-usd") SwapQuoteHeaderState(
                from = SwapTokenAmountState(
                    bigIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                    smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                    amount = stringResByNumber(BigDecimal("0.8")), fiatAmount = stringRes(""),
                    token = stringRes("BTC"), chain = stringRes("Bitcoin"),
                ),
                to = SwapTokenAmountState(
                    bigIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                    smallIcon = imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_zec_shielded),
                    amount = stringResByNumber(amount), fiatAmount = stringRes(""),
                    token = stringRes("ZEC"), chain = stringRes("Zcash"),
                ),
            ) else SwapQuoteHeaderState(null, null),
            status = TransactionDetailSwapStatusRowState(
                title = stringRes(if (live) R.string.swapAndPay_status else R.string.private_swap_saved_status),
                status = if (completed) SwapStatus.SUCCESS else SwapStatus.PENDING,
                mode = TransactionDetailSwapStatusRowState.Mode.SWAP_INTO_ZEC,
            ),
            depositTo = TransactionDetailInfoRowState(
                stringRes(R.string.private_swap_deposit_reference), stringRes("Sample deposit").withStyle()
            ),
            recipient = empty,
            quotedOutput = if (live) {
                TransactionDetailInfoRowState(stringRes(R.string.private_quoted_output), stringRes("0.075 ZEC").withStyle())
            } else {
                empty
            },
            maxSlippage = empty,
            timestamp = TransactionDetailInfoRowState(
                stringRes(if (live) R.string.transactionHistory_timestamp else R.string.private_swap_created_at),
                stringRes("20 November 2026, 12:00").withStyle()
            ),
            message = null,
            errorFooter = null,
            infoFooter = if (live) null else stringRes(R.string.private_swap_refresh_unavailable),
            primaryButton = if (live) null else ButtonState(
                text = stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain),
                onClick = {}, style = ButtonStyle.SECONDARY,
            ),
            onBack = {},
            showLiveDetails = live,
        ),
        appBarState = null,
    )
    return true
}
