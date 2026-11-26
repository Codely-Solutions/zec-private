package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.PaymentDraft
import co.electriccoin.zcash.ui.common.datasource.PaymentDraftOwner
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProposalPreparationCancellationTest {
    private val account = EditAccountFixtures.keystone()
    private val accounts = mockk<AccountDataSource> {
        coEvery { getSelectedAccount() } returns account
    }
    private val dataSource = mockk<ProposalDataSource>()
    private val quote = mockk<SwapQuote>()

    @Test
    fun cancelledSoftwareProposalCannotReplaceOrClearTheNewProposal() = runTest {
        val repository = ZashiProposalRepositoryImpl(accounts, dataSource, mockk(), mockk())
        assertLateProposalCannotMutateReplacement(repository.transactionProposal) {
            repository.createExactInputSwapProposal(it, quote, account)
        }
    }

    @Test
    fun cancelledKeystoneProposalCannotReplaceOrClearTheNewProposal() = runTest {
        val repository = keystone()
        assertLateProposalCannotMutateReplacement(repository.transactionProposal) {
            repository.createExactInputSwapProposal(it, quote, account)
        }
    }

    @Test
    fun cancelledKeystonePcztCannotReplacePreparationForTheSameProposal() = runTest {
        val repository = keystone()
        val newSend = send("synthetic-new")
        val currentProposal = proposal(newSend)
        coEvery { dataSource.createExactInputProposal(account, newSend, quote) } returns currentProposal
        repository.createExactInputSwapProposal(newSend, quote, account)
        val gate = CompletableDeferred<Continuation<Pczt>>()
        coEvery { dataSource.createPcztFromProposal(account, currentProposal.proposal, any()) } coAnswers {
            suspendCoroutine { gate.complete(it) }
        }
        val old = launch { repository.createPCZTFromProposal() }
        runCurrent()
        assertTrue(gate.isCompleted)
        old.cancel()

        val replacement = Pczt(byteArrayOf(2))
        coEvery { dataSource.createPcztFromProposal(account, currentProposal.proposal, any()) } returns replacement
        coEvery { dataSource.addProofsToPczt(any(), any()) } returns Pczt(byteArrayOf(3))
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
        val oldSend = send("synthetic-old")
        val newSend = send("synthetic-new")
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

    @Test
    fun ownedPaymentCreatesThePcztBeforePublishingTheNewProposal() = runTest {
        val repository = keystone()
        val send = send("reviewed-recipient")
        val request = "zcash:synthetic-request"
        val owner = PaymentDraftOwner(account, "reviewed-payment") { true }
        val regular = RegularTransactionProposal(send.destination, send.amount, send.memo, mockk())
        val zip321 =
            Zip321TransactionProposal(send.destination, send.amount, send.memo, mockk(), binaryMemoByteCount = 4)
        coEvery { dataSource.createProposal(account, send) } returns regular
        coEvery { dataSource.createZip321Proposal(account, request) } returns zip321
        coEvery { dataSource.addProofsToPczt(any(), any()) } returns Pczt(byteArrayOf(2))
        val cases = listOf(PaymentDraft.Send(send) to regular, PaymentDraft.Zip321(request) to zip321)
        for ((draft, proposal) in cases) {
            val previous = repository.transactionProposal.value
            val prepared = Pczt(byteArrayOf(1))
            coEvery { dataSource.createPcztFromProposal(account, proposal.proposal, any()) } coAnswers {
                assertSame(previous, repository.transactionProposal.value)
                arg<() -> Unit>(2).invoke()
                prepared
            }

            assertSame(proposal, repository.preparePayment(draft, owner))
            assertSame(proposal, repository.transactionProposal.value)
            repository.approveForSigning(proposal)
            assertSame(prepared, repository.getProposalPCZT())
        }
        repository.clear()
    }

    @Test
    fun revokedOrReplacedDraftCannotEnterNativePcztCreation() = runTest {
        val repository = keystone()
        val send = send("reviewed-recipient")
        val proposal = RegularTransactionProposal(send.destination, send.amount, send.memo, mockk())
        coEvery { dataSource.createProposal(account, send) } returns proposal
        for (replace in listOf(false, true)) {
            val previous = proposal.copy(proposal = mockk())
            repository.transactionProposal.value = previous
            var allowed = true
            val owner = PaymentDraftOwner(account, "reviewed-payment") { allowed }
            var nativeEntered = false
            var expected = previous
            coEvery { dataSource.createPcztFromProposal(account, proposal.proposal, any()) } coAnswers {
                if (replace) {
                    expected = proposal.copy(proposal = mockk())
                    repository.transactionProposal.value = expected
                } else {
                    allowed = false
                }
                arg<() -> Unit>(2).invoke()
                nativeEntered = true
                Pczt(byteArrayOf(1))
            }

            assertFailsWith<CancellationException> { repository.preparePayment(PaymentDraft.Send(send), owner) }
            assertFalse(nativeEntered)
            assertSame(expected, repository.transactionProposal.value)
        }
        repository.clear()
    }

    private suspend fun send(recipient: String) =
        ZecSend(WalletAddress.Unified.new(recipient), Zatoshi(100), Memo(""), null)
}
