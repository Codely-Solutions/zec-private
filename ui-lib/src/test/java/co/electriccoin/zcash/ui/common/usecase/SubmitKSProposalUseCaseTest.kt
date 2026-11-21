package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [SubmitKSProposalUseCase] submits an already-prepared Keystone proposal: it clears swap state,
 * submits off-thread, and navigates to the progress screen. A swap proposal additionally kicks off
 * [ProcessSwapTransactionUseCase] on a successful submit, and the off-thread submit always clears the
 * send prefill (even when it throws).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubmitKSProposalUseCaseTest {
    @Test
    fun swapProposalClearsSubmitsProcessesAndNavigates() =
        runTest {
            val fx = useCase()
            val proposal = swapProposal()
            val submitResult = mockk<SubmitResult>(relaxed = true)
            every { fx.keystoneProposalRepository.transactionProposal } returns MutableStateFlow(proposal)
            coEvery { fx.keystoneProposalRepository.submit(any()) } returns submitResult

            fx.useCase(proposal)

            verify(exactly = 1) { fx.swapRepository.clear() }
            coVerify(exactly = 1) { fx.keystoneProposalRepository.submit(any()) }
            coVerify(exactly = 1) { fx.processSwapTransaction(proposal, submitResult) }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
            verify(exactly = 1) { fx.prefillSend.clear() }
            // Swap state is cleared before submitting, and navigation happens after submit is kicked off.
            coVerifyOrder {
                fx.swapRepository.clear()
                fx.keystoneProposalRepository.submit(any())
                fx.navigationRouter.replace(TransactionProgressArgs)
            }
        }

    @Test
    fun nonSwapProposalSubmitsWithoutProcessingSwap() =
        runTest {
            val fx = useCase()
            val proposal = mockk<TransactionProposal>()
            every { fx.keystoneProposalRepository.transactionProposal } returns MutableStateFlow(proposal)
            coEvery { fx.keystoneProposalRepository.submit(any()) } returns mockk(relaxed = true)

            fx.useCase(proposal)

            verify(exactly = 1) { fx.swapRepository.clear() }
            coVerify(exactly = 1) { fx.keystoneProposalRepository.submit(any()) }
            coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
            verify(exactly = 1) { fx.prefillSend.clear() }
        }

    @Test
    fun submitFailureStillClearsPrefillAndStillNavigates() =
        runTest {
            val fx = useCase()
            val proposal = swapProposal()
            every { fx.keystoneProposalRepository.transactionProposal } returns MutableStateFlow(proposal)
            coEvery { fx.keystoneProposalRepository.submit(any()) } throws RuntimeException("boom")

            fx.useCase(proposal)

            // The submit failure is swallowed: no swap processing, but the prefill is still cleared
            // (finally) and navigation to progress happens regardless of the async outcome.
            coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
            verify(exactly = 1) { fx.prefillSend.clear() }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
        }

    @Test
    fun aSignedScanCannotSubmitTheReplacementProposal() = runTest {
        val fx = useCase()
        val signed = swapProposal()
        every { fx.keystoneProposalRepository.transactionProposal } returns MutableStateFlow(swapProposal())

        assertFailsWith<IllegalStateException> { fx.useCase(signed) }

        coVerify(exactly = 0) { fx.keystoneProposalRepository.submit(any()) }
        verify(exactly = 0) { fx.swapRepository.clear() }
        verify(exactly = 0) { fx.navigationRouter.replace(TransactionProgressArgs) }
    }

    private fun swapProposal(): SwapTransactionProposal =
        mockk {
            every { quote } returns
                mockk {
                    every { destinationAsset } returns
                        SwapAssetTestFixture.asset(tokenTicker = "eth", chainTicker = "eth")
                }
        }

    /** Builds the use case with the off-thread submit scope pinned to the test scheduler. */
    private fun TestScope.useCase(): Fixtures {
        val fx = Fixtures()
        fx.useCase.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return fx
    }

    private class Fixtures {
        val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true)
        val navigationRouter = mockk<NavigationRouter>(relaxed = true)
        val swapRepository = mockk<SwapRepository>(relaxed = true)
        val processSwapTransaction = mockk<ProcessSwapTransactionUseCase>(relaxed = true)
        val prefillSend = mockk<PrefillSendUseCase>(relaxed = true)
        val useCase =
            SubmitKSProposalUseCase(
                keystoneProposalRepository = keystoneProposalRepository,
                navigationRouter = navigationRouter,
                swapRepository = swapRepository,
                processSwapTransaction = processSwapTransaction,
                prefillSend = prefillSend
            )
    }
}
