package co.electriccoin.zcash.ui.common.usecase

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.repository.LedgerProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SubmitLedgerProposalUseCase(
    private val ledgerProposalRepository: LedgerProposalRepository,
    private val navigationRouter: NavigationRouter,
    private val swapRepository: SwapRepository,
    private val processSwapTransaction: ProcessSwapTransactionUseCase,
    private val prefillSend: PrefillSendUseCase,
    private val submissionProgress: SubmissionProgressStore? = null,
) {
    @set:RestrictTo(RestrictTo.Scope.TESTS)
    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Submits the proposal the device has signed and replaces the whole back stack with the
     * Transaction Progress screen. An empty repository leaves nothing to submit, so the session
     * fails instead of staying on its signed state with Cancel disabled.
     */
    suspend operator fun invoke(sourceEntryId: String? = null) {
        val proposal = ledgerProposalRepository.transactionProposal.value
        if (proposal == null) {
            Twig.warn { "Ledger signing: signed without a proposal to submit" }
            ledgerProposalRepository.failSignedSessionWithoutProposal()
            return
        }
        swapRepository.clear()
        val progress = sourceEntryId?.let {
            submissionProgress?.begin(proposal, owner = ledgerProposalRepository.signingOwner(proposal))
        }
        submitLedgerProposal(proposal, progress)
        navigationRouter.showTransactionProgress(sourceEntryId, progress?.id, replaceWalletStack = true)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun submitLedgerProposal(proposal: TransactionProposal, progress: SubmissionProgress?) {
        scope.launch {
            try {
                val result = ledgerProposalRepository.submit(proposal)
                progress?.record(result)
                if (proposal is SwapTransactionProposal) {
                    processSwapTransaction(proposal, result)
                }
            } catch (e: Exception) {
                progress?.recordFailure(e)
                Twig.warn { "Ledger proposal submission failed: ${e.javaClass.simpleName}" }
            } finally {
                prefillSend.clear()
            }
        }
    }
}
