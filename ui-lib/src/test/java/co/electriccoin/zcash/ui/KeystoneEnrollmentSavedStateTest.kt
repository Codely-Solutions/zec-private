package co.electriccoin.zcash.ui

import android.os.Bundle
import android.os.Parcel
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
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
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import co.electriccoin.zcash.ui.screen.selectkeystoneaccount.SelectKeystoneAccount
import com.keystone.module.ZcashAccount
import com.keystone.module.ZcashAccounts
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
            val scanner = ParseKeystoneSignInRequestUseCase(
                routing,
                mockk<KeystoneSDKProvider> { every { parseZcashAccounts(any()) } returns accounts },
                session,
            )
            val activity = mockk<android.app.Activity>()
            val navigator = NavigatorImpl(
                activity, first, KeyboardManager(false, null), mockk(), mockk(), mockk()
            )
            val route = launch(start = CoroutineStart.UNDISPATCHED) {
                navigator.executeCommand(routing.observePipeline().first() as CustomNavigationCommand)
            }
            scanner.onSuccess(mockk())
            route.join()

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
    fun olderActivityStateIsDiscarded() {
        val previous = Bundle().apply {
            putInt("wallet_navigation_state_version", 1)
            putString("keystone_enrollment", SYNTHETIC_UFVK)
        }
        assertNull(MainActivity.restorableNavigationState(previous))
        assertNull(MainActivity.restorableNavigationState(Bundle()))
    }

    private fun roundTrip(first: NavHostController): NavHostController {
        val restored = newController()
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(assertNotNull(first.saveState()))
            val serialized = parcel.marshall()
            assertFalse(serialized.toString(Charsets.UTF_8).contains(SYNTHETIC_UFVK))
            assertFalse(serialized.toString(Charsets.UTF_16LE).contains(SYNTHETIC_UFVK))
            parcel.setDataPosition(0)
            restored.restoreState(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
        return restored.also { it.installGraph() }
    }

    private fun newController() = NavHostController(RuntimeEnvironment.getApplication()).apply {
        navigatorProvider.addNavigator(ComposeNavigator())
    }

    private fun NavHostController.installGraph() {
        graph = createGraph(startDestination = HomeArgs) {
            composable<HomeArgs> { }
            hardwareEnrollmentScreens()
        }
    }

    private companion object {
        const val SYNTHETIC_UFVK = "uview1synthetic-keystone-enrollment-fixture"
    }
}
