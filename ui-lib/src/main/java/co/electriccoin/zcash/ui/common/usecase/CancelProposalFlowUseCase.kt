package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
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

        when (proposal) {
            is ExactInputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                navigationRouter.backTo(SwapArgs::class)
            }

            is ExactOutputSwapTransactionProposal -> {
                swapRepository.clearQuote()
                navigationRouter.backTo(PayArgs::class)
            }

            is MigrationSweepTransactionProposal -> navigationRouter.back()

            is ShieldTransactionProposal -> {
                // Reached via ShieldFundsUseCase's Keystone branch, which (unlike the Zashi
                // branch) never pops/replaces the screen the shield prompt was shown on before
                // pushing the Sign screen. So that origin screen (ShieldFundsInfo dialog,
                // Balances, or Home) is still directly beneath us on the back stack — falling
                // through to the `else` branch's `backTo(Send::class)` would silently no-op
                // (Send was never pushed for this flow), leaving the user stuck on the
                // Sign/reject sheet. A plain back() pops the Sign screen and reveals it again.
                navigationRouter.back()
            }

            is Zip321TransactionProposal -> {
                if (proposal.binaryMemoByteCount != null) {
                    // The review replaced the scanner without creating a Send draft.
                    navigationRouter.back()
                } else {
                    backToSend(clearSendForm)
                }
            }

            is RegularTransactionProposal -> {
                if (proposal.isDonation) navigationRouter.backTo(DonationArgs::class) else backToSend(clearSendForm)
            }

            else -> backToSend(clearSendForm)
        }
    }

    private fun backToSend(clearSendForm: Boolean) {
        if (clearSendForm) observeClearSend.requestClear()
        navigationRouter.backTo(Send::class)
    }
}
