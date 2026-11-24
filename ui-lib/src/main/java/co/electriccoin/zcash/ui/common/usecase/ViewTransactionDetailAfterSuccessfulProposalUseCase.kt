package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.swap.detail.SwapDetailArgs
import co.electriccoin.zcash.ui.screen.transactiondetail.TransactionDetailArgs

class ViewTransactionDetailAfterSuccessfulProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val navigationRouter: NavigationRouter,
) {
    operator fun invoke(txId: String) = open(TransactionDetailArgs(txId))

    operator fun invoke(proposal: SwapTransactionProposal, fundingTxId: String? = null) = open(
        SwapDetailArgs(
            proposal.quote.depositAddress.address,
            proposal.fundingAccount.sdkAccount.accountUuid.toStorageKeyId(),
            fundingTxId,
        )
    )

    private fun open(route: Any) {
        zashiProposalRepository.clear()
        keystoneProposalRepository.clear()
        ledgerProposalPipeline.clear()
        navigationRouter.replaceAll(route)
    }
}
