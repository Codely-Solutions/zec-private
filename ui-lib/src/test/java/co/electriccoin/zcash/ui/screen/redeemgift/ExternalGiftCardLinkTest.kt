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

        repeat(2) { assertTrue(handleExternalLink(host = "gift.zodl.com", isRedelivery = false)) }
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

        assertTrue(HandleExternalLinkUseCase(launchNavigation)("GIFT.ZODL.COM", isRedelivery = false))
        assertEquals(LaunchNavigationRequests.Kind.GIFT_SCAN, launchNavigation.changes.value.single().kind)
    }

    @Test
    fun redeliveredGiftLinkDoesNotAskAgain() {
        val launchNavigation = LaunchNavigationRequests()

        assertTrue(HandleExternalLinkUseCase(launchNavigation)("gift.zodl.com", isRedelivery = true))

        assertTrue(launchNavigation.changes.value.isEmpty())
    }

    @Test
    fun zcashUriWaitsForAppAccessAndCanOpenTheScannerDuringOnboarding() = runTest {
        val launchNavigation = LaunchNavigationRequests()
        val delivered = mutableListOf<NavigationCommand>()

        assertFalse(HandleExternalLinkUseCase(launchNavigation)(host = null, isRedelivery = false))
        launchNavigation.deliver(walletReady = true, hasAppAccess = { false }) { delivered += it }
        assertTrue(delivered.isEmpty())

        launchNavigation.deliver(walletReady = false, hasAppAccess = { true }) { delivered += it }
        assertEquals(listOf<NavigationCommand>(NavigationCommand.Forward(listOf(ThirdPartyScan))), delivered)
        assertTrue(launchNavigation.changes.value.isEmpty())
    }

    @Test
    fun lookalikeHostDoesNotRequestGiftScanner() {
        val launchNavigation = LaunchNavigationRequests()

        assertFalse(HandleExternalLinkUseCase(launchNavigation)("gift.zodl.com.evil.example", isRedelivery = false))
        assertEquals(LaunchNavigationRequests.Kind.THIRD_PARTY_SCAN, launchNavigation.changes.value.single().kind)
    }
}
