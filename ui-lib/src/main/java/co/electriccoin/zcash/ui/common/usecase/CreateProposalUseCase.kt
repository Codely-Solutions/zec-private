package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.forwardFrom
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.PaymentDraft
import co.electriccoin.zcash.ui.common.datasource.TexUnsupportedOnKSException
import co.electriccoin.zcash.ui.common.extension.normalizedSendAmount
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.screen.insufficientfunds.InsufficientFundsArgs
import co.electriccoin.zcash.ui.screen.texunsupported.TEXUnsupportedArgs

class CreateProposalUseCase(
    private val review: PreparePaymentReviewUseCase,
    private val navigationRouter: NavigationRouter,
) {
    suspend operator fun invoke(zecSend: ZecSend, floor: Boolean, sendEntryId: String, canReview: () -> Boolean) {
        val owner = review.capture(sendEntryId, isCurrentDraft = canReview) ?: return
        if (owner.account is ViewOnlyAccount) throw AccountOperationUnsupportedException.viewOnly()
        try {
            review.open(owner, PaymentDraft.Send(zecSend.normalize(floor))) { route, _ ->
                NavigationCommand.Forward(listOf(route))
            }
        } catch (_: TexUnsupportedOnKSException) {
            navigationRouter.forwardFrom(sendEntryId, owner.permits) {
                TEXUnsupportedArgs(isLedger = owner.account is LedgerAccount)
            }
        } catch (_: InsufficientFundsException) {
            navigationRouter.forwardFrom(sendEntryId, owner.permits) { InsufficientFundsArgs }
        }
    }

    private fun ZecSend.normalize(floor: Boolean): ZecSend {
        val amount = requireNotNull(amount.normalizedSendAmount(floor)) { "Invalid send amount" }
        val supportsMemo = destination is WalletAddress.Unified || destination is WalletAddress.Sapling
        require(amount.value > 0L || (supportsMemo && memo.value.isNotEmpty())) {
            "Zero-ZEC sends require a shielded recipient and a memo"
        }
        return copy(amount = amount, memo = if (supportsMemo) memo else Memo(""))
    }
}
