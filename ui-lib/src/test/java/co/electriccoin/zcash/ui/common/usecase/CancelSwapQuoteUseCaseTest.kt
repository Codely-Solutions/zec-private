package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/** Cancelling a quote leaves independent payment drafts intact. */
class CancelSwapQuoteUseCaseTest {
    private val swapRepository = mockk<SwapRepository>(relaxed = true)
    private val zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true)
    private val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true)
    private val ledgerProposalPipeline = mockk<LedgerProposalPipeline>(relaxed = true)
    private val navigationRouter = mockk<NavigationRouter>(relaxed = true)
    private val useCase =
        CancelSwapQuoteUseCase(
            swapRepository = swapRepository,
            zashiProposalRepository = zashiProposalRepository,
            keystoneProposalRepository = keystoneProposalRepository,
            ledgerProposalPipeline = ledgerProposalPipeline,
            navigationRouter = navigationRouter
        )

    @Test
    fun clearsOnlyTheExactQuotedProposalThenNavigatesBack() {
        val quote = mockk<SwapQuote>()
        val current = SwapQuoteData.Success(quote)
        val matching = mockk<ExactInputSwapTransactionProposal> { every { this@mockk.quote } returns quote }
        every { swapRepository.quote } returns MutableStateFlow(current)
        every { zashiProposalRepository.transactionProposal } returns MutableStateFlow(matching)
        val unrelated = mockk<RegularTransactionProposal>()
        every { keystoneProposalRepository.transactionProposal } returns MutableStateFlow(unrelated)
        every { ledgerProposalPipeline.transactionProposal } returns MutableStateFlow(null)

        useCase()

        verify(exactly = 1) { zashiProposalRepository.clear(matching) }
        verify(exactly = 0) { keystoneProposalRepository.clear(any()) }
        verify(exactly = 0) { ledgerProposalPipeline.clear(any()) }
        verifyOrder {
            zashiProposalRepository.clear(matching)
            swapRepository.clearQuote(current)
            navigationRouter.back()
        }
    }
}
