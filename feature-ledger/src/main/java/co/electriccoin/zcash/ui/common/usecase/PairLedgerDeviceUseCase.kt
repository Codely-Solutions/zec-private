package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.ledger.LedgerAccountPairing
import cash.z.ecc.android.sdk.ledger.LedgerBluetoothDevice
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.LedgerDeviceDataSource
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.LedgerAccountBindingProvider
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentAccess
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentToken
import co.electriccoin.zcash.ui.common.repository.LedgerPairingRepository
import co.electriccoin.zcash.ui.common.repository.LedgerRepairTarget
import co.electriccoin.zcash.ui.common.repository.LedgerRepairTargetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pairs the ZIP 32 account the user chose on the device and stashes the result for the birthday
 * screens that follow. Returns the account the wallet already holds for that viewing key instead,
 * if any — the caller then shows the "Account Already Added" sheet rather than importing a
 * duplicate. An account the wallet holds without a Ledger binding gets the new pairing's binding
 * stored instead, so it can be signed for again.
 *
 * When the flow pairs an account again ([LedgerRepairTargetRepository] holds its UUID), the pairing
 * is stored with that account whatever binding it had, since the one it had may be unusable, and a
 * Ledger that exports any other viewing key is the wrong Ledger. The wallet holds one Ledger account
 * at most, so a viewing key no Ledger account has while another one exists is the wrong Ledger too;
 * nothing is stashed for import either way.
 */
class PairLedgerDeviceUseCase(
    private val ledgerDeviceDataSource: LedgerDeviceDataSource,
    private val ledgerPairingRepository: LedgerPairingRepository,
    private val ledgerRepairTargetRepository: LedgerRepairTargetRepository,
    private val accountDataSource: AccountDataSource,
    private val ledgerAccountBindingProvider: LedgerAccountBindingProvider,
    private val walletEnrollment: WalletEnrollmentAccess,
) {
    private val bindingMutex = Mutex()

    @Suppress("TooGenericExceptionCaught")
    suspend operator fun invoke(
        device: LedgerBluetoothDevice,
        zip32AccountIndex: Zip32AccountIndex,
    ): PairLedgerDeviceResult {
        val target = ledgerRepairTargetRepository.current()
        val owner = (target?.owner ?: walletEnrollment.capture())?.takeIf(walletEnrollment::permits)
            ?: return PairLedgerDeviceResult.Superseded
        return try {
            pairForTarget(device, zip32AccountIndex, target, owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (owns(target, owner)) throw e
            PairLedgerDeviceResult.Superseded
        }
    }

    private suspend fun pairForTarget(
        device: LedgerBluetoothDevice,
        zip32AccountIndex: Zip32AccountIndex,
        target: LedgerRepairTarget?,
        owner: WalletEnrollmentToken,
    ): PairLedgerDeviceResult {
        val pairing = ledgerDeviceDataSource.pair(device, zip32AccountIndex)
        if (!owns(target, owner)) return PairLedgerDeviceResult.Superseded
        val ledgerAccounts = accountDataSource.getAllAccounts().filterIsInstance<LedgerAccount>()
        val existing = ledgerAccounts.firstOrNull { account -> account.sdkAccount.ufvk == pairing.ufvk.encoding }
        return when {
            target != null && existing != null && existing.sdkAccount.accountUuid == target.accountUuid ->
                rebind(existing, pairing, target, owner)
            target == null && existing != null && !existing.isBound ->
                rebind(existing, pairing, null, owner)
            else -> stagePairing(target, owner, pairing, ledgerAccounts, existing)
        }
    }

    private fun owns(target: LedgerRepairTarget?, owner: WalletEnrollmentToken) =
        ledgerRepairTargetRepository.current() === target && walletEnrollment.permits(owner)

    private suspend fun stagePairing(
        target: LedgerRepairTarget?,
        owner: WalletEnrollmentToken,
        pairing: LedgerAccountPairing,
        ledgerAccounts: List<LedgerAccount>,
        existing: LedgerAccount?,
    ): PairLedgerDeviceResult = walletEnrollment.stage(owner) {
        when {
            !owns(target, owner) -> PairLedgerDeviceResult.Superseded
            target == null && ledgerAccounts.isEmpty() -> {
                ledgerPairingRepository.set(pairing, owner)
                PairLedgerDeviceResult.Paired
            }
            else -> {
                ledgerPairingRepository.clear()
                if (target == null && existing != null) {
                    PairLedgerDeviceResult.AlreadyAdded(existing)
                } else {
                    PairLedgerDeviceResult.WrongLedger
                }
            }
        }
    } ?: PairLedgerDeviceResult.Superseded

    private suspend fun rebind(
        existing: LedgerAccount,
        pairing: LedgerAccountPairing,
        target: LedgerRepairTarget?,
        owner: WalletEnrollmentToken,
    ): PairLedgerDeviceResult = bindingMutex.withLock {
        val uuid = existing.sdkAccount.accountUuid
        walletEnrollment.complete(owner) { synchronizer ->
            if (!owns(target, owner) || synchronizer.getAccounts().none { it.accountUuid == uuid }) {
                return@complete null
            }
            walletEnrollment.write(owner) {
                if (!owns(target, owner)) return@write null
                ledgerPairingRepository.clear()
                ledgerAccountBindingProvider.save(
                    accountUuid = uuid,
                    deviceIdentityEncoding = pairing.binding.deviceIdentity.encoding,
                    zip32AccountIndex = pairing.binding.zip32AccountIndex.index,
                )
                val ownsResult = if (target == null) owns(null, owner) else
                    walletEnrollment.permits(owner) && ledgerRepairTargetRepository.clear(target)
                PairLedgerDeviceResult.Rebound(existing).takeIf { ownsResult }
            }
        } ?: PairLedgerDeviceResult.Superseded
    }
}

sealed interface PairLedgerDeviceResult {
    data object Paired : PairLedgerDeviceResult

    data object Superseded : PairLedgerDeviceResult

    data class AlreadyAdded(
        val account: WalletAccount
    ) : PairLedgerDeviceResult

    /**
     * The pairing's binding is now stored with an account the wallet already held: one without a
     * Ledger binding, or the account being paired again.
     */
    data class Rebound(
        val account: WalletAccount
    ) : PairLedgerDeviceResult

    /**
     * The Ledger exported a viewing key other than the account being paired again, or a second
     * Ledger's while the wallet already holds one. Nothing was stored or stashed.
     */
    data object WrongLedger : PairLedgerDeviceResult
}
