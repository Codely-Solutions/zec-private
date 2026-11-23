package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.WalletAccount
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProposalPreparationCancellationTest {
    private val account = mockk<WalletAccount>()
    private val accounts = mockk<AccountDataSource> {
        coEvery { getSelectedAccount() } returns account
    }
    private val dataSource = mockk<ProposalDataSource>()
    private val quote = mockk<SwapQuote>()
    private val oldSend = send("synthetic-old")
    private val newSend = send("synthetic-new")

    @Test
    fun cancelledSoftwareProposalCannotReplaceOrClearTheNewProposal() = runTest {
        val repository = ZashiProposalRepositoryImpl(accounts, dataSource, mockk(), mockk())
        assertLateProposalCannotMutateReplacement(repository.transactionProposal) {
            repository.createExactInputSwapProposal(it, quote)
        }
    }

    @Test
    fun cancelledKeystoneProposalCannotReplaceOrClearTheNewProposal() = runTest {
        val repository = keystone()
        assertLateProposalCannotMutateReplacement(repository.transactionProposal) {
            repository.createExactInputSwapProposal(it, quote)
        }
    }

    @Test
    fun cancelledKeystonePcztCannotReplacePreparationForTheSameProposal() = runTest {
        val repository = keystone()
        val currentProposal = proposal(newSend)
        coEvery { dataSource.createExactInputProposal(account, newSend, quote) } returns currentProposal
        repository.createExactInputSwapProposal(newSend, quote)
        val gate = CompletableDeferred<Continuation<Pczt>>()
        coEvery { dataSource.createPcztFromProposal(account, currentProposal.proposal) } coAnswers {
            suspendCoroutine { gate.complete(it) }
        }
        val old = launch { repository.createPCZTFromProposal() }
        runCurrent()
        assertTrue(gate.isCompleted)
        old.cancel()

        val replacement = Pczt(byteArrayOf(2))
        coEvery { dataSource.createPcztFromProposal(account, currentProposal.proposal) } returns replacement
        coEvery { dataSource.addProofsToPczt(any()) } returns Pczt(byteArrayOf(3))
        try {
            repository.createPCZTFromProposal()
        } finally {
            gate.await().resumeWith(Result.success(Pczt(byteArrayOf(1))))
        }
        old.join()
        assertSame(replacement, repository.getProposalPCZT())
        repository.clear()
    }

    private suspend fun TestScope.assertLateProposalCannotMutateReplacement(
        state: StateFlow<TransactionProposal?>,
        prepare: suspend (ZecSend) -> Unit,
    ) {
        val lateResults = listOf(
            Result.success(proposal(oldSend)),
            Result.failure(InsufficientFundsException()),
            Result.failure(TransactionProposalNotCreatedException(IllegalStateException("synthetic SDK failure"))),
        )
        for (result in lateResults) {
            val gate = CompletableDeferred<Continuation<ExactInputSwapTransactionProposal>>()
            coEvery { dataSource.createExactInputProposal(account, oldSend, quote) } coAnswers {
                // Match an SDK operation that completes independently of coroutine cancellation.
                suspendCoroutine { gate.complete(it) }
            }
            val request = launch { prepare(oldSend) }
            runCurrent()
            assertTrue(gate.isCompleted)
            request.cancel()
            val replacement = proposal(newSend)
            coEvery { dataSource.createExactInputProposal(account, newSend, quote) } returns replacement
            try {
                prepare(newSend)
            } finally {
                gate.await().resumeWith(result)
            }
            request.join()
            assertSame(replacement, state.value)
        }
    }

    private fun keystone() = KeystoneProposalRepositoryImpl(accounts, dataSource, mockk(), mockk())

    private fun proposal(send: ZecSend) =
        ExactInputSwapTransactionProposal(send.destination, send.amount, send.memo, mockk(), quote, account)

    private fun send(recipient: String) = ZecSend(WalletAddress.Unified.new(recipient), Zatoshi(100), Memo(""), null)
}
