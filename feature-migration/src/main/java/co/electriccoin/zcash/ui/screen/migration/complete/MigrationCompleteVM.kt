package co.electriccoin.zcash.ui.screen.migration.complete

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_RESIDUAL_MIN_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.requireSpendable
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.HasLockedOrchardDustStorageProvider
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationNotifier
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.GetIronwoodBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardBalanceUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.LockOrchardBalanceUseCase
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.migration.lockexplainer.MigrationLockExplainerArgs
import co.electriccoin.zcash.ui.screen.migration.success.MigrationSuccessArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.work.MigrationScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val lockOrchardBalance: LockOrchardBalanceUseCase,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val biometricRepository: BiometricRepository,
    private val proposalDataSource: ProposalDataSource,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val migrationScheduler: MigrationScheduler,
    private val migrationNotifier: MigrationNotifier,
) : ViewModel() {
    private data class Summary(
        val accountKeyId: String,
        val totalTransferred: Long,
        val totalCount: Int,
        val firstAt: Long,
        val lastAt: Long,
        val dustZatoshi: Long,
    )

    // Cached across a failed-then-retried "Migrate anyway" attempt so a GrpcFailure retry (see
    // immediateSubmitFailureMessage's kdoc in MigrationReviewVM, the reference implementation this
    // mirrors) resubmits the exact same already-built/signed Proposal instead of re-proposing (which
    // could pick different notes) or re-prompting biometrics.
    private data class MigrateAnywayProposal(
        val proposal: Proposal,
        val amountZatoshi: Zatoshi
    )

    private var pendingMigrateAnywayProposal: MigrateAnywayProposal? = null

    private val loadLce = mutableLce<Summary>()
    private val migrateAnywayLce = mutableLce<Unit>()

    // Locking runs inline on this screen (the "Lock balance" button) rather than in the explainer
    // sheet, so its loading/error state is kept OUT of groupLce below — a lock in progress shows a
    // spinner on the button, it must never blank the whole success screen behind a full-screen
    // loader, and a lock error simply re-enables the button for a retry.
    private val lockLce = mutableLce<Unit>()
    private val migrateAnywayFailure = MutableStateFlow<SubmitResult?>(null)

    private val completionOwnerKey = viewModelScope.async(start = CoroutineStart.LAZY) {
        args.accountKeyId ?: getSelectedWalletAccount().sdkAccount.accountUuid.toStorageKeyId()
    }

    init {
        loadLce.execute {
            val owner = activeCompletionOwner()
            val uuid = owner.sdkAccount.accountUuid
            val key = uuid.toStorageKeyId()
            migrationScheduler.cancel(key)
            migrationNotifier.cancel(key)
            migrationNotifier.cancelStepDue(key)
            val summary = if (args.isResidueOnly) {
                Summary(
                    accountKeyId = key,
                    totalTransferred = getIronwoodBalance(uuid).value,
                    totalCount = 0,
                    firstAt = 0L,
                    lastAt = 0L,
                    dustZatoshi = getOrchardBalance(uuid).value,
                )
            } else {
                val sdk = checkNotNull(getOrchardMigrationSdk(key)) { "Migration account is unavailable" }
                val migration = sdk.getMigrationSummary()
                Summary(
                    accountKeyId = key,
                    totalTransferred = migration?.totalMigratedZatoshi ?: 0L,
                    totalCount = migration?.transferCount ?: 0,
                    firstAt = migration?.firstMinedEpochSeconds ?: 0L,
                    lastAt = migration?.lastMinedEpochSeconds ?: 0L,
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
            migrateAnywayFailure,
        ) { lce, isLocked, migrateAnywayState, lockState, failure ->
            lce.success?.let { summary ->
                createState(summary, isLocked, migrateAnywayState.loading, lockState.loading, failure)
            }
        }.withLce(groupLce(loadLce, migrateAnywayLce), errorStateMapper::mapToState).stateIn(this)

    private fun createState(
        summary: Summary,
        isLocked: Boolean,
        isMigrating: Boolean,
        isLocking: Boolean,
        failure: SubmitResult?,
    ): MigrationCompleteState =
        MigrationCompleteState(
            totalTransferred = stringRes(Zatoshi(summary.totalTransferred)),
            remainingDust = if (summary.dustZatoshi > 0L) stringRes(Zatoshi(summary.dustZatoshi)) else null,
            isDustLocked = isLocked,
            transfersProgress =
                stringRes(
                    DesignR.string.migrationComplete_transfersProgress,
                    summary.totalCount,
                    summary.totalCount
                ),
            // The full campaign span between its first and last MINED transfer -- both timestamps
            // are already on-chain-public, so the pre-broadcast correlation risk the privacy floor
            // guards against (see formatMigrationDuration's kdoc) doesn't apply here; flooring it
            // would only inflate an already-short real duration up to a full hour on mainnet.
            duration =
                stringRes(
                    formatMigrationDuration(summary.lastAt - summary.firstAt, applyPrivacyFloor = false)
                ),
            isMigrating = isMigrating,
            isLocking = isLocking,
            isResidueOnly = args.isResidueOnly,
            onDone = ::onDone,
            onMigrateAnyway = { migrateAnywayLce.guardLoading(::onMigrateAnyway) },
            onLockBalance = { lockLce.guardLoading(::onLockBalance) },
            onHelp = ::onHelp,
            failureSheet =
                failure?.let {
                    MigrationTransferFailureState(
                        message = migrateAnywaySubmitFailureMessage(it),
                        // Only a GrpcFailure is safely resubmittable — see MigrationReviewVM's
                        // identical reasoning for onRetry there.
                        onRetry =
                            if (it is SubmitResult.GrpcFailure) {
                                {
                                    migrateAnywayFailure.value = null
                                    migrateAnywayLce.execute { retryMigrateAnyway() }
                                }
                            } else {
                                null
                            },
                        onDismiss = { migrateAnywayFailure.value = null },
                    )
                },
        )

    private fun migrateAnywaySubmitFailureMessage(result: SubmitResult): StringResource =
        when (result) {
            is SubmitResult.GrpcFailure -> stringRes(DesignR.string.migrationComplete_migrateAnywayFailureNetworkError)
            is SubmitResult.Failure -> stringRes(DesignR.string.migrationComplete_migrateAnywayFailureRejected)
            is SubmitResult.Error -> stringRes(DesignR.string.migrationComplete_migrateAnywayFailureError)
            is SubmitResult.Partial -> stringRes(DesignR.string.migrationComplete_migrateAnywayFailurePartial)
            is SubmitResult.Success -> error("migrateAnywaySubmitFailureMessage called with a Success result")
        }

    @Suppress("TooGenericExceptionCaught") // Leave acknowledgment available after a storage or account error.
    private fun onDone() {
        viewModelScope.launch {
            try {
                val owner = activeCompletionOwner()
                val uuid = owner.sdkAccount.accountUuid
                // A Keystone account can need another round even after the current one completes.
                val moreRoundsNeeded = owner is KeystoneAccount &&
                    getOrchardBalance(uuid).value >= MIGRATION_RESIDUAL_MIN_ZATOSHI
                if (!moreRoundsNeeded) {
                    hasSeenMigrationCompleteStorageProvider.store(uuid.toStorageKeyId(), true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                migrationLog("Migration acknowledgment unavailable (${e.javaClass.simpleName}).")
            }
            navigationRouter.backToRoot()
        }
    }

    private fun onLockBalance() =
        lockLce.execute {
            val key = activeCompletionOwner().sdkAccount.accountUuid.toStorageKeyId()
            lockOrchardBalance(key)
            hasLockedOrchardDustStorageProvider.store(key, true)
        }

    // The "?" in the top bar opens the lock explainer purely as information about what locking does.
    private fun onHelp() = navigationRouter.forward(MigrationLockExplainerArgs)

    // Mirrors MigrationReviewVM.confirmImmediate() — the canonical reference implementation for
    // sweeping a residual balance via the IMMEDIATE-mode send-max Proposal. Unlike Review, there's
    // no separate propose-then-confirm split here: propose and submit both happen in this single
    // user-triggered action, since this screen never shows a review step of its own for it.
    private fun onMigrateAnyway() =
        migrateAnywayLce.execute {
            val owner = activeCompletionOwner()
            try {
                biometricRepository.requestBiometrics(
                    request =
                        BiometricRequest(
                            message =
                                stringRes(
                                    R.string.authentication_system_ui_subtitle,
                                    stringRes(R.string.authentication_use_case_send_funds)
                                )
                        )
                )
            } catch (_: BiometricsFailureException) {
                return@execute
            } catch (_: BiometricsCancelledException) {
                return@execute
            }
            activeCompletionOwner()
            val uuid = owner.sdkAccount.accountUuid
            val sdk = checkNotNull(getOrchardMigrationSdk(uuid.toStorageKeyId())) { "Migration account is unavailable" }
            val amount = getOrchardBalance(uuid)
            val proposal = sdk.proposeImmediateMigration()
            pendingMigrateAnywayProposal = MigrateAnywayProposal(proposal, amount)
            submitMigrateAnyway(proposal, amount)
        }

    private suspend fun retryMigrateAnyway() {
        val cached = pendingMigrateAnywayProposal ?: return
        submitMigrateAnyway(cached.proposal, cached.amountZatoshi)
    }

    private suspend fun submitMigrateAnyway(proposal: Proposal, amountZatoshi: Zatoshi) {
        val owner = activeCompletionOwner()
        if (owner is LedgerAccount) {
            throw AccountOperationUnsupportedException.ledger()
        }
        owner.requireSpendable()
        if (owner is KeystoneAccount) {
            // Keystone can't sign in-process — adopt the already-built send-max proposal into the
            // app's existing generic external-signer pipeline exactly as an ordinary Keystone send
            // does (no migration-specific PCZT/QR machinery — one ordinary PCZT, same as any
            // regular Keystone send).
            keystoneProposalRepository.setMigrationSweepProposal(proposal, amountZatoshi)
            // Required before navigating — SignKeystoneTransactionVM's QR encoder is built from the
            // already-created PCZT (createPCZTEncoder() reads KeystoneProposalRepository's cached
            // proposalPczt); it never calls createPCZTFromProposal() itself.
            keystoneProposalRepository.createPCZTFromProposal(owner)
            activeCompletionOwner()
            keystoneProposalRepository.approveForSigning()
            navigationRouter.forward(SignKeystoneTransactionArgs)
            return
        }
        val usk = zashiSpendingKeyDataSource.getZashiSpendingKey()
        activeCompletionOwner()
        val result =
            withContext(NonCancellable) {
                proposalDataSource.submitTransaction(proposal, usk)
            }
        when (result) {
            is SubmitResult.Success -> navigationRouter.forward(MigrationSuccessArgs(result.txIds.lastOrNull()))
            else -> migrateAnywayFailure.value = result
        }
    }
}
