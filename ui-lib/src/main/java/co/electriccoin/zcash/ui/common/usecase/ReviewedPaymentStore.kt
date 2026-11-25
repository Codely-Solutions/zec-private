package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import java.util.UUID

/** The route keeps an opaque ID. Payment data stays in the original wallet session. */
class ReviewedPaymentStore(
    private val accounts: AccountDataSource,
    private val walletWriteAccess: WalletWriteAccess,
) {
    private val current = MutableStateFlow<Review?>(null)

    internal fun bind(proposal: SendTransactionProposal, owner: AccountUuid): ReviewTransactionArgs {
        val id = UUID.randomUUID().toString()
        current.value = Review(id, proposal, owner, walletWriteAccess.token())
        return ReviewTransactionArgs(id)
    }

    internal fun observe(routeId: String?) =
        combine(current, accounts.allAccounts, walletWriteAccess.version) { review, wallets, _ ->
            review?.takeIf {
                routeId == it.id && walletWriteAccess.permits(it.wallet) &&
                    wallets?.firstOrNull { account -> account.isSelected }?.sdkAccount?.accountUuid == it.owner
            }?.proposal
        }

    internal fun discard(id: String?) {
        val review = current.value
        if (review?.id == id) current.compareAndSet(review, null)
    }

    private data class Review(
        val id: String,
        val proposal: SendTransactionProposal,
        val owner: AccountUuid,
        val wallet: Long,
    )
}
