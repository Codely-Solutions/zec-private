package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.BlockHeight
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.common.ledger.LedgerAccountImporter
import co.electriccoin.zcash.ui.common.model.LedgerPairingMissingException
import co.electriccoin.zcash.ui.common.model.ViewOnlyPendingImportMissingException
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyAccountImporter
import co.electriccoin.zcash.ui.screen.connecthw.CommittedEnrollmentBirthday
import co.electriccoin.zcash.ui.screen.connecthw.HWWalletEnrollment
import co.electriccoin.zcash.ui.screen.connecthw.KeystoneEnrollmentSession
import co.electriccoin.zcash.ui.screen.connectkeystone.connected.KeystoneConnectedArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenFlow
import co.electriccoin.zcash.ui.screen.scankeystone.ScanKeystoneSignInRequest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one place the shared enrollment screens dispatch on vendor: everything else about them —
 * the birthday question, the estimation, the manual height, the Lce wiring — is identical.
 */
class CreateHWWalletAccountUseCase(
    private val keystoneSession: KeystoneEnrollmentSession,
    private val createKeystoneAccount: CreateKeystoneAccountUseCase,
    private val ledgerAccountImporter: LedgerAccountImporter,
    private val viewOnlyAccountImporter: ViewOnlyAccountImporter,
    private val navigationRouter: NavigationRouter,
) {
    /**
     * Pending account data is held in memory and expires when the process ends.
     */
    fun isReady(enrollment: HWWalletEnrollment): Boolean =
        when (enrollment) {
            is HWWalletEnrollment.Keystone -> {
                keystoneSession.resolve(enrollment.sessionId)?.accounts?.isNotEmpty() == true
            }

            is HWWalletEnrollment.Ledger -> {
                ledgerAccountImporter.hasPendingPairing()
            }

            is HWWalletEnrollment.ViewOnly -> {
                viewOnlyAccountImporter.hasPendingImport()
            }
        }

    fun committedBirthday(enrollment: HWWalletEnrollment): CommittedEnrollmentBirthday? =
        when (enrollment) {
            is HWWalletEnrollment.Keystone ->
                keystoneSession.enrollment(enrollment.sessionId)?.imported?.value
                    ?.let { CommittedEnrollmentBirthday(it.birthday, it.account) }
            HWWalletEnrollment.Ledger -> ledgerAccountImporter.committedBirthday()
            HWWalletEnrollment.ViewOnly -> viewOnlyAccountImporter.committedBirthday()
        }

    fun observeCommittedBirthday(enrollment: HWWalletEnrollment): Flow<CommittedEnrollmentBirthday?> =
        when (enrollment) {
            is HWWalletEnrollment.Keystone ->
                keystoneSession.enrollment(enrollment.sessionId)?.imported?.map { imported ->
                    imported?.let { CommittedEnrollmentBirthday(it.birthday, it.account) }
                } ?: flowOf(null)
            HWWalletEnrollment.Ledger -> ledgerAccountImporter.committedBirthdays
            HWWalletEnrollment.ViewOnly -> viewOnlyAccountImporter.committedBirthdays
        }

    suspend fun finish(
        enrollment: HWWalletEnrollment,
        birthday: CommittedEnrollmentBirthday,
        sourceEntryId: String? = null,
    ) {
        if (committedBirthday(enrollment) != birthday) return
        if (enrollment == HWWalletEnrollment.ViewOnly) {
            viewOnlyAccountImporter.finishImport(sourceEntryId)
        } else {
            invoke(enrollment, birthday.height, sourceEntryId)
        }
    }

    /**
     * Imports the hardware-wallet account being enrolled at [birthday]. A view-only import is not
     * imported here: its birthday is stored with the pending key and the flow goes on to name the
     * account, which imports it. A Ledger pairing or a pending view-only key that went missing since
     * the screen opened cannot come back by trying again, so the user is returned to the wallet root
     * instead of an error that offers a retry.
     */
    suspend operator fun invoke(
        enrollment: HWWalletEnrollment,
        birthday: BlockHeight?,
        sourceEntryId: String? = null,
    ) {
        if (committedBirthday(enrollment)?.let { it.height != birthday } == true) return
        when (enrollment) {
            is HWWalletEnrollment.Keystone -> {
                importKeystone(enrollment.sessionId, birthday, sourceEntryId)
            }

            is HWWalletEnrollment.Ledger -> {
                try {
                    ledgerAccountImporter.importAccount(birthday, sourceEntryId)
                } catch (_: LedgerPairingMissingException) {
                    navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.BackToRoot }
                }
            }

            is HWWalletEnrollment.ViewOnly -> {
                try {
                    viewOnlyAccountImporter.continueWithBirthday(birthday, sourceEntryId)
                } catch (_: ViewOnlyPendingImportMissingException) {
                    navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.BackToRoot }
                }
            }
        }
    }

    private suspend fun importKeystone(sessionId: String, birthday: BlockHeight?, sourceEntryId: String?) {
        val pending = keystoneSession.enrollment(sessionId)
        val account = pending?.accounts?.accounts?.firstOrNull()
        if (pending == null || account == null) {
            navigationRouter.stageResultFrom(sourceEntryId) {
                NavigationCommand.ReplaceFrom(ScanKeystoneSignInRequest::class, listOf(ScanKeystoneSignInRequest))
            }
            return
        }
        keystoneSession.importMutex.withLock {
            val imported = pending.imported.value
            val birthdayChanged = imported != null && imported.birthday != birthday
            if (birthdayChanged || keystoneSession.enrollment(sessionId) !== pending) return@withLock
            val approvedBirthday = withContext(NonCancellable) {
                createKeystoneAccount(pending, account, birthday)
            }
            if (keystoneSession.enrollment(sessionId) === pending) {
                currentCoroutineContext().ensureActive()
                val next = if (approvedBirthday == null) KeystoneConnectedArgs else KeepOpenArgs(KeepOpenFlow.KEYSTONE)
                navigationRouter.stageResultFrom(sourceEntryId, { keystoneSession.enrollment(sessionId) === pending }) {
                    keystoneSession.clear(sessionId)
                    NavigationCommand.ReplaceFrom(ScanKeystoneSignInRequest::class, listOf(next))
                }
            }
        }
    }
}
