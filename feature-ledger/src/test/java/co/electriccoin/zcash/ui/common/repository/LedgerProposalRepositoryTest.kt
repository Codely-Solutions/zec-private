package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.exception.LedgerException
import cash.z.ecc.android.sdk.exception.PcztException
import cash.z.ecc.android.sdk.ledger.LedgerSigningProgress
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.LedgerBindingUnusableException
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldingApproval
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.LedgerIssueKind
import co.electriccoin.zcash.ui.common.model.LedgerIssueRetry
import co.electriccoin.zcash.ui.common.model.LedgerSigningState
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmissionProgress
import co.electriccoin.zcash.ui.common.usecase.SubmissionProgressStore
import co.electriccoin.zcash.ui.common.usecase.SubmitLedgerProposalUseCase
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import java.math.BigDecimal

/**
 * The Ledger signing session: the connect/sign state machine, retry over a kept link versus a
 * rescan, cancellation, submission, and the generation guard that keeps a session that already
 * unwound from overwriting the one that replaced it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedgerProposalRepositoryTest : LedgerProposalRepositoryTestBase() {
    @Test
    fun aRevokedShieldApprovalCannotReachLedgerSigningOnResume() = runTest(dispatcher) {
        val account = ledgerAccount()
        var approved = true
        val approval = ShieldingApproval(account) { approved }
        coEvery { accountDataSource.getSelectedAccount() } returns account
        coEvery { proposalDataSource.createShieldProposal(approval) } returns ShieldTransactionProposal(mockk())
        coEvery { proposalDataSource.createPcztFromProposal(any(), any(), any()) } returns Pczt(byteArrayOf(1))
        coEvery { proposalDataSource.addProofsToPczt(any()) } returns Pczt(byteArrayOf(2))
        every { ledgerSigningDataSource.isLinked } returns true
        val proposal = repository.createShieldProposal(approval)
        repository.createPCZTFromProposal()
        repository.approveForSigning(proposal)
        repository.startSigning()

        approved = false
        repository.startSigning()
        runCurrent()
        val failure = assertIs<LedgerSigningState.Failed>(repository.signingState.value)
        assertEquals(LedgerIssueKind.EXPIRED_SHIELD_APPROVAL, failure.issue.kind)
        assertEquals(LedgerIssueRetry.NONE, failure.issue.retry)
        coVerify(exactly = 0) { ledgerSigningDataSource.sign(any(), any(), any(), any()) }
    }

    @Test
    fun signedHandoffDoesNotDependOnASheetObserverAndCannotAttachToAReplacement() = runTest(dispatcher) {
        every { ledgerSigningDataSource.isLinked } returns true
        coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns Pczt(byteArrayOf(5))
        val account = ledgerAccount()
        givenPczt(account)
        val original = repository.getTransactionProposal()
        val accepted = mutableListOf<SignedLedgerSubmission>()

        repository.startSigning { accepted.add(it) }
        runCurrent()
        val signed = accepted.single()
        assertSame(original, signed.proposal)
        assertEquals(account.sdkAccount.accountUuid, signed.owner)
        assertEquals(LedgerSigningState.Signed, repository.signingState.value)
        repository.cancelSigning(onlyUnsigned = true)
        assertFalse(repository.cancelUnsignedTransaction())
        assertEquals(LedgerSigningState.Signed, repository.signingState.value)
        repository.startSigning { accepted.add(it) }
        runCurrent()
        assertEquals(1, accepted.size)

        givenPczt(account, proposal = original)
        assertFailsWith<IllegalStateException> { signed.submit() }
        coVerify(exactly = 0) { proposalDataSource.submitTransaction(any<Pczt>(), any<Pczt>(), any(), any()) }
    }

    @Test
    fun aSupersededProofCancelsTheArtifactAndTerminatesItsAwaitingSubmission() = runTest(dispatcher) {
        every { ledgerSigningDataSource.isLinked } returns true
        coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns Pczt(byteArrayOf(5))
        val account = ledgerAccount()
        givenPczt(account, proofsGate = CompletableDeferred())
        val original = repository.getTransactionProposal()
        val progress = mockk<SubmissionProgress>(relaxed = true)
        val store = mockk<SubmissionProgressStore> { every { begin(original, any()) } returns progress }
        val submit = SubmitLedgerProposalUseCase(
            mockk(relaxed = true), mockk(relaxed = true), processSwapTransaction, mockk(relaxed = true), store,
        ).apply { scope = CoroutineScope(dispatcher) }
        repository.startSigning { submit(it, "sign") }
        runCurrent()
        verify(exactly = 0) { progress.recordFailure(any()) }

        repository.clear(original)
        runCurrent()

        assertNull(repository.submitState.value)
        verify(exactly = 1) { progress.recordFailure(any<IllegalStateException>()) }
        coVerify(exactly = 0) { proposalDataSource.submitTransaction(any<Pczt>(), any<Pczt>(), any(), any()) }
    }

    @Test
    fun recreatedLedgerRepositoryPublishesMissingProposalToReview() = runTest(dispatcher) {
        val account = ledgerAccount()
        every { accountDataSource.selectedAccount } returns flowOf(account)
        givenPczt(account)
        val oldReview = ObserveProposalUseCase(mockk(), repository, mockk(), accountDataSource)
        assertNotNull(oldReview.observeNullable().first())

        val recreated = LedgerProposalRepositoryImpl(
            accountDataSource, proposalDataSource, ledgerDeviceDataSource, ledgerSigningDataSource,
            processSwapTransaction,
        )
        val restoredReview = ObserveProposalUseCase(mockk(), recreated, mockk(), accountDataSource)
        assertNull(restoredReview.observeNullable().first())
    }

    @Test
    fun quoteExpiryDuringPcztTransactionCreationReachesTheFinalFundingGuard() = runTest(dispatcher) {
        every { ledgerSigningDataSource.isLinked } returns true
        coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns Pczt(byteArrayOf(5))
        var wall = Instant.parse("2099-12-31T23:59:59Z")
        val clock = object : kotlin.time.Clock { override fun now() = wall }
        var elapsed = 0L
        val origin = mockk<SwapAsset>(relaxed = true) {
            every { tokenTicker } returns "zec"
            every { chainTicker } returns "zec"
            every { blockchain.chainTicker } returns "zec"
            every { assetId } returns "zec-zec"
            every { decimals } returns 8
        }
        val quote = object : SwapQuote by mockk<SwapQuote>() {
            override val originAsset = origin
            override val destinationAsset = mockk<SwapAsset>(relaxed = true)
            override val mode = SwapMode.EXACT_INPUT
            override val provider = "near"
            override val depositAddress = DynamicSwapAddress("synthetic-deposit")
            override val amountInFormatted = BigDecimal.ONE
            override val amountInUsd = BigDecimal("100")
            override val amountOutFormatted = BigDecimal.ONE
            override val affiliateFeeUsd = BigDecimal.ZERO
            override val deadline = Instant.parse("2100-01-01T00:00:00Z")
        }
        val publication = SwapQuoteData.Success(quote, fundingClock = clock, elapsedRealtime = { elapsed })
        val approved = ExactInputSwapTransactionProposal(
            WalletAddress.Unified.new("synthetic-deposit"), Zatoshi(100000000), Memo(""),
            mockk<Proposal> { every { totalFeeRequired() } returns Zatoshi(0L) }, quote, ledgerAccount(),
        )
        givenPczt(ledgerAccount(), proposal = approved, publication = publication)
        repository.startSigning()
        runCurrent()
        var broadcast = false
        coEvery { proposalDataSource.submitTransaction(any<Pczt>(), any<Pczt>(), any(), any()) } answers {
            wall -= 2.seconds
            elapsed = 1_000L
            arg<() -> Unit>(3).invoke()
            broadcast = true
            SubmitResult.Success(listOf("synthetic"))
        }

        assertFailsWith<IllegalStateException> { repository.submit(approved) }
        assertFalse(broadcast)
    }

    @Test
    fun quoteExpiringWhileAwaitingProofsAfterSigningCannotBroadcast() = runTest(dispatcher) {
        val proofsGate = CompletableDeferred<Unit>()
        every { ledgerSigningDataSource.isLinked } returns true
        coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns Pczt(byteArrayOf(5))
        var wall = Instant.parse("2099-12-31T23:59:59Z")
        val clock = object : kotlin.time.Clock { override fun now() = wall }
        var elapsed = 0L
        val origin = mockk<SwapAsset>(relaxed = true) {
            every { tokenTicker } returns "zec"
            every { chainTicker } returns "zec"
            every { blockchain.chainTicker } returns "zec"
            every { assetId } returns "zec-zec"
            every { decimals } returns 8
        }
        val quote = object : SwapQuote by mockk<SwapQuote>() {
            override val originAsset = origin
            override val mode = SwapMode.EXACT_INPUT
            override val provider = "near"
            override val depositAddress = DynamicSwapAddress("synthetic-deposit")
            override val amountInFormatted = BigDecimal.ONE
            override val deadline = Instant.parse("2100-01-01T00:00:00Z")
        }
        val publication = SwapQuoteData.Success(quote, fundingClock = clock, elapsedRealtime = { elapsed })
        val approved = ExactInputSwapTransactionProposal(
            WalletAddress.Unified.new("synthetic-deposit"), Zatoshi(100000000), Memo(""),
            mockk<Proposal>(), quote, ledgerAccount(),
        )
        givenPczt(ledgerAccount(), proofsGate = proofsGate, proposal = approved, publication = publication)
        repository.startSigning()
        runCurrent()
        assertEquals(LedgerSigningState.Signed, repository.signingState.value)
        val submitting = async { runCatching { repository.submit(approved) } }
        runCurrent()
        assertFalse(submitting.isCompleted)

        wall -= 2.seconds
        elapsed = 1_000L
        proofsGate.complete(Unit)
        runCurrent()

        val error = assertIs<IllegalStateException>(submitting.await().exceptionOrNull())
        coVerify(exactly = 0) { proposalDataSource.submitTransaction(any<Pczt>(), any<Pczt>(), any(), any()) }
        val result = assertIs<SubmitProposalState.Result>(repository.submitState.value)
        val recorded = assertIs<IllegalStateException>(assertIs<SubmitResult.Error>(result.submitResult).cause)
        assertEquals(error.message, recorded.message)
    }

    @Test
    fun replacingApprovedProposalWhileAwaitingProofsCannotBroadcast() = runTest(dispatcher) {
        val proofsGate = CompletableDeferred<Unit>()
        givenPczt(ledgerAccount(), proofsGate = proofsGate)
        val approved = repository.getTransactionProposal()
        every { ledgerSigningDataSource.isLinked } returns true
        coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns Pczt(byteArrayOf(5))
        repository.startSigning()
        runCurrent()
        assertEquals(LedgerSigningState.Signed, repository.signingState.value)
        val submitting = async { runCatching { repository.submit(approved) } }
        runCurrent()
        assertFalse(submitting.isCompleted)

        repository.transactionProposal.value = mockk<TransactionProposal>()
        proofsGate.complete(Unit)
        runCurrent()

        assertIs<IllegalStateException>(submitting.await().exceptionOrNull())
        coVerify(exactly = 0) { proposalDataSource.submitTransaction(any<Pczt>(), any<Pczt>(), any(), any()) }
    }

    @Test
    fun progressMapsToTheSigningStatesAsItArrives() =
        runTest(dispatcher) {
            givenPczt(ledgerAccount())
            runCurrent()
            every { ledgerSigningDataSource.isLinked } returns true

            val progress = slot<(LedgerSigningProgress) -> Unit>()
            val signed = Pczt(byteArrayOf(5))
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), capture(progress)) } coAnswers {
                progress.captured(LedgerSigningProgress.IdentifyingDevice)
                assertEquals(LedgerSigningState.Streaming(0, 0), repository.signingState.value)

                progress.captured(LedgerSigningProgress.Streaming(3, 10))
                assertEquals(LedgerSigningState.Streaming(3, 10), repository.signingState.value)

                progress.captured(LedgerSigningProgress.AwaitingReviewOnDevice)
                assertEquals(LedgerSigningState.AwaitingReview, repository.signingState.value)

                progress.captured(LedgerSigningProgress.Signing)
                assertEquals(LedgerSigningState.Signing, repository.signingState.value)

                progress.captured(LedgerSigningProgress.Complete)
                assertEquals(LedgerSigningState.Signing, repository.signingState.value)

                signed
            }

            repository.startSigning()
            runCurrent()

            assertEquals(LedgerSigningState.Signed, repository.signingState.value)
        }

    @Test
    fun submitPassesTheProofsPcztAndTheSignedPcztToSubmitTransaction() =
        runTest(dispatcher) {
            val proofsPczt = Pczt(byteArrayOf(7))
            givenPczt(ledgerAccount(), proofsPczt = proofsPczt)
            runCurrent()
            every { ledgerSigningDataSource.isLinked } returns true
            val signed = Pczt(byteArrayOf(5))
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns signed
            val submitResult = mockk<SubmitResult>(relaxed = true)
            coEvery { proposalDataSource.submitTransaction(proofsPczt, signed, any(), any()) } returns submitResult

            repository.startSigning()
            runCurrent()
            assertEquals(LedgerSigningState.Signed, repository.signingState.value)

            val result = repository.submit()

            assertEquals(submitResult, result)
            coVerify(exactly = 1) { proposalDataSource.submitTransaction(proofsPczt, signed, any(), any()) }
        }

    @Test
    fun userRejectedFailsWithSameLinkAndRetrySignsOverTheSameLinkWithoutARescan() =
        runTest(dispatcher) {
            givenPczt(ledgerAccount())
            runCurrent()
            every { ledgerSigningDataSource.isLinked } returns true
            var signCalls = 0
            val signed = Pczt(byteArrayOf(5))
            val rejected = mockk<LedgerException.UserRejected>(relaxed = true) { every { isRestartable } returns true }
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } coAnswers {
                signCalls++
                if (signCalls == 1) throw rejected else signed
            }

            repository.startSigning()
            runCurrent()

            val failed = repository.signingState.value
            assertTrue(failed is LedgerSigningState.Failed)
            assertEquals(LedgerIssueKind.REJECTED, failed.issue.kind)
            assertEquals(LedgerIssueRetry.SAME_LINK, failed.issue.retry)

            repository.retry()
            runCurrent()

            assertEquals(LedgerSigningState.Signed, repository.signingState.value)
            coVerify(exactly = 0) { ledgerSigningDataSource.connect(any(), any()) }
        }

    @Test
    fun disconnectedFailsWithReconnectAndRetryRescans() =
        runTest(dispatcher) {
            givenPczt(ledgerAccount())
            runCurrent()
            var linked = false
            every { ledgerSigningDataSource.isLinked } answers { linked }
            coEvery { ledgerSigningDataSource.connect(any(), any()) } answers { linked = true }
            var signCalls = 0
            val signed = Pczt(byteArrayOf(5))
            val disconnected = mockk<LedgerException.Disconnected>(relaxed = true)
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } coAnswers {
                signCalls++
                if (signCalls == 1) {
                    linked = false
                    throw disconnected
                } else {
                    signed
                }
            }

            devices.value = listOf(device("AA"))
            repository.startSigning()
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()

            val failed = repository.signingState.value
            assertTrue(failed is LedgerSigningState.Failed)
            assertEquals(LedgerIssueRetry.RECONNECT, failed.issue.retry)

            repository.retry()
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals(LedgerSigningState.Signed, repository.signingState.value)
            coVerify(exactly = 2) { ledgerSigningDataSource.connect(any(), any()) }
        }

    @Test
    fun cancelSigningClosesTheLinkAndKeepsTheProposalPcztAndProofs() =
        runTest(dispatcher) {
            val proofsPczt = Pczt(byteArrayOf(7))
            val proofsGate = CompletableDeferred<Unit>()
            var proofsCancelled = false
            val pczt =
                givenPczt(
                    ledgerAccount(),
                    proofsPczt = proofsPczt,
                    proofsGate = proofsGate,
                    onProofsCancelled = { proofsCancelled = true }
                )
            runCurrent()
            clearMocks(ledgerSigningDataSource, answers = false)

            repository.startSigning()
            runCurrent()

            repository.cancelSigning()
            runCurrent()

            assertNull(repository.signingState.value)
            coVerify(exactly = 1) { ledgerSigningDataSource.close() }
            assertSame(pczt, repository.getProposalPCZT())
            assertNotNull(repository.transactionProposal.value)
            assertFalse(proofsCancelled)

            every { ledgerSigningDataSource.isLinked } returns true
            val signed = Pczt(byteArrayOf(5))
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns signed
            val submitResult = mockk<SubmitResult>(relaxed = true)
            coEvery { proposalDataSource.submitTransaction(proofsPczt, signed, any(), any()) } returns submitResult
            repository.startSigning()
            runCurrent()
            proofsGate.complete(Unit)
            runCurrent()

            assertEquals(submitResult, repository.submit())
            coVerify(exactly = 1) { proposalDataSource.addProofsToPczt(any()) }
        }

    @Test
    fun cancelBeforeDeviceSigningAllowsReapprovalWithoutReservingFunding() = runTest(dispatcher) {
        val reviewed = ExactInputSwapTransactionProposal(
            WalletAddress.Unified.new("synthetic-deposit"), Zatoshi(100), Memo(""), mockk(), mockk(), ledgerAccount()
        )
        every { reviewed.quote.deadline } returns Instant.parse("2100-01-01T00:00:00Z")
        val publication = SwapQuoteData.Success(reviewed.quote)
        givenPczt(ledgerAccount(), proposal = reviewed, publication = publication)
        repository.startSigning()
        runCurrent()
        assertEquals(LedgerSigningState.Scanning, repository.signingState.value)

        repository.cancelSigning()
        runCurrent()
        assertSame(reviewed, repository.transactionProposal.value)
        repository.approveForSigning(reviewed, publication)
        every { ledgerSigningDataSource.isLinked } returns true
        coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns Pczt(byteArrayOf(5))
        repository.startSigning()
        runCurrent()

        assertEquals(LedgerSigningState.Signed, repository.signingState.value)
        coVerify(exactly = 0) {
            metadataRepository.markTxAsSwap(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                account = any(), walletWriteToken = 0L)
        }
        coVerify(exactly = 0) { proposalDataSource.submitTransaction(any<Pczt>(), any<Pczt>(), any(), any()) }
    }

    @Test
    fun cancellingOrClearingWithNoSessionOrLinkLeavesTheLinkAlone() =
        runTest(dispatcher) {
            repository.cancelSigning()
            repository.clear()
            runCurrent()

            coVerify(exactly = 0) { ledgerSigningDataSource.close() }
            assertNull(repository.signingState.value)
        }

    @Test
    fun clearResetsTheProposalPcztProofsAndSigningState() =
        runTest(dispatcher) {
            givenPczt(ledgerAccount())
            runCurrent()

            repository.clear()
            runCurrent()

            assertNull(repository.transactionProposal.value)
            assertNull(repository.submitState.value)
            assertNull(repository.getProposalPCZT())
            assertNull(repository.signingState.value)
        }

    @Test
    fun submitAwaitsTheProofsJobAndSurfacesItsError() =
        runTest(dispatcher) {
            val proofsError = mockk<PcztException.AddProofsToPcztException>(relaxed = true)
            val proofsGate = CompletableDeferred<Unit>()
            givenPczt(ledgerAccount(), proofsError = proofsError, proofsGate = proofsGate)
            runCurrent()
            every { ledgerSigningDataSource.isLinked } returns true
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns Pczt(byteArrayOf(5))

            repository.startSigning()
            runCurrent()
            assertEquals(LedgerSigningState.Signed, repository.signingState.value)

            val submitting = async { runCatching { repository.submit() } }
            runCurrent()
            assertFalse(submitting.isCompleted)

            proofsGate.complete(Unit)
            runCurrent()

            assertTrue(submitting.isCompleted)
            assertSame(proofsError, submitting.await().exceptionOrNull())
        }

    @Test
    fun createPcztFromProposalStartsACleanSubmissionCycle() =
        runTest(dispatcher) {
            givenPczt(ledgerAccount())
            runCurrent()
            every { ledgerSigningDataSource.isLinked } returns true
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } returns Pczt(byteArrayOf(5))
            val submitResult = mockk<SubmitResult>(relaxed = true)
            coEvery {
                proposalDataSource.submitTransaction(any<Pczt>(), any<Pczt>(), any(), any())
            } returns submitResult
            repository.startSigning()
            runCurrent()
            repository.submit()
            assertNotNull(repository.submitState.value)

            repository.createPCZTFromProposal()
            runCurrent()

            assertNull(repository.submitState.value)
            assertNull(repository.signingState.value)
            assertFailsWith<IllegalStateException> { repository.submit() }
        }

    /**
     * The retry arrives while the failed session is still inside its own publish of the failure,
     * i.e. while its job is still active.
     */

    @Test
    fun retryWhileTheFailedSessionIsStillUnwindingStartsANewSession() =
        runTest(dispatcher) {
            givenPczt(ledgerAccount())
            runCurrent()
            every { ledgerSigningDataSource.isLinked } returns true
            var signCalls = 0
            val signed = Pczt(byteArrayOf(5))
            val rejected = mockk<LedgerException.UserRejected>(relaxed = true)
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } coAnswers {
                signCalls++
                if (signCalls == 1) throw rejected else signed
            }
            var retried = false
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.signingState.collect { state ->
                    if (state is LedgerSigningState.Failed && !retried) {
                        retried = true
                        repository.retry()
                    }
                }
            }

            repository.startSigning()
            runCurrent()

            assertTrue(retried)
            assertEquals(2, signCalls)
            assertEquals(LedgerSigningState.Signed, repository.signingState.value)
        }

    @Test
    fun aCorruptStoredBindingFailsWithUnboundAndOffersNoRetry() =
        runTest(dispatcher) {
            givenPczt(ledgerAccount())
            runCurrent()
            every { ledgerSigningDataSource.isLinked } returns true
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } throws
                LedgerBindingUnusableException(IllegalArgumentException("corrupt"))

            repository.startSigning()
            runCurrent()

            val failed = repository.signingState.value
            assertTrue(failed is LedgerSigningState.Failed)
            assertEquals(LedgerIssueKind.UNBOUND, failed.issue.kind)
            assertEquals(LedgerIssueRetry.NONE, failed.issue.retry)
        }

    @Test
    fun anUnboundAccountFailsWithUnboundAndOffersNoRetry() =
        runTest(dispatcher) {
            givenPczt(ledgerAccount(bound = true))
            runCurrent()
            coEvery { accountDataSource.getSelectedAccount() } returns ledgerAccount(bound = false)

            repository.startSigning()
            runCurrent()

            val failed = repository.signingState.value
            assertTrue(failed is LedgerSigningState.Failed)
            assertEquals(LedgerIssueKind.UNBOUND, failed.issue.kind)
            assertEquals(LedgerIssueRetry.NONE, failed.issue.retry)
            coVerify(exactly = 0) { ledgerSigningDataSource.connect(any(), any()) }
        }

    /**
     * Simulates a session that is cancelled and immediately replaced while its own `sign` call is
     * still outstanding, by draining the replacement to completion from inside the outstanding
     * call's mock before it finally resolves — the "late" resolution can then only be published if
     * the generation guard is missing.
     */

    @Test
    fun aLateFailureFromACancelledSessionNeverOverwritesTheSessionThatReplacedIt() =
        runTest(dispatcher) {
            givenPczt(ledgerAccount())
            runCurrent()
            every { ledgerSigningDataSource.isLinked } returns true
            var signCalls = 0
            val signed = Pczt(byteArrayOf(5))
            val disconnected = mockk<LedgerException.Disconnected>(relaxed = true)
            coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } coAnswers {
                signCalls++
                if (signCalls == 1) {
                    repository.cancelSigning()
                    repository.startSigning()
                    dispatcher.scheduler.runCurrent()
                    throw disconnected
                } else {
                    signed
                }
            }

            repository.startSigning()
            runCurrent()

            assertEquals(LedgerSigningState.Signed, repository.signingState.value)
            assertEquals(2, signCalls)
        }

    @Test
    fun losingTheProposalDuringDeviceSigningCannotAdmitAHandoff() = runTest(dispatcher) {
        every { ledgerSigningDataSource.isLinked } returns true
        val deviceResult = CompletableDeferred<Pczt>()
        coEvery { ledgerSigningDataSource.sign(any(), any(), any(), any()) } coAnswers { deviceResult.await() }
        givenPczt(ledgerAccount())
        val accepted = mutableListOf<SignedLedgerSubmission>()
        repository.startSigning { accepted.add(it) }
        runCurrent()

        repository.transactionProposal.value = null
        deviceResult.complete(Pczt(byteArrayOf(5)))
        runCurrent()

        assertEquals(emptyList(), accepted)
        val failed = assertIs<LedgerSigningState.Failed>(repository.signingState.value)
        assertEquals(LedgerIssueRetry.NONE, failed.issue.retry)
    }

    @Test
    fun signingWithoutAPcztFailsWithoutARetry() =
        runTest(dispatcher) {
            repository.startSigning()
            runCurrent()

            val failed = repository.signingState.value
            assertTrue(failed is LedgerSigningState.Failed)
            assertEquals(LedgerIssueKind.UNKNOWN, failed.issue.kind)
            assertEquals(LedgerIssueRetry.NONE, failed.issue.retry)
        }
}
