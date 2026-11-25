package co.electriccoin.zcash.ui.common.usecase

import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.repository.SignedLedgerSubmission
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SubmitLedgerProposalUseCase(
    private val navigationRouter: NavigationRouter,
    private val swapRepository: SwapRepository,
    private val processSwapTransaction: ProcessSwapTransactionUseCase,
    private val prefillSend: PrefillSendUseCase,
    private val submissionProgress: SubmissionProgressStore? = null,
) {
    @set:RestrictTo(RestrictTo.Scope.TESTS)
    @VisibleForTesting
    internal var scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Queues the signed preparation before the sheet is told that the device finished. */
    operator fun invoke(signed: SignedLedgerSubmission, sourceEntryId: String? = null) {
        if (!signed.enqueueOnce()) return
        signed.reviewedQuote?.let(swapRepository::clearIfCurrent)
        val progress = sourceEntryId?.let { submissionProgress?.begin(signed.proposal, owner = signed.owner) }
        submitLedgerProposal(signed, progress)
        navigationRouter.showTransactionProgress(sourceEntryId, progress?.id, replaceWalletStack = true)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun submitLedgerProposal(signed: SignedLedgerSubmission, progress: SubmissionProgress?) {
        val proposal = signed.proposal
        val originalPrefill = prefillSend.pendingThrough()
        scope.launch {
            try {
                val result = signed.submit()
                progress?.record(result)
                if (proposal is SwapTransactionProposal) {
                    processSwapTransaction(proposal, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress?.recordFailure(e)
                Twig.warn { "Ledger proposal submission failed: ${e.javaClass.simpleName}" }
            } finally {
                prefillSend.clearThrough(originalPrefill)
            }
        }
    }
}
