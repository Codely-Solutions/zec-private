package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.ledger.LedgerAccountPairing
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the pairing a Ledger just produced while the user answers the birthday question, and
 * nothing longer. In memory only: the pairing carries a viewing key, and the enrollment screens
 * that read it fall back to the wallet root when process death empties it.
 */
interface LedgerPairingRepository {
    val pairing: StateFlow<LedgerAccountPairing?>

    val imported: StateFlow<ImportedLedgerAccount?>

    fun set(pairing: LedgerAccountPairing, owner: WalletEnrollmentToken)

    fun owner(pairing: LedgerAccountPairing): WalletEnrollmentToken?

    fun get(): LedgerAccountPairing?

    fun clear()

    fun importedAccount(pairing: LedgerAccountPairing): ImportedLedgerAccount?

    fun recordImport(pairing: LedgerAccountPairing, account: ImportedLedgerAccount): Boolean

    fun clear(pairing: LedgerAccountPairing): Boolean
}

data class ImportedLedgerAccount(val account: Account, val birthday: BlockHeight?)

class LedgerPairingRepositoryImpl : LedgerPairingRepository {
    private val mutablePairing = MutableStateFlow<LedgerAccountPairing?>(null)
    private val mutableImported = MutableStateFlow<ImportedLedgerAccount?>(null)
    override val imported = mutableImported.asStateFlow()
    private var currentOwner: WalletEnrollmentToken? = null

    override val pairing: StateFlow<LedgerAccountPairing?> = mutablePairing.asStateFlow()

    @Synchronized
    override fun set(pairing: LedgerAccountPairing, owner: WalletEnrollmentToken) {
        currentOwner = owner
        mutableImported.value = null
        mutablePairing.value = pairing
    }

    override fun get(): LedgerAccountPairing? = mutablePairing.value

    @Synchronized
    override fun owner(pairing: LedgerAccountPairing): WalletEnrollmentToken? =
        currentOwner.takeIf { mutablePairing.value === pairing }

    @Synchronized
    override fun clear() {
        currentOwner = null
        mutableImported.value = null
        mutablePairing.value = null
    }

    @Synchronized
    override fun importedAccount(pairing: LedgerAccountPairing) =
        mutableImported.value.takeIf { mutablePairing.value === pairing }

    @Synchronized
    override fun recordImport(pairing: LedgerAccountPairing, account: ImportedLedgerAccount): Boolean {
        if (mutablePairing.value !== pairing) return false
        mutableImported.value = account
        return true
    }

    @Synchronized
    override fun clear(pairing: LedgerAccountPairing): Boolean {
        if (mutablePairing.value !== pairing) return false
        clear()
        return true
    }
}
