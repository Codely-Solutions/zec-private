package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository

class CancelSwapQuoteUseCase(
    private val swapRepository: SwapRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val navigationRouter: NavigationRouter,
) {
    operator fun invoke() {
        clear()
        navigationRouter.back()
    }

    internal suspend fun cancelFrom(sourceEntryId: String, canCancel: () -> Boolean): Boolean =
        navigationRouter.commitFrom(sourceEntryId, canCancel) {
            clear()
            NavigationCommand.Back
        }

    private fun clear() {
        val quote = swapRepository.quote.value
        discardPreparedSwap(quote, zashiProposalRepository, keystoneProposalRepository, ledgerProposalPipeline)
        swapRepository.clearQuote(quote as? SwapQuoteData.Success)
    }
}

internal fun discardPreparedSwap(
    expected: SwapQuoteData?,
    software: ZashiProposalRepository,
    keystone: KeystoneProposalRepository,
    ledger: LedgerProposalPipeline,
) {
    val quote = (expected as? SwapQuoteData.Success)?.quote ?: return
    (software.transactionProposal.value as? SwapTransactionProposal)
        ?.takeIf { it.quote === quote }?.let { software.clear(it) }
    (keystone.transactionProposal.value as? SwapTransactionProposal)
        ?.takeIf { it.quote === quote }?.let { keystone.clear(it) }
    (ledger.transactionProposal.value as? SwapTransactionProposal)
        ?.takeIf { it.quote === quote }?.let { ledger.clear(it) }
}
