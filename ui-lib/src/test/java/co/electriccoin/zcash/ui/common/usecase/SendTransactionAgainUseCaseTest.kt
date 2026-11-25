package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SendTransactionAgainUseCaseTest {
    @Test
    fun anAmbiguousHistoricalPaymentCannotPrefillOrOpenSend() = runTest {
        val router = mockk<NavigationRouter>(relaxed = true)
        val prefill = mockk<PrefillSendUseCase>(relaxed = true)
        val accounts = mockk<AccountDataSource>()
        val again = SendTransactionAgainUseCase(
            prefill, router, mockk(), accounts, AppAccessSession().apply { unlock() },
        )

        again(mockk { every { canSendAgain } returns false }, "detail")

        verify(exactly = 0) { prefill.requestFromTransactionDetail(any()) }
        verify(exactly = 0) { router.custom(any()) }
    }

    @Test
    fun aCoveredDetailCannotPrefillOrOpenSendAfterAccountLookup() = runTest {
        val entry = "transaction-detail"
        val active = MutableStateFlow(ActiveNavigationEntry(entry, null))
        val router = mockk<NavigationRouter>(relaxed = true) {
            every { activeEntry } returns active
        }
        val sender = EditAccountFixtures.zodl()
        val lookupStarted = CompletableDeferred<Unit>()
        val allowLookup = CompletableDeferred<Unit>()
        val accounts = mockk<AccountDataSource> {
            every { allAccounts } returns MutableStateFlow(listOf(sender))
            coEvery { getSelectedAccount() } coAnswers {
                lookupStarted.complete(Unit)
                allowLookup.await()
                sender
            }
        }
        val prefill = mockk<PrefillSendUseCase>(relaxed = true)
        val sendAgain = SendTransactionAgainUseCase(
            prefill, router, RejectIfSelectedAccountCannotSpendUseCase(accounts, mockk()),
            accounts, AppAccessSession().apply { unlock() },
        )
        val detail = mockk<DetailedTransactionData> {
            every { canSendAgain } returns true
            every { transaction } returns mockk { every { accountUuid } returns sender.sdkAccount.accountUuid }
        }

        val pending = launch { sendAgain(detail, entry) }
        lookupStarted.await()
        active.value = ActiveNavigationEntry("scanner", null)
        allowLookup.complete(Unit)
        pending.join()

        verify(exactly = 0) { prefill.requestFromTransactionDetail(any()) }
        verify(exactly = 0) { router.forward(*anyVararg()) }
        verify(exactly = 0) { router.custom(any()) }
    }
}
