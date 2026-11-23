package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.SwapQuote
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuote
import co.electriccoin.zcash.ui.common.model.near.reviewedIdentity
import co.electriccoin.zcash.ui.common.repository.IncomingSwapOwner
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import java.math.BigDecimal

class SaveORSwapUseCase(
    private val swapRepository: SwapRepository,
    private val metadataRepository: MetadataRepository,
    private val navigationRouter: NavigationRouter,
    private val walletWriteAccess: WalletWriteAccess = WalletWriteAccess(),
) {
    suspend operator fun invoke(expected: SwapQuoteData.Success) {
        if (swapRepository.quote.value !== expected) return
        persistBeforeFunding(expected.quote, expected.incomingOwner)
        if (swapRepository.quote.value !== expected || !isWalletCurrent(expected)) return
        swapRepository.clear()
        navigationRouter.backToRoot()
    }

    internal fun isWalletCurrent(publication: SwapQuoteData.Success): Boolean =
        publication.incomingOwner?.let { walletWriteAccess.permits(it.walletWriteToken) } ?: true

    suspend fun persistBeforeFunding(quote: SwapQuote, owner: IncomingSwapOwner? = null) {
        check(owner == null || walletWriteAccess.permits(owner.walletWriteToken)) { "Swap wallet changed" }
        val identity = quote.reviewedIdentity()
        val deposit = quote.depositAddress.address
        if (identity == null && metadataRepository.getSwapMetadata(deposit, owner?.account) != null) return
        check(metadataRepository.markTxAsSwap(
                depositAddress = deposit,
                provider = quote.provider,
                totalFees = Zatoshi(0),
                totalFeesUsd = BigDecimal(0),
                amountOutFormatted = quote.amountOutFormatted,
                mode = quote.mode,
                status = SwapStatus.PENDING,
                origin = quote.originAsset,
                destination = quote.destinationAsset,
                reviewedIdentity = identity,
                signedQuoteEvidence = (quote as? NearSwapQuote)?.response?.signedEvidence,
                account = owner?.account,
                walletWriteToken = owner?.walletWriteToken,
            )) { "Unable to save swap tracking before funding" }
    }
}
