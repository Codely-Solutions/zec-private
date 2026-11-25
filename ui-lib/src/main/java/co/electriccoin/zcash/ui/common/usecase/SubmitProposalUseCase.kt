package co.electriccoin.zcash.ui.common.usecase

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.stageResultFrom
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.ledger.LedgerNavigator
import co.electriccoin.zcash.ui.common.ledger.LedgerProposalPipeline
import co.electriccoin.zcash.ui.common.model.AccountOperationUnsupportedException
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
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
    ) {
        if (!submissionMutex.tryLock()) return
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
            val account = accountDataSource.getSelectedAccount()
            if (!account.isSpendable) {
                navigateToError(
                    ErrorArgs.General(
                        AccountOperationUnsupportedException.viewOnly()
                    )
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
            when (account) {
                is KeystoneAccount -> {
                    keystoneProposalRepository.approveForSigning(proposal, reviewedQuote)
                    navigationRouter.stageResultFrom(sourceEntryId, { currentProposal(account) === proposal }) {
                        NavigationCommand.Replace(listOf(SignKeystoneTransactionArgs))
                    }
                }

                is LedgerAccount -> {
                    ledgerProposalPipeline.approveForSigning(proposal, reviewedQuote)
                    ledgerNavigator.forwardToSign()
                }

                is ViewOnlyAccount -> {
                    throw AccountOperationUnsupportedException.viewOnly()
                }

                is ZodlAccount -> {
                    val owner = if (sourceEntryId != null) account.sdkAccount.accountUuid else null
                    submitOrShowSoftware(proposal, reviewedQuote, sourceEntryId, owner)
                }
            }
        } catch (_: BiometricsFailureException) {
            // do nothing
        } catch (_: BiometricsCancelledException) {
            // do nothing
        } catch (e: IllegalStateException) {
            navigateToError(ErrorArgs.General(e))
        } finally {
            submissionMutex.unlock()
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
    ) {
        if (submittedSoftwareProposal?.get() !== proposal) {
            swapRepository.clear()
            submittedSoftwareProposal = WeakReference(proposal)
            val progress = owner?.let { submissionProgress?.begin(proposal, it) }
            submittedProgressId = progress?.id
            submitZashiProposal(proposal, reviewedQuote, progress)
        }
        navigationRouter.showTransactionProgress(sourceEntryId, submittedProgressId)
    }

    @Suppress("TooGenericExceptionCaught") // Retain the SDK result or a typed failure for its original review.
    private fun submitZashiProposal(
        proposal: TransactionProposal,
        reviewedQuote: SwapQuoteData.Success?,
        progress: SubmissionProgress?,
    ) {
        scope.launch {
            try {
                val result = zashiProposalRepository.submit(proposal, reviewedQuote)
                progress?.record(result)
                if (proposal is SwapTransactionProposal) {
                    processSwapTransaction(proposal, result)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                progress?.recordFailure(error)
            } finally {
                prefillSend.clear()
            }
        }
    }
}

/** Presents an already admitted submission. Reopening this handoff never submits again. */
fun NavigationRouter.showTransactionProgress(
    sourceEntryId: String?,
    submissionId: String?,
    replaceWalletStack: Boolean = false,
) = stageResultFrom(sourceEntryId) {
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
