package co.electriccoin.zcash.ui.screen.restore.seed

import cash.z.ecc.android.sdk.model.SeedPhrase
import java.util.UUID

/** Owned by the seed entry's ViewModel. Only the random identifier crosses a navigation boundary. */
internal class RestoreSession {
    private var entry: Entry? = null

    fun start(seed: SeedPhrase): String {
        val current = entry
        if (current != null && current.seed == seed) return current.id
        return UUID.randomUUID().toString().also { entry = Entry(it, seed) }
    }

    fun resolve(id: String): SeedPhrase? = entry?.takeIf { it.id == id }?.seed

    fun clear(id: String? = null) {
        if (id == null || entry?.id == id) entry = null
    }

    private class Entry(val id: String, val seed: SeedPhrase)
}
