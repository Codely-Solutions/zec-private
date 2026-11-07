package co.electriccoin.zcash.ui.screen.transactiondetail.info

import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.design.component.SwapQuoteHeaderState
import co.electriccoin.zcash.ui.design.component.ZashiMessageState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.StyledStringResource

sealed interface TransactionDetailInfoState

data class SendShieldedState(
    val contact: StringResource?,
    val address: StyledStringResource,
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val onTransactionAddressClick: () -> Unit,
    val fee: StringResource,
    val completedTimestamp: StringResource,
    val memo: TransactionDetailMemosState,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class SendSwapState(
    val status: SwapStatus?,
    val message: ZashiMessageState?,
    val quoteHeader: SwapQuoteHeaderState,
    val depositAddress: StyledStringResource,
    val totalFees: StringResource?,
    val recipientAddress: StyledStringResource?,
    val transactionId: StringResource,
    val refundedAmount: StringResource?,
    val onTransactionIdClick: () -> Unit,
    val onDepositAddressClick: () -> Unit,
    val onRecipientAddressClick: (() -> Unit)?,
    val maxSlippage: StringResource?,
    val note: StringResource?,
    val isSlippageRealized: Boolean,
    val isPending: Boolean,
    val completedTimestamp: StringResource,
    val donationBasisZec: String? = null,
) : TransactionDetailInfoState

data class SendTransparentState(
    val contact: StringResource?,
    val address: StringResource,
    val addressAbbreviated: StyledStringResource,
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val onTransactionAddressClick: () -> Unit,
    val fee: StringResource,
    val completedTimestamp: StringResource,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class ReceiveShieldedState(
    val memo: TransactionDetailMemosState,
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val completedTimestamp: StringResource,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class ReceiveTransparentState(
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val completedTimestamp: StringResource,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class ShieldingState(
    val transactionId: StringResource,
    val onTransactionIdClick: () -> Unit,
    val completedTimestamp: StringResource,
    val fee: StringResource,
    val note: StringResource?,
    val isPending: Boolean
) : TransactionDetailInfoState

data class TransactionDetailMemosState(
    val memos: List<TransactionDetailMemoState>?,
)

data class TransactionDetailMemoState(
    val content: StringResource,
    val onClick: () -> Unit
)
