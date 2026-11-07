package co.electriccoin.zcash.ui.screen.donation

import co.electriccoin.zcash.ui.common.model.zcash.DonationPolicy
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass
import cash.z.wallet.sdk.internal.ffi.ProposalOuterClass.ValuePool
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DonationPolicyTest {
    @Test
    fun exactPercentageUsesZatoshiAndRoundsDown() {
        assertEquals(1_234_567L, DonationPolicy.suggestedZatoshi(BigDecimal("1.23456789"), BigDecimal("1")))
        assertEquals(0L, DonationPolicy.suggestedZatoshi(BigDecimal("0.00000001"), BigDecimal("99")))
        assertEquals(100_000_000L, DonationPolicy.suggestedZatoshi(BigDecimal.ONE, BigDecimal("100")))
        assertEquals(0L, DonationPolicy.suggestedZatoshi(BigDecimal.ONE, BigDecimal.ZERO))
    }

    @Test
    fun invalidPercentAndBasisAreRejected() {
        for (percent in listOf("-0.01", "100.01")) {
            assertFailsWith<IllegalArgumentException> {
                DonationPolicy.suggestedZatoshi(BigDecimal.ONE, BigDecimal(percent))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DonationPolicy.suggestedZatoshi(BigDecimal("-1"), BigDecimal.ONE)
        }
    }

    @Test
    fun shieldedInputOutputAndChangeAreAccepted() {
        for (pool in listOf(ValuePool.Sapling, ValuePool.Orchard, ValuePool.Ironwood)) {
            DonationPolicy.requireShielded(proposal(step(pool)), send())
        }
    }

    @Test
    fun transparentAndUnknownInputsAreRejected() {
        for (pool in listOf(ValuePool.Transparent, ValuePool.PoolNotSpecified)) {
            reject(step().clearInputs().addInputs(input(pool)))
        }
        reject(step().clearInputs().addInputs(
            ProposalOuterClass.ProposedInput.newBuilder().setReceivedOutput(
                ProposalOuterClass.ReceivedOutput.newBuilder().setValuePoolValue(999).setValue(150_000)
            )
        ))
    }

    @Test
    fun priorStepInputsAndMissingInputsAreRejected() {
        reject(step().clearInputs())
        reject(step().clearInputs().addInputs(
            ProposalOuterClass.ProposedInput.newBuilder().setPriorStepOutput(
                ProposalOuterClass.PriorStepOutput.newBuilder().setStepIndex(0).setPaymentIndex(0)
            )
        ))
    }

    @Test
    fun transparentOrUnknownPaymentPoolIsRejected() {
        for (pool in listOf(ValuePool.Transparent, ValuePool.PoolNotSpecified)) {
            reject(step().clearPaymentOutputPools().addPaymentOutputPools(paymentPool(pool)))
        }
        reject(step().clearPaymentOutputPools().addPaymentOutputPools(
            ProposalOuterClass.PaymentOutputPool.newBuilder().setValuePoolValue(999)
        ))
        reject(step().clearPaymentOutputPools())
        reject(step().addPaymentOutputPools(paymentPool(ValuePool.Orchard)))
    }

    @Test
    fun transparentUnknownAndEphemeralChangeAreRejected() {
        for (pool in listOf(ValuePool.Transparent, ValuePool.PoolNotSpecified)) {
            reject(step().setBalance(balance(pool)))
        }
        reject(step().setBalance(balance().clearProposedChange().addProposedChange(
            ProposalOuterClass.ChangeValue.newBuilder().setValuePoolValue(999).setValue(40_000)
        )))
        reject(step().setBalance(balance().clearProposedChange().addProposedChange(
            ProposalOuterClass.ChangeValue.newBuilder().setValuePool(ValuePool.Orchard)
                .setValue(40_000).setIsEphemeral(true)
        )))
    }

    @Test
    fun shieldingMultipleStepsAndMissingStepsAreRejected() {
        reject(step().setIsShielding(true))
        assertFailsWith<IllegalArgumentException> { DonationPolicy.requireShielded(proposal(step(), step()), send()) }
        assertFailsWith<IllegalArgumentException> { DonationPolicy.requireShielded(proposal(), send()) }
    }

    @Test
    fun amountMismatchAndIdentifyingMemoAreRejected() {
        reject(step().setBalance(balance().setFeeRequired(10_001)))
        assertFailsWith<IllegalArgumentException> {
            DonationPolicy.requireShielded(proposal(step()), send(amount = 99_999))
        }
        assertFailsWith<IllegalArgumentException> {
            DonationPolicy.requireShielded(proposal(step()), send(memo = "identity"))
        }
    }

    @Test
    fun serializedPaymentMustMatchReviewWithoutMemoOrExtraRecipients() {
        reject(step().setTransactionRequest("zcash:${DonationPolicy.RECIPIENT}?amount=0.002"))
        reject(step().setTransactionRequest("zcash:t1Rv4exT7bqhZqi2j7xz8bUHDMxwosrjADU?amount=0.001"))
        reject(step().setTransactionRequest("zcash:${DonationPolicy.RECIPIENT}?amount=0.001&memo=aWRlbnRpdHk"))
        reject(step().setTransactionRequest(
            "zcash:${DonationPolicy.RECIPIENT}?amount=0.001&address.1=${DonationPolicy.RECIPIENT}&amount.1=0.001"
        ))
        assertFailsWith<org.zecdev.zip321.ZIP321.Errors.InvalidURI> {
            DonationPolicy.requireShielded(proposal(step().setTransactionRequest("not a payment request")), send())
        }
        reject(step().clearPaymentOutputPools().addPaymentOutputPools(
            paymentPool(ValuePool.Orchard).setPaymentIndex(1)
        ))
    }

    private fun reject(step: ProposalOuterClass.ProposalStep.Builder) {
        assertFailsWith<IllegalArgumentException> { DonationPolicy.requireShielded(proposal(step), send()) }
    }

    private fun proposal(vararg steps: ProposalOuterClass.ProposalStep.Builder): Proposal {
        val bytes = ProposalOuterClass.Proposal.newBuilder()
            .setFeeRule(ProposalOuterClass.FeeRule.Zip317)
            .addAllSteps(steps.map { it.build() }).build().toByteArray()
        return mockk { every { toByteArray() } returns bytes }
    }

    private fun step(pool: ValuePool = ValuePool.Orchard) = ProposalOuterClass.ProposalStep.newBuilder()
        .setTransactionRequest("zcash:${DonationPolicy.RECIPIENT}?amount=0.001")
        .addInputs(input(pool)).addPaymentOutputPools(paymentPool(pool)).setBalance(balance(pool))

    private fun input(pool: ValuePool) = ProposalOuterClass.ProposedInput.newBuilder().setReceivedOutput(
        ProposalOuterClass.ReceivedOutput.newBuilder().setValuePool(pool).setValue(150_000)
    )

    private fun paymentPool(pool: ValuePool) = ProposalOuterClass.PaymentOutputPool.newBuilder().setValuePool(pool)

    private fun balance(pool: ValuePool = ValuePool.Orchard) = ProposalOuterClass.TransactionBalance.newBuilder()
        .setFeeRequired(10_000).addProposedChange(
            ProposalOuterClass.ChangeValue.newBuilder().setValuePool(pool).setValue(40_000)
        )

    private fun send(amount: Long = 100_000, memo: String = "") = ZecSend(
        destination = mockk<WalletAddress.Unified> { every { address } returns DonationPolicy.RECIPIENT },
        amount = Zatoshi(amount),
        memo = Memo(memo),
        proposal = null
    )
}
