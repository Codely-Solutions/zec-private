package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the Ledger account the connect flow pairs again when the sign sheet's Pair Ledger opened
 * it, so the pairing is checked against that account instead of adding a Ledger. Empty while the
 * flow adds a Ledger. In memory only, like the selected device: process death empties both, and
 * pairing without a target still refuses a second Ledger.
 *
 * The account's ZIP 32 index on the device is kept with it when its binding still holds one, so the
 * scan screen can offer it as the account to pair; the user may still choose another.
 */
interface LedgerRepairTargetRepository {
    fun set(
        accountUuid: AccountUuid,
        zip32AccountIndex: Zip32AccountIndex?,
        owner: WalletEnrollmentToken,
    )

    fun get(): AccountUuid? = current()?.accountUuid

    fun current(): LedgerRepairTarget?

    fun clear(expected: LedgerRepairTarget): Boolean

    /**
     * The target's stored index; null when there is no target or its binding holds no index.
     */
    fun getZip32AccountIndex(): Zip32AccountIndex?

    fun clear()
}

class LedgerRepairTargetRepositoryImpl : LedgerRepairTargetRepository {
    private val target = MutableStateFlow<LedgerRepairTarget?>(null)

    override fun set(
        accountUuid: AccountUuid,
        zip32AccountIndex: Zip32AccountIndex?,
        owner: WalletEnrollmentToken,
    ) = target.update { LedgerRepairTarget(accountUuid, zip32AccountIndex, owner) }

    override fun current(): LedgerRepairTarget? = target.value

    override fun clear(expected: LedgerRepairTarget): Boolean = target.compareAndSet(expected, null)

    override fun getZip32AccountIndex(): Zip32AccountIndex? = target.value?.zip32AccountIndex

    override fun clear() = target.update { null }
}

/** Each repair launch owns a distinct target, including repeated launches for the same account. */
class LedgerRepairTarget(
    val accountUuid: AccountUuid,
    val zip32AccountIndex: Zip32AccountIndex?,
    val owner: WalletEnrollmentToken,
)
