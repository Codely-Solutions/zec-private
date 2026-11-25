package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.ledger.LedgerAccountPairing
import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.LedgerPairingMissingException
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentAccess
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentToken
import co.electriccoin.zcash.ui.common.repository.ImportedLedgerAccount
import co.electriccoin.zcash.ui.common.repository.LedgerPairingRepository
import co.electriccoin.zcash.ui.screen.connectledger.connected.LedgerConnectedArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CreateLedgerAccountUseCase(
    private val accountDataSource: AccountDataSource,
    private val ledgerPairingRepository: LedgerPairingRepository,
    private val synchronizerProvider: SynchronizerProvider,
    private val navigationRouter: NavigationRouter,
    private val walletEnrollment: WalletEnrollmentAccess,
) {
    private val mutex = Mutex()

    /**
     * @throws LedgerPairingMissingException if the pending pairing is gone — the flow was resumed
     *         after process death, and the device has to be paired again.
     * @throws InitializeException.ImportAccountCollisionException if the wallet already holds an
     *         account sharing a viewing key component with the paired one; trying again cannot
     *         succeed, and the pairing is kept, as for any other failed import.
     */
    @Throws(
        InitializeException.ImportAccountException::class,
        InitializeException.ImportAccountCollisionException::class,
    )
    suspend operator fun invoke(birthday: BlockHeight? = null, sourceEntryId: String? = null) {
        val pairing = ledgerPairingRepository.get() ?: throw LedgerPairingMissingException()
        val owner = ledgerPairingRepository.owner(pairing)?.takeIf(walletEnrollment::permits)
            ?: throw LedgerPairingMissingException()
        mutex.withLock {
            if (ledgerPairingRepository.get() !== pairing) return
            if (ledgerPairingRepository.importedAccount(pairing)?.let { it.birthday != birthday } == true) return
            val route = walletEnrollment.complete(owner) { sync ->
                if (ledgerPairingRepository.owner(pairing) == owner) {
                    importAndSelect(sync, pairing, birthday, owner)
                } else {
                    null
                }
            }
            currentCoroutineContext().ensureActive()
            route?.let { destination ->
                navigationRouter.stageResultFrom(sourceEntryId, { walletEnrollment.permits(owner) }) { NavigationCommand.Forward(listOf(destination)) }
            }
        }
    }

    private suspend fun importAndSelect(
        sync: Synchronizer,
        pairing: LedgerAccountPairing,
        birthday: BlockHeight?,
        owner: WalletEnrollmentToken,
    ): Any? {
        val imported = ledgerPairingRepository.importedAccount(pairing) ?: importAccount(sync, pairing, birthday, owner)
        if (imported == null || !selectImported(pairing, imported, owner)) return null
        return when {
            !ledgerPairingRepository.clear(pairing) -> null
            imported.birthday == null -> LedgerConnectedArgs
            else -> KeepOpenArgs(KeepOpenFlow.LEDGER)
        }
    }

    private suspend fun selectImported(
        pairing: LedgerAccountPairing,
        imported: ImportedLedgerAccount,
        owner: WalletEnrollmentToken,
    ): Boolean = walletEnrollment.write(owner) {
        if (ledgerPairingRepository.owner(pairing) != owner) return@write false
        accountDataSource.selectAccount(imported.account)
        synchronizerProvider.resetSynchronizer()
        true
    } == true

    private suspend fun importAccount(
        sync: Synchronizer,
        pairing: LedgerAccountPairing,
        birthday: BlockHeight?,
        owner: WalletEnrollmentToken,
    ): ImportedLedgerAccount? {
        val imported = ImportedLedgerAccount(
            account = accountDataSource.importLedgerAccount(sync, pairing, birthday),
            birthday = birthday,
        )
        if (ledgerPairingRepository.recordImport(pairing, imported)) return imported
        if (walletEnrollment.permits(owner)) synchronizerProvider.resetSynchronizer()
        return null
    }
}
