package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_RESIDUAL_MIN_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.requireSpendable
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.HasLockedOrchardDustStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetIronwoodBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationSnapshotUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.LockOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.lockexplainer.MigrationLockExplainerArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.stageResultFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import co.electriccoin.zcash.ui.design.R as DesignR

class MigrationCompleteVM(
    private val args: MigrationCompleteArgs,
    private val getOrchardBalance: GetOrchardBalanceUseCase,
    private val getIronwoodBalance: GetIronwoodBalanceUseCase,
    private val hasSeenMigrationCompleteStorageProvider: HasSeenMigrationCompleteStorageProvider,
    private val hasLockedOrchardDustStorageProvider: HasLockedOrchardDustStorageProvider,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val getMigrationSnapshot: GetMigrationSnapshotUseCase,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val lockOrchardBalance: LockOrchardBalanceUseCase,
    private val biometricRepository: BiometricRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val submitProposal: SubmitProposalUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }

    private data class Summary(
        val accountKeyId: String,
        val canShowCompletion: Boolean,
        val totalTransferred: Long?,
        val totalCount: Int?,
        val durationSeconds: Long?,
        val dustZatoshi: Long,
    )

    private val loadLce = mutableLce<Summary>()
    private val migrateAnywayLce = mutableLce<Unit>()

    // Locking runs inline on this screen (the "Lock balance" button) rather than in the explainer
    // sheet, so its loading/error state is kept OUT of groupLce below — a lock in progress shows a
    // spinner on the button, it must never blank the whole success screen behind a full-screen
    // loader, and a lock error simply re-enables the button for a retry.
    private val lockLce = mutableLce<Unit>()
    private val refreshedDust = MutableStateFlow<Long?>(null)

    private val completionOwnerKey = viewModelScope.async(start = CoroutineStart.LAZY) {
        args.accountKeyId ?: getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
    }

    init {
        loadLce.execute {
            val owner = activeCompletionOwner()
            val uuid = owner.sdkAccount.accountUuid
            val key = uuid.toStorageKeyId()
            val summary = if (args.isResidueOnly) {
                Summary(
                    accountKeyId = key,
                    canShowCompletion = true,
                    totalTransferred = getIronwoodBalance(uuid).value,
                    totalCount = null,
                    durationSeconds = null,
                    dustZatoshi = getOrchardBalance(uuid).value,
                )
            } else {
                // The pinned SDK's historical summary combines runs and accounts. Only a
                // current snapshot requested for this account can supply these figures.
                val snapshot = getMigrationSnapshot(key)
                val transfers = snapshot?.takeIf { it.areAllMined }?.transfers
                val canShowCompletion = transfers != null || (snapshot == null &&
                    getOrchardMigrationSdk(key)?.getMigrationStateUnreconciled() == MigrationState.Complete)
                val minedAt = transfers?.let { rows -> rows.mapNotNull { it.minedAt }.takeIf { it.size == rows.size } }
                Summary(
                    accountKeyId = key,
                    canShowCompletion = canShowCompletion,
                    totalTransferred = transfers?.takeIf { it.all { row -> row.amountZatoshi > 0L } }
                        ?.sumOf { it.amountZatoshi },
                    totalCount = transfers?.size,
                    durationSeconds = minedAt?.takeIf { it.isNotEmpty() }
                        ?.let { (it.max() - it.min()).inWholeSeconds },
                    dustZatoshi = getOrchardBalance(uuid).value,
                )
            }
            activeCompletionOwner()
            summary
        }
    }

    private suspend fun activeCompletionOwner(): WalletAccount {
        val key = completionOwnerKey.await()
        val owner = getSelectedWalletAccount()
        check(owner.sdkAccount.accountUuid.toStorageKeyId() == key) {
            "The migration belongs to a different account"
        }
        return owner
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LceState<MigrationCompleteState>> =
        combine(
            loadLce.state,
            loadLce.state.flatMapLatest { state ->
                state.success?.accountKeyId?.let(hasLockedOrchardDustStorageProvider::observe) ?: flowOf(false)
            },
            migrateAnywayLce.state,
            lockLce.state,
            refreshedDust,
        ) { lce, isLocked, migrateAnywayState, lockState, latestDust ->
            lce.success?.let { summary ->
                createState(
                    summary.copy(dustZatoshi = latestDust ?: summary.dustZatoshi),
                    isLocked, migrateAnywayState.loading, lockState.loading,
                )
            }
        }.withLce(groupLce(loadLce, migrateAnywayLce), errorStateMapper::mapToState).stateIn(this)

    private fun createState(
        summary: Summary,
        isLocked: Boolean,
        isMigrating: Boolean,
        isLocking: Boolean,
    ): MigrationCompleteState =
        MigrationCompleteState(
            canShowCompletion = summary.canShowCompletion,
            totalTransferred = summary.totalTransferred?.let { stringRes(Zatoshi(it)) },
            remainingDust = if (summary.dustZatoshi > 0L) stringRes(Zatoshi(summary.dustZatoshi)) else null,
            isDustLocked = isLocked,
            transfersProgress = summary.totalCount?.let {
                stringRes(DesignR.string.migrationComplete_transfersProgress, it, it)
            },
            duration = summary.durationSeconds?.let {
                stringRes(formatMigrationDuration(it, applyPrivacyFloor = false))
            },
            isMigrating = isMigrating,
            isLocking = isLocking,
            isResidueOnly = args.isResidueOnly,
            onDone = ::onDone,
            onMigrateAnyway = { migrateAnywayLce.guardLoading { onMigrateAnyway(summary.dustZatoshi) } },
            onLockBalance = { lockLce.guardLoading { onLockBalance(summary.dustZatoshi) } },
            onHelp = ::onHelp,
        )

    @Suppress("TooGenericExceptionCaught") // Leave acknowledgment available after a storage or account error.
    private fun onDone() {
        viewModelScope.launch {
            try {
                val owner = activeCompletionOwner()
                val uuid = owner.sdkAccount.accountUuid
                // A Keystone account can need another round even after the current one completes.
                val moreRoundsNeeded = owner is KeystoneAccount &&
                    getOrchardBalance(uuid).value >= MIGRATION_RESIDUAL_MIN_ZATOSHI
                if (!moreRoundsNeeded && (args.isResidueOnly || hasCompletionEvidence(uuid.toStorageKeyId()))) {
                    hasSeenMigrationCompleteStorageProvider.store(uuid.toStorageKeyId(), true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                migrationLog("Migration acknowledgment unavailable (${e.javaClass.simpleName}).")
            }
            navigationRouter.stageResultFrom(sourceEntryId) { NavigationCommand.BackToRoot }
        }
    }

    private fun onLockBalance(reviewedDust: Long) =
        lockLce.execute {
            val key = activeCompletionOwner().sdkAccount.accountUuid.toStorageKeyId()
            val locked = ownsCompletionEntry() && lockOrchardBalance(key) {
                matchesReviewedResidue(reviewedDust)
            }
            if (locked) hasLockedOrchardDustStorageProvider.store(key, true)
        }

    // The "?" in the top bar opens the lock explainer purely as information about what locking does.
    private fun onHelp() = navigationRouter.forward(MigrationLockExplainerArgs)

    private fun onMigrateAnyway(reviewedDust: Long) =
        migrateAnywayLce.execute {
            val owner = activeCompletionOwner()
            if (!ownsCompletionEntry()) return@execute
            if (owner is LedgerAccount) throw AccountOperationUnsupportedException.ledger()
            owner.requireSpendable()
            if (!matchesReviewedResidue(reviewedDust)) return@execute
            if (owner is KeystoneAccount && !authenticateMigrateAnyway()) return@execute
            activeCompletionOwner()

            val key = owner.sdkAccount.accountUuid.toStorageKeyId()
            check(args.isResidueOnly || hasCompletionEvidence(key)) {
                "The migration has not completed."
            }
            val sdk = checkNotNull(getOrchardMigrationSdk(key)) { "Migration account is unavailable" }
            val proposal = sdk.proposeImmediateMigration()
            if (!matchesReviewedResidue(reviewedDust)) return@execute
            if (owner is KeystoneAccount) {
                val reviewed = keystoneProposalRepository.setMigrationSweepProposal(proposal)
                keystoneProposalRepository.createPCZTFromProposal(owner)
                if (!matchesReviewedResidue(reviewedDust)) {
                    keystoneProposalRepository.clear(reviewed)
                    return@execute
                }
                keystoneProposalRepository.approveForSigning(reviewed)
                navigationRouter.stageResultFrom(sourceEntryId, {
                    keystoneProposalRepository.transactionProposal.value === reviewed
                }) {
                    NavigationCommand.Forward(listOf(SignKeystoneTransactionArgs))
                }
            } else {
                // The normal transaction progress path retains submission identity and lets
                // the SDK reconcile its original transaction after an uncertain result.
                val reviewed = zashiProposalRepository.setMigrationSweepProposal(proposal)
                submitProposal(reviewed, sourceEntryId = sourceEntryId)
            }
        }

    private suspend fun matchesReviewedResidue(reviewed: Long): Boolean {
        val account = activeCompletionOwner().sdkAccount.accountUuid
        val balance = getOrchardBalance(account).value
        activeCompletionOwner()
        if (balance != reviewed) refreshedDust.value = balance
        return ownsCompletionEntry() && balance == reviewed && reviewed > 0
    }

    private fun ownsCompletionEntry() = sourceEntryId == null || navigationRouter.activeEntry.value?.id == sourceEntryId

    private suspend fun authenticateMigrateAnyway(): Boolean =
        try {
            biometricRepository.requestBiometrics(
                BiometricRequest(
                    message = stringRes(
                        R.string.authentication_system_ui_subtitle,
                        stringRes(R.string.authentication_use_case_send_funds)
                    )
                )
            )
            true
        } catch (_: BiometricsFailureException) {
            false
        } catch (_: BiometricsCancelledException) {
            false
        }

    private suspend fun hasCompletionEvidence(key: String): Boolean {
        val snapshot = getMigrationSnapshot(key)
        return snapshot?.areAllMined ?: (
            getOrchardMigrationSdk(key)?.getMigrationStateUnreconciled() == MigrationState.Complete
        )
    }

}
