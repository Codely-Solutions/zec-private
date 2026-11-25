package co.electriccoin.zcash.ui

import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.VKType
import co.electriccoin.zcash.ui.screen.exportvk.ExportVKArgs
import co.electriccoin.zcash.ui.screen.exportvk.detail.VKDetailArgs
import co.electriccoin.zcash.ui.screen.home.HomeArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `replaceFrom` command the Full Viewing Key consent sheet relies on (MOB-1883): it reaches the pipeline
 * carrying both the route to pop back to and the routes to push in their original order, and the router's
 * half-second backoff only swallows a command identical to the one still in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationRouterImplTest {
    private lateinit var dispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun replaceFromEmitsTheCommandWithTheRouteAndTheRoutesInOrder() =
        runTest(dispatcher) {
            val router = NavigationRouterImpl()
            val commands = collectedCommands(router)
            val detail = VKDetailArgs(VKType.FULL, "full-review")

            router.replaceFrom(ExportVKArgs::class, detail)
            advanceUntilIdle()

            assertEquals(
                listOf(NavigationCommand.ReplaceFrom(ExportVKArgs::class, listOf(detail))),
                commands
            )
        }

    @Test
    fun replaceFromWithDifferentRoutesIsNotDebounced() =
        runTest(dispatcher) {
            val router = NavigationRouterImpl()
            val commands = collectedCommands(router)
            val full = VKDetailArgs(VKType.FULL, "full-review")
            val incoming = VKDetailArgs(VKType.INCOMING, "incoming-review")

            router.replaceFrom(ExportVKArgs::class, full)
            runCurrent()
            router.replaceFrom(ExportVKArgs::class, incoming)
            runCurrent()
            router.replaceFrom(ExportVKArgs::class, incoming)
            runCurrent()

            assertEquals(
                listOf(
                    NavigationCommand.ReplaceFrom(ExportVKArgs::class, listOf(full)),
                    NavigationCommand.ReplaceFrom(ExportVKArgs::class, listOf(incoming)),
                ),
                commands
            )
        }

    @Test
    fun lockedWalletDropsOldCallbacksButRetainsAnExplicitLaunchRequest() =
        runTest(dispatcher) {
            val session = AppAccessSession()
            val launchNavigation = LaunchNavigationRequests()
            val router = NavigationRouterImpl()
            val requestedProgress = NavigationCommand.ReplaceAll(listOf(HomeArgs, TestMigrationProgress))
            val delivered = mutableListOf<NavigationCommand>()
            val authorized = { session.permits(session.grant.value) }

            router.forward(ExportVKArgs)
            runCurrent()
            val notice = launchNavigation.beginMigrationProgress("owner", runStepPending = true)
            val pending = LaunchNavigationRequests.SavedMigrationNotice("owner", runStepPending = true)
            assertEquals(pending, launchNavigation.migrationNoticeToSave)
            assertEquals(pending, launchNavigation.pendingMigrationLookup)
            launchNavigation.deliver(walletReady = true, hasAppAccess = { true }) { delivered += it }
            assertTrue(delivered.isEmpty())

            launchNavigation.finishMigrationProgress(notice, requestedProgress)
            assertEquals(null, launchNavigation.pendingMigrationLookup)
            assertEquals(pending.copy(runStepPending = false), launchNavigation.migrationNoticeToSave)
            launchNavigation.deliver(walletReady = true, hasAppAccess = authorized) { delivered += it }
            assertTrue(delivered.isEmpty())

            session.unlock()
            val genericCallbacks = collectedCommands(router)
            launchNavigation.deliver(walletReady = false, hasAppAccess = authorized) { delivered += it }
            assertTrue(delivered.isEmpty())
            launchNavigation.deliver(walletReady = true, hasAppAccess = authorized) { delivered += it }
            launchNavigation.deliver(walletReady = true, hasAppAccess = authorized) { delivered += it }

            assertEquals(listOf<NavigationCommand>(requestedProgress), delivered)
            assertTrue(genericCallbacks.isEmpty())
        }

    private fun TestScope.collectedCommands(router: NavigationRouter): List<BaseNavigationCommand> {
        val commands = mutableListOf<BaseNavigationCommand>()
        backgroundScope.launch { router.observePipeline().collect { commands += it } }
        runCurrent()
        return commands
    }

    private data object TestMigrationProgress
}
