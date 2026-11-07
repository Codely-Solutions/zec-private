package co.electriccoin.zcash.ui.common.donation

import android.content.Context
import co.electriccoin.zcash.ui.screen.donation.DonationPreferences
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ValuePool
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigDecimal
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** No SDK signer, broadcaster, wallet seed or network implementation is instantiated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AutoDonationCoordinatorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("private_auto_donation_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("private_donation_suggestions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun savedSuggestionAndUnenrolledSuccessCannotSpend() = runTest {
        DonationPreferences(context).percent = BigDecimal.ONE
        val f = Fixture(this)
        f.engine.enroll(f.quote, listOf(f.depositId))
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertNull(f.store.latestOutcome())
        f.verifyNoSpending()
    }

    @Test fun historicalQuoteCannotEnrollAfterAuthorization() = runTest {
        val f = Fixture(this)
        f.authorize()
        f.quote.timestamp = Clock.System.now() - 60.seconds
        f.engine.enroll(f.quote, listOf(f.depositId))
        assertNull(f.store.reserve("deposit"))
        f.verifyNoSpending()
    }

    @Test fun revocationAfterEnrollmentPreventsSpendingAndConsumesEnrollment() = runTest {
        val f = Fixture(this)
        f.enroll()
        f.store.disable()
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("authorization unavailable"))
        assertNull(f.store.reserve("deposit"))
        f.verifyNoSpending()
    }

    @Test fun reauthorizationCannotRevivePreviouslyEnrolledSwap() = runTest {
        val f = Fixture(this)
        f.enroll()
        f.store.authorize(f.accountId, BigDecimal("2"))
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        f.verifyNoSpending()
    }

    @Test fun successAndReplayCreateOnlyOneDonation() = runTest {
        val f = Fixture(this)
        f.enroll()
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("submitted"))
        f.engine.onStatus(f.status)
        f.newEngine(this).onStatus(f.status)
        advanceUntilIdle()
        coVerify(exactly = 1) { f.proposals.createProposal(any(), any()) }
        coVerify(exactly = 1) { f.keys.getZashiSpendingKey() }
        coVerify(exactly = 1) { f.proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) }
    }

    @Test fun incomingConfirmedReceiptUsesExecutedOutput() = runTest {
        val f = Fixture(this)
        f.prepareIncoming(101)
        f.enroll()
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("submitted"))
        coVerify(exactly = 1) { f.proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) }
    }

    @Test fun incomingReceiptBeforeEnrollmentCannotSpend() = runTest {
        val f = Fixture(this)
        f.prepareIncoming(100)
        f.enroll()
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("predates swap enrollment"))
        f.verifyNoSpending()
    }

    @Test fun timeoutDuringSubmissionCannotRetry() = runTest {
        val f = Fixture(this)
        f.enroll()
        coEvery { f.proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) } coAnswers {
            awaitCancellation()
        }
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("uncertain"))
        f.engine.onStatus(f.status)
        f.newEngine(this).onStatus(f.status)
        advanceUntilIdle()
        coVerify(exactly = 1) { f.proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) }
    }

    @Test fun feeEqualToDonationNeverObtainsKeyOrSubmits() = runTest {
        val f = Fixture(this, fee = 100_000)
        f.enroll()
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("network fee"))
        coVerify(exactly = 1) { f.proposals.createProposal(any(), any()) }
        f.verifyNoSigning()
    }

    @Test fun feeGreaterThanDonationNeverObtainsKeyOrSubmits() = runTest {
        val f = Fixture(this, fee = 100_001)
        f.enroll()
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("network fee"))
        f.verifyNoSigning()
    }

    @Test fun unknownExecutedAmountCannotFallBackToQuote() = runTest {
        val f = Fixture(this)
        f.enroll()
        f.status.executedAmountIn = null
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("settled input"))
        f.verifyNoSpending()
    }

    @Test fun negativeRefundCannotIncreaseDonationBasis() = runTest {
        val f = Fixture(this)
        f.enroll()
        f.status.refundedFormatted = BigDecimal("-1")
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("refund"))
        f.verifyNoSpending()
    }

    @Test fun missingDepositEvidenceCannotSpend() = runTest {
        val f = Fixture(this)
        f.authorize()
        f.engine.enroll(f.quote)
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("missing transaction evidence"))
        f.verifyNoSpending()
    }

    @Test fun duplicateDepositProofCannotCountTheSameSpendTwice() = runTest {
        val f = Fixture(this)
        f.authorize()
        f.engine.enroll(f.quote, listOf(f.depositId, f.depositId))
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("missing transaction evidence"))
        f.verifyNoSpending()
    }

    @Test fun providerAmountMustEqualConfirmedNativeDeposit() = runTest {
        val f = Fixture(this)
        f.enroll()
        f.status.depositedAmountFormatted = BigDecimal("0.2")
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("amount differs"))
        f.verifyNoSpending()
    }

    @Test fun malformedProposalCannotReachSigner() = runTest {
        val f = Fixture(this, malformedProposal = true)
        f.enroll()
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("uncertain"))
        coVerify(exactly = 1) { f.proposals.createProposal(any(), any()) }
        f.verifyNoSigning()
    }

    @Test fun revocationQueuedBeforeCommitGateWins() = runTest {
        val f = Fixture(this)
        f.enroll()
        AutoDonationSpendingGate.mutex.lock()
        try {
            launch { AutoDonationSpendingGate.mutex.withLock { f.store.disable() } }
            runCurrent()
            f.engine.onStatus(f.status)
            runCurrent()
            f.verifyNoSpending()
        } finally {
            AutoDonationSpendingGate.mutex.unlock()
        }
        advanceUntilIdle()
        assertNull(f.store.consent())
        f.verifyNoSpending()
    }

    @Test fun manualProposalPublishedAtCommitGatePreventsAutomaticSpend() = runTest {
        val f = Fixture(this)
        f.enroll()
        AutoDonationSpendingGate.mutex.lock()
        try {
            f.engine.onStatus(f.status)
            runCurrent()
            f.manualProposal.value = mockk()
        } finally {
            AutoDonationSpendingGate.mutex.unlock()
        }
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("another transaction"))
        f.verifyNoSpending()
    }

    private inner class Fixture(scope: CoroutineScope, fee: Long = 10_000, malformedProposal: Boolean = false) {
        val store = AutoDonationStore(context)
        val accountUuid = AccountUuid.new(ByteArray(16) { it.toByte() })
        val accountId = accountUuid.toString()
        private val sdkAccount = Account.new(accountUuid)
        private val account = mockk<ZodlAccount> {
            every { sdkAccount } returns this@Fixture.sdkAccount
            every { spendableShieldedBalance } returns Zatoshi(100_000_000)
        }
        private val accounts = mockk<AccountDataSource> {
            coEvery { getSelectedAccount() } returns account
            coEvery { getZashiAccount() } returns account
        }
        val keys = mockk<ZashiSpendingKeyDataSource> {
            coEvery { getZashiSpendingKey() } returns mockk()
        }
        val manualProposal = MutableStateFlow<TransactionProposal?>(null)
        private val uiProposals = mockk<ZashiProposalRepository> {
            every { transactionProposal } returns manualProposal
            every { submitState } returns MutableStateFlow<SubmitProposalState?>(null)
        }
        private val nativeTxId = TransactionId.new(ByteArray(32) { (it + 1).toByte() })
        val depositId = nativeTxId.txIdString()
        private val transaction = mockk<TransactionOverview> {
            every { txId } returns nativeTxId
            every { transactionState } returns TransactionState.Confirmed
            every { isSentTransaction } returns true
            // Native wallet debit includes the network fee; swap deposit excludes it.
            every { netValue } returns Zatoshi(10_010_000)
            every { feePaid } returns Zatoshi(10_000)
        }
        private val synchronizer = mockk<Synchronizer> {
            coEvery { getTransactions(accountUuid) } returns flowOf(listOf(transaction))
        }
        private val synchronizers = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } returns this@Fixture.synchronizer
        }
        private val zec = mockk<SwapAsset> {
            every { tokenTicker } returns "zec"
            every { chainTicker } returns "zec"
        }
        private val other = mockk<SwapAsset> {
            every { tokenTicker } returns "btc"
            every { chainTicker } returns "btc"
        }
        val quote = TestQuote(zec, other)
        val status = TestStatus(quote)
        private val proposal = proposal(fee, malformedProposal)
        val proposals = mockk<ProposalDataSource> {
            coEvery { createProposal(any(), any()) } answers {
                val send = secondArg<cash.z.ecc.android.sdk.model.ZecSend>()
                RegularTransactionProposal(send.destination, send.amount, send.memo, proposal)
            }
            coEvery { submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) } returns
                SubmitResult.Success(listOf("mock-donation-id"))
        }
        val engine = newEngine(scope)

        fun newEngine(scope: CoroutineScope) = AutoDonationCoordinator(
            store, accounts, proposals, keys, synchronizers, uiProposals, scope
        )

        fun prepareIncoming(minedHeight: Long) {
            quote.originAsset = other
            quote.destinationAsset = zec
            every { synchronizer.fullyScannedHeight } returns MutableStateFlow(BlockHeight.new(100))
            status.destinationTransactionIds = listOf(depositId)
            status.executedAmountOut = BigDecimal("0.1")
            every { transaction.isSentTransaction } returns false
            every { transaction.netValue } returns Zatoshi(10_000_000)
            every { transaction.minedHeight } returns BlockHeight.new(minedHeight)
        }

        fun authorize() { store.authorize(accountId, BigDecimal.ONE) }

        suspend fun enroll() {
            authorize()
            engine.enroll(quote, listOf(depositId))
        }

        fun verifyNoSigning() {
            coVerify(exactly = 0) { keys.getZashiSpendingKey() }
            coVerify(exactly = 0) { proposals.submitTransaction(any<Proposal>(), any<UnifiedSpendingKey>()) }
        }

        fun verifyNoSpending() {
            coVerify(exactly = 0) { proposals.createProposal(any(), any()) }
            verifyNoSigning()
        }
    }

    /** Concrete address-bearing values avoid MockK reflection through inline SwapAddress getters. */
    private class TestQuote(
        override var originAsset: SwapAsset,
        override var destinationAsset: SwapAsset,
    ) : SwapQuote {
        override val depositAddress = DynamicSwapAddress("deposit")
        override val destinationAddress = DynamicSwapAddress("destination")
        override val refundAddress = DynamicSwapAddress("refund")
        override val provider = "test-provider"
        override val mode = SwapMode.EXACT_INPUT
        override val amountIn = BigDecimal(10_000_000)
        override val amountInFormatted = BigDecimal("0.1")
        override val amountInUsd = BigDecimal.ZERO
        override val amountOut = BigDecimal(10_000_000)
        override val amountOutFormatted = BigDecimal("0.1")
        override val amountOutUsd = BigDecimal.ZERO
        override val affiliateFee = BigDecimal.ZERO
        override val affiliateFeeUsd = BigDecimal.ZERO
        override val slippage = BigDecimal.ZERO
        override var timestamp = Clock.System.now() + 5.seconds
        override val deadline get() = timestamp + 60.seconds
    }

    private class TestStatus(private val quote: TestQuote) : SwapQuoteStatus {
        override val originAsset get() = quote.originAsset
        override val destinationAsset get() = quote.destinationAsset
        override val depositAddress get() = quote.depositAddress
        override val destinationAddress get() = quote.destinationAddress
        override val timestamp get() = java.time.Instant.ofEpochSecond(quote.timestamp.epochSeconds)
        override val deadline get() = quote.deadline
        override val status = SwapStatus.SUCCESS
        override val mode get() = quote.mode
        override val isSlippageRealized = true
        override val maxSlippage = BigDecimal.ZERO
        override val amountInFormatted get() = quote.amountInFormatted
        override val amountInUsd get() = quote.amountInUsd
        override val amountOutFormatted get() = quote.amountOutFormatted
        override val amountOutUsd get() = quote.amountOutUsd
        override var depositedAmountFormatted: BigDecimal? = BigDecimal("0.1")
        override var executedAmountIn: BigDecimal? = BigDecimal("0.1")
        override var executedAmountOut: BigDecimal? = null
        override var refundedFormatted: BigDecimal? = BigDecimal.ZERO
        override var destinationTransactionIds: List<String> = emptyList()
    }

    private fun proposal(fee: Long, malformed: Boolean): Proposal {
        val step = ProposalOuterClass.ProposalStep.newBuilder()
            .setTransactionRequest("zcash:${DonationPolicy.RECIPIENT}?amount=0.001")
            .addInputs(ProposalOuterClass.ProposedInput.newBuilder().setReceivedOutput(
                ProposalOuterClass.ReceivedOutput.newBuilder().setValuePool(ValuePool.Orchard).setValue(100_000 + fee)
            ))
            .addPaymentOutputPools(ProposalOuterClass.PaymentOutputPool.newBuilder().setValuePool(ValuePool.Orchard))
            .setBalance(ProposalOuterClass.TransactionBalance.newBuilder().setFeeRequired(fee))
        if (malformed) step.clearInputs()
        val bytes = ProposalOuterClass.Proposal.newBuilder().setFeeRule(ProposalOuterClass.FeeRule.Zip317)
            .addSteps(step).build().toByteArray()
        return mockk {
            every { toByteArray() } returns bytes
            every { totalFeeRequired() } returns Zatoshi(fee)
        }
    }
}
