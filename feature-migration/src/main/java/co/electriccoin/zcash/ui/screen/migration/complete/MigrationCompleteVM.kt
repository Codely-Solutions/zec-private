package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_RESIDUAL_MIN_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.requireSpendable
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetIronwoodBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetMigrationSnapshotUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.LockOrchardBalanceUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.lockexplainer.MigrationLockExplainerArgs
import co.electriccoin.zcash.ui.screen.migration.review.MigrationReviewArgs
import co.electriccoin.zcash.ui.stageResultFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val navigationRouter: NavigationRouter,
    private val errorStateMapper: ErrorMapperUseCase,
    private val getMigrationSnapshot: GetMigrationSnapshotUseCase,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val lockOrchardBalance: LockOrchardBalanceUseCase,
    private val sourceEntryId: String? = null,
) : ViewModel() {
    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }

    private data class Summary(
        val accountUuid: AccountUuid,
        val canMigrateResidue: Boolean,
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
    private val lockLce = mutableLce<Boolean>()

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
                    accountUuid = uuid,
                    canMigrateResidue = owner.isSpendable && owner !is LedgerAccount,
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
                    accountUuid = uuid,
                    canMigrateResidue = owner.isSpendable && owner !is LedgerAccount,
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
                state.success?.accountUuid?.let(getOrchardBalance::observe) ?: flowOf(null)
            },
            migrateAnywayLce.state,
            lockLce.state,
        ) { lce, available, migrateAnywayState, lockState ->
            val lockNotice = when {
                lockState.success == true -> stringRes(DesignR.string.migrationComplete_lockRequestCompleted)
                lockState.error != null -> stringRes(DesignR.string.migrationComplete_lockRequestFailed)
                else -> null
            }
            lce.success?.let { summary ->
                createState(
                    summary.copy(dustZatoshi = available?.value ?: summary.dustZatoshi),
                    lockNotice, migrateAnywayState.loading, lockState.loading,
                )
            }
        }.withLce(groupLce(loadLce, migrateAnywayLce), errorStateMapper::mapToState).stateIn(this)

    private fun createState(
        summary: Summary,
        lockNotice: StringResource?,
        isMigrating: Boolean,
        isLocking: Boolean,
    ): MigrationCompleteState =
        MigrationCompleteState(
            canShowCompletion = summary.canShowCompletion,
            canMigrateResidue = summary.canMigrateResidue,
            totalTransferred = summary.totalTransferred?.let { stringRes(Zatoshi(it)) },
            remainingDust = if (summary.dustZatoshi > 0L) stringRes(Zatoshi(summary.dustZatoshi)) else null,
            lockNotice = lockNotice,
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
            ownsCompletionEntry && lockOrchardBalance(key) {
                matchesReviewedResidue(reviewedDust)
            }
        }

    // The "?" in the top bar opens the lock explainer purely as information about what locking does.
    private fun onHelp() = navigationRouter.forward(MigrationLockExplainerArgs)

    private fun onMigrateAnyway(reviewedDust: Long) =
        migrateAnywayLce.execute {
            val owner = activeCompletionOwner()
            if (!ownsCompletionEntry) return@execute
            if (owner is LedgerAccount) throw AccountOperationUnsupportedException.ledger()
            owner.requireSpendable()
            if (!matchesReviewedResidue(reviewedDust)) return@execute
            val key = owner.sdkAccount.accountUuid.toStorageKeyId()
            check(args.isResidueOnly || hasCompletionEvidence(key)) {
                "The migration has not completed."
            }
            activeCompletionOwner()
            navigationRouter.stageResultFrom(sourceEntryId, { ownsCompletionEntry }) {
                NavigationCommand.Forward(listOf(MigrationReviewArgs(MigrationMode.IMMEDIATE, key)))
            }
        }

    private suspend fun matchesReviewedResidue(reviewed: Long): Boolean {
        val account = activeCompletionOwner().sdkAccount.accountUuid
        val balance = getOrchardBalance(account).value
        activeCompletionOwner()
        return ownsCompletionEntry && balance == reviewed && reviewed > 0
    }

    private val ownsCompletionEntry
        get() = sourceEntryId == null || navigationRouter.activeEntry.value?.id == sourceEntryId

    private suspend fun hasCompletionEvidence(key: String): Boolean {
        val snapshot = getMigrationSnapshot(key)
        return snapshot?.areAllMined ?: (
            getOrchardMigrationSdk(key)?.getMigrationStateUnreconciled() == MigrationState.Complete
        )
    }

}
