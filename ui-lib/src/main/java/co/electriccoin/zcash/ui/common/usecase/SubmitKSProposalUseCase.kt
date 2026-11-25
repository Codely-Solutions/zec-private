package co.electriccoin.zcash.ui.common.usecase

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.TransactionProposal
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.MissingKeystoneSigningPreparation
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import kotlinx.coroutines.CancellationException
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
        val progress = sourceEntryId?.let {
            submissionProgress?.begin(proposal, owner = keystoneProposalRepository.signingOwner(proposal))
        }
        submitKSProposal(proposal, preparationId, progress)
        navigationRouter.showTransactionProgress(sourceEntryId, progress?.id)
    }

    @Suppress("TooGenericExceptionCaught") // Retain the SDK result or a typed failure for its original review.
    private fun submitKSProposal(
        proposal: TransactionProposal,
        preparationId: String,
        progress: SubmissionProgress?,
    ) {
        val originalPrefill = prefillSend.pendingThrough()
        scope.launch {
            try {
                val result = keystoneProposalRepository.submit(proposal, preparationId)
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
