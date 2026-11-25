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
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
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
    suspend operator fun invoke(clearSendForm: Boolean = true) {
        val proposal =
            when (accountDataSource.getSelectedAccount()) {
                is ZodlAccount -> zashiProposalRepository.getTransactionProposal()
                is KeystoneAccount -> keystoneProposalRepository.getTransactionProposal()
                is LedgerAccount -> ledgerProposalPipeline.getTransactionProposal()
                is ViewOnlyAccount -> null
            }

        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        ledgerProposalPipeline.clear()
        navigationRouter.stageResultFrom(null) { cancelRoute(proposal, clearSendForm) }
    }

    fun rejectKeystone(proposal: TransactionProposal, account: AccountUuid, sourceEntryId: String?) {
        fun isOwner(): Boolean = accountDataSource.allAccounts.value
            ?.any { it is KeystoneAccount && it.isSelected && it.sdkAccount.accountUuid == account } == true

        if (!isOwner() || keystoneProposalRepository.transactionProposal.value !== proposal) return
        navigationRouter.stageResultFrom(sourceEntryId, isStillAllowed = ::isOwner) {
            if (keystoneProposalRepository.transactionProposal.value !== proposal) {
                null
            } else {
                keystoneProposalRepository.clear()
                cancelRoute(proposal, clearSendForm = true)
            }
        }
    }

    private fun cancelRoute(proposal: TransactionProposal?, clearSendForm: Boolean): NavigationCommand =
        when (proposal) {
            is ExactInputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                NavigationCommand.BackTo(SwapArgs::class)
            }

            is ExactOutputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                NavigationCommand.BackTo(PayArgs::class)
            }

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

            else -> backToSend(clearSendForm)
        }

    private fun backToSend(clearSendForm: Boolean): NavigationCommand {
        if (clearSendForm) observeClearSend.requestClear()
        return NavigationCommand.BackTo(Send::class)
    }
}
