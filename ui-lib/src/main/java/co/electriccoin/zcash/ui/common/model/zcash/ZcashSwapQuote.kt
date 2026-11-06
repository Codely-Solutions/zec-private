package co.electriccoin.zcash.ui.common.model.zcash

import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.extension.ZERO
import co.electriccoin.zcash.ui.common.model.SwapQuote
import java.math.BigDecimal
import java.math.MathContext

/**
 * Zcash wallet-funded quote presentation. The input USD unit price prices a Zcash fee when the
 * origin is ZEC. Incoming externally funded quotes have no wallet proposal and add no wallet fee.
 * ZEC Private requests zero affiliate fees; decimal provider fee fields remain available separately.
 */
fun SwapQuote.getTotal(proposal: Proposal?): BigDecimal = amountInFormatted + proposal.zecFee()

fun SwapQuote.getTotalUsd(proposal: Proposal?): BigDecimal = amountInUsd + zecFeeUsd(proposal)

fun SwapQuote.getTotalFeesUsd(proposal: Proposal?): BigDecimal = affiliateFeeUsd + zecFeeUsd(proposal)

fun SwapQuote.getTotalFeesZatoshi(proposal: Proposal?): Zatoshi = proposal?.totalFeeRequired() ?: Zatoshi.ZERO

private fun Proposal?.zecFee(): BigDecimal = this?.totalFeeRequired()?.convertZatoshiToZec() ?: BigDecimal.ZERO

private fun SwapQuote.zecFeeUsd(proposal: Proposal?): BigDecimal {
    if (proposal == null) return BigDecimal.ZERO
    val originUsdUnitPrice = amountInUsd.divide(amountInFormatted, MathContext.DECIMAL128)
    return originUsdUnitPrice.multiply(proposal.zecFee(), MathContext.DECIMAL128)
}
