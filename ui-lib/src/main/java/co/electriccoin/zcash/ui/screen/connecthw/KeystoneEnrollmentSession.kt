package co.electriccoin.zcash.ui.screen.connecthw

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.BlockHeight
import com.keystone.module.ZcashAccounts
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/** Pending Keystone accounts owned by the scanner back-stack entry in this process. */
class KeystoneEnrollmentSession {
    private var pending: Pending? = null
    internal val importMutex = Mutex()

    internal fun start(accounts: ZcashAccounts): String =
        UUID.randomUUID().toString().also { pending = Pending(it, accounts) }

    internal fun resolve(id: String): ZcashAccounts? = enrollment(id)?.accounts

    internal fun enrollment(id: String): Pending? = pending?.takeIf { it.id == id }

    internal fun clear(id: String) {
        if (pending?.id == id) pending = null
    }

    internal class Pending(val id: String, val accounts: ZcashAccounts) {
        var imported: Imported? = null
    }

    internal data class Imported(val account: Account, val birthday: BlockHeight?)
}
