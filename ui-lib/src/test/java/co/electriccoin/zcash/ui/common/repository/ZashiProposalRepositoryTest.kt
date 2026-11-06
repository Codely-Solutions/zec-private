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
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.FakeSwapQuote
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuote
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

class ZashiProposalRepositoryTest {
    private val proposals = mockk<ProposalDataSource>()
    private val keys = mockk<ZashiSpendingKeyDataSource>()
    private val repository = ZashiProposalRepositoryImpl(mockk<AccountDataSource>(), proposals, keys)

    @Test
    fun quoteExpiringDuringSpendingKeyAcquisitionCannotBroadcast() = runBlocking {
        var currentDeadline = Instant.parse("2100-01-01T00:00:00Z")
        val quote = object : SwapQuote by quote() {
            override val deadline: Instant get() = currentDeadline
        }
        repository.transactionProposal.value = ExactInputSwapTransactionProposal(
            WalletAddress.Unified.new("synthetic-deposit"), Zatoshi(100000000), Memo(""), mockk<Proposal>(), quote
        )
        coEvery { keys.getZashiSpendingKey() } answers {
            currentDeadline = Instant.parse("2000-01-01T00:00:00Z")
            mockk<UnifiedSpendingKey>()
        }

        val error = assertFailsWith<IllegalArgumentException> { repository.submit() }

        coVerify(exactly = 0) { proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) }
        val result = assertIs<SubmitProposalState.Result>(repository.submitState.value)
        assertSame(error, assertIs<SubmitResult.Error>(result.submitResult).cause)
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
        coEvery { proposals.submitTransaction(proposal, key) } returns expected

        assertEquals(expected, repository.submit())
        coVerify(exactly = 1) { proposals.submitTransaction(proposal, key) }
    }

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
