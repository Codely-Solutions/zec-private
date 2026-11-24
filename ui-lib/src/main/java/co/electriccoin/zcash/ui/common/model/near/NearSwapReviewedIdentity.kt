package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.model.SwapQuote
import java.security.MessageDigest

internal fun SwapQuote.reviewedIdentity(): String? =
    (this as? NearSwapQuote)?.response?.reviewedIdentity()

/** Bind the funding terms without storing a second copy of the route or quote. */
internal fun QuoteResponseDto.reviewedIdentity(): String {
    val approvedAmounts = listOf(
        quote.amountIn, quote.amountInFormatted, quote.minAmountIn,
        quote.amountOut, quote.amountOutFormatted, quote.minAmountOut, quoteRequest.amount
    ).map { it.stripTrailingZeros().toPlainString() }
    val approvedTerms = approvedAmounts + listOf(quote.deadline.toString(), quoteRequest.slippageTolerance.toString())
    return REVIEWED_FUNDING_TERMS_PREFIX + hashReviewedFields(reviewedRouteFields() + approvedTerms)
}

private const val REVIEWED_FUNDING_TERMS_PREFIX = "v2:"

internal fun String?.bindsReviewedFundingTerms(): Boolean = this?.startsWith(REVIEWED_FUNDING_TERMS_PREFIX) == true

internal fun QuoteResponseDto.matchesReviewedIdentity(expected: String): Boolean =
    if (expected.bindsReviewedFundingTerms()) reviewedIdentity() == expected
    else hashReviewedFields(reviewedRouteFields()) == expected

private fun QuoteResponseDto.reviewedRouteFields() = listOf(
    "near-swap-v1", quote.depositAddress, quoteRequest.originAsset, quoteRequest.destinationAsset,
    requireNotNull(quoteRequest.swapType).apiValue, quoteRequest.recipient, quoteRequest.refundTo
)

private fun hashReviewedFields(fields: List<String>): String {
    val framed = fields.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(framed.encodeToByteArray()).toHexString()
}
