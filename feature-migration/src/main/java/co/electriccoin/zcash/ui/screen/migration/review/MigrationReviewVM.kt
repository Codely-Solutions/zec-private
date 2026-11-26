package co.electriccoin.zcash.ui.screen.migration.review

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.MigrationSchedule
import cash.z.ecc.android.sdk.MigrationState
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.TransferProposal
import cash.z.ecc.android.sdk.TransferResult
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.migration.migrationLog
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.awaitForegroundAtEntry
import co.electriccoin.zcash.ui.commitFrom
import co.electriccoin.zcash.ui.discardResultFrom
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LceState
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.groupLce
import co.electriccoin.zcash.ui.common.model.guardLoading
import co.electriccoin.zcash.ui.common.model.migration.MigrationKeystoneRound
import co.electriccoin.zcash.ui.common.model.migration.MigrationMode
import co.electriccoin.zcash.ui.common.model.migration.MigrationPreparationDetails
import co.electriccoin.zcash.ui.common.model.migration.MigrationPreparationStepDetail
import co.electriccoin.zcash.ui.common.model.migration.MigrationTransferFailureState
import co.electriccoin.zcash.ui.common.model.migration.estimatedSecondsBetweenHeights
import co.electriccoin.zcash.ui.common.model.migration.formatMigrationDuration
import co.electriccoin.zcash.ui.common.model.migration.migrationFailureMessage
import co.electriccoin.zcash.ui.common.model.migration.preparationStepStatus
import co.electriccoin.zcash.ui.common.model.migration.preparationStepTimeLabel
import co.electriccoin.zcash.ui.common.model.migration.preparationStepTitle
import co.electriccoin.zcash.ui.common.model.migration.rateReferenceHeight
import co.electriccoin.zcash.ui.common.model.mutableLce
import co.electriccoin.zcash.ui.common.model.requireSpendable
import co.electriccoin.zcash.ui.common.model.stateIn
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.model.withLce
import co.electriccoin.zcash.ui.common.provider.IsMigrationTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletActionAccess
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepository
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.repository.RestartMigrationScheduleRepository
import co.electriccoin.zcash.ui.common.repository.ReviewedMigrationSchedule
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.FinalizeMigrationScheduleUseCase
import co.electriccoin.zcash.ui.common.usecase.GetOrchardMigrationSdkUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.SubmitProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.enhanceFinalizedSplit
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicCurrencyNumber
import co.electriccoin.zcash.ui.screen.migration.keystonesign.MigrationKeystoneSignArgs
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledArgs
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.work.MigrationDriveOnce
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.MathContext
import co.electriccoin.zcash.ui.design.R as DesignR

// Protocol target block interval in seconds, used as the fallback until a measured rate is
// captured at propose time.
private const val DEFAULT_SECONDS_PER_BLOCK = 75L

class MigrationReviewVM(
    private val args: MigrationReviewArgs,
    private val getOrchardMigrationSdk: GetOrchardMigrationSdkUseCase,
    private val pendingMigrationScheduleRepository: PendingMigrationScheduleRepository,
    private val pendingKeystone: PendingKeystoneMigrationPcztsRepository,
    private val restartMigrationScheduleRepository: RestartMigrationScheduleRepository,
    private val finalizeMigrationSchedule: FinalizeMigrationScheduleUseCase,
    private val navigationRouter: NavigationRouter,
    private val exchangeRateRepository: ExchangeRateRepository,
    private val getSelectedWalletAccount: GetSelectedWalletAccountUseCase,
    private val errorStateMapper: ErrorMapperUseCase,
    private val zashiSpendingKeyDataSource: ZashiSpendingKeyDataSource,
    private val biometricRepository: BiometricRepository,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val submitProposal: SubmitProposalUseCase,
    private val sourceEntryId: String? = null,
    private val synchronizerProvider: SynchronizerProvider,
    private val migrationTorEnabled: IsMigrationTorEnabledStorageProvider,
    private val walletActionAccess: WalletActionAccess,
    private val migrationDrive: MigrationDriveOnce,
) : ViewModel() {
    override fun onCleared() {
        navigationRouter.discardResultFrom(sourceEntryId)
        super.onCleared()
    }

    // The SDK binds the Orchard-only sweep in the proposal and exposes its exact fee.
    private sealed class ReviewProposal {
        data class Automatic(
            val reviewed: ReviewedMigrationSchedule,
            val keystoneRunCount: Int?
        ) : ReviewProposal()

        data class Immediate(val proposal: Proposal) : ReviewProposal()
    }

    // Measured block rate captured at propose time; 75s until then. Drives every
    // height-to-time label on this screen (bursty testnet vs the protocol constant).
    @Volatile
    private var secondsPerBlock: Long = DEFAULT_SECONDS_PER_BLOCK

    /**
     * The wallet network's NU7 activation height, captured with [secondsPerBlock]: a plan whose heights span it is
     * converted to time in two segments (75 s before, 25 s from activation), not one.
     */
    @Volatile
    private var nu7ActivationHeight: Long? = null

    /**
     * The height the measured rate belongs to: the estimated chain tip it was read at, not a plan anchor, which sits
     * up to 287 blocks below it. Attributing the rate to a plan's own start would price the post-NU7 blocks of a
     * plan anchored just before NU7 as if the rate were a pre-NU7 one.
     */
    @Volatile
    private var rateReferenceTip: Long? = null

    private fun secondsBetween(
        fromHeight: Long,
        toHeight: Long,
        rateHeight: Long = rateReferenceTip ?: fromHeight,
    ): Long = estimatedSecondsBetweenHeights(fromHeight, toHeight, secondsPerBlock, nu7ActivationHeight, rateHeight)

    private val proposeLce = mutableLce<ReviewProposal>()
    private val confirmLce = mutableLce<Unit>()
    private var reviewedOwnerKey: String? = null
    private var committedMigration: CommittedMigration? = null

    private data class CommittedMigration(val reviewed: ReviewedMigrationSchedule, val showSchedule: Boolean)

    private class ConfirmationAuthority(
        private val owner: Job,
        private val isForeground: () -> Boolean,
        val canPresent: () -> Boolean,
    ) {
        fun canConfirm() = owner.isActive && isForeground() && canPresent()
    }
    private val isKeystoneAccount = getSelectedWalletAccount.observe().map { it is KeystoneAccount }
    private val failure = MutableStateFlow<TransferResult?>(null)

    init {
        loadProposal()
    }

    private fun loadProposal() {
        proposeLce.execute {
            // Timed, bracketed logging (2026-08-07 Review-screen slow-load investigation): pairs
            // with the MIGRATION_DB_ACCESS_MUTEX queued/acquired/released lines OrchardMigrationSdkImpl.logged()
            // now emits, so a slow Review load is attributable to a SPECIFIC call, and — via the
            // matching SDK-side lines — to either real mutex contention (queued behind a named
            // operation) or the Rust computation itself simply taking that long.
            val initStartMs = SystemClock.elapsedRealtime()
            migrationLog("MIGRATION_DIAG ReviewVM: propose start (mode=${args.mode})")
            val account = getSelectedWalletAccount()
            val key = account.sdkAccount.accountUuid.toStorageKeyId()
            check(args.accountKeyId == null || args.accountKeyId == key) {
                "The migration draft belongs to a different account"
            }
            reviewedOwnerKey = key
            val sdk = checkNotNull(getOrchardMigrationSdk(key)) { "Migration account is unavailable" }
            val result =
                when (args.mode) {
                    MigrationMode.IMMEDIATE -> {
                        val t0 = SystemClock.elapsedRealtime()
                        migrationLog("MIGRATION_DIAG ReviewVM: proposeImmediateMigration start")
                        val proposal = sdk.proposeImmediateMigration()
                        migrationLog(
                            "MIGRATION_DIAG ReviewVM: proposeImmediateMigration done in " +
                                "${SystemClock.elapsedRealtime() - t0}ms"
                        )
                        ReviewProposal.Immediate(proposal)
                    }

                    MigrationMode.AUTOMATIC -> {
                        // If MigrationTransferInvalidVM.onContinue() already obtained a fresh schedule
                        // via restartCurrentMigrationStep() — whose own doc requires that returned
                        // schedule to go through this normal confirmation flow rather than being
                        // silently re-proposed — reuse that exact schedule instead of calling
                        // proposeMigrationTransfers() again (see RestartMigrationScheduleRepository's
                        // doc: the two calls compute independent guesses over the same balance that
                        // aren't guaranteed to agree). Falls back to a fresh proposal for every
                        // ordinary, non-recovery entry into this screen.
                        val reviewed = loadAutomaticPlan(sdk, key, account is KeystoneAccount)
                        val schedule = reviewed.schedule
                        // IMMEDIATE has no Keystone branch at all (a documented pre-existing gap —
                        // see MigrationReviewVM.confirmAutomatic()'s Keystone check below), so round
                        // display is AUTOMATIC-only. Stateless preview, called fresh on every Review
                        // entry — never cached.
                        val keystoneRunCount =
                            if (account is KeystoneAccount) {
                                val t0 = SystemClock.elapsedRealtime()
                                migrationLog("MIGRATION_DIAG ReviewVM: estimateMigrationRunCount start")
                                val count = sdk.estimateMigrationRunCount()
                                migrationLog(
                                    "MIGRATION_DIAG ReviewVM: estimateMigrationRunCount done in " +
                                        "${SystemClock.elapsedRealtime() - t0}ms"
                                )
                                count
                            } else {
                                null
                            }
                        val t0 = SystemClock.elapsedRealtime()
                        secondsPerBlock = sdk.estimatedSecondsPerBlock()
                        nu7ActivationHeight = sdk.nu7ActivationHeight
                        rateReferenceTip =
                            rateReferenceHeight(
                                estimatedTip = sdk.estimatedChainTip(),
                                fallbackHeight = schedule.transfers.maxOfOrNull { it.anchorHeight },
                            )
                        migrationLog(
                            "MIGRATION_DIAG ReviewVM: estimatedSecondsPerBlock done in " +
                                "${SystemClock.elapsedRealtime() - t0}ms"
                        )
                        migrationLog("MigrationReviewVM: proposed ${schedule.transfers.size} transfer(s).")
                        ReviewProposal.Automatic(reviewed, keystoneRunCount)
                    }
                }
            migrationLog(
                "MIGRATION_DIAG ReviewVM: propose done in ${SystemClock.elapsedRealtime() - initStartMs}ms total"
            )
            result
        }
    }

    val state: StateFlow<LceState<MigrationReviewState>> =
        combine(
            proposeLce.state,
            exchangeRateRepository.state,
            isKeystoneAccount,
            failure,
            confirmLce.state
        ) { lce, rate, isKeystone, f, confirmState ->
            lce.success?.let { proposal -> createState(proposal, confirmState.loading, rate, isKeystone, f) }
        }.withLce(groupLce(proposeLce, confirmLce)) { error ->
            val message = when (error.cause) {
                is PrivateNoteSplitUnavailable -> stringRes(DesignR.string.migrationReview_privateSplitUnavailable)
                is MigrationFinalizationPending -> stringRes(DesignR.string.private_migration_finish_scheduling)
                else -> null
            }
            errorStateMapper.mapToState(error, message = message)
        }
            .stateIn(this)

    private fun createState(
        proposal: ReviewProposal,
        isConfirming: Boolean,
        exchangeRateState: ExchangeRateState,
        isKeystone: Boolean,
        failureResult: TransferResult?,
    ): MigrationReviewState =
        when (proposal) {
            is ReviewProposal.Automatic -> {
                createAutomaticState(proposal, isConfirming, exchangeRateState, isKeystone, failureResult)
            }

            is ReviewProposal.Immediate -> {
                createImmediateState(proposal, isConfirming)
            }
        }

    private fun createAutomaticState(
        proposal: ReviewProposal.Automatic,
        isConfirming: Boolean,
        exchangeRateState: ExchangeRateState,
        isKeystone: Boolean,
        failureResult: TransferResult?,
    ): MigrationReviewState {
        val sched = proposal.reviewed.schedule
        val total = sched.transfers.sumOf { it.amountZatoshi }
        // From the plan's "now" reference (anchorHeight — every transfer shares the same plan-time
        // tip) to the LAST transfer's height, matching scheduledLabel()'s per-transfer calculation
        // below and MigrationScheduledVM/MigrationProgressVM's createdAt-to-last-scheduled span —
        // NOT firstAtHeight-to-lastAtHeight, which omits the wait before the first transfer and
        // previously made this summary disagree with the per-transfer rows and the other two
        // migration screens (confirmed live: header claimed a shorter span than the last
        // transfer's own "due in ~Nh" label showed).
        val anchorHeight = sched.transfers.minOfOrNull { it.anchorHeight } ?: 0L
        val lastAtHeight = sched.transfers.maxOfOrNull { it.nextExecutableAfterHeight } ?: 0L
        val spanSeconds = secondsBetween(anchorHeight, lastAtHeight)
        val totalAmount = stringRes(Zatoshi(total))
        return MigrationReviewState(
            mode = args.mode,
            totalAmount = totalAmount,
            totalFiatAmount = fiatAmount(Zatoshi(total), exchangeRateState),
            estimatedDuration = stringRes(formatMigrationDuration(spanSeconds)),
            preparations =
                if (sched.preparations.size > 1) {
                    emptyList()
                } else {
                    sched.preparations.mapIndexed { i, p ->
                        MigrationReviewPreparationState(
                            number = i + 1,
                            scheduledLabel = scheduledLabelForPrep(p, sched)
                        )
                    }
                },
            preparationsSummarySubtitle = preparationsSummarySubtitle(sched),
            preparationDetails = preparationDetails(sched, totalAmount),
            transfers =
                sched.transfers.mapIndexed { i, t ->
                    MigrationReviewTransferState(
                        index = i + 1,
                        totalCount = sched.transfers.size,
                        amount = stringRes(Zatoshi(t.amountZatoshi)),
                        fiatAmount = fiatAmount(Zatoshi(t.amountZatoshi), exchangeRateState),
                        scheduledLabel = scheduledLabel(t),
                    )
                },
            isKeystone = isKeystone,
            keystoneRound =
                proposal.keystoneRunCount?.takeIf { it > 1 }?.let { MigrationKeystoneRound(current = 1, total = it) },
            isConfirming = isConfirming,
            onConfirm = { proposeLce.guardLoading { onConfirmAutomatic(proposal) } },
            onBack = ::onBack,
            failureSheet =
                failureResult?.let {
                    MigrationTransferFailureState(
                        message = migrationFailureMessage(it),
                        onRetry = {
                            failure.value = null
                            proposeLce.guardLoading { onConfirmAutomatic(proposal) }
                        },
                        onDismiss = { failure.value = null },
                    )
                },
        )
    }

    // The public SDK exposes the sweep fee. The exact eligible amount is available in history after creation.
    private fun createImmediateState(
        proposal: ReviewProposal.Immediate,
        isConfirming: Boolean,
    ): MigrationReviewState {
        val fee = proposal.proposal.totalFeeRequired()
        return MigrationReviewState(
            mode = args.mode,
            totalAmount = stringRes(DesignR.string.migrationReview_eligibleOrchardFunds),
            totalFiatAmount = null,
            estimatedDuration = stringRes(formatMigrationDuration(0L)),
            transfers =
                listOf(
                    MigrationReviewTransferState(
                        index = 1,
                        totalCount = 1,
                        amount = stringRes(DesignR.string.migrationReview_eligibleOrchardFunds),
                        fiatAmount = null,
                        scheduledLabel = stringRes(DesignR.string.migrationReview_sendImmediately),
                    )
                ),
            fee = stringRes(fee),
            isConfirming = isConfirming,
            onConfirm = { confirmLce.guardLoading { onConfirmImmediate(proposal) } },
            onBack = ::onBack,
            // Submit failures now surface on the Sending screen (which owns the broadcast) rather
            // than here — this screen only hands the signed proposal off after biometric auth.
            failureSheet = null,
        )
    }

    private fun fiatAmount(zatoshi: Zatoshi, exchangeRateState: ExchangeRateState): StringResource? {
        val data = exchangeRateState as? ExchangeRateState.Data ?: return null
        val conversion = data.currencyConversion ?: return null
        return stringResByDynamicCurrencyNumber(
            amount =
                zatoshi
                    .convertZatoshiToZec()
                    .multiply(BigDecimal(conversion.priceOfZec), MathContext.DECIMAL128),
            ticker = data.expectedCurrency.symbol,
        )
    }

    private fun onConfirmAutomatic(proposal: ReviewProposal.Automatic) =
        confirmLce.execute {
            if (!ownsReviewEntry() || proposeLce.state.value.success !== proposal) return@execute
            val account = requireReviewAccount()
            val canOperate = walletActionAccess.capture(account) ?: return@execute
            val authority = ConfirmationAuthority(currentCoroutineContext().job, ::ownsReviewEntry) {
                viewModelScope.isActive && canOperate() && proposeLce.state.value.success === proposal
            }
            if (!authority.canConfirm()) return@execute
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
            if (navigationRouter.awaitForegroundAtEntry(sourceEntryId) && authority.canConfirm()) {
                confirmAutomatic(proposal.reviewed, account, authority)
            }
        }

    private suspend fun loadAutomaticPlan(
        sdk: OrchardMigrationSdk,
        accountKeyId: String,
        usesKeystone: Boolean
    ): ReviewedMigrationSchedule {
        if (sdk.getMigrationStateUnreconciled() is MigrationState.InProgress) {
            return checkNotNull(pendingMigrationScheduleRepository.review(accountKeyId).takeIf { usesKeystone }) {
                "Return to Home to recover the existing migration."
            }
        }
        val restarted = restartMigrationScheduleRepository.consume(accountKeyId)
        return if (restarted != null && restarted.proposalHandle >= 0) {
            ReviewedMigrationSchedule(restarted)
        } else {
            sdk.prepareReviewedAutomaticMigration()
        }
    }

    private suspend fun confirmAutomatic(
        reviewed: ReviewedMigrationSchedule,
        account: WalletAccount,
        authority: ConfirmationAuthority,
    ) {
        val accountKeyId = account.sdkAccount.accountUuid.toStorageKeyId()
        checkNotNull(migrationDrive.withExclusiveAccess<Unit>(accountKeyId) {
            if (authority.canConfirm()) confirmAutomaticOnDrive(reviewed, account, accountKeyId, authority)
        }) { "Another migration step is in progress" }
    }

    private suspend fun confirmAutomaticOnDrive(
        reviewed: ReviewedMigrationSchedule,
        account: WalletAccount,
        accountKeyId: String,
        authority: ConfirmationAuthority,
    ) {
        val sdk = checkNotNull(getOrchardMigrationSdk(accountKeyId)) { "Migration account is unavailable" }
        if (!authority.canConfirm() || resumeCommittedMigration(sdk, reviewed, accountKeyId, authority.canPresent)) {
            return
        }
        val nativePlanActive = sdk.getMigrationStateUnreconciled() is MigrationState.InProgress
        val pending = pendingMigrationScheduleRepository.review(accountKeyId)
        check(!nativePlanActive || (account is KeystoneAccount && pending === reviewed)) {
            "The migration changed. Return to Home to recover it."
        }
        if (!authority.canConfirm()) return
        val newTorPreference = args.useTor?.takeUnless { requestedTor -> !requestedTor && nativePlanActive }
        if (account is KeystoneAccount) {
            confirmKeystone(reviewed, accountKeyId, newTorPreference, authority)
        } else {
            confirmSoftwareAutomatic(sdk, reviewed, accountKeyId, newTorPreference, authority)
        }
    }

    private suspend fun confirmSoftwareAutomatic(
        sdk: OrchardMigrationSdk,
        reviewed: ReviewedMigrationSchedule,
        accountKeyId: String,
        newTorPreference: Boolean?,
        authority: ConfirmationAuthority,
    ) {
        val split = reviewed.noteSplit
        if (split != null && (newTorPreference ?: migrationTorEnabled.get(accountKeyId))) {
            throw PrivateNoteSplitUnavailable()
        }
        if (!authority.canConfirm()) return
        newTorPreference?.let { migrationTorEnabled.store(accountKeyId, it) }
        try {
            val spendingKey = zashiSpendingKeyDataSource.getZashiSpendingKey()
            currentCoroutineContext().ensureActive()
            if (!authority.canConfirm()) return
            withContext(NonCancellable) {
                if (authority.canConfirm()) {
                    commitSoftwareMigration(sdk, reviewed, accountKeyId, spendingKey, authority)
                }
            }
        } catch (e: RuntimeException) {
            if (!e.requiresNewMigrationReview()) throw e
            if (sdk.getMigrationStateUnreconciled() is MigrationState.InProgress) {
                // The SDK owns the already-committed plan. Do not draw or sign a replacement.
                navigationRouter.stageResultFrom(sourceEntryId, authority.canPresent) { NavigationCommand.BackToRoot }
            } else {
                loadProposal()
            }
        }
    }

    private suspend fun commitSoftwareMigration(
        sdk: OrchardMigrationSdk,
        reviewed: ReviewedMigrationSchedule,
        accountKeyId: String,
        spendingKey: UnifiedSpendingKey,
        authority: ConfirmationAuthority,
    ) {
        val splitResult = reviewed.noteSplit?.let { sdk.submitNoteSplit(it, spendingKey) }
        if (splitResult != null && splitResult !is TransferResult.Success) {
            onSplitFailure(splitResult, sdk, reviewed, accountKeyId, authority.canPresent)
        } else if (authority.canConfirm()) {
            sdk.signAndStoreMigrationSchedule(reviewed.schedule, spendingKey)
            recordCommittedMigration(reviewed, accountKeyId, showSchedule = true, canPresent = authority.canPresent)
            synchronizerProvider.enhanceFinalizedSplit(viewModelScope, splitResult)
        } else if (splitResult is TransferResult.Success && sdk.hasCommitted(reviewed.schedule)) {
            recordCommittedMigration(reviewed, accountKeyId, showSchedule = false, canPresent = authority.canPresent)
        }
    }

    private suspend fun onSplitFailure(
        result: TransferResult,
        sdk: OrchardMigrationSdk,
        reviewed: ReviewedMigrationSchedule,
        accountKeyId: String,
        canPresent: () -> Boolean,
    ) {
        if (result is TransferResult.NetworkError && result.retryable && sdk.hasCommitted(reviewed.schedule)) {
            recordCommittedMigration(reviewed, accountKeyId, showSchedule = false, canPresent = canPresent)
        } else if (canPresent()) {
            failure.value = result
        }
    }

    private suspend fun recordCommittedMigration(
        reviewed: ReviewedMigrationSchedule,
        accountKeyId: String,
        showSchedule: Boolean,
        canPresent: () -> Boolean,
    ) {
        val committed = CommittedMigration(reviewed, showSchedule)
        committedMigration = committed
        finishCommittedMigration(committed, accountKeyId, canPresent)
    }

    private suspend fun resumeCommittedMigration(
        sdk: OrchardMigrationSdk,
        reviewed: ReviewedMigrationSchedule,
        accountKeyId: String,
        canPresent: () -> Boolean,
    ): Boolean {
        val committed = committedMigration?.takeIf { it.reviewed === reviewed } ?: return false
        if (sdk.hasCommitted(reviewed.schedule)) {
            finishCommittedMigration(committed, accountKeyId, canPresent)
        } else {
            navigationRouter.stageResultFrom(sourceEntryId, canPresent) { NavigationCommand.BackToRoot }
        }
        return true
    }

    @Suppress("TooGenericExceptionCaught") // After native commit, retry only the app's scheduling work.
    private suspend fun finishCommittedMigration(
        committed: CommittedMigration,
        accountKeyId: String,
        canPresent: () -> Boolean,
    ) {
        if (!migrationDrive.permitsForeground(accountKeyId)) return
        try {
            finalizeMigrationSchedule(committed.reviewed.schedule, args.mode, accountKeyId, false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            migrationLog("Migration scheduling follow-up unavailable (${e.javaClass.simpleName}).")
            throw MigrationFinalizationPending()
        }
        navigationRouter.stageResultFrom(sourceEntryId, canPresent) {
            if (committed.showSchedule) {
                NavigationCommand.Forward(listOf(MigrationScheduledArgs(accountKeyId)))
            } else {
                NavigationCommand.BackToRoot
            }
        }
    }

    private suspend fun confirmKeystone(
        reviewed: ReviewedMigrationSchedule,
        accountKeyId: String,
        newTorPreference: Boolean?,
        authority: ConfirmationAuthority,
    ) {
        if (!authority.canConfirm()) return
        newTorPreference?.let { migrationTorEnabled.store(accountKeyId, it) }
        // Keystone can't sign in-process — hand the unsigned schedule off to the QR
        // sign/scan detour; FinalizeMigrationScheduleUseCase runs after a successful scan
        // instead (MigrationKeystoneScanVM), not here.
        if (!authority.canConfirm()) return
        val existingPlan = pendingMigrationScheduleRepository.review(accountKeyId)
        val existingSignatures = pendingKeystone.peek(accountKeyId)
        if (existingPlan === reviewed && existingSignatures?.isFullySigned == true) {
            navigationRouter.stageResultFrom(sourceEntryId, authority.canPresent) {
                NavigationCommand.Forward(listOf(MigrationScheduledArgs(accountKeyId)))
            }
        } else {
            if (existingPlan !== reviewed && existingSignatures != null) {
                pendingKeystone.clear(accountKeyId, existingSignatures)
            }
            pendingMigrationScheduleRepository.set(accountKeyId, reviewed)
            navigationRouter.stageResultFrom(sourceEntryId, authority.canPresent) {
                NavigationCommand.Forward(listOf(MigrationKeystoneSignArgs(args.mode, accountKeyId)))
            }
        }
    }

    // The IMMEDIATE send-max sweep is, from the wallet's point of view, an ordinary send — so it
    // reuses the exact same submit pipeline every other send does: adopt the proposal as the current
    // MigrationSweepTransactionProposal, then hand off to SubmitProposalUseCase (biometrics + async
    // broadcast + Transaction Progress screen, whose sending/success states already render the
    // migration-sweep "…migrated to Ironwood" copy). No migration-specific screen or handoff.
    private fun onConfirmImmediate(review: ReviewProposal.Immediate) =
        confirmLce.execute {
            val account = requireReviewAccount()
            if (!ownsReviewEntry() || proposeLce.state.value.success !== review) return@execute
            if (account is KeystoneAccount) {
                // Keystone can't sign in-process — adopt the already-built send-max proposal into the
                // app's existing generic external-signer pipeline exactly as an ordinary Keystone
                // send does (one ordinary PCZT, same as any regular Keystone send). Biometrics are
                // requested here because the Keystone branch skips SubmitProposalUseCase (which owns
                // biometrics for the Zashi path) in favour of the QR sign/scan detour.
                val canSign = walletActionAccess.capture(account) ?: return@execute
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
                if (navigationRouter.awaitForegroundAtEntry(sourceEntryId)) {
                    prepareImmediateKeystone(review, account, canSign)
                }
            } else {
                val reviewed = zashiProposalRepository.setMigrationSweepProposal(review.proposal)
                submitProposal(reviewed, sourceEntryId = sourceEntryId, reviewedOwner = account.sdkAccount.accountUuid)
            }
        }

    private suspend fun prepareImmediateKeystone(
        review: ReviewProposal.Immediate,
        account: KeystoneAccount,
        canSign: () -> Boolean,
    ) {
        val canPrepare = { canSign() && ownsReviewEntry() && proposeLce.state.value.success === review }
        if (!canPrepare()) return
        val reviewed = keystoneProposalRepository.setMigrationSweepProposal(review.proposal)
        var delivered = false
        try {
            keystoneProposalRepository.prepareReviewedMigration(reviewed, account, canPrepare)
            delivered = navigationRouter.commitFrom(sourceEntryId, {
                canPrepare() && keystoneProposalRepository.transactionProposal.value === reviewed
            }) {
                if (keystoneProposalRepository.activateReviewedMigration(reviewed, canSign)) {
                    NavigationCommand.Forward(listOf(SignKeystoneTransactionArgs))
                } else {
                    null
                }
            }
        } finally {
            if (!delivered) keystoneProposalRepository.clear(reviewed)
        }
    }

    private fun ownsReviewEntry() = navigationRouter.isForegroundAtEntry(sourceEntryId)

    private suspend fun requireReviewAccount() = getSelectedWalletAccount().also { account ->
        check(account.sdkAccount.accountUuid.toStorageKeyId() == reviewedOwnerKey) {
            "The migration review belongs to a different account"
        }
        if (account is LedgerAccount) throw AccountOperationUnsupportedException.ledger()
        account.requireSpendable()
    }

    private class PrivateNoteSplitUnavailable : IllegalStateException("The SDK cannot submit this preparation over Tor")

    private fun onBack() = proposeLce.guardLoading { navigationRouter.back() }

    // Public, always-reachable fallback for the screen's hoisted BackHandler — unlike onBack()
    // above, this is NOT gated behind proposeLce.guardLoading. onBack() is only reachable via
    // state.content (the LCE success case); when propose fails permanently (e.g. NothingToMigrate
    // once migration is already complete — see the 2026-08-02 stale-banner/dead-end bug) content
    // stays null forever and there is no in-flight propose operation left to guard, so this must
    // navigate back unconditionally.
    fun navigateBack() = navigationRouter.back()

    private fun scheduledLabel(t: TransferProposal): StringResource {
        val secondsUntil = secondsBetween(t.anchorHeight, t.nextExecutableAfterHeight)
        return when {
            secondsUntil <= 0 -> stringRes("Ready now")

            // Shares formatMigrationDuration's resolution rules (minute-level on testnet).
            else -> stringRes(formatMigrationDuration(secondsUntil))
        }
    }

    // Preparations carry no per-item anchorHeight; use the transfers' commit-tip baseline
    // (same origin the transfer labels use).
    private fun scheduledLabelForPrep(
        p: cash.z.ecc.android.sdk.PreparationStep,
        sched: MigrationSchedule,
    ): StringResource {
        val baseline = sched.transfers.minOfOrNull { it.anchorHeight } ?: p.broadcastHeight
        val secondsUntil = secondsBetween(baseline, p.broadcastHeight)
        return if (secondsUntil <= 0) stringRes("Ready now") else stringRes(formatMigrationDuration(secondsUntil))
    }

    // "in ~X hours · N steps" — the collapsed "Split Balance" row's subtitle (Figma "PR App
    // Designs Q3'26" node 5207:16023, 2026-08-03). Only non-null with more than one preparation;
    // unlike scheduledLabelForPrep, this never says "Ready now" — the sheet's own per-row time
    // column (see preparationStepTimeLabel) always reads a relative estimate, never a status word.
    private fun preparationsSummarySubtitle(sched: MigrationSchedule): StringResource? {
        val preparations = sched.preparations
        if (preparations.size <= 1) return null
        val first = preparations.minByOrNull { it.broadcastHeight } ?: return null
        val baseline = sched.transfers.minOfOrNull { it.anchorHeight } ?: first.broadcastHeight
        val secondsUntil = secondsBetween(baseline, first.broadcastHeight)
        return preparationStepTimeLabel(secondsUntil) + stringRes(" · ${preparations.size} steps")
    }

    // The "Show details" sheet's full per-step breakdown — see MigrationPreparationDetails' doc.
    // Only non-null alongside [preparationsSummarySubtitle] (more than one preparation). Nothing
    // has been sent yet at this pre-confirm stage, so isSent/isAwaitingSignature are always false —
    // only a step's own dependencies or timing can produce a non-"Ready to send" status here.
    private fun preparationDetails(
        sched: MigrationSchedule,
        totalAmount: StringResource,
    ): MigrationPreparationDetails? {
        val preparations = sched.preparations
        if (preparations.size <= 1) return null
        val baseline = sched.transfers.minOfOrNull { it.anchorHeight } ?: return null
        val numberById = preparations.mapIndexed { i, p -> p.id to (i + 1) }.toMap()
        return MigrationPreparationDetails(
            stepCount = preparations.size,
            totalAmount = totalAmount,
            steps =
                preparations.mapIndexed { i, p ->
                    val dependsOnNumbers = p.dependsOn.mapNotNull { numberById[it] }.sorted()
                    val secondsUntil = secondsBetween(baseline, p.broadcastHeight)
                    MigrationPreparationStepDetail(
                        title = preparationStepTitle(i + 1, preparations.size),
                        timeLabel = preparationStepTimeLabel(secondsUntil),
                        statusLabel =
                            preparationStepStatus(
                                isSent = false,
                                isAwaitingSignature = false,
                                dependsOnNumbers = dependsOnNumbers,
                                isDueNow = secondsUntil <= 0,
                            ),
                    )
                },
            onDismiss = {},
        )
    }
}

internal fun Throwable.requiresNewMigrationReview(): Boolean =
    message?.contains("StalePlan") == true ||
        message?.contains("BoundaryCheckpointMissing") == true ||
        message?.contains("superseded by a newer proposal", ignoreCase = true) == true ||
        message?.contains("No pending migration proposal", ignoreCase = true) == true

private const val MAX_MIGRATION_PROPOSAL_ATTEMPTS = 8

private suspend fun OrchardMigrationSdk.prepareReviewedAutomaticMigration(): ReviewedMigrationSchedule {
    val requiresSplit = isNoteSplitNeeded()
    repeat(MAX_MIGRATION_PROPOSAL_ATTEMPTS) {
        // The pinned SDK can expose an unsigned native handle as a negative Kotlin Long and
        // refuses that handle when passed back over JNI. Retry only before displaying the plan.
        if (requiresSplit) {
            val split = prepareNoteSplit()
            if (split.proposalHandle >= 0) {
                val schedule = proposeMigrationTransfersFromSplit(split)
                check(schedule.proposalHandle == split.proposalHandle) {
                    "The migration plan changed during preparation"
                }
                return ReviewedMigrationSchedule(schedule, split)
            }
        } else {
            val schedule = proposeMigrationTransfers()
            if (schedule.proposalHandle >= 0) return ReviewedMigrationSchedule(schedule)
        }
    }
    error("The SDK did not provide a usable migration proposal. Try again.")
}

private class MigrationFinalizationPending : IllegalStateException()

private suspend fun OrchardMigrationSdk.hasCommitted(schedule: cash.z.ecc.android.sdk.MigrationSchedule): Boolean {
    if (getMigrationStateUnreconciled() !is MigrationState.InProgress) return false
    val stored = getMigrationTransferStates()?.transfers.orEmpty().filter { it.isTransfer }
    return stored.isNotEmpty() &&
        stored.associate { it.id to it.amountZatoshi } == schedule.transfers.associate { it.id to it.amountZatoshi }
}
