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
import java.time.Instant
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
    ) {
        reserveFunding(transactionProposal, null, validateFunding)
    }

    private suspend fun reserveFunding(
        transactionProposal: SwapTransactionProposal,
        walletWriteToken: Long?,
        validateFunding: () -> Unit,
    ): Instant = fundingMutex.withLock {
        validateFunding()
        val reference = transactionProposal.destination.address
        check(
            reference !in reservedReferences &&
                metadataRepository.getSwapMetadata(reference, transactionProposal.fundingAccount) == null
        ) {
            "This swap is already tracked. Check its status before funding."
        }
        validateFunding()
        val createdAt = Instant.now()
        if (walletWriteToken == null) {
            writePendingTracking(transactionProposal)
        } else {
            writeFundingReservation(transactionProposal, walletWriteToken, createdAt)
        }
        reservedReferences += reference
        val selectedSwapAsset = transactionProposal.quote.destinationAsset
        metadataRepository.addSwapAssetToHistory(
            tokenTicker = selectedSwapAsset.tokenTicker,
            chainTicker = selectedSwapAsset.chainTicker,
            account = transactionProposal.fundingAccount,
        )
        createdAt
    }

    suspend fun submitReservedFunding(
        proposal: SwapTransactionProposal,
        validateFunding: () -> Unit,
        submit: suspend (validate: () -> Unit, enterEndpoint: () -> Unit) -> SubmitResult,
    ): SubmitResult {
        val generation = walletWriteAccess.token()
        val validate = {
            check(walletWriteAccess.permits(generation)) { "The wallet changed before swap funding" }
            validateFunding()
        }
        val createdAt = reserveFunding(proposal, generation, validate)
        val entryResolved = AtomicBoolean(false)
        try {
            validate()
            return submit(validate) {
                validate()
                check(entryResolved.compareAndSet(false, true)) { "Swap funding is no longer active" }
            }
        } finally {
            if (entryResolved.compareAndSet(false, true)) {
                // Only a still-closed gate proves that no SDK endpoint was entered.
                recordUnsubmittedFundingSafely(proposal, generation, createdAt)
            }
        }
    }

    private suspend fun writeFundingReservation(
        proposal: SwapTransactionProposal,
        generation: Long,
        createdAt: Instant,
    ) {
        var acknowledged = false
        try {
            // Finish the write before inspecting it if the caller loses its acknowledgement.
            withContext(NonCancellable) { writePendingTracking(proposal, generation, createdAt) }
            currentCoroutineContext().ensureActive()
            acknowledged = true
        } finally {
            if (!acknowledged) recordUnsubmittedFundingSafely(proposal, generation, createdAt)
        }
    }

    private suspend fun recordUnsubmittedFundingSafely(
        proposal: SwapTransactionProposal,
        generation: Long,
        createdAt: Instant,
    ): Unit = withContext(NonCancellable) {
        val identity = proposal.quote.reviewedIdentity() ?: return@withContext
        repeat(OUTCOME_WRITE_ATTEMPTS) {
            val saved = runCatchingRecoverable {
                // Apply the outcome to the exact write without needing a separate synced read.
                metadataRepository.markSwapFundingNotSubmitted(
                    depositAddress = proposal.destination.address,
                    createdAt = createdAt,
                    reviewedIdentity = identity,
                    account = proposal.fundingAccount,
                    walletWriteToken = generation,
                )
            }.getOrDefault(false)
            if (saved) return@withContext
        }
        Twig.warn { "Unable to confirm the unsubmitted swap funding outcome was saved" }
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
        reservationCreatedAt: Instant? = null,
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
            reservationCreatedAt = reservationCreatedAt,
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

private const val OUTCOME_WRITE_ATTEMPTS = 3
