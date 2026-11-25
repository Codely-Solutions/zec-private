package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.repository.SignedLedgerSubmission
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * [SubmitLedgerProposalUseCase] submits the proposal the device has signed, off-thread, and replaces
 * the whole back stack with Transaction Progress in one command. Swap proposals additionally run
 * [ProcessSwapTransactionUseCase], and the send prefill is always cleared once submission settles,
 * success or failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubmitLedgerProposalUseCaseTest {
    @Test
    fun submitsAndNavigatesToProgressWithoutClearingAnUnrelatedSwap() =
        runTest {
            val fx = useCase()
            val signed = fx.signed(mockk())

            fx.useCase(signed)

            coVerify(exactly = 1) { fx.nativeSubmit() }
            verify(exactly = 0) { fx.swapRepository.clearIfCurrent(any()) }
            verify(exactly = 1) { fx.navigationRouter.replaceAll(TransactionProgressArgs) }
        }

    @Test
    fun eachSignedPreparationEntersTheQueueOnlyOnce() = runTest {
        val fx = useCase()
        val first = fx.signed(mockk())
        val second = fx.signed(mockk())

        fx.useCase(first)
        fx.useCase(first)
        fx.useCase(second)

        coVerify(exactly = 2) { fx.nativeSubmit() }
        verify(exactly = 2) { fx.navigationRouter.replaceAll(TransactionProgressArgs) }
    }

    @Test
    fun swapProposalsRunPostProcessing() =
        runTest {
            val fx = useCase()
            val proposal = mockk<SwapTransactionProposal>(relaxed = true)
            val result = mockk<SubmitResult>(relaxed = true)
            val reviewedQuote = mockk<SwapQuoteData.Success>()
            fx.useCase(fx.signed(proposal, result, reviewedQuote))

            verify(exactly = 1) { fx.swapRepository.clearIfCurrent(reviewedQuote) }
            coVerify(exactly = 1) { fx.processSwapTransaction(proposal, result) }
        }

    @Test
    fun nonSwapProposalsSkipPostProcessing() =
        runTest {
            val fx = useCase()
            fx.useCase(fx.signed(mockk()))

            coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
        }

    @Test
    fun theSendPrefillIsClearedEvenWhenSubmitFails() =
        runTest {
            val fx = useCase()
            val signed = fx.signed(mockk())
            coEvery { fx.nativeSubmit() } throws RuntimeException("boom")

            fx.useCase(signed)

            verify(exactly = 1) { fx.prefillSend.clearThrough(any()) }
            verify(exactly = 1) { fx.navigationRouter.replaceAll(TransactionProgressArgs) }
        }

    @Test
    fun completedLedgerSubmissionDiscardsOnlyRequestsPresentWhenItStarted() = runTest {
        val fx = useCase()
        val nativeResult = CompletableDeferred<SubmitResult>()
        val signed = fx.signed(mockk())
        coEvery { fx.nativeSubmit() } coAnswers { nativeResult.await() }
        every { fx.prefillSend.pendingThrough() } returns 1

        fx.useCase(signed)
        every { fx.prefillSend.pendingThrough() } returns 2
        nativeResult.complete(mockk(relaxed = true))

        verify(exactly = 1) { fx.prefillSend.clearThrough(1) }
        verify(exactly = 0) { fx.prefillSend.clearThrough(2) }
    }

    private fun Fixtures.signed(
        proposal: TransactionProposal,
        submitResult: SubmitResult = mockk(relaxed = true),
        reviewedQuote: SwapQuoteData.Success? = null,
    ): SignedLedgerSubmission {
        coEvery { nativeSubmit() } returns submitResult
        return SignedLedgerSubmission(proposal, owner = null, reviewedQuote = reviewedQuote, submit = nativeSubmit)
    }

    /** Pins the use case's off-thread submit scope to the test scheduler so it runs eagerly. */
    private fun TestScope.useCase(): Fixtures {
        val fx = Fixtures()
        fx.useCase.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return fx
    }

    private class Fixtures {
        val navigationRouter = mockk<NavigationRouter>(relaxed = true)
        val nativeSubmit = mockk<suspend () -> SubmitResult>()
        val swapRepository = mockk<SwapRepository>(relaxed = true)
        val processSwapTransaction = mockk<ProcessSwapTransactionUseCase>(relaxed = true)
        val prefillSend = mockk<PrefillSendUseCase>(relaxed = true)
        val useCase =
            SubmitLedgerProposalUseCase(
                navigationRouter = navigationRouter,
                swapRepository = swapRepository,
                processSwapTransaction = processSwapTransaction,
                prefillSend = prefillSend,
            )
    }
}
