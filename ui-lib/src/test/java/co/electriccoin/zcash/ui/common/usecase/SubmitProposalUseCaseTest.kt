package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
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
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
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
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * [SubmitProposalUseCase] is gated by a biometric prompt and then branches on account type:
 * - a failed/cancelled biometric does nothing;
 * - Keystone accounts navigate to the PCZT signing flow (no clear, no submit here);
 * - Zashi accounts clear swap state, submit the proposal off-thread, then navigate to progress.
 *
 * Swap proposals additionally record the destination asset in history, and a successful Zashi submit
 * of a swap proposal kicks off [ProcessSwapTransactionUseCase]. The off-thread submit always clears
 * the send prefill (even when it throws).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubmitProposalUseCaseTest {
    @Test
    fun biometricsFailureDoesNothing() =
        runTest {
            val fx = useCase()
            coEvery { fx.biometricRepository.requestBiometrics(any()) } throws BiometricsFailureException()

            fx.useCase()

            coVerify(exactly = 0) { fx.accountDataSource.getSelectedAccount() }
            coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any()) }
            verify(exactly = 0) { fx.navigationRouter.replace(*anyVararg()) }
        }

    @Test
    fun biometricsCancellationDoesNothing() =
        runTest {
            val fx = useCase()
            coEvery { fx.biometricRepository.requestBiometrics(any()) } throws BiometricsCancelledException()

            fx.useCase()

            coVerify(exactly = 0) { fx.accountDataSource.getSelectedAccount() }
            verify(exactly = 0) { fx.navigationRouter.replace(*anyVararg()) }
        }

    @Test
    fun keystoneSwapProposalAddsHistoryAndNavigatesToSignKeystone() =
        runTest {
            val fx = useCase()
            val proposal = swapProposal(tokenTicker = "eth", chainTicker = "eth")
            fx.givenKeystone(proposal)

            fx.useCase(proposal)

            verify(exactly = 1) { fx.metadataRepository.addSwapAssetToHistory("eth", "eth") }
            verify(exactly = 1) { fx.navigationRouter.replace(SignKeystoneTransactionArgs) }
            coVerifyOrder {
                fx.processSwapTransaction.persistBeforeFunding(proposal)
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
            fx.givenKeystone(mockk<TransactionProposal>())

            fx.useCase()

            verify(exactly = 0) { fx.metadataRepository.addSwapAssetToHistory(any(), any()) }
            verify(exactly = 1) { fx.navigationRouter.replace(SignKeystoneTransactionArgs) }
            coVerify(exactly = 0) { fx.keystoneProposalRepository.submit() }
        }

    @Test
    fun ledgerProposalOpensTheSignSheetWithoutSubmittingOrClearingSwapState() =
        runTest {
            val fx = useCase()
            fx.givenLedger(mockk<TransactionProposal>())

            fx.useCase()

            verify(exactly = 1) { fx.ledgerNavigator.forwardToSign() }
            verify(exactly = 0) { fx.swapRepository.clear() }
            verify(exactly = 0) { fx.prefillSend.clear() }
            verify(exactly = 0) { fx.navigationRouter.replace(*anyVararg()) }
        }

    @Test
    fun zashiSwapProposalAddsHistoryClearsSubmitsProcessesAndNavigates() =
        runTest {
            val fx = useCase()
            val proposal = swapProposal(tokenTicker = "eth", chainTicker = "eth")
            val submitResult = mockk<SubmitResult>(relaxed = true)
            fx.givenZashi(proposal, submitResult)

            fx.useCase(proposal)

            verify(exactly = 1) { fx.metadataRepository.addSwapAssetToHistory("eth", "eth") }
            verify(exactly = 1) { fx.swapRepository.clear() }
            coVerify(exactly = 1) { fx.zashiProposalRepository.submit(any()) }
            coVerify(exactly = 1) { fx.processSwapTransaction(proposal, submitResult) }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
            verify(exactly = 1) { fx.prefillSend.clear() }
            // Swap state is cleared before submitting, and navigation happens after submit is kicked off.
            coVerifyOrder {
                fx.processSwapTransaction.persistBeforeFunding(proposal)
                fx.swapRepository.clear()
                fx.zashiProposalRepository.submit(proposal)
                fx.navigationRouter.replace(TransactionProgressArgs)
            }
        }

    @Test
    fun zashiNonSwapProposalSubmitsWithoutProcessingSwap() =
        runTest {
            val fx = useCase()
            fx.givenZashi(mockk<TransactionProposal>())

            fx.useCase()

            verify(exactly = 0) { fx.metadataRepository.addSwapAssetToHistory(any(), any()) }
            verify(exactly = 1) { fx.swapRepository.clear() }
            coVerify(exactly = 1) { fx.zashiProposalRepository.submit(any()) }
            coVerify(exactly = 0) { fx.processSwapTransaction(any(), any()) }
            verify(exactly = 1) { fx.navigationRouter.replace(TransactionProgressArgs) }
            verify(exactly = 1) { fx.prefillSend.clear() }
        }

    @Test
    fun approvedSwapCannotStartASecondSubmission() = runTest {
        val fx = useCase()
        val proposal = swapProposal("eth", "eth")
        fx.givenZashi(proposal)
        fx.useCase(proposal)
        fx.useCase(proposal)
        coVerify(exactly = 1) { fx.processSwapTransaction.persistBeforeFunding(any()) }
        coVerify(exactly = 1) { fx.zashiProposalRepository.submit(any()) }
    }

    @Test
    fun existingSwapBlocksPaymentUntilStatusIsReviewed() = runTest {
        val fx = useCase()
        val proposal = swapProposal("eth", "eth")
        fx.givenZashi(proposal)
        coEvery { fx.metadataRepository.getSwapMetadata(any()) } returns mockk<TransactionSwapMetadata>()
        fx.useCase(proposal)
        coVerify(exactly = 0) { fx.processSwapTransaction.persistBeforeFunding(any()) }
        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any()) }
    }

    @Test
    fun zashiSubmitFailureStillClearsPrefillAndStillNavigates() =
        runTest {
            val fx = useCase()
            coEvery { fx.accountDataSource.getSelectedAccount() } returns spendable<ZodlAccount>()
            val proposal = swapProposal(tokenTicker = "eth", chainTicker = "eth")
            coEvery { fx.zashiProposalRepository.getTransactionProposal() } returns proposal
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
        coVerify(exactly = 0) { fx.processSwapTransaction.persistBeforeFunding(any()) }
        verify(exactly = 0) { fx.navigationRouter.replace(*anyVararg()) }
    }

    @Test
    fun replacementDuringSwapTrackingCannotReachTheSdkSubmission() = runTest {
        val sdk = mockk<ProposalDataSource>()
        val actualRepository = ZashiProposalRepositoryImpl(mockk(), sdk, mockk())
        val fx = useCase(actualRepository)
        coEvery { fx.accountDataSource.getSelectedAccount() } returns spendable<ZodlAccount>()
        val reviewed = swapProposal("eth", "eth")
        actualRepository.transactionProposal.value = reviewed
        val trackingStarted = CompletableDeferred<Unit>()
        val finishTracking = CompletableDeferred<Unit>()
        coEvery { fx.processSwapTransaction.persistBeforeFunding(reviewed) } coAnswers {
            trackingStarted.complete(Unit)
            finishTracking.await()
        }

        val approval = launch { fx.useCase(reviewed) }
        trackingStarted.await()
        actualRepository.transactionProposal.value = swapProposal("btc", "btc")
        finishTracking.complete(Unit)
        approval.join()

        val result = actualRepository.submitState.filterIsInstance<SubmitProposalState.Result>().first()
        assertIs<IllegalStateException>(assertIs<SubmitResult.Error>(result.submitResult).cause)
        coVerify(exactly = 0) { sdk.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) }
    }

    @Test
    fun swapSubmissionRequiresTheReviewedProposal() = runTest {
        val fx = useCase()
        fx.givenZashi(swapProposal("eth", "eth"))
        fx.useCase()
        coVerify(exactly = 0) { fx.zashiProposalRepository.submit(any()) }
        coVerify(exactly = 0) { fx.processSwapTransaction.persistBeforeFunding(any()) }
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
        coVerify(exactly = 0) { fx.processSwapTransaction.persistBeforeFunding(any()) }
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
        coEvery { keystoneProposalRepository.getTransactionProposal() } returns proposal
    }

    private fun Fixtures.givenLedger(proposal: TransactionProposal) {
        coEvery { accountDataSource.getSelectedAccount() } returns spendable<LedgerAccount>()
        coEvery { ledgerProposalPipeline.getTransactionProposal() } returns proposal
    }

    private fun Fixtures.givenZashi(
        proposal: TransactionProposal,
        submitResult: SubmitResult = mockk(relaxed = true)
    ) {
        coEvery { accountDataSource.getSelectedAccount() } returns spendable<ZodlAccount>()
        coEvery { zashiProposalRepository.getTransactionProposal() } returns proposal
        coEvery { zashiProposalRepository.submit(proposal) } returns submitResult
    }

    /** Builds the use case with the off-thread submit scope pinned to the test scheduler. */
    private fun TestScope.useCase(
        repository: ZashiProposalRepository = mockk(relaxed = true)
    ): Fixtures {
        val fx = Fixtures(repository)
        // The use case launches submission on its own scope; run it eagerly on the test scheduler.
        fx.useCase.scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return fx
    }

    private class Fixtures(val zashiProposalRepository: ZashiProposalRepository) {
        val navigationRouter = mockk<NavigationRouter>(relaxed = true)
        val accountDataSource = mockk<AccountDataSource>(relaxed = true)
        val keystoneProposalRepository = mockk<KeystoneProposalRepository>(relaxed = true)
        val ledgerProposalPipeline = mockk<LedgerProposalPipeline>(relaxed = true)
        val ledgerNavigator = mockk<LedgerNavigator>(relaxed = true)
        val biometricRepository = mockk<BiometricRepository>(relaxed = true)
        val swapRepository = mockk<SwapRepository>(relaxed = true)
        val metadataRepository = mockk<MetadataRepository>(relaxed = true)
        val processSwapTransaction = mockk<ProcessSwapTransactionUseCase>(relaxed = true)
        init {
            coEvery { metadataRepository.getSwapMetadata(any()) } returns null
        }
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
                metadataRepository = metadataRepository,
                processSwapTransaction = processSwapTransaction,
                prefillSend = prefillSend,
                navigateToError = mockk(relaxed = true),
            )
    }

    private inline fun <reified T : WalletAccount> spendable(): T = mockk<T> { every { isSpendable } returns true }
}
