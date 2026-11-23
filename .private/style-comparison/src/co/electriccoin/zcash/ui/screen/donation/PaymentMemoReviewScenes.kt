package co.electriccoin.zcash.ui.screen.donation

import androidx.compose.runtime.Composable
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.component.ButtonState
import co.electriccoin.zcash.ui.design.component.ChipButtonState
import co.electriccoin.zcash.ui.design.util.Ellipsize
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByAddress
import co.electriccoin.zcash.ui.screen.error.DialogView
import co.electriccoin.zcash.ui.screen.error.ErrorState
import co.electriccoin.zcash.ui.screen.reviewtransaction.AmountState
import co.electriccoin.zcash.ui.screen.reviewtransaction.FinancialInfoState
import co.electriccoin.zcash.ui.screen.reviewtransaction.MessageState
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReceiverExpandedState
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionState
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionView
import co.electriccoin.zcash.ui.screen.reviewtransaction.SenderState
import co.electriccoin.zcash.ui.design.R as DesignR

internal const val REVIEW_MEMO_BYTE_COUNT = 4

@Composable
internal fun PaymentMemoReviewScene(screen: String): Boolean {
    when (screen) {
        "zip321-non-text-review" -> ReviewTransactionView(
            ReviewTransactionState(
                title = stringRes(R.string.send_requestPayment_title),
                items = listOf(
                    AmountState(null, Zatoshi(100_000), ExchangeRateState.OptedOut),
                    SenderState(
                        stringRes(R.string.accounts_sendingFrom),
                        imageRes(DesignR.drawable.ic_item_zodl_glyph),
                        stringRes("Synthetic account"),
                    ),
                    ReceiverExpandedState(
                        title = stringRes(R.string.send_requestPayment_requestedBy),
                        name = null,
                        address = stringResByAddress(
                            "zs1mrhc9y7jdh5r9ece8u5khgvj9kg0zgkxzdduyv0whkg7lkcrkx5xqem3e48avjq9wn2rukydkwn",
                            Ellipsize.END,
                        ),
                        showButton = ChipButtonState(text = stringRes(DesignR.string.general_show), onClick = {}),
                        saveButton = null,
                    ),
                    MessageState(
                        stringRes(R.string.send_requestPayment_for),
                        stringRes(R.string.zip321_binary_memo, REVIEW_MEMO_BYTE_COUNT),
                    ),
                    FinancialInfoState(stringRes(R.string.private_wallet_transaction_fee), Zatoshi(10_000)),
                ),
                primaryButton = ButtonState(stringRes(R.string.tabs_send), onClick = {}),
                onBack = {},
            )
        )
        "zip321-uneditable-memo" -> DialogView(
            ErrorState(
                title = stringRes(R.string.zip321_non_text_memo_title),
                message = stringRes(R.string.zip321_non_text_memo_edit_error),
                positive = ButtonState(stringRes(DesignR.string.general_ok), onClick = {}),
                negative = null,
                onBack = {},
            )
        )
        else -> return false
    }
    return true
}
