package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.model.SwapAsset
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/** Sparse fields follow the OneClick SwapDetails contract; values and addresses are synthetic. */
internal object SwapInputProvenanceFixture {
    val cases = listOf(
        Triple("""{"amountIn":"80000000","amountInUsd":"24"}""", "0.8", "24"),
        Triple("""{"amountInFormatted":"0.8"}""", "0.8", null),
        Triple("""{"amountIn":"80000000"}""", "0.8", null),
        Triple("""{"amountInFormatted":"0.8","amountInUsd":"24"}""", "0.8", "24"),
        Triple("""{"amountInUsd":"24"}""", "1", "30"),
        Triple("{}", "1", "30"),
    )

    fun status(
        origin: SwapAsset,
        destination: SwapAsset,
        deposit: String,
        mode: SwapType,
        details: String,
    ): NearSwapQuoteStatus {
        val base = SwapOutputProvenanceFixture.status(origin, destination, deposit, mode, false, false)
        val quote = base.response.quoteResponse.let {
            it.copy(quoteRequest = it.quoteRequest.copy(
                amount = if (mode == SwapType.EXACT_OUTPUT) it.quote.amountOut else it.quote.amountIn
            ))
        }
        // Rewrite only raw units to the selected asset's decimals, preserving the independent shape matrix.
        val fields = details.replace("80000000", BigDecimal("0.8").movePointRight(origin.decimals).toPlainString())
        val response = Json.decodeFromString<SwapStatusResponseDto>(
            """{"quoteResponse":${Json.encodeToString(QuoteResponseDto.serializer(), quote)},
                "status":"SUCCESS","updatedAt":"2026-11-23T00:00:00Z","swapDetails":$fields}"""
        )
        return base.copy(response = response)
    }
}
