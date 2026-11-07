package co.electriccoin.zcash.ui.common.donation

import android.content.Context
import co.electriccoin.zcash.ui.screen.donation.DonationPreferences
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
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
import co.electriccoin.zcash.ui.common.model.NetworkDimension
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.VersionInfo
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
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
        mockkObject(VersionInfo.Companion)
        every { VersionInfo.NETWORK_DIMENSION } returns NetworkDimension.MAINNET
    }

    @After fun releaseMocks() {
        unmockkObject(VersionInfo.Companion)
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
        every { f.quote.timestamp } returns Clock.System.now() - 60.seconds
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
        every { f.status.executedAmountIn } returns null
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("missing settled input"))
        f.verifyNoSpending()
    }

    @Test fun negativeRefundCannotIncreaseDonationBasis() = runTest {
        val f = Fixture(this)
        f.enroll()
        every { f.status.refundedFormatted } returns BigDecimal("-1")
        f.engine.onStatus(f.status)
        advanceUntilIdle()
        assertTrue(assertNotNull(f.store.latestOutcome()).contains("inconsistent refund"))
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
        every { f.status.depositedAmountFormatted } returns BigDecimal("0.2")
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
            coEvery { getSynchronizer() } returns synchronizer
        }
        private val zec = mockk<SwapAsset> {
            every { tokenTicker } returns "zec"
            every { chainTicker } returns "zec"
        }
        private val other = mockk<SwapAsset> {
            every { tokenTicker } returns "btc"
            every { chainTicker } returns "btc"
        }
        val quote = mockk<SwapQuote> {
            every { timestamp } returns Clock.System.now() + 5.seconds
            every { originAsset } returns zec
            every { destinationAsset } returns other
            every { depositAddress } returns DynamicSwapAddress("deposit")
            every { destinationAddress } returns DynamicSwapAddress("destination")
        }
        val status = mockk<SwapQuoteStatus> {
            every { status } returns SwapStatus.SUCCESS
            every { depositAddress } returns DynamicSwapAddress("deposit")
            every { destinationAddress } returns DynamicSwapAddress("destination")
            every { depositedAmountFormatted } returns BigDecimal("0.1")
            every { executedAmountIn } returns BigDecimal("0.1")
            every { refundedFormatted } returns BigDecimal.ZERO
        }
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
