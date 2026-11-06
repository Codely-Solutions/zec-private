package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.wallet.foundation.AssetAmount
import co.electriccoin.zcash.wallet.foundation.AssetId
import co.electriccoin.zcash.wallet.foundation.DepositInstructions
import co.electriccoin.zcash.wallet.foundation.FlowComposition
import co.electriccoin.zcash.wallet.foundation.FundingCapability
import co.electriccoin.zcash.wallet.foundation.ProviderId
import co.electriccoin.zcash.wallet.foundation.QuoteMode
import co.electriccoin.zcash.wallet.foundation.RecoveryReference
import java.time.Instant

/** ZEC product policy: incoming external deposits and outgoing local transactions are distinct flows. */
object ZecSwapComposition {
    private val near = ProviderId("near")

    fun funding(mode: SwapMode): FundingCapability =
        when (mode) {
            SwapMode.FLEX_INPUT -> FundingCapability.EXTERNAL_DEPOSIT
            SwapMode.EXACT_INPUT, SwapMode.EXACT_OUTPUT -> FundingCapability.LOCAL_TRANSACTION
        }

    fun validate(quote: SwapQuote, now: Instant = Instant.now()) {
        val funding = funding(quote.mode)
        require(
            if (funding == FundingCapability.LOCAL_TRANSACTION) quote.originAsset.isZCashAsset
            else quote.destinationAsset.isZCashAsset && !quote.originAsset.isZCashAsset
        ) { "Quote does not match the selected product flow" }
        val asset = quote.originAsset.foundationId()
        val instructions =
            DepositInstructions(
                amount = AssetAmount(asset, quote.amountInFormatted, quote.originAsset.decimals),
                address = quote.depositAddress.address,
                recovery = RecoveryReference(ProviderId(quote.provider), 1, quote.depositAddress.address),
                expiresAt = Instant.ofEpochSecond(quote.deadline.epochSeconds, quote.deadline.nanosecondsOfSecond.toLong())
            )
        FlowComposition(
            provider = near,
            quoteModes = setOf(QuoteMode.valueOf(quote.mode.name)),
            depositAssets = setOf(asset),
            fundingCapabilities = setOf(funding)
        ).validate(QuoteMode.valueOf(quote.mode.name), funding, instructions, now)
    }

    /** Provider IDs are namespaced; ticker equality alone cannot identify an asset on another network. */
    private fun SwapAsset.foundationId() = AssetId("mainnet:${blockchain.chainTicker}", assetId)
}
