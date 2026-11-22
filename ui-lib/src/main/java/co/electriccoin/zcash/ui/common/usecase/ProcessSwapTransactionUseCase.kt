package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.spackle.Twig
import co.electriccoin.zcash.ui.common.datasource.SwapTransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuote
import co.electriccoin.zcash.ui.common.model.near.reviewedIdentity
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

class ProcessSwapTransactionUseCase(
    private val metadataRepository: MetadataRepository,
    private val swapRepository: SwapRepository,
    // private val ephemeralAddressRepository: EphemeralAddressRepository,
) {
    private val fundingMutex = Mutex()
    // The selected-account observation can lag a successful durable write.
    private val reservedReferences = mutableSetOf<String>()

    suspend fun reserveFunding(
        transactionProposal: SwapTransactionProposal,
        validateFunding: () -> Unit,
    ) = fundingMutex.withLock {
        validateFunding()
        val reference = transactionProposal.destination.address
        check(reference !in reservedReferences && metadataRepository.getSwapMetadata(reference) == null) {
            "This swap is already tracked. Check its status before funding."
        }
        validateFunding()
        writePendingTracking(transactionProposal)
        reservedReferences += reference
        val selectedSwapAsset = transactionProposal.quote.destinationAsset
        metadataRepository.addSwapAssetToHistory(
            tokenTicker = selectedSwapAsset.tokenTicker,
            chainTicker = selectedSwapAsset.chainTicker
        )
    }

    suspend operator fun invoke(transactionProposal: SwapTransactionProposal, result: SubmitResult) {
        fundingMutex.withLock {
            val reference = transactionProposal.destination.address
            if (reference !in reservedReferences && metadataRepository.getSwapMetadata(reference) == null) {
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

    private suspend fun writePendingTracking(transactionProposal: SwapTransactionProposal) {
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
        )) { "Unable to save swap tracking before funding" }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun submitDepositTransactions(transactionProposal: SwapTransactionProposal, result: SubmitResult) {
        suspend fun submit(txId: String, transactionProposal: SwapTransactionProposal) {
            try {
                swapRepository.submitDepositTransaction(
                    txId = txId,
                    transactionProposal = transactionProposal
                )
            } catch (e: Exception) {
                Twig.error(e) { "Unable to submit deposit transaction" }
            }
        }

        result.txIds
            .filter { it.isNotEmpty() }
            .forEach {
                submit(it, transactionProposal)
                yield()
            }
    }
}
