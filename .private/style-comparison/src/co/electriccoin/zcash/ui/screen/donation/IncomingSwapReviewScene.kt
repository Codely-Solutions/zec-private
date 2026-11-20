package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.Composable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ButtonStyle
import co.electriccoin.zcash.ui.design.component.SwapQuoteHeaderState
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailState
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailView
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailHeaderState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailInfoRowState
import co.electriccoin.zcash.ui.screen.transactiondetail.infoitems.TransactionDetailSwapStatusRowState

/** Synthetic saved state with inert actions; production state transitions have JVM coverage. */
@Composable
internal fun IncomingSwapReviewScene(screen: String): Boolean {
    if (screen !in setOf("incoming-saved-pending", "incoming-saved-completed")) return false
    val completed = screen == "incoming-saved-completed"
    val empty = TransactionDetailInfoRowState(stringRes(""), message = null)
    SwapDetailView(
        state = SwapDetailState(
            transactionHeader = TransactionDetailHeaderState(
                title = stringRes(if (completed) R.string.private_swap_saved_output else R.string.swapAndPay_status_pendingDeposit),
                amount = stringRes("0.07799797"),
                icons = listOf(
                    imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_btc),
                    imageRes(R.drawable.ic_transaction_received),
                    imageRes(co.electriccoin.zcash.ui.design.R.drawable.ic_token_zec),
                ),
            ),
            quoteHeader = SwapQuoteHeaderState(null, null),
            status = TransactionDetailSwapStatusRowState(
                title = stringRes(R.string.private_swap_saved_status),
                status = if (completed) SwapStatus.SUCCESS else SwapStatus.PENDING,
                mode = TransactionDetailSwapStatusRowState.Mode.SWAP_INTO_ZEC,
            ),
            depositTo = TransactionDetailInfoRowState(
                stringRes(R.string.swapToZec_depositTo), stringRes("Sample deposit").withStyle()
            ),
            recipient = empty,
            totalFees = empty,
            maxSlippage = empty,
            timestamp = TransactionDetailInfoRowState(
                stringRes(R.string.private_swap_saved_update), stringRes("20 November 2026, 12:00").withStyle()
            ),
            message = null,
            errorFooter = null,
            infoFooter = stringRes(R.string.private_swap_refresh_unavailable),
            primaryButton = ButtonState(
                text = stringRes(co.electriccoin.zcash.ui.design.R.string.disconnectHWWallet_tryAgain),
                onClick = {}, style = ButtonStyle.SECONDARY,
            ),
            onBack = {},
            showLiveDetails = false,
        ),
        appBarState = null,
    )
    return true
}
