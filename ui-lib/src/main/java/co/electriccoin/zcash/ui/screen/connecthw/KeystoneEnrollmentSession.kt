package co.electriccoin.zcash.ui.screen.connecthw

import com.keystone.module.ZcashAccounts
import java.util.UUID

/** Pending Keystone accounts owned by the scanner back-stack entry in this process. */
class KeystoneEnrollmentSession {
    private var pending: Pending? = null

    internal fun start(accounts: ZcashAccounts): String =
        UUID.randomUUID().toString().also { pending = Pending(it, accounts) }

    internal fun resolve(id: String): ZcashAccounts? = pending?.takeIf { it.id == id }?.accounts

    internal fun clear(id: String) {
        if (pending?.id == id) pending = null
    }

    private class Pending(val id: String, val accounts: ZcashAccounts)
}
