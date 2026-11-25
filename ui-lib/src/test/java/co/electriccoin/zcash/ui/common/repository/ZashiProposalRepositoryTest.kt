package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ShieldTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.usecase.ProcessSwapTransactionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ZashiProposalRepositoryTest {
    private val proposals = mockk<ProposalDataSource>()
    private val keys = mockk<ZashiSpendingKeyDataSource>()
    private val metadata = mockk<MetadataRepository>(relaxed = true)
    private val repository = ZashiProposalRepositoryImpl(
        mockk<AccountDataSource>(), proposals, keys, ProcessSwapTransactionUseCase(metadata, mockk())
    )

    @Test
    fun migrationAdoptionReturnsTheExactReviewIdentityPublishedForAuthentication() {
        val sdkProposal = mockk<Proposal>()
        val adopted = repository.setMigrationSweepProposal(sdkProposal)

        assertSame(adopted, repository.transactionProposal.value)
        assertSame(sdkProposal, adopted.proposal)
    }

    @Test
    fun shieldingAdmitsOnlyItsOwnProposalAndPreservesAReplacementDraft() = runBlocking {
        val shield = ShieldTransactionProposal(mockk<Proposal>())
        val draft = RegularTransactionProposal(
            WalletAddress.Unified.new("synthetic-recipient"), Zatoshi(10), Memo(""), mockk<Proposal>()
        )
        repository.transactionProposal.value = draft
        val draftState = SubmitProposalState.Result(SubmitResult.Success(listOf("fixture")))
        repository.submitState.value = draftState

        assertFailsWith<IllegalStateException> { repository.submit(shield) }
        repository.clearIfCurrent(shield)

        assertSame(draft, repository.transactionProposal.value)
        assertSame(draftState, repository.submitState.value)
        coVerify(exactly = 0) { keys.getZashiSpendingKey() }
        coVerify(exactly = 0) { proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>(), any()) }
    }

    @Test
    fun quoteExpiringDuringSpendingKeyAcquisitionCannotBroadcast() = runBlocking {
        var currentDeadline = Instant.parse("2100-01-01T00:00:00Z")
        val quote = object : SwapQuote by quote() {
            override val deadline: Instant get() = currentDeadline
        }
        val reviewed = ExactInputSwapTransactionProposal(
            WalletAddress.Unified.new("synthetic-deposit"), Zatoshi(100000000), Memo(""),
            mockk<Proposal>(), quote, mockk(),
        )
        repository.transactionProposal.value = reviewed
        coEvery { keys.getZashiSpendingKey() } answers {
            currentDeadline = Instant.parse("2000-01-01T00:00:00Z")
            mockk<UnifiedSpendingKey>()
        }

        val error = assertFailsWith<IllegalStateException> {
            repository.submit(reviewed, SwapQuoteData.Success(reviewed.quote))
        }

        coVerify(exactly = 0) { proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>(), any()) }
        val result = assertIs<SubmitProposalState.Result>(repository.submitState.value)
        val recorded = assertIs<IllegalStateException>(assertIs<SubmitResult.Error>(result.submitResult).cause)
        assertEquals(error.message, recorded.message)
    }

    @Test
    fun nativeSendStillSubmitsWithoutSwapValidation() = runBlocking {
        val proposal = mockk<Proposal>()
        val key = mockk<UnifiedSpendingKey>()
        val expected = SubmitResult.Success(listOf("synthetic-tx"))
        repository.transactionProposal.value = RegularTransactionProposal(
            WalletAddress.Unified.new("synthetic-recipient"), Zatoshi(1), Memo(""), proposal
        )
        coEvery { keys.getZashiSpendingKey() } returns key
        coEvery { proposals.submitTransaction(proposal, key, any()) } returns expected

        assertEquals(expected, repository.submit())
        coVerify(exactly = 1) { proposals.submitTransaction(proposal, key, any()) }
    }

    @Test
    fun reviewedSwapCannotSubmitAReplacementFromTheRepository() = runBlocking {
        val reviewed = ExactInputSwapTransactionProposal(
            WalletAddress.Unified.new("synthetic-deposit"), Zatoshi(100000000), Memo(""),
            mockk<Proposal>(), quote(), mockk(),
        )
        repository.transactionProposal.value = RegularTransactionProposal(
            WalletAddress.Unified.new("synthetic-recipient"), Zatoshi(1), Memo(""), mockk<Proposal>()
        )

        assertFailsWith<IllegalStateException> {
            repository.submit(reviewed, SwapQuoteData.Success(reviewed.quote))
        }

        coVerify(exactly = 0) { keys.getZashiSpendingKey() }
        coVerify(exactly = 0) { proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>(), any()) }
    }

    @Test
    fun durableTrackingCompletesBeforeTheSdkCanFundTheReview() = runBlocking {
        val key = mockk<UnifiedSpendingKey>()
        val reviewed = swapProposal()
        val expected = SubmitResult.Success(listOf("synthetic-tx"))
        repository.transactionProposal.value = reviewed
        coEvery { keys.getZashiSpendingKey() } returns key
        coEvery { metadata.getSwapMetadata(any(), any()) } returns null
        coEvery {
            metadata.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                account = any(), walletWriteToken = 0L, reservationCreatedAt = ofType<java.time.Instant>(),
            )
        } returns true
        coEvery { proposals.submitTransaction(reviewed.proposal, key, any()) } returns expected

        assertEquals(expected, repository.submit(reviewed, SwapQuoteData.Success(reviewed.quote)))

        coVerifyOrder {
            metadata.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                account = any(), walletWriteToken = 0L, reservationCreatedAt = ofType<java.time.Instant>(),
            )
            proposals.submitTransaction(reviewed.proposal, key, any())
        }
    }

    @Test
    fun replacementDuringDurableTrackingCannotReachTheSdkSubmission() = runBlocking {
        val reviewed = swapProposal()
        repository.transactionProposal.value = reviewed
        coEvery { keys.getZashiSpendingKey() } returns mockk()
        coEvery { metadata.getSwapMetadata(any(), any()) } returns null
        val trackingStarted = CompletableDeferred<Unit>()
        val finishTracking = CompletableDeferred<Unit>()
        coEvery {
            metadata.markTxAsSwap(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                account = any(), walletWriteToken = 0L, reservationCreatedAt = ofType<java.time.Instant>(),
            )
        } coAnswers {
            trackingStarted.complete(Unit)
            finishTracking.await()
            true
        }

        val approval = async {
            assertFailsWith<IllegalStateException> {
                repository.submit(reviewed, SwapQuoteData.Success(reviewed.quote))
            }
        }
        try {
            withTimeout(5.seconds) { trackingStarted.await() }
            repository.transactionProposal.value = swapProposal()
        } finally {
            finishTracking.complete(Unit)
        }
        approval.await()

        coVerify(exactly = 0) { proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>(), any()) }
    }

    private suspend fun swapProposal() = ExactInputSwapTransactionProposal(
        WalletAddress.Unified.new("synthetic-deposit"),
        Zatoshi(100000000),
        Memo(""),
        mockk<Proposal> { every { totalFeeRequired() } returns Zatoshi(1000) },
        quote(),
        mockk(),
    )

    private fun quote() = FakeSwapQuote(
        originAsset = SwapAssetTestFixture.zecAsset(),
        destinationAsset = SwapAssetTestFixture.asset(),
        mode = SwapMode.EXACT_INPUT,
        amountIn = BigDecimal("100000000"),
        amountInFormatted = BigDecimal.ONE,
        amountOutFormatted = BigDecimal.ONE,
        depositAddress = "synthetic-deposit",
        destinationAddress = "synthetic-destination",
        refundAddress = "synthetic-refund"
    )
}
