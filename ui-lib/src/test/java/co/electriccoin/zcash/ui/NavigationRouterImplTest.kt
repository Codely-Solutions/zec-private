package co.electriccoin.zcash.ui

import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.VKType
import co.electriccoin.zcash.ui.screen.exportvk.ExportVKArgs
import co.electriccoin.zcash.ui.screen.exportvk.detail.VKDetailArgs
import co.electriccoin.zcash.ui.screen.redeemgift.ScanGiftCardArgs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
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
            val requestedProgress = NavigationCommand.ReplaceAll(listOf(TestMigrationProgress))
            val delivered = mutableListOf<NavigationCommand>()
            val authorized = { session.permits(session.grant.value) }

            router.forward(ExportVKArgs)
            runCurrent()
            val notice = launchNavigation.beginMigrationProgress("owner", runStepPending = true)
            val pending = LaunchNavigationRequests.SavedMigrationNotice("owner", runStepPending = true)
            assertEquals(pending, launchNavigation.migrationNoticeToSave)
            assertEquals(pending, launchNavigation.pendingMigrationLookup)
            launchNavigation.deliver(walletReady = true, hasAppAccess = { true }) { command, _ ->
                delivered.add(command)
            }
            assertTrue(delivered.isEmpty())

            launchNavigation.finishMigrationProgress(notice, requestedProgress)
            assertEquals(null, launchNavigation.pendingMigrationLookup)
            assertEquals(pending.copy(runStepPending = false), launchNavigation.migrationNoticeToSave)
            launchNavigation.deliver(walletReady = true, hasAppAccess = authorized) { command, _ ->
                delivered.add(command)
            }
            assertTrue(delivered.isEmpty())

            session.unlock()
            val genericCallbacks = collectedCommands(router)
            launchNavigation.deliver(walletReady = false, hasAppAccess = authorized) { command, _ ->
                delivered.add(command)
            }
            assertTrue(delivered.isEmpty())
            repeat(2) {
                launchNavigation.deliver(walletReady = true, hasAppAccess = authorized) { command, _ ->
                    delivered.add(command)
                }
            }

            assertEquals(listOf<NavigationCommand>(requestedProgress), delivered)
            assertTrue(genericCallbacks.isEmpty())
        }

    @Test
    fun interruptedExplicitLaunchIsSavedUntilConfirmedDelivery() = runTest(dispatcher) {
        val requests = LaunchNavigationRequests()
        val delivered = mutableListOf<NavigationCommand>()
        val waitingForKeyboard = CompletableDeferred<Unit>()
        requests.requestGiftScan()
        val previous = launch {
            requests.deliver(walletReady = true, hasAppAccess = { true }) { command, isCurrent ->
                waitingForKeyboard.await()
                isCurrent() && delivered.add(command)
            }
        }
        runCurrent()
        assertEquals(listOf("GIFT_SCAN"), requests.saveExternalScans())
        previous.cancelAndJoin()
        assertTrue(delivered.isEmpty())
        requests.deliver(walletReady = true, hasAppAccess = { true }) { _, _ -> false }
        assertEquals(listOf("GIFT_SCAN"), requests.saveExternalScans())

        repeat(2) {
            requests.deliver(walletReady = true, hasAppAccess = { true }) { command, isCurrent ->
                isCurrent() && delivered.add(command)
            }
        }
        val expected: List<NavigationCommand> =
            listOf(NavigationCommand.Forward(listOf(ScanGiftCardArgs(isFromExternalLink = true))))
        assertEquals(expected, delivered)
        assertTrue(requests.saveExternalScans().isEmpty())
    }

    @Test
    fun replacingTheGraphObserverRevokesCallbacksFromItsPredecessor() {
        val router = NavigationRouterImpl()
        val first = router.registerEntryObserver()
        first.update(ActiveNavigationEntry("old", null))
        val replacement = router.registerEntryObserver()
        val newEntry = ActiveNavigationEntry("new", null)
        replacement.update(newEntry)
        first.update(ActiveNavigationEntry("late", null))
        first.close()
        assertEquals(newEntry, router.activeEntry.value)
        replacement.close()
        assertEquals(null, router.activeEntry.value)
    }

    private fun TestScope.collectedCommands(router: NavigationRouter): List<BaseNavigationCommand> {
        val commands = mutableListOf<BaseNavigationCommand>()
        backgroundScope.launch { router.observePipeline().collect { commands += it } }
        runCurrent()
        return commands
    }

    private data object TestMigrationProgress
}
