package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.whileAtEntry
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.PaymentDraft
import co.electriccoin.zcash.ui.common.datasource.PaymentDraftOwner
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs

/** Takes a user-reviewed draft from its exact entry to its matching Review route. */
class PreparePaymentReviewUseCase(
    private val accounts: AccountDataSource,
    private val appAccess: AppAccessSession,
    private val walletWriteAccess: WalletWriteAccess,
    private val router: NavigationRouter,
    private val software: ZashiProposalRepository,
    private val keystone: KeystoneProposalRepository,
    private val ledger: LedgerProposalPipeline,
    private val reviews: ReviewedPaymentStore,
) {
    internal fun capture(
        entryId: String?,
        expectedOwner: AccountUuid? = null,
        isCurrentDraft: () -> Boolean = { true },
    ): PaymentDraftOwner? {
        val account = selectedAccount() ?: return null
        val id = account.sdkAccount.accountUuid
        if (expectedOwner != null && expectedOwner != id) return null
        val grant = appAccess.grant.value
        val wallet = walletWriteAccess.token()
        val permitted = {
            router.isAtEntry(entryId) && isCurrentDraft() && appAccess.permits(grant) &&
                walletWriteAccess.permits(wallet) && selectedAccount()?.sdkAccount?.accountUuid == id
        }
        return PaymentDraftOwner(account, entryId, permitted).takeIf { permitted() }
    }

    internal suspend fun open(
        owner: PaymentDraftOwner,
        draft: PaymentDraft,
        route: (ReviewTransactionArgs, SendTransactionProposal) -> NavigationCommand,
    ): Boolean {
        var published: SendTransactionProposal? = null
        var delivered = false
        try {
            val prepared = router.whileAtEntry(owner.entryId) {
                prepare(draft, owner).also { published = it }
            } ?: return false
            return router.commitFrom(owner.entryId, { owner.permits() && isPublished(owner, prepared) }) {
                val review = reviews.bind(prepared, owner.account.sdkAccount.accountUuid)
                route(review, prepared).also { delivered = true }
            }
        } finally {
            if (!delivered) published?.let { clear(owner, it) }
        }
    }

    private suspend fun prepare(draft: PaymentDraft, owner: PaymentDraftOwner): SendTransactionProposal =
        when (owner.account) {
            is ZodlAccount -> software.preparePayment(draft, owner)
            is KeystoneAccount -> keystone.preparePayment(draft, owner)
            is LedgerAccount -> ledger.preparePayment(draft, owner)
            is ViewOnlyAccount -> throw AccountOperationUnsupportedException.viewOnly()
        }

    private fun isPublished(owner: PaymentDraftOwner, proposal: SendTransactionProposal) =
        proposal === when (owner.account) {
            is ZodlAccount -> software.transactionProposal.value
            is KeystoneAccount -> keystone.transactionProposal.value
            is LedgerAccount -> ledger.transactionProposal.value
            is ViewOnlyAccount -> null
        }

    private fun clear(owner: PaymentDraftOwner, proposal: SendTransactionProposal) {
        when (owner.account) {
            is ZodlAccount -> software.clear(proposal)
            is KeystoneAccount -> keystone.clear(proposal)
            is LedgerAccount -> ledger.clear(proposal)
            is ViewOnlyAccount -> Unit
        }
    }

    private fun selectedAccount() = accounts.allAccounts.value?.firstOrNull { it.isSelected }
}
