package co.electriccoin.zcash.ui.screen.error

import android.content.Intent
import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.LceContent
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.SubmitProposalState
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSupportUseCase
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveTransactionSubmitStateUseCase
import co.electriccoin.zcash.ui.common.usecase.SendEmailUseCase
import co.electriccoin.zcash.ui.common.usecase.WalletReportAccess
import co.electriccoin.zcash.ui.screen.support.model.SupportInfo
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressVM
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ErrorReportHandoffTest {
    @Test
    fun walletReportsUseTheOriginalGrantAndOwnerAtTheChooser() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (route in Route.entries) {
                for (change in Change.entries) {
                    if (route == Route.MAPPER && change == Change.ACCOUNT) continue
                    assertReport(route, change)
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun walletlessRestoreOffersABlankSupportDraftWithoutCollectingDiagnostics() {
        val application = RuntimeEnvironment.getApplication()
        val diagnostics = mockk<GetSupportUseCase>()
        val access = WalletReportAccess(AppAccessSession(), WalletWriteAccess(), mockk())
        val mapper = ErrorMapperUseCase(SendEmailUseCase(application, diagnostics, mockk()), mockk(), access)
        val failure = LceContent.Error(IllegalStateException("synthetic private detail"), {}, {})
        requireNotNull(mapper.mapToState(failure, walletlessOnboarding = true).state.secondaryAction).onClick()
        val chooser = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        coVerify(exactly = 0) { diagnostics() }
    }

    private suspend fun TestScope.assertReport(route: Route, change: Change) {
        val application = RuntimeEnvironment.getApplication()
        val session = AppAccessSession().apply { unlock() }
        val writeAccess = WalletWriteAccess()
        val selected = MutableStateFlow<WalletAccount?>(null)
        val accounts = mockk<AccountDataSource> { every { selectedAccount } returns selected }
        val permission = WalletReportAccess(session, writeAccess, ObserveSelectedWalletAccountUseCase(accounts))
        val release = CompletableDeferred<Unit>()
        val collecting = CompletableDeferred<Unit>()
        val diagnostics = mockk<GetSupportUseCase> {
            coEvery { this@mockk() } coAnswers {
                collecting.complete(Unit)
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
        val partial = SubmitProposalState.Result(SubmitResult.Partial(listOf("synthetic-tx"), listOf("synthetic")))
        val progress = TransactionProgressVM(
            mockk<ObserveProposalUseCase> { every { observeNullable() } returns flowOf(mockk<TransactionProposal>()) },
            mockk<ObserveTransactionSubmitStateUseCase> { every { this@mockk() } returns flowOf(partial) },
            mockk(), mockk(), mockk(), send, mockk(), router, permission,
        )
        val progressState = backgroundScope.launch { progress.state.collect {} }
        try {
            runCurrent()
            selected.value = EditAccountFixtures.zodl()
            runCurrent()
            when (route) {
                Route.SYNC -> syncVm.state.value.support.onClick()
                Route.GENERAL -> requireNotNull(general.state.value.negative).onClick()
                Route.PARTIAL -> requireNotNull(progress.state.value?.primaryButton).onClick()
                Route.MAPPER -> {
                    val mapper = ErrorMapperUseCase(send, mockk(), permission)
                    val state = mapper.mapToState(LceContent.Error(failure, {}, {})).state
                    requireNotNull(state.secondaryAction).onClick()
                }
            }
            runCurrent()
            assertTrue(collecting.isCompleted)
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
            verify(exactly = if (allowed && route in listOf(Route.GENERAL, Route.SYNC)) 1 else 0) { router.back() }
        } finally {
            general.viewModelScope.cancel()
            syncVm.viewModelScope.cancel()
            progress.viewModelScope.cancel()
            progressState.cancel()
        }
    }

    private enum class Change { LOCK, ACCOUNT, RESET, NONE }
    private enum class Route { GENERAL, SYNC, PARTIAL, MAPPER }
}
