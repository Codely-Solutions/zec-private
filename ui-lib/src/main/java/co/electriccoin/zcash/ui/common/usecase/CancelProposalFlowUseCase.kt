package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SendTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.donation.DonationArgs
import co.electriccoin.zcash.ui.screen.pay.PayArgs
import co.electriccoin.zcash.ui.screen.send.Send
import co.electriccoin.zcash.ui.screen.swap.SwapArgs

class CancelProposalFlowUseCase(
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val navigationRouter: NavigationRouter,
    private val observeClearSend: ObserveClearSendUseCase,
    private val accountDataSource: AccountDataSource,
    private val swapRepository: SwapRepository,
) {
    fun rejectKeystone(proposal: TransactionProposal, account: AccountUuid, sourceEntryId: String?) =
        reject(proposal, account, sourceEntryId, clearSendForm = true)

    fun rejectReviewed(proposal: SendTransactionProposal, account: AccountUuid, sourceEntryId: String?) =
        reject(proposal, account, sourceEntryId, clearSendForm = false)

    private fun reject(
        proposal: TransactionProposal,
        owner: AccountUuid,
        sourceEntryId: String?,
        clearSendForm: Boolean,
    ) {
        val account = selectedAccount(owner) ?: return
        val isAllowed = { selectedAccount(owner) != null && currentProposal(account) === proposal }
        navigationRouter.stageResultFrom(sourceEntryId, isAllowed) {
            clear(account, proposal)
            cancelRoute(proposal, clearSendForm)
        }
    }

    private fun selectedAccount(owner: AccountUuid) =
        accountDataSource.allAccounts.value?.firstOrNull { it.isSelected && it.sdkAccount.accountUuid == owner }

    private fun currentProposal(account: WalletAccount) =
        when (account) {
            is ZodlAccount -> zashiProposalRepository.transactionProposal.value
            is KeystoneAccount -> keystoneProposalRepository.transactionProposal.value
            is LedgerAccount -> ledgerProposalPipeline.transactionProposal.value
            is ViewOnlyAccount -> null
        }

    private fun clear(account: WalletAccount, proposal: TransactionProposal) {
        when (account) {
            is ZodlAccount -> zashiProposalRepository.clear(proposal)
            is KeystoneAccount -> keystoneProposalRepository.clear(proposal)
            is LedgerAccount -> ledgerProposalPipeline.clear(proposal)
            is ViewOnlyAccount -> Unit
        }
        if (proposal is SwapTransactionProposal) {
            val quote = swapRepository.quote.value as? SwapQuoteData.Success
            if (quote?.quote === proposal.quote) swapRepository.clearQuote(quote)
        }
    }

    private fun cancelRoute(proposal: TransactionProposal, clearSendForm: Boolean): NavigationCommand =
        when (proposal) {
            is ExactInputSwapTransactionProposal -> NavigationCommand.BackTo(SwapArgs::class)

            is ExactOutputSwapTransactionProposal -> NavigationCommand.BackTo(PayArgs::class)

            is MigrationSweepTransactionProposal -> NavigationCommand.Back

            is ShieldTransactionProposal -> {
                // Reached via ShieldFundsUseCase's Keystone branch, which (unlike the Zashi
                // branch) never pops/replaces the screen the shield prompt was shown on before
                // pushing the Sign screen. So that origin screen (ShieldFundsInfo dialog,
                // Balances, or Home) is still directly beneath us on the back stack — falling
                // through to the `else` branch's `backTo(Send::class)` would silently no-op
                // (Send was never pushed for this flow), leaving the user stuck on the
                // Sign/reject sheet. A plain back() pops the Sign screen and reveals it again.
                NavigationCommand.Back
            }

            is Zip321TransactionProposal -> {
                if (proposal.binaryMemoByteCount != null) {
                    // The review replaced the scanner without creating a Send draft.
                    NavigationCommand.Back
                } else {
                    backToSend(clearSendForm)
                }
            }

            is RegularTransactionProposal -> {
                if (proposal.isDonation) NavigationCommand.BackTo(DonationArgs::class) else backToSend(clearSendForm)
            }

        }

    private fun backToSend(clearSendForm: Boolean): NavigationCommand {
        if (clearSendForm) observeClearSend.requestClear()
        return NavigationCommand.BackTo(Send::class)
    }
}
