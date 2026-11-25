package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import co.electriccoin.zcash.ui.util.CloseableScopeHolderImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class BroadcastTransportFenceTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun remainingEndpointCallbacksPreventAutomaticSwitchAfterSuccessUntilTheyDrain() = runTest {
        val transport = WalletTransportAccess()
        val fast = endpoint("fast.example.com")
        val slow = endpoint("slow.example.com")
        val transaction = CreatedTransaction(
            txId = FirstClassByteArray(byteArrayOf(72)),
            raw = FirstClassByteArray(byteArrayOf(72, 72)),
            expiryHeight = null,
        )
        val workerScope = CoroutineScope(
            backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])
        )
        val graceMillis = 100L
        val result = transport.whileOperating { lease ->
            val submitter = MultiEndpointTransactionSubmitter(
                closeableScopeHolder = CloseableScopeHolderImpl(workerScope),
                gracePeriod = graceMillis.milliseconds,
                logger = quiet,
                onDrained = lease.retainUntilDrain(),
                submit = { _, target ->
                    if (target == slow) awaitCancellation()
                    TransactionSubmitResult.Success(transaction.txId)
                }
            )
            submitter.submitTransactions(listOf(transaction), listOf(fast, slow), "[TransportFenceTest]")
        }
        assertIs<TransactionSubmitResult.Success>(result.single())

        var switched = false
        transport.ifIdleSwitch { switched = true }
        assertFalse(switched)
        advanceTimeBy(graceMillis)
        runCurrent()

        transport.ifIdleSwitch { switched = true }
        assertTrue(switched)
    }

    private fun endpoint(host: String) = LightWalletEndpoint(host = host, port = 443, isSecure = true)

    private val quiet = object : MultiEndpointTransactionSubmitterLogger {
        override fun info(message: () -> String) = Unit

        override fun warn(message: () -> String) = Unit

        override fun error(message: () -> String) = Unit
    }
}
