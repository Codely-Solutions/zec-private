package co.electriccoin.zcash.ui.common.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Holds gift card links scanned, picked from an image or pasted inside the app in memory between the scanner and the
 * redeem screen. Navigation routes only ever carry the random id returned by [stash]: routes are serialized into the
 * saved instance state, and a gift card link carries a spending secret that must not end up there or in logs.
 *
 * A link that comes from outside the app (an app link, any other VIEW intent) is discarded because another app
 * could have altered it on its way in.
 *
 * Nothing here is persisted. After process death the ids no longer resolve, and the redeem screen asks the user to
 * open the link again.
 */
interface GiftCardLinkStore {
    /**
     * Keeps [link] and returns the id to retrieve it with.
     */
    fun stash(link: String): String

    /**
     * Returns the link for [id] and forgets it.
     */
    fun take(id: String): String?
}

class GiftCardLinkStoreImpl : GiftCardLinkStore {
    private val links = MutableStateFlow(emptyMap<String, String>())

    override fun stash(link: String): String =
        UUID.randomUUID().toString().also { id -> links.update { it + (id to link) } }

    override fun take(id: String): String? = links.getAndUpdate { it - id }[id]
}
