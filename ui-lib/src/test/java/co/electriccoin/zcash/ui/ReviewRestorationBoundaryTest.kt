package co.electriccoin.zcash.ui

import android.os.Parcel
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.EditAccountFixtures
import co.electriccoin.zcash.ui.common.usecase.ObserveProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveTransactionSubmitStateUseCase
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.ErrorBottomSheet
import co.electriccoin.zcash.ui.screen.error.ErrorDialog
import co.electriccoin.zcash.ui.screen.error.SyncErrorArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.screen.swap.orconfirmation.ORSwapConfirmationArgs
import co.electriccoin.zcash.ui.screen.swap.quote.SwapQuoteArgs
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityHistoryArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressVM
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewRestorationBoundaryTest {
    @Test
    fun restoredDialogRetainsItsRouteButLosesTheErrorOwner() {
        val router = mockk<NavigationRouter>(relaxed = true)
        val error = ErrorArgs.General(IllegalStateException("synthetic check"))
        val original = NavigateToErrorUseCase(router)
        original(error)
        val routes = mutableListOf<ErrorDialog>()
        verify(exactly = 1) { router.forward(capture(routes)) }
        val route = routes.single()
        assertSame(error, original.argsFor(route))
        original(error)
        val routedAgain = mutableListOf<ErrorDialog>()
        verify(exactly = 2) { router.forward(capture(routedAgain)) }
        val otherRoute = routedAgain.last()
        assertSame(error, original.argsFor(route))
        original.clear(route)
        assertNull(original.argsFor(route))
        assertSame(error, original.argsFor(otherRoute))

        val previous = controller().apply { installGraph(); navigate(route) }
        val restored = roundTrip(previous)
        assertEquals(route, restored.currentBackStackEntry?.toRoute<ErrorDialog>())
        assertNull(NavigateToErrorUseCase(router).argsFor(route))
        assertEquals(true, restored.popBackStack())
        assertEquals(HomeArgs, restored.currentBackStackEntry?.toRoute<HomeArgs>())
    }

    @Test
    fun restoredTransactionProgressOffersActivityWithTheFreshSoftwareRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val previous = controller().apply { installGraph(); navigate(TransactionProgressArgs) }
            val restored = roundTrip(previous)
            assertEquals(TransactionProgressArgs, restored.currentBackStackEntry?.toRoute<TransactionProgressArgs>())

            val account = EditAccountFixtures.zodl()
            val accounts = mockk<AccountDataSource> { every { selectedAccount } returns flowOf(account) }
            val repository = ZashiProposalRepositoryImpl(
                accountDataSource = accounts,
                proposalDataSource = ProposalDataSourceImpl(mockk(), mockk(), mockk()),
                zashiSpendingKeyDataSource = mockk(),
                processSwapTransaction = mockk()
            )
            val router = mockk<NavigationRouter>(relaxed = true)
            val vm = TransactionProgressVM(
                reportAccess = co.electriccoin.zcash.ui.screen.error.noReportAccess(),
                ObserveProposalUseCase(mockk(), mockk(), repository, accounts),
                ObserveTransactionSubmitStateUseCase(mockk(), mockk(), repository, accounts),
                mockk(), mockk(), mockk(), mockk(), mockk(), router
            )
            store.put("progress", vm)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            runCurrent()
            assertNull(repository.transactionProposal.value)
            val recovery = assertNotNull(vm.state.value)
            assertNotNull(recovery.primaryButton).onClick()
            recovery.onBack()
            verify(exactly = 2) { router.replaceAll(ActivityHistoryArgs) }
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun siblingDestinationsKeepTheirTypedRouteAcrossProcessStateRestoration() {
        for (route in listOf(
            ErrorBottomSheet("sheet"), SyncErrorArgs("sync"), SwapQuoteArgs, ORSwapConfirmationArgs,
            SignKeystoneTransactionArgs,
        )) {
            val previous = controller().apply { installGraph(); navigate(route) }
            val restored = roundTrip(previous)
            val restoredEntry = assertNotNull(restored.currentBackStackEntry)
            val actual = when (route) {
                is ErrorBottomSheet -> restoredEntry.toRoute<ErrorBottomSheet>()
                is SyncErrorArgs -> restoredEntry.toRoute<SyncErrorArgs>()
                SwapQuoteArgs -> restoredEntry.toRoute<SwapQuoteArgs>()
                ORSwapConfirmationArgs -> restoredEntry.toRoute<ORSwapConfirmationArgs>()
                else -> restoredEntry.toRoute<SignKeystoneTransactionArgs>()
            }
            assertEquals(route, actual)
            assertEquals(true, restored.popBackStack())
            assertEquals(HomeArgs, restored.currentBackStackEntry?.toRoute<HomeArgs>())
        }
    }

    private fun controller() = NavHostController(RuntimeEnvironment.getApplication()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
    }

    private fun NavHostController.installGraph() {
        graph = createGraph(startDestination = HomeArgs) {
            composable<HomeArgs> { }
            composable<TransactionProgressArgs> { }
            dialogComposable<ErrorDialog> { }
            dialogComposable<ErrorBottomSheet> { }
            dialogComposable<SyncErrorArgs> { }
            dialogComposable<SwapQuoteArgs> { }
            composable<ORSwapConfirmationArgs> { }
            composable<SignKeystoneTransactionArgs> { }
        }
    }

    private fun roundTrip(first: NavHostController): NavHostController {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(assertNotNull(first.saveState()))
            parcel.setDataPosition(0)
            controller().apply {
                restoreState(parcel.readBundle(javaClass.classLoader))
                installGraph()
            }
        } finally {
            parcel.recycle()
        }
    }
}
