package co.electriccoin.zcash.ui.screen.error

import android.content.Intent
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.GetSupportUseCase
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletReportAccess
import co.electriccoin.zcash.ui.screen.support.model.SupportInfo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorReportHandoffTest {
    @Test
    fun generalAndSyncReportsUseTheOriginalGrantAndOwnerAtTheChooser() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (sync in listOf(false, true)) {
                for (change in Change.entries) assertReport(sync, change)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private suspend fun TestScope.assertReport(sync: Boolean, change: Change) {
        val application = RuntimeEnvironment.getApplication()
        val session = AppAccessSession().apply { unlock() }
        val writeAccess = WalletWriteAccess()
        val selected = MutableStateFlow<WalletAccount?>(EditAccountFixtures.zodl())
        val accounts = mockk<AccountDataSource> { every { selectedAccount } returns selected }
        val permission = WalletReportAccess(session, writeAccess, ObserveSelectedWalletAccountUseCase(accounts))
        val release = CompletableDeferred<Unit>()
        val diagnostics = mockk<GetSupportUseCase> {
            coEvery { this@mockk() } coAnswers {
                release.await()
                mockk<SupportInfo> { every { toSupportString(any()) } returns "Synthetic diagnostics" }
            }
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        val owner = NavigateToErrorUseCase(router)
        val send = SendEmailUseCase(application, diagnostics, mockk())
        val failure = IllegalStateException("synthetic failure")
        val general = ErrorVM(ErrorArgs.General(failure), owner, router, send, mockk(), permission)
        val syncVm = SyncErrorVM(
            ErrorArgs.SyncError(SynchronizerError.Critical(failure)), router, send, mockk(),
            mockk<IsTorEnabledUseCase> { every { observe() } returns flowOf(false) }, owner, permission,
        )
        try {
            runCurrent()
            if (sync) syncVm.state.value.support.onClick() else requireNotNull(general.state.value.negative).onClick()
            runCurrent()
            when (change) {
                Change.LOCK -> {
                    session.lock()
                    session.unlock()
                }
                Change.ACCOUNT -> selected.value = EditAccountFixtures.keystone()
                Change.RESET -> writeAccess.beginReset()
                Change.NONE -> Unit
            }
            runCurrent()
            release.complete(Unit)
            runCurrent()
            val allowed = change == Change.NONE
            val chooserAction = shadowOf(application).nextStartedActivity?.action
            assertEquals(if (allowed) Intent.ACTION_CHOOSER else null, chooserAction)
            verify(exactly = if (allowed) 1 else 0) { router.back() }
        } finally {
            general.viewModelScope.cancel()
            syncVm.viewModelScope.cancel()
        }
    }

    private enum class Change { LOCK, ACCOUNT, RESET, NONE }
}
