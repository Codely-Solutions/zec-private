package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Account
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.PendingViewOnlyImport
import co.electriccoin.zcash.ui.common.repository.ViewOnlyPendingImportRepositoryImpl
import co.electriccoin.zcash.ui.screen.connecthw.CommittedEnrollmentBirthday
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletEnrollment
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import co.electriccoin.zcash.viewingkey.ViewOnlyAccountImporterImpl
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ViewOnlyEnrollmentOwnershipTest {
    @Test
    fun replacingTheWalletWithdrawsTheImportedReceiptAndRecoversAStaleFinishTap() = runTest {
        val wallet = WalletWriteAccess()
        val enrollmentAccess = ViewOnlyAccountFixtures.enrollmentAccess(wallet)
        val repository = ViewOnlyPendingImportRepositoryImpl(enrollmentAccess)
        val account = mockk<Account>()
        repository.set(
            PendingViewOnlyImport(
                ufvk = "synthetic key",
                name = "Test",
                importedAccount = account,
                enrollment = enrollmentAccess.capture(),
            )
        )
        val navigationRouter = mockk<NavigationRouter>(relaxed = true)
        val importer = mockk<ImportViewOnlyAccountUseCase>()
        val shared = CreateHWWalletAccountUseCase(
            KeystoneEnrollmentSession(enrollmentAccess),
            mockk(),
            mockk(),
            ViewOnlyAccountImporterImpl(repository, navigationRouter, importer),
            navigationRouter,
        )
        val flow = HWWalletEnrollment.ViewOnly
        val shown = mutableListOf<CommittedEnrollmentBirthday?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            shared.observeCommittedBirthday(flow).collect { shown.add(it) }
        }
        runCurrent()
        val original = CommittedEnrollmentBirthday(null, account)
        assertEquals(original, shown.last())

        wallet.beginReset()
        wallet.finishReset()
        wallet.replaceWallet {}
        runCurrent()

        assertNull(repository.get())
        assertNull(shown.last())
        shared.finish(flow, original)
        verify(exactly = 1) { navigationRouter.backToRoot() }
        coVerify(exactly = 0) { importer.invoke(any()) }
    }
}
