package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class SubmissionProgressStoreTest {
    private val accountA = account(1)
    private val accountB = account(2)
    private val accounts = MutableStateFlow<List<WalletAccount>?>(listOf(accountA))
    private val wallet = WalletWriteAccess()
    private val store = SubmissionProgressStore(
        mockk<AccountDataSource> { every { allAccounts } returns accounts },
        wallet,
    )

    @Test
    fun aLateResultCannotMixWithAnotherSubmissionOrWallet() = runTest {
        val original = mockk<TransactionProposal>()
        val first = checkNotNull(store.begin(original, accountA.sdkAccount.accountUuid))
        assertSame(original, store.observe(first.id).first().first)

        accounts.value = listOf(accountB)
        assertEquals(null to null, store.observe(first.id).first())
        assertFalse(store.canShow(first.id))

        val nextProposal = mockk<TransactionProposal>()
        val second = checkNotNull(store.begin(nextProposal))
        first.record(SubmitResult.Success(listOf("first-fixture-tx")))
        assertEquals(null to null, store.observe(first.id).first())
        assertEquals(nextProposal to SubmitProposalState.Submitting, store.observe(second.id).first())

        val secondResult = SubmitResult.Success(listOf("second-fixture-tx"))
        second.record(secondResult)
        second.recordFailure(IllegalStateException("notification failed"))
        assertEquals(nextProposal to SubmitProposalState.Result(secondResult), store.observe(second.id).first())
        wallet.beginReset()
        assertEquals(null to null, store.observe(second.id).first())
        assertFalse(store.canShow(second.id))
    }

    private fun account(marker: Byte): WalletAccount = mockk {
        every { isSelected } returns true
        every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16) { marker }))
    }
}
