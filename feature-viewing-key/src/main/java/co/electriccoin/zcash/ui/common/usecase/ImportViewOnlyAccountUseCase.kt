package co.electriccoin.zcash.ui.common.usecase

import androidx.compose.ui.graphics.Color
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccountAlreadyExistsException
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccountImportException
import co.electriccoin.zcash.ui.common.model.ViewOnlyPendingImportMissingException
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentAccess
import co.electriccoin.zcash.ui.common.provider.WalletEnrollmentToken
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.PendingViewOnlyImport
import co.electriccoin.zcash.ui.common.repository.ViewOnlyPendingImportRepository
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenFlow
import co.electriccoin.zcash.ui.screen.viewingkey.alreadyimported.AlreadyImportedVKArgs
import co.electriccoin.zcash.ui.screen.viewingkey.imported.ImportedVKArgs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Imports the pending viewing key under its name at its birthday, selects the new account,
 * forgets the key and restarts the synchronizer, then moves on: straight to the success screen when
 * the account imports from the chain tip, through the keep-open screen when it has history to scan.
 * A key the wallet turns out to hold already ends the flow on the "already imported" sheet.
 *
 * Imports run one at a time. A call that finds the pending import it started with already taken by
 * the call before it (a double tap) returns without doing anything, so it neither imports the key a
 * second time nor reports the account the first call just created as already imported. Registered
 * as a single, so every caller shares the one lock.
 *
 * Once admitted, the import and its finalization run across caller cancellation. If selection
 * fails, the pending import retains the created account so retry can finish selecting it. A
 * cancelled caller skips navigation.
 */
internal class ImportViewOnlyAccountUseCase(
    private val pendingImportRepository: ViewOnlyPendingImportRepository,
    private val accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
    private val navigationRouter: NavigationRouter,
    private val metadataRepository: MetadataRepository,
    private val walletEnrollment: WalletEnrollmentAccess,
) {
    private val mutex = Mutex()

    /**
     * @throws ViewOnlyPendingImportMissingException if the pending import is gone, typically after
     *         process death.
     * @throws ViewOnlyAccountImportException if the SDK rejected the key.
     */
    suspend operator fun invoke(sourceEntryId: String? = null) {
        val requested = pendingImportRepository.get() ?: throw ViewOnlyPendingImportMissingException()
        mutex.withLock {
            if (pendingImportRepository.get() === requested) import(requested, sourceEntryId)
        }
    }

    private suspend fun import(pending: PendingViewOnlyImport, sourceEntryId: String?) {
        val enrollment = pending.enrollment
        if (enrollment == null || !walletEnrollment.permits(enrollment)) {
            pendingImportRepository.replace(pending, null)
            throw ViewOnlyPendingImportMissingException()
        }
        val route = walletEnrollment.complete(enrollment) { synchronizer ->
            if (pendingImportRepository.get() === pending) {
                importAndSelect(pending, enrollment, synchronizer)
            } else {
                null
            }
        }
        currentCoroutineContext().ensureActive()
        if (!walletEnrollment.permits(enrollment)) {
            pendingImportRepository.get()?.takeIf { it.enrollment == enrollment }?.let {
                pendingImportRepository.replace(it, null)
            }
            throw ViewOnlyPendingImportMissingException()
        }
        route?.let { destination ->
            navigationRouter.stageResultFrom(sourceEntryId, { walletEnrollment.permits(enrollment) }) {
                NavigationCommand.Forward(listOf(destination))
            }
        }
    }

    /**
     * Imports and selects the account, forgets the key and restarts the synchronizer, and returns
     * where the flow goes next.
     */
    private suspend fun importAndSelect(
        pending: PendingViewOnlyImport,
        enrollment: WalletEnrollmentToken,
        synchronizer: Synchronizer,
    ): Any? {
        val approved =
            try {
                pending.takeIf { it.importedAccount != null } ?: importAccount(pending, enrollment, synchronizer)
            } catch (_: ViewOnlyAccountAlreadyExistsException) {
                return AlreadyImportedVKArgs.takeIf { pendingImportRepository.replace(pending, null) }
            }
        return approved?.let { finishImport(it, enrollment) }
    }

    private suspend fun importAccount(
        pending: PendingViewOnlyImport,
        enrollment: WalletEnrollmentToken,
        synchronizer: Synchronizer,
    ): PendingViewOnlyImport? {
        val created = accountDataSource.importViewOnlyAccount(
            synchronizer = synchronizer,
            ufvk = pending.ufvk,
            name = pending.name,
            birthday = pending.birthday,
        )
        val approved = pending.copy(importedAccount = created)
        if (pendingImportRepository.replace(pending, approved)) return approved
        if (walletEnrollment.permits(enrollment)) synchronizerProvider.resetSynchronizer()
        return null
    }

    private suspend fun finishImport(approved: PendingViewOnlyImport, enrollment: WalletEnrollmentToken): Any? {
        val created = checkNotNull(approved.importedAccount)
        val selected = walletEnrollment.write(enrollment) {
            if (pendingImportRepository.get() !== approved) return@write false
            accountDataSource.selectAccount(created)
            true
        } == true
        if (!selected) return null
        approved.color?.let { storeColor(created, it, enrollment) }
        if (walletEnrollment.permits(enrollment)) synchronizerProvider.resetSynchronizer()
        return when {
            !pendingImportRepository.replace(approved, null) -> null
            approved.birthday != null -> KeepOpenArgs(KeepOpenFlow.VIEW_ONLY)
            else -> ImportedVKArgs
        }
    }

    /**
     * Stores [color] for [created] once the wallet lists it. The wait and the write together get at
     * most [COLOR_BUDGET], so a stuck store never holds back the synchronizer restart; running out of
     * it is only logged.
     */
    private suspend fun storeColor(
        created: Account,
        color: Color,
        enrollment: WalletEnrollmentToken,
    ) {
        withTimeoutOrNull(COLOR_BUDGET) {
            val account =
                accountDataSource.allAccounts
                    .mapNotNull { accounts ->
                        accounts?.firstOrNull { it.sdkAccount.accountUuid == created.accountUuid }
                    }.first()
            metadataRepository.setAccountColor(account, color, enrollment)
        } ?: Twig.warn { "View-only import: the account colour was not stored within $COLOR_BUDGET" }
    }

    private companion object {
        /**
         * The colour is cosmetic and the user waits behind it, so the whole step keeps the cap the
         * wait alone had; the account normally lists at once and the write is one small file.
         */
        val COLOR_BUDGET = 5.seconds
    }
}
