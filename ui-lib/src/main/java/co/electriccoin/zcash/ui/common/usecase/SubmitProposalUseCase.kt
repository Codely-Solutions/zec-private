package co.electriccoin.zcash.ui.common.usecase

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.awaitForegroundAtEntry
import co.electriccoin.zcash.ui.isAtEntry
import co.electriccoin.zcash.ui.isForegroundAtEntry
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.whileAtEntry
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MigrationSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerNavigator
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.BiometricsFailureException
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.requireFundingApproval
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.error.ErrorArgs
import co.electriccoin.zcash.ui.screen.error.NavigateToErrorUseCase
import co.electriccoin.zcash.ui.screen.error.showFrom
import co.electriccoin.zcash.ui.screen.signkeystonetransaction.SignKeystoneTransactionArgs
import co.electriccoin.zcash.ui.screen.transactionhistory.ActivityHistoryArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.BoundTransactionProgressArgs
import co.electriccoin.zcash.ui.screen.transactionprogress.TransactionProgressArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.lang.ref.WeakReference

class SubmitProposalUseCase(
    private val navigationRouter: NavigationRouter,
    private val accountDataSource: AccountDataSource,
    private val zashiProposalRepository: ZashiProposalRepository,
    private val keystoneProposalRepository: KeystoneProposalRepository,
    private val ledgerProposalPipeline: LedgerProposalPipeline,
    private val ledgerNavigator: LedgerNavigator,
    private val biometricRepository: BiometricRepository,
    private val swapRepository: SwapRepository,
    private val processSwapTransaction: ProcessSwapTransactionUseCase,
    private val prefillSend: PrefillSendUseCase,
    private val navigateToError: NavigateToErrorUseCase,
    private val appAccess: AppAccessSession,
    private val wallet: WalletWriteAccess,
    private val submissionProgress: SubmissionProgressStore? = null,
) {
    private val submissionMutex = Mutex()
    private var submittedSoftwareProposal: WeakReference<TransactionProposal>? = null
    private var submittedProgressId: String? = null
    @set:RestrictTo(RestrictTo.Scope.TESTS)
    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Submit Zashi proposal and navigate to Transaction Progress screen, navigate to Keystone PCZT flow, or open
     * the Ledger sign sheet over the confirmation screen. An account that cannot spend gets the
     * unsupported-operation error instead, before any proposal is read. The proposal must still be the one
     * displayed for review when authentication completes.
     */
    suspend operator fun invoke(
        reviewedProposal: TransactionProposal?,
        reviewedQuote: SwapQuoteData.Success? = null,
        sourceEntryId: String? = null,
        reviewedOwner: AccountUuid? = null,
    ) {
        if (!navigationRouter.isAtEntry(sourceEntryId) || !submissionMutex.tryLock()) return
        val grant = appAccess.grant.value
        val walletToken = wallet.token()
        val sameSession = { appAccess.permits(grant) && wallet.permits(walletToken) }
        try {
            val requiresForeground = reviewedProposal is MigrationSweepTransactionProposal
            val account = navigationRouter.whileAtEntry(sourceEntryId) {
                if (sameSession()) authenticate() else null
            }?.takeIf { awaitSubmissionEntry(sourceEntryId, requiresForeground) } ?: return
            val selection = accountDataSource.confirmSelection(account)
            val canSubmit = { sameSession() && selection?.canOperate() == true }
            if (canSubmit() && ownsSubmissionEntry(sourceEntryId, requiresForeground)) {
                approveSubmission(account, reviewedProposal, reviewedQuote, sourceEntryId, reviewedOwner, canSubmit)
            }
        } catch (_: BiometricsFailureException) {
            // do nothing
        } catch (_: BiometricsCancelledException) {
            // do nothing
        } catch (e: IllegalStateException) {
            if (sameSession()) navigateToError.showFrom(sourceEntryId, ErrorArgs.General(e))
        } finally {
            submissionMutex.unlock()
        }
    }

    private suspend fun awaitSubmissionEntry(sourceEntryId: String?, requiresForeground: Boolean): Boolean =
        !requiresForeground || navigationRouter.awaitForegroundAtEntry(sourceEntryId)

    private fun ownsSubmissionEntry(sourceEntryId: String?, requiresForeground: Boolean): Boolean =
        if (requiresForeground) {
            navigationRouter.isForegroundAtEntry(sourceEntryId)
        } else {
            navigationRouter.isAtEntry(sourceEntryId)
        }

    private suspend fun authenticate(): WalletAccount {
        biometricRepository.requestBiometrics(
            request = BiometricRequest(
                message = stringRes(
                    R.string.authentication_system_ui_subtitle,
                    stringRes(R.string.authentication_use_case_send_funds)
                )
            )
        )
        return accountDataSource.getSelectedAccount()
    }

    @Suppress("LongParameterList")
    private fun approveSubmission(
        account: WalletAccount,
        reviewedProposal: TransactionProposal?,
        reviewedQuote: SwapQuoteData.Success?,
        sourceEntryId: String?,
        reviewedOwner: AccountUuid?,
        canSubmit: () -> Boolean,
    ) {
        check(reviewedOwner == null || account.sdkAccount.accountUuid == reviewedOwner) {
            "The selected account changed. Review the payment again before sending."
        }
        if (!account.isSpendable) {
            navigateToError.showFrom(
                sourceEntryId,
                ErrorArgs.General(AccountOperationUnsupportedException.viewOnly()),
            )
            return
        }
        val proposal = checkNotNull(currentProposal(account)) {
            "The reviewed transaction is unavailable. Review it again before sending."
        }
        check(proposal === reviewedProposal) {
            "The reviewed transaction changed. Review it again before sending."
        }
        if (proposal is SwapTransactionProposal) {
            check(proposal.fundingAccount.sdkAccount.accountUuid == account.sdkAccount.accountUuid) {
                "The reviewed swap belongs to a different funding account."
            }
            check(swapRepository.quote.value === reviewedQuote) { "The reviewed swap quote changed." }
            proposal.requireFundingApproval(reviewedQuote)
        }
        val canSign = { canSubmit() && currentProposal(account) === proposal }
        when (account) {
            is KeystoneAccount -> {
                keystoneProposalRepository.approveForSigning(proposal, reviewedQuote, canSubmit)
                navigationRouter.stageResultFrom(sourceEntryId, canSign) {
                    NavigationCommand.Replace(listOf(SignKeystoneTransactionArgs))
                }
            }

            is LedgerAccount -> {
                ledgerProposalPipeline.approveForSigning(proposal, reviewedQuote, canSubmit)
                ledgerNavigator.forwardToSign(sourceEntryId, canSign)
            }

            is ViewOnlyAccount -> throw AccountOperationUnsupportedException.viewOnly()

            is ZodlAccount -> {
                val owner = account.sdkAccount.accountUuid.takeIf { sourceEntryId != null }
                submitOrShowSoftware(proposal, reviewedQuote, sourceEntryId, owner, canSubmit)
            }
        }
    }

    private fun currentProposal(account: WalletAccount): TransactionProposal? =
        when (account) {
            is KeystoneAccount -> keystoneProposalRepository.transactionProposal.value
            is LedgerAccount -> ledgerProposalPipeline.transactionProposal.value
            is ViewOnlyAccount -> throw AccountOperationUnsupportedException.viewOnly()
            is ZodlAccount -> zashiProposalRepository.transactionProposal.value
        }

    private fun submitOrShowSoftware(
        proposal: TransactionProposal,
        reviewedQuote: SwapQuoteData.Success?,
        sourceEntryId: String?,
        owner: AccountUuid?,
        canSubmit: () -> Boolean,
    ) {
        if (submittedSoftwareProposal?.get() !== proposal) {
            swapRepository.clear()
            submittedSoftwareProposal = WeakReference(proposal)
            val progress = owner?.let { submissionProgress?.begin(proposal, it) }
            submittedProgressId = progress?.id
            submitZashiProposal(proposal, reviewedQuote, progress) {
                canSubmit() && (proposal !is MigrationSweepTransactionProposal ||
                    isForegroundForMigrationSweep(sourceEntryId, progress?.id))
            }
        }
        navigationRouter.showTransactionProgress(sourceEntryId, submittedProgressId)
    }

    private fun isForegroundForMigrationSweep(sourceEntryId: String?, progressId: String?): Boolean =
        sourceEntryId == null || navigationRouter.activeEntry.value?.let { active ->
            active.hostResumed &&
                (active.id == sourceEntryId || progressId != null && active.submissionProgressId == progressId)
        } == true

    @Suppress("TooGenericExceptionCaught") // Retain the SDK result or a typed failure for its original review.
    private fun submitZashiProposal(
        proposal: TransactionProposal,
        reviewedQuote: SwapQuoteData.Success?,
        progress: SubmissionProgress?,
        canSubmit: () -> Boolean,
    ) {
        val originalPrefill = prefillSend.pendingThrough()
        scope.launch {
            try {
                val result = zashiProposalRepository.submit(proposal, reviewedQuote) {
                    check(canSubmit()) { "The wallet context changed before sending." }
                }
                progress?.record(result)
                if (proposal is SwapTransactionProposal) {
                    processSwapTransaction(proposal, result)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                progress?.recordFailure(error)
            } finally {
                prefillSend.clearThrough(originalPrefill)
            }
        }
    }
}

/** Presents an already admitted submission. Reopening this handoff never submits again. */
fun NavigationRouter.showTransactionProgress(
    sourceEntryId: String?,
    submissionId: String?,
    replaceWalletStack: Boolean = false,
) = stageResultFrom(sourceEntryId, retainAfterSourceCloses = replaceWalletStack) {
    val route = when {
        sourceEntryId == null -> TransactionProgressArgs
        submissionId != null -> BoundTransactionProgressArgs(submissionId)
        else -> ActivityHistoryArgs
    }
    if (replaceWalletStack || route == ActivityHistoryArgs) {
        NavigationCommand.ReplaceAll(listOf(route))
    } else {
        NavigationCommand.Replace(listOf(route))
    }
}
