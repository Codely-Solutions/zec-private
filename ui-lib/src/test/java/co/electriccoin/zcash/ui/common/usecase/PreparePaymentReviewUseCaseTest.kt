package co.electriccoin.zcash.ui.common.usecase

import androidx.navigation.NavBackStackEntry
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.PaymentDraft
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.RegularTransactionProposal
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.screen.reviewtransaction.ReviewTransactionArgs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PreparePaymentReviewUseCaseTest {
    @Test
    fun aDelayedSendCannotReplaceThePaymentAdmittedFromAnotherEntry() = runTest {
        val h = Harness()
        val oldSend = send("old-recipient")
        val activeSend = send("active-recipient")
        val oldResult = proposal(oldSend)
        val activeResult = proposal(activeSend)
        val prepared = CompletableDeferred<Unit>()
        coEvery { h.datasource.createProposal(h.wallet, oldSend) } coAnswers {
            withContext(NonCancellable) { prepared.await() }
            oldResult
        }
        coEvery { h.datasource.createProposal(h.wallet, activeSend) } returns activeResult

        val old = launch { h.open(oldSend, "send") }
        runCurrent()
        h.entry.value = ActiveNavigationEntry("external", null)
        assertTrue(h.open(activeSend, "external"))
        val review = h.routes.single()
        assertSame(activeResult, h.store.observe(review.reviewId).first())

        prepared.complete(Unit)
        old.join()
        assertSame(activeResult, h.repository.transactionProposal.value)
        assertEquals(listOf(review), h.routes)
    }

    @Test
    fun theSameEntryCannotReviewWorkPreparedForThePreviousAccount() = runTest {
        val h = Harness()
        val oldSend = send("old-recipient")
        val started = CompletableDeferred<Unit>()
        val prepared = CompletableDeferred<Unit>()
        coEvery { h.datasource.createProposal(h.wallet, oldSend) } coAnswers {
            started.complete(Unit)
            prepared.await()
            proposal(oldSend)
        }
        val old = launch { h.open(oldSend, "send") }
        started.await()
        h.accounts.value = listOf(EditAccountFixtures.keystone(isSelected = true))
        prepared.complete(Unit)
        old.join()

        assertNull(h.repository.transactionProposal.value)
        assertTrue(h.routes.isEmpty())
        assertNull(h.review.capture("covered"))
    }

    private fun send(address: String) =
        ZecSend(WalletAddress.Unified.new(address), Zatoshi(5_000), Memo(""), null)

    private fun proposal(send: ZecSend) =
        RegularTransactionProposal(send.destination, send.amount, send.memo, mockk())

    private class Harness {
        val wallet = EditAccountFixtures.zodl()
        val accounts = MutableStateFlow<List<WalletAccount>?>(listOf(wallet))
        private val datasourceAccounts = mockk<AccountDataSource> { every { allAccounts } returns accounts }
        val entry = MutableStateFlow<ActiveNavigationEntry?>(ActiveNavigationEntry("send", null))
        val routes = mutableListOf<ReviewTransactionArgs>()
        val datasource = mockk<ProposalDataSource>()
        val repository = ZashiProposalRepositoryImpl(datasourceAccounts, datasource, mockk(), mockk())
        private val walletWriteAccess = WalletWriteAccess()
        val store = ReviewedPaymentStore(datasourceAccounts, walletWriteAccess)
        private val router = mockk<NavigationRouter> {
            every { activeEntry } returns entry
            every { custom(any()) } answers {
                val activeId = entry.value?.id
                val active = mockk<NavBackStackEntry> { every { id } returns checkNotNull(activeId) }
                val command = firstArg<(NavBackStackEntry?) -> NavigationCommand?>()(active)
                if (command is NavigationCommand.ForEntry) {
                    val route = if (command.isStillAllowed()) command.buildCommand() else null
                    val review = (route as? NavigationCommand.Forward)?.routes?.single() as? ReviewTransactionArgs
                    if (review != null) routes += review
                    command.acknowledge(review != null)
                }
            }
        }
        val review = paymentReviewFixture(
            datasourceAccounts, router, repository, wallet = walletWriteAccess, reviews = store,
        )

        suspend fun open(send: ZecSend, entryId: String) =
            review.open(assertNotNull(review.capture(entryId)), PaymentDraft.Send(send)) { route, _ ->
                NavigationCommand.Forward(listOf(route))
            }
    }
}
