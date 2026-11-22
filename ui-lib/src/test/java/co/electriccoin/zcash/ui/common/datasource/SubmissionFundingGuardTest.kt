package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.util.CloseableScopeHolderImpl
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SubmissionFundingGuardTest {
    @Test
    fun aQueuedBroadcastChecksFundingAtSdkEntryAndCachesRefusalForEveryEndpoint() = runTest {
        var allowed = true
        var checks = 0
        var submissions = 0
        val refusal = IllegalStateException("Synthetic expired review")
        val submitter = MultiEndpointTransactionSubmitter(
            closeableScopeHolder = CloseableScopeHolderImpl(backgroundScope),
            logger = noOpLogger,
            beforeFirstSubmission = {
                checks++
                if (!allowed) throw refusal
            },
            submit = { transaction, _ ->
                submissions++
                TransactionSubmitResult.Success(transaction.txId)
            }
        )
        val pending = async {
            assertFailsWith<IllegalStateException> {
                submitter.submitTransactions(listOf(transaction(1)), listOf(endpoint("one"), endpoint("two")), LOG_TAG)
            }
        }
        assertFalse(pending.isCompleted)
        allowed = false
        assertEquals(refusal.message, pending.await().message)
        assertEquals(1, checks)
        assertEquals(0, submissions)
    }

    @Test
    fun fundingIsCheckedOnceAcrossEveryEndpointAndSubsequentTransaction() = runTest {
        var allowed = true
        var checks = 0
        var submissions = 0
        val submitter = MultiEndpointTransactionSubmitter(
            closeableScopeHolder = CloseableScopeHolderImpl(backgroundScope),
            logger = noOpLogger,
            beforeFirstSubmission = { checks++; check(allowed) },
            submit = { transaction, _ ->
                submissions++
                allowed = false
                TransactionSubmitResult.Success(transaction.txId)
            }
        )
        val results = submitter.submitTransactions(
            listOf(transaction(1), transaction(2)), listOf(endpoint("one"), endpoint("two")), LOG_TAG
        )
        assertEquals(2, results.size)
        assertEquals(1, checks)
        assertEquals(4, submissions)
    }

    private fun transaction(index: Int) = CreatedTransaction(
        FirstClassByteArray(byteArrayOf(index.toByte())), FirstClassByteArray(byteArrayOf(1, 2)), null
    )

    private fun endpoint(host: String) = LightWalletEndpoint(host, 443, true)

    private companion object {
        const val LOG_TAG = "[FundingGuardTest]"
        val noOpLogger = object : MultiEndpointTransactionSubmitterLogger {
            override fun info(message: () -> String) = Unit
            override fun warn(message: () -> String) = Unit
            override fun error(message: () -> String) = Unit
        }
    }
}
