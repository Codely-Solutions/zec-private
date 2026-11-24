package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuote
import co.electriccoin.zcash.ui.common.model.near.reviewedIdentity
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.provider.runCatchingRecoverable
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

class ProcessSwapTransactionUseCase(
    private val metadataRepository: MetadataRepository,
    private val swapRepository: SwapRepository,
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
    // private val ephemeralAddressRepository: EphemeralAddressRepository,
) {
    private val fundingMutex = Mutex()
    // Keep successful reservations in memory for the lifetime of the funding pipeline.
    private val reservedReferences = mutableSetOf<String>()

    suspend fun reserveFunding(
        transactionProposal: SwapTransactionProposal,
        validateFunding: () -> Unit,
    ) = reserveFunding(transactionProposal, null, validateFunding)

    private suspend fun reserveFunding(
        transactionProposal: SwapTransactionProposal,
        walletWriteToken: Long?,
        validateFunding: () -> Unit,
    ) = fundingMutex.withLock {
        validateFunding()
        val reference = transactionProposal.destination.address
        check(
            reference !in reservedReferences &&
                metadataRepository.getSwapMetadata(reference, transactionProposal.fundingAccount) == null
        ) {
            "This swap is already tracked. Check its status before funding."
        }
        validateFunding()
        if (walletWriteToken == null) {
            writePendingTracking(transactionProposal)
        } else {
            writeFundingReservation(transactionProposal, walletWriteToken)
        }
        reservedReferences += reference
        val selectedSwapAsset = transactionProposal.quote.destinationAsset
        metadataRepository.addSwapAssetToHistory(
            tokenTicker = selectedSwapAsset.tokenTicker,
            chainTicker = selectedSwapAsset.chainTicker,
            account = transactionProposal.fundingAccount,
        )
    }

    suspend fun submitReservedFunding(
        proposal: SwapTransactionProposal,
        validateFunding: () -> Unit,
        submit: suspend (() -> Unit) -> SubmitResult,
    ): SubmitResult {
        val generation = walletWriteAccess.token()
        val validate = {
            check(walletWriteAccess.permits(generation)) { "The wallet changed before swap funding" }
            validateFunding()
        }
        reserveFunding(proposal, generation, validate)
        val entryResolved = AtomicBoolean(false)
        try {
            validate()
            return submit {
                validate()
                check(entryResolved.compareAndSet(false, true)) { "Swap funding is no longer active" }
            }
        } finally {
            if (entryResolved.compareAndSet(false, true)) {
                // Only a still-closed gate proves that no SDK endpoint was entered.
                recordUnsubmittedFundingSafely(proposal, generation)
            }
        }
    }

    private suspend fun writeFundingReservation(proposal: SwapTransactionProposal, generation: Long) {
        var acknowledged = false
        try {
            // Finish the write before inspecting it if the caller loses its acknowledgement.
            withContext(NonCancellable) { writePendingTracking(proposal, generation) }
            currentCoroutineContext().ensureActive()
            acknowledged = true
        } finally {
            if (!acknowledged) recordUnsubmittedFundingSafely(proposal, generation)
        }
    }

    private suspend fun recordUnsubmittedFundingSafely(proposal: SwapTransactionProposal, generation: Long) =
        withContext(NonCancellable) {
            runCatchingRecoverable { recordUnsubmittedFunding(proposal, generation) }.onFailure { error ->
                Twig.warn { "Unable to record unsubmitted swap funding (${error.javaClass.simpleName})" }
            }
        }

    private suspend fun recordUnsubmittedFunding(proposal: SwapTransactionProposal, generation: Long) {
        val identity = proposal.quote.reviewedIdentity() ?: return
        val saved = metadataRepository.getSwapMetadata(proposal.destination.address, proposal.fundingAccount) ?: return
        if (saved.reviewedIdentity == identity && saved.walletWriteToken == generation) {
            metadataRepository.markSwapFundingNotSubmitted(saved)
        }
    }

    suspend operator fun invoke(transactionProposal: SwapTransactionProposal, result: SubmitResult) {
        fundingMutex.withLock {
            val reference = transactionProposal.destination.address
            if (reference !in reservedReferences &&
                metadataRepository.getSwapMetadata(reference, transactionProposal.fundingAccount) == null
            ) {
                writePendingTracking(transactionProposal)
            }
        }
        // invalidateEphemeralAddress(result)
        submitDepositTransactions(transactionProposal, result)
    }

    // private suspend fun invalidateEphemeralAddress(result: SubmitResult) {
    //     when (result) {
    //         is SubmitResult.Failure,
    //         is SubmitResult.GrpcFailure,
    //         is SubmitResult.Success -> ephemeralAddressRepository.invalidate()
    //
    //         is SubmitResult.Partial -> {
    //             // do nothing
    //         }
    //     }
    // }

    private suspend fun writePendingTracking(
        transactionProposal: SwapTransactionProposal,
        walletWriteToken: Long? = null,
    ) {
        check(metadataRepository.markTxAsSwap(
            depositAddress = transactionProposal.destination.address,
            provider = transactionProposal.quote.provider,
            totalFees = transactionProposal.totalFees,
            totalFeesUsd = transactionProposal.totalFeesUsd,
            amountOutFormatted = transactionProposal.quote.amountOutFormatted,
            origin = transactionProposal.quote.originAsset,
            destination = transactionProposal.quote.destinationAsset,
            mode = transactionProposal.quote.mode,
            status = SwapStatus.PENDING,
            reviewedIdentity = transactionProposal.quote.reviewedIdentity(),
            signedQuoteEvidence = (transactionProposal.quote as? NearSwapQuote)?.response?.signedEvidence,
            account = transactionProposal.fundingAccount,
            walletWriteToken = walletWriteToken,
        )) { "Unable to save swap tracking before funding" }
    }

    private suspend fun submitDepositTransactions(transactionProposal: SwapTransactionProposal, result: SubmitResult) {
        result.txIds
            .filter { it.isNotEmpty() }
            .forEach { txId ->
                runCatchingRecoverable {
                    swapRepository.submitDepositTransaction(txId, transactionProposal)
                }.onFailure {
                    Twig.warn { "Unable to notify swap provider of deposit" }
                }
                yield()
            }
    }
}
