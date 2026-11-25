package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.WalletAddress

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerNavigator
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [SubmitProposalUseCase] is gated by a biometric prompt and then branches on account type:
 * - a failed/cancelled biometric does nothing;
 * - Keystone accounts navigate to the PCZT signing flow (no clear, no submit here);
 * - Zashi accounts clear swap state, submit the proposal off-thread, then navigate to progress.
 *
 * An accepted Zashi swap submission kicks off [ProcessSwapTransactionUseCase]. The off-thread submit always clears
 * the send prefill (even when it throws).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubmitProposalUseCaseTest {
    @Test
    fun biometricsFailureDoesNothing() =
        runTest {
            val fx = useCase()
            coEvery { fx.biometricRepository.requestBiometrics(any()) } throws BiometricsFailureException()

            fx.useCase(null)

            coVerify(exactly = 0) { fx.accountDataSource.getSelectedAccount() }
            coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any(), matchNullable { true }) }
            verify(exactly = 0) { fx.navigationRouter.replace(*anyVararg()) }
        }

    @Test
    fun biometricsCancellationDoesNothing() =
        runTest {
            val fx = useCase()
            coEvery { fx.biometricRepository.requestBiometrics(any()) } throws BiometricsCancelledException()

            fx.useCase(null)

            coVerify(exactly = 0) { fx.accountDataSource.getSelectedAccount() }
            verify(exactly = 0) { fx.navigationRouter.replace(*anyVararg()) }
        }

    @Test
    fun keystoneSwapApprovalRetainsTheExactProposalForSigning() =
        runTest {
            val fx = useCase()
            val proposal = swapProposal(tokenTicker = "eth", chainTicker = "eth")
            fx.givenKeystone(proposal)

            fx.submitReviewed(proposal)

            verify(exactly = 1) { fx.navigationRouter.replace(SignKeystoneTransactionArgs) }
            coVerifyOrder {
                fx.keystoneProposalRepository.approveForSigning(proposal, any())
                fx.navigationRouter.replace(SignKeystoneTransactionArgs)
            }
            // Keystone defers submission to the PCZT flow — nothing is cleared, submitted, processed,
            // or prefill-cleared on this path.
            verify(exactly = 0) { fx.swapRepository.clear() }
            coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any(), matchNullable { true }) }
            coVerify(exactly = 0) { fx.keystoneProposalRepository.submit() }
            coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
            verify(exactly = 0) { fx.prefillSend.clear() }
        }

    @Test
    fun keystoneNonSwapProposalNavigatesWithoutTouchingHistory() =
        runTest {
            val fx = useCase()
            val proposal = mockk<TransactionProposal>()
            fx.givenKeystone(proposal)

            fx.submitReviewed(proposal)

            verify(exactly = 1) { fx.navigationRouter.replace(SignKeystoneTransactionArgs) }
            coVerify(exactly = 0) { fx.keystoneProposalRepository.submit() }
        }

    @Test
    fun ledgerProposalOpensTheSignSheetWithoutSubmittingOrClearingSwapState() =
        runTest {
            val fx = useCase()
            val proposal = mockk<TransactionProposal>()
            fx.givenLedger(proposal)

            fx.submitReviewed(proposal)

            verify(exactly = 1) { fx.ledgerNavigator.forwardToSign(null, any()) }
            verify(exactly = 0) { fx.swapRepository.clear() }
            verify(exactly = 0) { fx.prefillSend.clear() }
            verify(exactly = 0) { fx.navigationRouter.replace(*anyVararg()) }
        }

    @Test
    fun zashiSwapProposalClearsSubmitsProcessesAndNavigates() =
        runTest {
            val fx = useCase()
            val proposal = swapProposal(tokenTicker = "eth", chainTicker = "eth")
            val submitResult = mockk<SubmitResult>(relaxed = true)
            fx.givenZashi(proposal, submitResult)

            fx.submitReviewed(proposal)

            verify(exactly = 1) { fx.swapRepository.clear() }
            coVerify(exactly = 1) { fx.zashiProposalRepository.submit(any(), matchNullable { true }) }
            coVerify(exactly = 1) { fx.processSwapTransaction(proposal, submitResult) }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
            verify(exactly = 1) { fx.prefillSend.clear() }
            // Swap state is cleared before submitting, and navigation happens after submit is kicked off.
            coVerifyOrder {
                fx.swapRepository.clear()
                fx.zashiProposalRepository.submit(proposal, any())
                fx.navigationRouter.replace(TransactionProgressArgs)
            }
        }

    @Test
    fun zashiNonSwapProposalSubmitsWithoutProcessingSwap() =
        runTest {
            val fx = useCase()
            val proposal = mockk<TransactionProposal>()
            fx.givenZashi(proposal)

            fx.submitReviewed(proposal)

            verify(exactly = 1) { fx.swapRepository.clear() }
            coVerify(exactly = 1) { fx.zashiProposalRepository.submit(any(), matchNullable { true }) }
            coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
            verify(exactly = 1) { fx.prefillSend.clear() }
        }

    @Test
    fun zashiSubmitFailureStillClearsPrefillAndStillNavigates() =
        runTest {
            val fx = useCase()
            val proposal = swapProposal(tokenTicker = "eth", chainTicker = "eth")
            fx.givenZashi(proposal)
            coEvery { fx.zashiProposalRepository.submit(any(), matchNullable { true }) } throws RuntimeException("boom")

            fx.submitReviewed(proposal)

            // The submit failure is swallowed: no swap processing, but the prefill is still cleared
            // (finally) and navigation to progress happens regardless of the async outcome.
            coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
            verify(exactly = 1) { fx.prefillSend.clear() }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
        }

    @Test
    fun replacementDuringAuthenticationCannotFundAnUnreviewedSwap() = runTest {
        val fx = useCase()
        val reviewed = swapProposal("eth", "eth")
        val replacement = swapProposal("btc", "btc")
        fx.givenZashi(reviewed)
        coEvery { fx.biometricRepository.requestBiometrics(any()) } coAnswers {
            fx.givenZashi(replacement)
        }
        fx.submitReviewed(reviewed)
        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any(), matchNullable { true }) }
        verify(exactly = 0) { fx.navigationRouter.replace(*anyVararg()) }
    }

    @Test
    fun clearedReviewDuringAuthenticationRejectsWithoutWaitingForAnotherProposal() = runTest {
        val fx = useCase()
        val reviewed = swapProposal("eth", "eth")
        val next = swapProposal("btc", "btc")
        val authenticationStarted = CompletableDeferred<Unit>()
        val completeAuthentication = CompletableDeferred<Unit>()
        fx.givenZashi(reviewed)
        coEvery { fx.biometricRepository.requestBiometrics(any()) } coAnswers {
            authenticationStarted.complete(Unit)
            completeAuthentication.await()
        }

        val approval = launch { fx.submitReviewed(reviewed) }
        authenticationStarted.await()
        fx.zashiProposal.value = null
        completeAuthentication.complete(Unit)
        withTimeout(1.seconds) { approval.join() }

        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any(), matchNullable { true }) }
        verify(exactly = 1) { fx.navigateToError(any()) }

        fx.givenZashi(next)
        fx.submitReviewed(next)
        coVerify(exactly = 1) { fx.zashiProposalRepository.submit(next, any()) }
    }

    @Test
    fun ledgerSwapCanBeApprovedAgainAfterLeavingTheSignSheetBeforeFunding() = runTest {
        val fx = useCase()
        val proposal = swapProposal("eth", "eth")
        fx.givenLedger(proposal)
        val publication = SwapQuoteData.Success(proposal.quote)
        fx.publication.value = publication

        fx.useCase(proposal, publication)
        fx.useCase(proposal, publication)

        verify(exactly = 2) { fx.ledgerProposalPipeline.approveForSigning(proposal, refEq(publication)) }
        verify(exactly = 2) { fx.ledgerNavigator.forwardToSign(null, any()) }
        coVerify(exactly = 0) { fx.processSwapTransaction.reserveFunding(any(), any()) }
        coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
    }

    @Test
    fun swapSubmissionRequiresTheReviewedProposal() = runTest {
        val fx = useCase()
        fx.givenZashi(swapProposal("eth", "eth"))
        fx.useCase(null)
        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any(), matchNullable { true }) }
    }

    @Test
    fun expirationDuringAuthenticationCannotStartFunding() = runTest {
        val fx = useCase()
        val proposal = swapProposal("eth", "eth")
        fx.givenZashi(proposal)
        var wall = Instant.parse("2099-12-31T23:59:59Z")
        val clock = object : kotlin.time.Clock { override fun now() = wall }
        var elapsed = 10_000L
        every { proposal.quote.deadline } returns Instant.parse("2100-01-01T00:00:00Z")
        val publication = SwapQuoteData.Success(proposal.quote, fundingClock = clock, elapsedRealtime = { elapsed })
        fx.publication.value = publication
        assertTrue(publication.canFund())
        coEvery { fx.biometricRepository.requestBiometrics(any()) } coAnswers {
            wall -= 2.seconds
            elapsed = 11_000L
        }
        fx.useCase(proposal, publication)
        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any(), matchNullable { true }) }
    }

    @Test
    fun outgoingApprovalRefusesAProposalForAnotherAccountAfterAuthentication() = runTest {
        val fx = useCase()
        val proposal = swapProposal(tokenTicker = "eth", chainTicker = "eth")
        fx.givenZashi(proposal)
        every { proposal.fundingAccount } returns EditAccountFixtures.keystone()

        fx.submitReviewed(proposal)

        verify(exactly = 1) { fx.navigateToError(any(), any()) }
        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any(), matchNullable { true }) }
        verify(exactly = 0) { fx.navigationRouter.replace(TransactionProgressArgs) }
    }

    private suspend fun swapProposal(
        tokenTicker: String,
        chainTicker: String
    ): SwapTransactionProposal {
        val address = WalletAddress.Unified.new("deposit-address")
        return mockk {
            every { destination } returns address
            every { quote } returns
                mockk {
                    every { destinationAsset } returns
                        SwapAssetTestFixture.asset(tokenTicker = tokenTicker, chainTicker = chainTicker)
                    every { deadline } returns kotlin.time.Instant.fromEpochMilliseconds(Long.MAX_VALUE)
                }
        }
    }

    private fun Fixtures.givenKeystone(proposal: TransactionProposal) {
        val account = EditAccountFixtures.keystone()
        coEvery { accountDataSource.getSelectedAccount() } returns account
        if (proposal is SwapTransactionProposal) every { proposal.fundingAccount } returns account
        every { keystoneProposalRepository.transactionProposal } returns MutableStateFlow(proposal)
    }

    private fun Fixtures.givenLedger(proposal: TransactionProposal) {
        val account = EditAccountFixtures.ledger()
        coEvery { accountDataSource.getSelectedAccount() } returns account
        if (proposal is SwapTransactionProposal) every { proposal.fundingAccount } returns account
        every { ledgerProposalPipeline.transactionProposal } returns MutableStateFlow(proposal)
    }

    private fun Fixtures.givenZashi(
        proposal: TransactionProposal,
        submitResult: SubmitResult = mockk(relaxed = true)
    ) {
        val account = EditAccountFixtures.zodl()
        coEvery { accountDataSource.getSelectedAccount() } returns account
        if (proposal is SwapTransactionProposal) every { proposal.fundingAccount } returns account
        zashiProposal.value = proposal
        coEvery { zashiProposalRepository.submit(proposal, matchNullable { true }) } returns submitResult
    }

    /** Builds the use case with the off-thread submit scope pinned to the test scheduler. */
    private fun TestScope.useCase(): Fixtures {
        val fx = Fixtures()
        // The use case launches submission on its own scope; run it eagerly on the test scheduler.
        fx.useCase.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return fx
    }

    private class Fixtures {
        val zashiProposal = MutableStateFlow<TransactionProposal?>(null)
        val zashiProposalRepository = mockk<ZashiProposalRepository>(relaxed = true) {
            every { transactionProposal } returns zashiProposal
        }
        val navigationRouter = mockk<NavigationRouter>(relaxed = true)
        val accountDataSource = mockk<AccountDataSource>(relaxed = true)
        val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true)
        val ledgerProposalPipeline = mockk<LedgerProposalPipeline>(relaxed = true)
        val ledgerNavigator = mockk<LedgerNavigator>(relaxed = true)
        val biometricRepository = mockk<BiometricRepository>(relaxed = true)
        val publication = MutableStateFlow<SwapQuoteData?>(null)
        val swapRepository = mockk<SwapRepository>(relaxed = true) {
            every { quote } returns publication
        }

        suspend fun submitReviewed(proposal: TransactionProposal) {
            val reviewed = (proposal as? SwapTransactionProposal)?.let { SwapQuoteData.Success(it.quote) }
            publication.value = reviewed
            useCase(proposal, reviewed)
        }
        val processSwapTransaction = mockk<ProcessSwapTransactionUseCase>(relaxed = true)
        val navigateToError = mockk<NavigateToErrorUseCase>(relaxed = true)
        val prefillSend = mockk<PrefillSendUseCase>(relaxed = true)
        val useCase =
            SubmitProposalUseCase(
                navigationRouter = navigationRouter,
                accountDataSource = accountDataSource,
                zashiProposalRepository = zashiProposalRepository,
                keystoneProposalRepository = keystoneProposalRepository,
                ledgerProposalPipeline = ledgerProposalPipeline,
                ledgerNavigator = ledgerNavigator,
                biometricRepository = biometricRepository,
                swapRepository = swapRepository,
                processSwapTransaction = processSwapTransaction,
                prefillSend = prefillSend,
                navigateToError = navigateToError,
            )
    }

}
