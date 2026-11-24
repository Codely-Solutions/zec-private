package co.electriccoin.zcash.ui.screen.redeemgift

import co.electriccoin.zcash.ui.LaunchNavigationRequests
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.usecase.HandleExternalLinkUseCase
import co.electriccoin.zcash.ui.screen.scan.thirdparty.ThirdPartyScan
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalGiftCardLinkTest {
    @Test
    fun giftLinkRetainsOnlyARequestForAnAuthorizedWalletScanner() = runTest {
        val launchNavigation = LaunchNavigationRequests()
        val handleExternalLink = HandleExternalLinkUseCase(launchNavigation)
        val session = AppAccessSession()
        val delivered = mutableListOf<NavigationCommand>()
        val authorized = { session.permits(session.grant.value) }

        repeat(2) { assertTrue(handleExternalLink("https", "gift.zodl.com", isRedelivery = false)) }
        launchNavigation.deliver(walletReady = true, hasAppAccess = authorized) { delivered += it }
        assertTrue(delivered.isEmpty())

        session.unlock()
        launchNavigation.deliver(walletReady = false, hasAppAccess = authorized) { delivered += it }
        assertTrue(delivered.isEmpty())
        launchNavigation.deliver(walletReady = true, hasAppAccess = authorized) { delivered += it }
        launchNavigation.deliver(walletReady = true, hasAppAccess = authorized) { delivered += it }

        val expected = NavigationCommand.Forward(listOf(ScanGiftCardArgs(isFromExternalLink = true)))
        assertEquals(listOf<NavigationCommand>(expected), delivered)
        assertTrue(launchNavigation.changes.value.isEmpty())
    }

    @Test
    fun giftHostIsMatchedIgnoringCase() {
        val launchNavigation = LaunchNavigationRequests()

        assertTrue(HandleExternalLinkUseCase(launchNavigation)("https", "GIFT.ZODL.COM", isRedelivery = false))
        assertEquals(LaunchNavigationRequests.Kind.GIFT_SCAN, launchNavigation.changes.value.single().kind)
    }

    @Test
    fun redeliveredGiftLinkDoesNotAskAgain() {
        val launchNavigation = LaunchNavigationRequests()

        assertTrue(HandleExternalLinkUseCase(launchNavigation)("https", "gift.zodl.com", isRedelivery = true))

        assertTrue(launchNavigation.changes.value.isEmpty())
    }

    @Test
    fun zcashUriWaitsForAppAccessAndCanOpenTheScannerDuringOnboarding() = runTest {
        val launchNavigation = LaunchNavigationRequests()
        val delivered = mutableListOf<NavigationCommand>()

        assertFalse(HandleExternalLinkUseCase(launchNavigation)("zcash", host = null, isRedelivery = false))
        launchNavigation.deliver(walletReady = true, hasAppAccess = { false }) { delivered += it }
        assertTrue(delivered.isEmpty())

        launchNavigation.deliver(walletReady = false, hasAppAccess = { true }) { delivered += it }
        assertEquals(listOf<NavigationCommand>(NavigationCommand.Forward(listOf(ThirdPartyScan))), delivered)
        assertTrue(launchNavigation.changes.value.isEmpty())
    }

    @Test
    fun lookalikeHostDoesNotRequestGiftScanner() {
        val launchNavigation = LaunchNavigationRequests()

        val handleExternalLink = HandleExternalLinkUseCase(launchNavigation)
        assertFalse(handleExternalLink("https", "gift.zodl.com.evil.example", isRedelivery = false))
        assertFalse(handleExternalLink("http", "gift.zodl.com", isRedelivery = false))
        assertTrue(launchNavigation.changes.value.isEmpty())
    }

    @Test
    fun recreationRestoresQueuedScannerAndIgnoresConsumedIntent() = runTest {
        val original = LaunchNavigationRequests()
        val handle = HandleExternalLinkUseCase(original)
        original.restoreExternalScans(emptyList())
        handle("zcash", null, isRedelivery = false)
        original.deliver(walletReady = true, hasAppAccess = { true }) { }
        handle("https", "gift.zodl.com", isRedelivery = false)
        val saved = original.saveExternalScans()
        assertEquals(listOf(LaunchNavigationRequests.Kind.GIFT_SCAN.name), saved)

        val restored = LaunchNavigationRequests()
        restored.restoreExternalScans(saved)
        HandleExternalLinkUseCase(restored)("zcash", null, isRedelivery = true)
        val delivered = mutableListOf<NavigationCommand>()
        restored.deliver(walletReady = true, hasAppAccess = { false }) { delivered += it }
        restored.deliver(walletReady = false, hasAppAccess = { true }) { delivered += it }
        assertTrue(delivered.isEmpty())
        restored.deliver(walletReady = true, hasAppAccess = { true }) { delivered += it }
        assertEquals(listOf(NavigationCommand.Forward(listOf(ScanGiftCardArgs(isFromExternalLink = true)))), delivered)
        restored.restoreExternalScans(saved)
        assertTrue(restored.changes.value.isEmpty())
    }
}
