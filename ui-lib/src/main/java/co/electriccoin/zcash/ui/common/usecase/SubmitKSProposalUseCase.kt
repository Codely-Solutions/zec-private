package co.electriccoin.zcash.ui.common.usecase

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.MissingKeystoneSigningPreparation
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SubmitKSProposalUseCase(
    private val keystoneProposalRepository: KeystoneProposalRepository,
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
     * Submit a prepared Keystone proposal and navigate to Transaction Progress screen.
     */
    operator fun invoke(proposal: TransactionProposal, preparationId: String, sourceEntryId: String? = null) {
        if (keystoneProposalRepository.transactionProposal.value !== proposal ||
            keystoneProposalRepository.signingPreparationId() != preparationId
        ) {
            throw MissingKeystoneSigningPreparation()
        }
        swapRepository.clear()
        val progress = if (sourceEntryId != null) submissionProgress?.begin(proposal) else null
        submitKSProposal(proposal, preparationId, progress)
        navigationRouter.showTransactionProgress(sourceEntryId, progress?.id)
    }

    private fun submitKSProposal(
        proposal: TransactionProposal,
        preparationId: String,
        progress: SubmissionProgress?,
    ) {
        scope.launch {
            try {
                val result = keystoneProposalRepository.submit(proposal, preparationId)
                progress?.record(result)
                if (proposal is SwapTransactionProposal) {
                    processSwapTransaction(proposal, result)
                }
            } catch (error: Exception) {
                progress?.recordFailure(error)
            } finally {
                prefillSend.clear()
            }
        }
    }
}
