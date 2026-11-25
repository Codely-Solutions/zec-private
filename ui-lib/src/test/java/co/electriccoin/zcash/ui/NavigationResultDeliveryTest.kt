package co.electriccoin.zcash.ui

import android.app.Activity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationResultDeliveryTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var controller: NavHostController

    @Test
    fun reportHandoffFollowsTheHostLifecycleAndOriginatingEntry() {
        install()
        compose.runOnIdle {
            val owner = object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            val router = NavigationRouterImpl()
            val stopTracking = controller.trackActiveEntry(router, owner.lifecycle)
            try {
                val canShare = router.bindReportHandoff { true }
                assertTrue(canShare())
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                assertFalse(canShare())
                val fromBackground = router.bindReportHandoff { true }
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
                assertTrue(canShare())
                assertFalse(fromBackground())
                controller.navigate(DeliveryOrigin)
                assertFalse(canShare())
            } finally {
                stopTracking()
            }
        }
    }

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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun poppedSigningDialogKeepsItsCommittedResultWithTheUnderlyingReview() {
        install()
        compose.runOnIdle {
            runTest {
                var resumed = false
                val router = collectOwnedNavigation { resumed }
                controller.navigate(DeliveryOrigin)
                val review = checkNotNull(controller.currentBackStackEntry).id
                controller.navigate(DeliverySigningSheet)
                val dialog = checkNotNull(router.activeEntry.value)
                assertEquals(review, dialog.contentEntryId)
                assertTrue(dialog.id != review)

                var deliveries = 0
                router.stageResultFrom(dialog.id, retainAfterSourceCloses = true) {
                    deliveries++
                    NavigationCommand.Replace(listOf(ActivityHistoryArgs))
                }
                runCurrent()
                controller.navigate(ScanGiftCardArgs(true))
                assertEquals(review, controller.previousBackStackEntry?.id)
                router.discardResultFrom(dialog.id, dialog.contentEntryId)

                resumed = true
                runCurrent()
                assertEquals(ScanGiftCardArgs(true), controller.currentBackStackEntry?.toRoute<ScanGiftCardArgs>())
                assertEquals(0, deliveries)
                assertTrue(controller.popBackStack())
                runCurrent()
                assertEquals(ActivityHistoryArgs, controller.currentBackStackEntry?.toRoute<ActivityHistoryArgs>())
                assertEquals(1, deliveries)
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
                dialog<DeliverySigningSheet> { Text("Hardware signing") }
                composable<ScanGiftCardArgs> { Text("External scan") }
                composable<ActivityHistoryArgs> { Text("Activity history") }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.collectOwnedNavigation(canCommit: () -> Boolean = { true }): NavigationRouterImpl {
        val router = NavigationRouterImpl()
        val stopTracking = controller.trackActiveEntry(router)
        val keyboard = KeyboardManager(isOpen = false, rootSoftwareKeyboardController = null)
        val navigator = NavigatorImpl(mockk<Activity>(), controller, keyboard, mockk(), mockk(), mockk())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                router.collectPendingForwards(navigator, canCommit)
            } finally {
                stopTracking()
            }
        }
        return router
    }
}

@Serializable
internal data class DeliveryMigrationArgs(val owner: String)

@Serializable
internal data object DeliveryOrigin

@Serializable
internal data object DeliverySigningSheet
