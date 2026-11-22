package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.WalletAddress

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerNavigator
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
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
import kotlin.time.Duration.Companion.seconds

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
            coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any()) }
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

            fx.useCase(proposal)

            verify(exactly = 1) { fx.navigationRouter.replace(SignKeystoneTransactionArgs) }
            coVerifyOrder {
                fx.keystoneProposalRepository.approveForSigning(proposal)
                fx.navigationRouter.replace(SignKeystoneTransactionArgs)
            }
            // Keystone defers submission to the PCZT flow — nothing is cleared, submitted, processed,
            // or prefill-cleared on this path.
            verify(exactly = 0) { fx.swapRepository.clear() }
            coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any()) }
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

            fx.useCase(proposal)

            verify(exactly = 1) { fx.navigationRouter.replace(SignKeystoneTransactionArgs) }
            coVerify(exactly = 0) { fx.keystoneProposalRepository.submit() }
        }

    @Test
    fun ledgerProposalOpensTheSignSheetWithoutSubmittingOrClearingSwapState() =
        runTest {
            val fx = useCase()
            val proposal = mockk<TransactionProposal>()
            fx.givenLedger(proposal)

            fx.useCase(proposal)

            verify(exactly = 1) { fx.ledgerNavigator.forwardToSign() }
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

            fx.useCase(proposal)

            verify(exactly = 1) { fx.swapRepository.clear() }
            coVerify(exactly = 1) { fx.zashiProposalRepository.submit(any()) }
            coVerify(exactly = 1) { fx.processSwapTransaction(proposal, submitResult) }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
            verify(exactly = 1) { fx.prefillSend.clear() }
            // Swap state is cleared before submitting, and navigation happens after submit is kicked off.
            coVerifyOrder {
                fx.swapRepository.clear()
                fx.zashiProposalRepository.submit(proposal)
                fx.navigationRouter.replace(TransactionProgressArgs)
            }
        }

    @Test
    fun zashiNonSwapProposalSubmitsWithoutProcessingSwap() =
        runTest {
            val fx = useCase()
            val proposal = mockk<TransactionProposal>()
            fx.givenZashi(proposal)

            fx.useCase(proposal)

            verify(exactly = 1) { fx.swapRepository.clear() }
            coVerify(exactly = 1) { fx.zashiProposalRepository.submit(any()) }
            coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
            verify(exactly = 1) { fx.prefillSend.clear() }
        }

    @Test
    fun zashiSubmitFailureStillClearsPrefillAndStillNavigates() =
        runTest {
            val fx = useCase()
            coEvery { fx.accountDataSource.getSelectedAccount() } returns spendable<ZodlAccount>()
            val proposal = swapProposal(tokenTicker = "eth", chainTicker = "eth")
            fx.zashiProposal.value = proposal
            coEvery { fx.zashiProposalRepository.submit(any()) } throws RuntimeException("boom")

            fx.useCase(proposal)

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
        fx.useCase(reviewed)
        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any()) }
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

        val approval = launch { fx.useCase(reviewed) }
        authenticationStarted.await()
        fx.zashiProposal.value = null
        completeAuthentication.complete(Unit)
        withTimeout(1.seconds) { approval.join() }

        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any()) }
        verify(exactly = 1) { fx.navigateToError(any()) }

        fx.givenZashi(next)
        fx.useCase(next)
        coVerify(exactly = 1) { fx.zashiProposalRepository.submit(next) }
    }

    @Test
    fun ledgerSwapCanBeApprovedAgainAfterLeavingTheSignSheetBeforeFunding() = runTest {
        val fx = useCase()
        val proposal = swapProposal("eth", "eth")
        fx.givenLedger(proposal)

        fx.useCase(proposal)
        fx.useCase(proposal)

        verify(exactly = 2) { fx.ledgerProposalPipeline.approveForSigning(proposal) }
        verify(exactly = 2) { fx.ledgerNavigator.forwardToSign() }
        coVerify(exactly = 0) { fx.processSwapTransaction.reserveFunding(any(), any()) }
        coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
    }

    @Test
    fun swapSubmissionRequiresTheReviewedProposal() = runTest {
        val fx = useCase()
        fx.givenZashi(swapProposal("eth", "eth"))
        fx.useCase(null)
        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any()) }
    }

    @Test
    fun expirationDuringAuthenticationCannotStartFunding() = runTest {
        val fx = useCase()
        val proposal = swapProposal("eth", "eth")
        fx.givenZashi(proposal)
        coEvery { fx.biometricRepository.requestBiometrics(any()) } coAnswers {
            every { proposal.quote.deadline } returns kotlin.time.Instant.fromEpochMilliseconds(0)
        }
        fx.useCase(proposal)
        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any()) }
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
        coEvery { accountDataSource.getSelectedAccount() } returns spendable<KeystoneAccount>()
        every { keystoneProposalRepository.transactionProposal } returns MutableStateFlow(proposal)
    }

    private fun Fixtures.givenLedger(proposal: TransactionProposal) {
        coEvery { accountDataSource.getSelectedAccount() } returns spendable<LedgerAccount>()
        every { ledgerProposalPipeline.transactionProposal } returns MutableStateFlow(proposal)
    }

    private fun Fixtures.givenZashi(
        proposal: TransactionProposal,
        submitResult: SubmitResult = mockk(relaxed = true)
    ) {
        coEvery { accountDataSource.getSelectedAccount() } returns spendable<ZodlAccount>()
        zashiProposal.value = proposal
        coEvery { zashiProposalRepository.submit(proposal) } returns submitResult
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
        val swapRepository = mockk<SwapRepository>(relaxed = true)
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

    private inline fun <reified T : WalletAccount> spendable(): T = mockk<T> { every { isSpendable } returns true }
}
