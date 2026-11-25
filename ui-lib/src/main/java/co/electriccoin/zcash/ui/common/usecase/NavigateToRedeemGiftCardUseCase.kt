package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.common.repository.GiftCardLinkStore
import co.electriccoin.zcash.ui.common.repository.GiftCardRepository
import co.electriccoin.zcash.ui.screen.redeemgift.RedeemGiftArgs
import co.electriccoin.zcash.ui.screen.redeemgift.ScanGiftCardArgs

/**
 * Routes gift card links scanned, picked from an image or pasted inside the app into the redeem flow. The link itself
 * is stashed in [GiftCardLinkStore] and only its id travels in the navigation route.
 */
class NavigateToRedeemGiftCardUseCase(
    private val giftCardRepository: GiftCardRepository,
    private val giftCardLinkStore: GiftCardLinkStore,
    private val navigationRouter: NavigationRouter,
) {
    fun isGiftCardLink(value: String): Boolean = giftCardRepository.isGiftCardLink(value)

    /**
     * Whether [value] could still become a gift card link as the user keeps typing, see
     * [GiftCardRepository.isGiftCardLinkStart].
     */
    fun isGiftCardLinkStart(value: String): Boolean = giftCardRepository.isGiftCardLinkStart(value)

    /**
     * Replaces the current screen (an address scanner that was handed a gift card link) with the redeem screen for
     * [link].
     */
    suspend fun replaceWithRedeem(link: String, sourceEntryId: String? = null): Boolean =
        navigationRouter.commitFrom(sourceEntryId) { NavigationCommand.Replace(listOf(redeemArgs(link))) }

    /**
     * Replaces the gift card flow's own screens, the gift card scanner and the paste link screen above it, with the
     * redeem screen for [link], so back from the redeem screen returns to the screen the scanner was opened from.
     */
    suspend fun replaceGiftCardScanWithRedeem(
        link: String,
        sourceEntryId: String? = null,
        onCommit: () -> Unit = {},
    ): Boolean =
        navigationRouter.commitFrom(sourceEntryId) {
            val route = redeemArgs(link)
            onCommit()
            NavigationCommand.ReplaceFrom(ScanGiftCardArgs::class, listOf(route))
        }

    private fun redeemArgs(link: String) = RedeemGiftArgs(giftCardLinkStore.stash(link))
}
