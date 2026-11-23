package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionRepositoryAccountTest {
    @Test
    fun approvedAccountRemainsTheQuerySourceWhenSelectionChanges() = runTest {
        assertApprovedAccountSnapshot(listOf(transaction(1)))
    }

    @Test
    fun emptyApprovedAccountRemainsEmptyWhenAnotherAccountHasTransactions() = runTest {
        assertApprovedAccountSnapshot(emptyList())
    }

    private suspend fun TestScope.assertApprovedAccountSnapshot(approvedRows: List<TransactionOverview>) {
        val approved = EditAccountFixtures.zodl()
        val other = EditAccountFixtures.keystone()
        val approvedUuid = approved.sdkAccount.accountUuid
        val otherUuid = other.sdkAccount.accountUuid
        val selected = MutableStateFlow<WalletAccount?>(approved)
        val approvedTransactions = MutableSharedFlow<List<TransactionOverview>>(replay = 1)
        val sdk = mockk<Synchronizer> {
            coEvery { getTransactions(approvedUuid) } returns approvedTransactions
            coEvery { getTransactions(otherUuid) } returns flowOf(listOf(transaction(2)))
            every { status } returns MutableStateFlow(Synchronizer.Status.SYNCED)
            coEvery { getTransactionOutputs() } returns emptyMap()
            coEvery { getRecipients() } returns emptyMap()
        }
        val accounts = mockk<AccountDataSource> { every { selectedAccount } returns selected }
        val provider = mockk<SynchronizerProvider> {
            every { synchronizer } returns MutableStateFlow<Synchronizer?>(sdk)
        }
        val repository = TransactionRepositoryImpl(accounts, provider)
        val pendingExport = async { repository.getTransactions(approvedUuid) }
        runCurrent()
        coVerify(exactly = 1) { sdk.getTransactions(approvedUuid) }

        selected.value = other
        runCurrent()
        assertFalse(pendingExport.isCompleted)
        selected.value = approved
        runCurrent()
        approvedTransactions.emit(approvedRows)

        val snapshot = pendingExport.await()
        assertEquals(approvedRows.map { it.txId }, snapshot.map { it.id })
        assertEquals(approvedRows.map { approvedUuid }, snapshot.map { it.accountUuid })
        coVerify(exactly = 1) { sdk.getTransactions(approvedUuid) }
        coVerify(exactly = 0) { sdk.getTransactions(otherUuid) }
    }

    private fun transaction(marker: Byte) =
        TransactionOverview(
            txId = TransactionId.new(ByteArray(32) { marker }),
            minedHeight = null,
            expiryHeight = null,
            index = null,
            raw = null,
            isSentTransaction = false,
            netValue = Zatoshi(1),
            totalSpent = Zatoshi(0),
            totalReceived = Zatoshi(1),
            feePaid = null,
            isChange = false,
            receivedNoteCount = 1,
            sentNoteCount = 0,
            memoCount = 0,
            blockTimeEpochSeconds = null,
            transactionState = TransactionState.Pending,
            isShielding = false,
            spentNoteCount = 0,
            poolCrossingValue = null,
            isTrusted = false,
            zip318Kind = Zip318Kind.NOT_CLASSIFIED,
        )
}
