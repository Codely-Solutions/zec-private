package co.electriccoin.zcash.ui

import android.os.Bundle
import android.os.Parcel
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.DialogNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.toRoute
import co.electriccoin.zcash.ui.common.provider.KeystoneSDKProvider
import co.electriccoin.zcash.ui.common.usecase.ParseKeystoneSignInRequestUseCase
import co.electriccoin.zcash.ui.design.KeyboardManager
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletEnrollment
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import co.electriccoin.zcash.ui.screen.connecthw.date.HWDateArgs
import co.electriccoin.zcash.ui.screen.connecthw.estimation.HWEstimationArgs
import co.electriccoin.zcash.ui.screen.connecthw.height.HWHeightArgs
import co.electriccoin.zcash.ui.screen.connecthw.neworactive.HWNewOrActiveArgs
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import co.electriccoin.zcash.ui.screen.scankeystone.viewmodel.ScanKeystoneSignInRequestViewModel
import co.electriccoin.zcash.ui.screen.selectkeystoneaccount.SelectKeystoneAccount
import co.electriccoin.zcash.ui.screen.transactionnote.TransactionNote
import com.keystone.module.DecodeResult
import com.keystone.module.ZcashAccount
import com.keystone.module.ZcashAccounts
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoneEnrollmentSavedStateTest {
    @Test
    fun scannerAndSharedRoutesRestoreOnlySessionIdsAndReturnToScanningAfterProcessLoss() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val first = newController().also { it.installGraph() }
            first.navigate(ScanKeystoneSignInRequest)
            val routing = NavigationRouterImpl()
            val session = KeystoneEnrollmentSession()
            val account = mockk<ZcashAccount> { every { ufvk } returns SYNTHETIC_UFVK }
            val accounts = mockk<ZcashAccounts> { every { this@mockk.accounts } returns listOf(account) }
            val sdk = mockk<KeystoneSDKProvider> {
                every { parseZcashAccounts(any()) } returns accounts
                every { decodeQR("frame") } returns DecodeResult(100, mockk())
            }
            val scanner = ParseKeystoneSignInRequestUseCase(routing, sdk, session)
            val route = async(start = CoroutineStart.UNDISPATCHED) {
                routing.observePipeline().first() as CustomNavigationCommand
            }
            scanner("frame", scanner.beginPresentation())
            newNavigator(first).executeCommand(route.await())

            val selection = assertNotNull(first.currentBackStackEntry).toRoute<SelectKeystoneAccount>()
            assertEquals(selection.sessionId, UUID.fromString(selection.sessionId).toString())
            val enrollment = HWWalletEnrollment.Keystone(selection.sessionId)
            val newOrActive = HWNewOrActiveArgs(enrollment)
            val date = HWDateArgs(enrollment)
            val estimation = HWEstimationArgs(enrollment, 500_000L)
            val height = HWHeightArgs(enrollment)
            listOf(newOrActive, date, estimation, height).forEach { first.navigate(it) }

            val restored = roundTrip(first)

            assertEquals(selection, restored.getBackStackEntry(selection).toRoute<SelectKeystoneAccount>())
            assertEquals(newOrActive, restored.getBackStackEntry(newOrActive).toRoute<HWNewOrActiveArgs>())
            assertEquals(date, restored.getBackStackEntry(date).toRoute<HWDateArgs>())
            assertEquals(estimation, restored.getBackStackEntry(estimation).toRoute<HWEstimationArgs>())
            assertEquals(height, restored.currentBackStackEntry?.toRoute<HWHeightArgs>())
            assertSame(accounts, session.resolve(selection.sessionId))

            val recreatedProcess = KeystoneEnrollmentSession()
            assertNull(recreatedProcess.resolve(selection.sessionId))
            restored.returnToKeystoneScanner()

            assertEquals(
                ScanKeystoneSignInRequest,
                restored.currentBackStackEntry?.toRoute<ScanKeystoneSignInRequest>()
            )
            assertEquals(HomeArgs, restored.previousBackStackEntry?.toRoute<HomeArgs>())

            scanner.close()
            assertNull(session.resolve(selection.sessionId))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun resumingTheScannerRejectsAnInterruptedHandoffAndAcceptsANewScan() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = newController().also { it.installGraph() }
            controller.navigate(ScanKeystoneSignInRequest)
            val routing = NavigationRouterImpl()
            val session = KeystoneEnrollmentSession()
            val account = mockk<ZcashAccount>()
            val accounts = mockk<ZcashAccounts> { every { this@mockk.accounts } returns listOf(account) }
            val sdk = mockk<KeystoneSDKProvider> {
                every { decodeQR(any()) } returns DecodeResult(100, mockk())
                every { parseZcashAccounts(any()) } returns accounts
                every { resetQRDecoder() } returns Unit
            }
            val scanner = ScanKeystoneSignInRequestViewModel(ParseKeystoneSignInRequestUseCase(routing, sdk, session))
            val navigator = newNavigator(controller)
            val original = scanner.beginPresentation()
            val staleCommand = async(start = CoroutineStart.UNDISPATCHED) {
                routing.observePipeline().first() as CustomNavigationCommand
            }
            scanner.onScanned("original", original).join()
            assertEquals(100, scanner.state.value.progress)

            controller.navigate(ThirdPartyScan)
            scanner.endPresentation(original)
            controller.popBackStack()
            val resumed = scanner.beginPresentation()
            navigator.executeCommand(staleCommand.await())
            assertEquals(
                ScanKeystoneSignInRequest,
                controller.currentBackStackEntry?.toRoute<ScanKeystoneSignInRequest>()
            )
            assertNull(scanner.state.value.progress)

            scanner.onScanned("late", original).join()
            verify(exactly = 0) { sdk.decodeQR("late") }
            val freshCommand = async(start = CoroutineStart.UNDISPATCHED) {
                routing.observePipeline().first() as CustomNavigationCommand
            }
            scanner.onScanned("fresh", resumed).join()
            navigator.executeCommand(freshCommand.await())
            verify(exactly = 1) { sdk.resetQRDecoder() }
            verify(exactly = 1) { sdk.decodeQR("fresh") }
            val selection = assertNotNull(controller.currentBackStackEntry).toRoute<SelectKeystoneAccount>()
            assertSame(accounts, session.resolve(selection.sessionId))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun olderActivityStateIsDiscarded() {
        val previous = Bundle().apply {
            putInt(NAVIGATION_VERSION_KEY, 1)
            putString("keystone_enrollment", SYNTHETIC_UFVK)
        }
        assertNull(MainActivity.restorableNavigationState(previous))
        assertNull(MainActivity.restorableNavigationState(Bundle()))
    }

    @Test
    fun oldNoteRouteIsDiscardedAndCurrentNoteRouteRestores() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val previous = newController().apply {
                graph = createGraph(startDestination = HomeArgs) {
                    composable<HomeArgs> { }
                    dialogComposable<Version2TransactionNote> { }
                }
            }
            val oldNote = Version2TransactionNote("fixture-tx")
            previous.navigate(oldNote)
            assertEquals(oldNote, previous.currentBackStackEntry?.toRoute<Version2TransactionNote>())
            assertEquals(HomeArgs, roundTrip(previous, version = 2).currentBackStackEntry?.toRoute<HomeArgs>())

            val current = newController().also { it.installGraph() }
            val currentNote = TransactionNote("fixture-tx", "00112233445566778899aabbccddeeff", 0L)
            current.navigate(currentNote)
            assertEquals(currentNote, roundTrip(current).currentBackStackEntry?.toRoute<TransactionNote>())
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun roundTrip(first: NavHostController, version: Int = 4): NavHostController {
        val restored = newController()
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(assertNotNull(first.saveState()).apply { putInt(NAVIGATION_VERSION_KEY, version) })
            val serialized = parcel.marshall()
            assertFalse(serialized.toString(Charsets.UTF_8).contains(SYNTHETIC_UFVK))
            assertFalse(serialized.toString(Charsets.UTF_16LE).contains(SYNTHETIC_UFVK))
            parcel.setDataPosition(0)
            restored.restoreState(MainActivity.restorableNavigationState(parcel.readBundle(javaClass.classLoader)))
        } finally {
            parcel.recycle()
        }
        return restored.also { it.installGraph() }
    }

    private fun newController() = NavHostController(RuntimeEnvironment.getApplication()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
        navigatorProvider.addNavigator(DialogNavigator())
    }

    private fun newNavigator(controller: NavHostController) =
        NavigatorImpl(mockk(), controller, KeyboardManager(false, null), mockk(), mockk(), mockk())

    private fun NavHostController.installGraph() {
        graph = createGraph(startDestination = HomeArgs) {
            composable<HomeArgs> { }
            hardwareEnrollmentScreens()
            composable<ThirdPartyScan> { }
            dialogComposable<TransactionNote> { }
        }
    }

    private companion object {
        const val NAVIGATION_VERSION_KEY = "wallet_navigation_state_version"
        const val SYNTHETIC_UFVK = "uview1synthetic-keystone-enrollment-fixture"
    }
}

@Serializable
@SerialName("co.electriccoin.zcash.ui.screen.transactionnote.TransactionNote")
private data class Version2TransactionNote(val txId: String)
