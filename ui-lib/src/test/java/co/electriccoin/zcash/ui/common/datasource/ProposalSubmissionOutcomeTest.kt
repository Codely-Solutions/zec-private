package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProposalSubmissionOutcomeTest {
    private val endpoint = LightWalletEndpoint("synthetic.invalid", 443, true)
    private val transaction = CreatedTransaction(
        FirstClassByteArray(ByteArray(32) { it.toByte() }),
        FirstClassByteArray(byteArrayOf(1, 2)),
        null,
    )
    private val accepted = TransactionSubmitResult.Success(transaction.txId)
    private val synchronizer = mockk<SdkSynchronizer>(relaxed = true)
    private val broadcaster = synchronizer.broadcaster
    private val source = ProposalDataSourceImpl(
        synchronizerProvider = mockk {
            coEvery { getSynchronizer() } returns this@ProposalSubmissionOutcomeTest.synchronizer
        },
        lightWalletEndpointProvider = mockk { every { getEndpoints() } returns listOf(endpoint) },
        walletServerSelectionProvider = mockk {
            coEvery { getServerSelection() } returns WalletServerSelection(endpoint, isAutomatic = true)
        },
    )

    @Test
    fun refusalBeforeCreationPropagatesWithoutSdkEntry() = runBlocking {
        assertFailsWith<IllegalStateException> {
            source.submitTransaction(proposal = mockk(), usk = mockk(), beforeCreation = { error("Stale review") })
        }
        coVerify(exactly = 0) { broadcaster.createProposedTransactions(any(), any()) }
    }

    @Test
    fun failedCreationWithUnknownPersistenceReturnsPending() = runBlocking {
        coEvery { broadcaster.createProposedTransactions(any(), any()) } throws IllegalStateException("SDK fixture")

        val result = source.submitTransaction(proposal = mockk(), usk = mockk())

        assertEquals(pending(), result)
        coVerify(exactly = 0) { broadcaster.submit(any(), any()) }
    }

    @Test
    fun refusalAtAppEndpointPreservesTheCreatedId() = runBlocking {
        coEvery { broadcaster.createProposedTransactions(any(), any()) } returns listOf(transaction)

        val result = source.submitTransaction(
            proposal = mockk(), usk = mockk(), beforeFirstSubmission = { error("Stale review") },
        )

        assertEquals(pending(accepted.txIdString()), result)
        coVerify(exactly = 0) { broadcaster.submit(any(), any()) }
    }

    @Test
    fun walletRefreshFailurePreservesAcknowledgedSubmission() = runBlocking {
        coEvery { broadcaster.createProposedTransactions(any(), any()) } returns listOf(transaction)
        coEvery { broadcaster.submit(transaction, endpoint) } returns accepted
        coEvery { synchronizer.refreshTransactions() } throws IllegalStateException("Refresh fixture")

        val result = source.submitTransaction(proposal = mockk(), usk = mockk())

        assertEquals(SubmitResult.Success(listOf(accepted.txIdString())), result)
        coVerify(exactly = 1) { broadcaster.submit(transaction, endpoint) }
        coVerify(exactly = 1) { synchronizer.refreshTransactions() }
    }

    private fun pending(vararg ids: String) =
        SubmitResult.GrpcFailure(ids.toList(), reason = SubmitResult.GrpcFailure.Reason.SDK_STATE_UNCERTAIN)
}
