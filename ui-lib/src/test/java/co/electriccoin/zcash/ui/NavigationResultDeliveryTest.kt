package co.electriccoin.zcash.ui

import android.app.Activity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import co.electriccoin.zcash.ui.design.KeyboardManager
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import co.electriccoin.zcash.ui.screen.redeemgift.ScanGiftCardArgs
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityHistoryArgs
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationResultDeliveryTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var controller: NavHostController

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun automaticRecoveryWaitsWhenTheScannerAlreadyOwnsTheScreen() {
        install()
        compose.runOnIdle {
            runTest {
                val keyboard = KeyboardManager(isOpen = false, rootSoftwareKeyboardController = null)
                val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk())
                val requests = LaunchNavigationRequests()
                backgroundScope.launch {
                    requests.deliverOnChanges(controller, true, { true }, navigator::executeLaunchCommand)
                }
                requests.requestGiftScan()
                runCurrent()
                assertEquals(ScanGiftCardArgs(true), controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>())

                val progress = DeliveryMigrationArgs("owner")
                requests.requestMigrationRecovery("owner", NavigationCommand.ReplaceAll(listOf(progress)))
                runCurrent()
                assertEquals(ScanGiftCardArgs(true), controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>())

                assertTrue(controller.popBackStack())
                runCurrent()
                assertEquals(progress, controller.currentBackStackEntry?.toRoute<DeliveryMigrationArgs>())

                controller.navigate(ScanGiftCardArgs(true))
                var ownsRecovery = true
                requests.requestMigrationRecovery("previous-owner", NavigationCommand.ReplaceAll(listOf(progress))) {
                    ownsRecovery
                }
                runCurrent()
                ownsRecovery = false
                assertTrue(controller.popBackStack())
                assertTrue(controller.popBackStack())
                runCurrent()
                assertEquals(HomeArgs, controller.currentBackStackEntry?.toRoute<HomeArgs>())
            }
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun lateSubmissionResultWaitsForItsOwnEntryWithoutDisplacingTheScanner() {
        install()
        compose.runOnIdle {
            runTest {
                val navigation = collectOwnedNavigation()
                val home = checkNotNull(controller.currentBackStackEntry).id
                controller.navigate(DeliveryOrigin)
                val origin = checkNotNull(controller.currentBackStackEntry).id
                controller.navigate(ScanGiftCardArgs(true))
                var resultDeliveries = 0
                navigation.stageResultFrom(origin) {
                    resultDeliveries++
                    NavigationCommand.Replace(listOf(ActivityHistoryArgs))
                }
                navigation.stageResultFrom(home) { NavigationCommand.Forward(listOf(DeliveryMigrationArgs("home"))) }
                runCurrent()
                assertEquals(ScanGiftCardArgs(true), controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>())
                assertEquals(0, resultDeliveries)

                assertTrue(controller.popBackStack())
                runCurrent()
                assertEquals(ActivityHistoryArgs, controller.currentBackStackEntry?.toRoute<ActivityHistoryArgs>())
                assertEquals(1, resultDeliveries)

                assertTrue(controller.popBackStack())
                runCurrent()
                assertEquals(
                    DeliveryMigrationArgs("home"),
                    controller.currentBackStackEntry?.toRoute<DeliveryMigrationArgs>(),
                )
                assertEquals(1, resultDeliveries)
            }
        }
    }

    private fun install() {
        compose.setContent {
            controller = rememberNavController()
            NavHost(controller, startDestination = HomeArgs) {
                composable<HomeArgs> { Text("Wallet home") }
                composable<DeliveryMigrationArgs> { Text("Migration progress") }
                composable<DeliveryOrigin> { Text("Signing") }
                composable<ScanGiftCardArgs> { Text("External scan") }
                composable<ActivityHistoryArgs> { Text("Activity history") }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.collectOwnedNavigation(): NavigationRouterImpl {
        val router = NavigationRouterImpl()
        val observer = router.registerEntryObserver()
        val listener = NavController.OnDestinationChangedListener { nav, _, _ ->
            observer.update(nav.currentBackStackEntry?.toActiveNavigationEntry())
        }
        controller.addOnDestinationChangedListener(listener)
        val keyboard = KeyboardManager(isOpen = false, rootSoftwareKeyboardController = null)
        val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                router.collectPendingForwards(navigator) { true }
            } finally {
                controller.removeOnDestinationChangedListener(listener)
                observer.close()
            }
        }
        return router
    }
}

@Serializable
private data class DeliveryMigrationArgs(val owner: String)

@Serializable
private data object DeliveryOrigin
