package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.common.datasource.ExactInputSwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.PaymentDraft
import co.electriccoin.zcash.ui.common.datasource.PaymentDraftOwner
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposalNotCreatedException
import co.electriccoin.zcash.ui.common.model.SwapQuote
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
class LedgerProposalPreparationCancellationTest : LedgerProposalRepositoryTestBase() {
    @Test
    fun cancelledProposalCannotReplaceOrClearTheNewProposal() = runTest(dispatcher) {
        val account = ledgerAccount()
        val quote = mockk<SwapQuote>()
        val oldSend = send("synthetic-old")
        val newSend = send("synthetic-new")
        fun proposal(send: ZecSend) =
            ExactInputSwapTransactionProposal(send.destination, send.amount, send.memo, mockk(), quote, account)

        coEvery { accountDataSource.getSelectedAccount() } returns account
        val lateResults = listOf(
            Result.success(proposal(oldSend)),
            Result.failure(InsufficientFundsException()),
            Result.failure(TransactionProposalNotCreatedException(IllegalStateException("synthetic SDK failure"))),
        )
        for (result in lateResults) {
            val gate = CompletableDeferred<Continuation<ExactInputSwapTransactionProposal>>()
            coEvery { proposalDataSource.createExactInputProposal(account, oldSend, quote) } coAnswers {
                // Match an SDK operation that completes independently of coroutine cancellation.
                suspendCoroutine { gate.complete(it) }
            }
            val request = launch { repository.createExactInputSwapProposal(oldSend, quote, account) }
            runCurrent()
            assertTrue(gate.isCompleted)
            request.cancel()
            val replacement = proposal(newSend)
            coEvery { proposalDataSource.createExactInputProposal(account, newSend, quote) } returns replacement
            try {
                repository.createExactInputSwapProposal(newSend, quote, account)
            } finally {
                gate.await().resumeWith(result)
            }
            request.join()
            assertSame(replacement, repository.transactionProposal.value)
        }
    }

    @Test
    fun cancelledPcztCannotReplacePreparationForTheSameProposal() = runTest(dispatcher) {
        val account = ledgerAccount()
        givenPczt(account)
        val current = repository.getTransactionProposal()
        val gate = CompletableDeferred<Continuation<Pczt>>()
        coEvery { proposalDataSource.createPcztFromProposal(account, current.proposal, any()) } coAnswers {
            suspendCoroutine { gate.complete(it) }
        }
        val old = launch { repository.createPCZTFromProposal() }
        runCurrent()
        assertTrue(gate.isCompleted)
        old.cancel()

        val replacement = Pczt(byteArrayOf(2))
        coEvery { proposalDataSource.createPcztFromProposal(account, current.proposal, any()) } returns replacement
        try {
            repository.createPCZTFromProposal()
        } finally {
            gate.await().resumeWith(Result.success(Pczt(byteArrayOf(1))))
        }
        old.join()
        assertSame(replacement, repository.getProposalPCZT())
        repository.clear()
    }

    @Test
    fun ownedPaymentCreatesThePcztBeforePublishingTheNewProposal() = runTest(dispatcher) {
        val account = ledgerAccount()
        val send = send("reviewed-recipient")
        val request = "zcash:synthetic-request"
        val owner = PaymentDraftOwner(account, "reviewed-payment") { true }
        val regular = RegularTransactionProposal(send.destination, send.amount, send.memo, mockk())
        val zip321 =
            Zip321TransactionProposal(send.destination, send.amount, send.memo, mockk(), binaryMemoByteCount = 4)
        coEvery { proposalDataSource.createProposal(account, send) } returns regular
        coEvery { proposalDataSource.createZip321Proposal(account, request) } returns zip321
        coEvery { proposalDataSource.addProofsToPczt(any(), any()) } returns Pczt(byteArrayOf(2))
        val cases = listOf(PaymentDraft.Send(send) to regular, PaymentDraft.Zip321(request) to zip321)
        for ((draft, proposal) in cases) {
            val previous = repository.transactionProposal.value
            val prepared = Pczt(byteArrayOf(1))
            coEvery { proposalDataSource.createPcztFromProposal(account, proposal.proposal, any()) } coAnswers {
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
    fun revokedOrReplacedDraftCannotEnterNativePcztCreation() = runTest(dispatcher) {
        val account = ledgerAccount()
        val send = send("reviewed-recipient")
        val proposal = RegularTransactionProposal(send.destination, send.amount, send.memo, mockk())
        coEvery { proposalDataSource.createProposal(account, send) } returns proposal
        for (replace in listOf(false, true)) {
            val previous = proposal.copy(proposal = mockk())
            repository.transactionProposal.value = previous
            var allowed = true
            val owner = PaymentDraftOwner(account, "reviewed-payment") { allowed }
            var nativeEntered = false
            var expected = previous
            coEvery { proposalDataSource.createPcztFromProposal(account, proposal.proposal, any()) } coAnswers {
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
