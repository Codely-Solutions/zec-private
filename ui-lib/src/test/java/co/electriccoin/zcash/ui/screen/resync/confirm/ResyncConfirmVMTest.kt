package co.electriccoin.zcash.ui.screen.resync.confirm

import androidx.lifecycle.ViewModelStore
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.LceError
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.usecase.ConfirmResyncUseCase
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetResyncDataFromHeightUseCase
import co.electriccoin.zcash.ui.common.usecase.ResyncErrorMapperUseCase
import co.electriccoin.zcash.ui.screen.resync.date.ResyncDateArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ResyncConfirmVMTest {
    private val height = BlockHeight.new(500_000)
    private val date = YearMonth.of(2020, 4)
    private val provider = mockk<PersistableWalletProvider>()
    private val estimate = mockk<GetResyncDataFromHeightUseCase>()
    private val confirm = mockk<ConfirmResyncUseCase>(relaxed = true)
    private val router = mockk<NavigationRouter>(relaxed = true)

    @Test
    fun walletInitializationFailureEmitsRetryWithoutWaitingForBirthday() = runTest {
        coEvery { provider.requirePersistableWallet() } throws IOException("synthetic wallet failure")
        fixture { vm, _ ->
            assertNull(vm.state.value.content)
            assertFalse(vm.state.value.isLoading)
            val error = assertNotNull(vm.state.value.error) as LceError.BottomSheet
            ready()
            error.state.primaryAction.onClick()
            assertNotNull(vm.state.value.content)
            assertNull(vm.state.value.error)
            coVerify(exactly = 1) { estimate.invoke(height) }
        }
    }

    @Test
    fun estimateFailureRetriesTheCompleteOperationAndConfirmUsesThatHeight() = runTest {
        ready()
        coEvery { estimate.invoke(height) } throws IOException("synthetic estimate failure")
        fixture { vm, _ ->
            val error = assertNotNull(vm.state.value.error) as LceError.BottomSheet
            assertNull(vm.state.value.content)
            ready()
            error.state.primaryAction.onClick()
            val content = assertNotNull(vm.state.value.content)
            content.change.onClick()
            verify { router.forward(match<ResyncDateArgs> { it.initialBlockHeight == height.value }) }
            content.confirm.onClick()
            coVerify(exactly = 1) { confirm.invoke(height) }
            coVerify(exactly = 2) { provider.requirePersistableWallet() }
        }
    }

    @Test
    fun cancellationOfEstimationEndsWorkWithoutErrorNavigationOrConfirmation() = runTest {
        ready()
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        coEvery { estimate.invoke(height) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        fixture { vm, store ->
            assertTrue(entered.isCompleted)
            assertTrue(vm.state.value.isLoading)
            store.clear()
            runCurrent()
            assertTrue(cancelled)
            assertNull(vm.state.value.error)
            verify(exactly = 0) { router.forward(any()) }
            coVerify(exactly = 0) { confirm.invoke(any()) }
        }
    }

    @Test
    fun confirmationFailureKeepsInitializedContentAndRetriesConfirmationOnly() = runTest {
        ready()
        coEvery { confirm.invoke(height) } throws IOException("synthetic resync failure")
        fixture { vm, _ ->
            assertNotNull(vm.state.value.content).confirm.onClick()
            assertNotNull(vm.state.value.content)
            val error = assertNotNull(vm.state.value.error) as LceError.BottomSheet
            coEvery { confirm.invoke(height) } returns Unit
            error.state.primaryAction.onClick()
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.isLoading)
            coVerify(exactly = 1) { estimate.invoke(height) }
            coVerify(exactly = 2) { confirm.invoke(height) }
        }
    }

    private fun ready() {
        coEvery { provider.requirePersistableWallet() } returns mockk { every { birthday } returns height }
        coEvery { estimate.invoke(height) } returns date
    }

    private fun TestScope.fixture(block: TestScope.(ResyncConfirmVM, ViewModelStore) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val vm = ResyncConfirmVM(
                persistableWalletProvider = provider,
                navigationRouter = router,
                confirmResync = confirm,
                getResyncDataFromHeight = estimate,
                errorStateMapper = ResyncErrorMapperUseCase(ErrorMapperUseCase(mockk(relaxed = true), mockk(relaxed = true))),
            )
            store.put("resync", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            block(vm, store)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
