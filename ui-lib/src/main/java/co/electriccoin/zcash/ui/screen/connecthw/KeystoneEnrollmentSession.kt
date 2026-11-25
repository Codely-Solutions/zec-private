package co.electriccoin.zcash.ui.screen.connecthw

import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentAccess
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentToken
import com.keystone.module.ZcashAccounts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/** Pending Keystone accounts owned by the scanner back-stack entry in this process. */
class KeystoneEnrollmentSession(private val walletEnrollment: WalletEnrollmentAccess) {
    private var pending: Pending? = null
    internal val importMutex = Mutex()

    internal fun captureWallet() = walletEnrollment.capture()

    @Synchronized
    internal fun start(accounts: ZcashAccounts, owner: WalletEnrollmentToken): String? =
        if (!walletEnrollment.permits(owner)) {
            null
        } else {
            UUID.randomUUID().toString().also { pending = Pending(it, accounts, owner) }
        }

    internal fun resolve(id: String): ZcashAccounts? = enrollment(id)?.accounts

    @Synchronized
    internal fun enrollment(id: String): Pending? {
        val current = pending?.takeIf { it.id == id } ?: return null
        return current.takeIf { walletEnrollment.permits(it.owner) }.also {
            if (it == null) pending = null
        }
    }

    @Synchronized
    internal fun clear(id: String) {
        if (pending?.id == id) pending = null
    }

    internal class Pending(val id: String, val accounts: ZcashAccounts, val owner: WalletEnrollmentToken) {
        val imported = MutableStateFlow<Imported?>(null)
    }

    internal data class Imported(val account: Account, val birthday: BlockHeight?)
}
