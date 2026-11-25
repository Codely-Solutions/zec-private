package co.electriccoin.zcash.ui.screen.transactionprogress

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ExactOutputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.withStyle
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pending-screen subtitle routing for a resubmittable GrpcFailure (MOB-1145): a timeout shows the
 * dedicated "may still have been broadcast" copy; a non-timeout failure shows its description when
 * present; otherwise nothing failure-specific is shown and the caller uses the proposal default.
 */
class TransactionProgressPendingDescriptionTest {
    @Test
    fun timeoutReasonUsesDedicatedTimeoutCopy() {
        assertEquals(
            stringRes(R.string.send_pendingTimeoutInfo).withStyle(),
            SubmitResult
                .GrpcFailure(txIds = listOf("a"), reason = SubmitResult.GrpcFailure.Reason.TIMEOUT)
                .pendingDescription()
        )
    }

    @Test
    fun uncertainSdkSubmissionUsesStatusCheckCopy() {
        assertEquals(
            stringRes(R.string.send_pendingSdkStateInfo).withStyle(),
            SubmitResult.GrpcFailure(emptyList(), reason = SubmitResult.GrpcFailure.Reason.SDK_STATE_UNCERTAIN)
                .pendingDescription()
        )
    }

    @Test
    fun nonTimeoutWithDescriptionUsesDescription() {
        assertEquals(
            stringRes("boom").withStyle(),
            SubmitResult.GrpcFailure(txIds = listOf("a"), description = "boom").pendingDescription()
        )
    }

    @Test
    fun nonTimeoutWithoutDescriptionFallsThrough() {
        assertNull(SubmitResult.GrpcFailure(txIds = listOf("a"), description = null).pendingDescription())
    }

    @Test
    fun proposalFallbackDistinguishesSwapFromPaymentAndKeepsTypedReason() {
        val swap = mockk<ExactInputSwapTransactionProposal>()
        val payment = mockk<ExactOutputSwapTransactionProposal>()
        val unacknowledged = SubmitResult.GrpcFailure(txIds = listOf("a"))
        assertEquals(stringRes(R.string.swapAndPay_pendingSwapInfo).withStyle(), unacknowledged.pendingSubtitle(swap))
        assertEquals(stringRes(R.string.swapAndPay_pendingPayInfo).withStyle(), unacknowledged.pendingSubtitle(payment))
        assertEquals(
            stringRes(R.string.send_pendingSdkStateInfo).withStyle(),
            unacknowledged.copy(reason = SubmitResult.GrpcFailure.Reason.SDK_STATE_UNCERTAIN).pendingSubtitle(payment),
        )
    }

    @Test
    fun nonTimeoutWithBlankDescriptionFallsThrough() {
        assertNull(SubmitResult.GrpcFailure(txIds = listOf("a"), description = "   ").pendingDescription())
    }
}
