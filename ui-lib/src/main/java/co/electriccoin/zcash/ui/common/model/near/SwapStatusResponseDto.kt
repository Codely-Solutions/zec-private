@file:OptIn(ExperimentalSerializationApi::class)

package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.serialization.NearSwapStatusSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.math.BigDecimal

@JsonIgnoreUnknownKeys
@Serializable
data class SwapStatusResponseDto(
    @SerialName("quoteResponse")
    val quoteResponse: QuoteResponseDto,
    @SerialName("status")
    @Serializable(with = NearSwapStatusSerializer::class)
    val status: SwapStatus? = null,
    @SerialName("updatedAt")
    val updatedAt: String,
    @SerialName("swapDetails")
    val swapDetails: SwapDetails? = null
)

@JsonIgnoreUnknownKeys
@Serializable
data class TransactionChainHash(val hash: String)

@JsonIgnoreUnknownKeys
@Serializable
data class SwapDetails(
    // @SerialName("intentHashes")
    // val intentHashes: List<String>,
    // @SerialName("nearTxHashes")
    // val nearTxHashes: List<String>,
    @SerialName("amountIn")
    @Serializable(NearProviderDecimalSerializer::class)
    val amountIn: BigDecimal? = null,
    @SerialName("amountInFormatted")
    @Serializable(NearProviderDecimalSerializer::class)
    val amountInFormatted: BigDecimal? = null,
    @SerialName("amountInUsd")
    @Serializable(NearProviderDecimalSerializer::class)
    val amountInUsd: BigDecimal? = null,
    @SerialName("amountOut")
    @Serializable(NearProviderDecimalSerializer::class)
    val amountOut: BigDecimal? = null,
    @SerialName("amountOutFormatted")
    @Serializable(NearProviderDecimalSerializer::class)
    val amountOutFormatted: BigDecimal? = null,
    @SerialName("amountOutUsd")
    @Serializable(NearProviderDecimalSerializer::class)
    val amountOutUsd: BigDecimal? = null,
    @SerialName("slippage")
    val slippage: Int? = null,
    @SerialName("originChainTxHashes")
    val originChainTxHashes: List<TransactionChainHash>? = null,
    @SerialName("destinationChainTxHashes")
    val destinationChainTxHashes: List<TransactionChainHash>? = null,
    @SerialName("refundedAmount")
    @Serializable(NearProviderDecimalSerializer::class)
    val refundedAmount: BigDecimal? = null,
    @SerialName("refundedAmountFormatted")
    @Serializable(NearProviderDecimalSerializer::class)
    val refundedAmountFormatted: BigDecimal? = null,
    @SerialName("refundedAmountUsd")
    @Serializable(NearProviderDecimalSerializer::class)
    val refundedAmountUsd: BigDecimal? = null,
    @SerialName("depositedAmount")
    @Serializable(NearProviderDecimalSerializer::class)
    val depositedAmount: BigDecimal? = null,
    @SerialName("depositedAmountFormatted")
    @Serializable(NearProviderDecimalSerializer::class)
    val depositedAmountFormatted: BigDecimal? = null,
    @SerialName("depositedAmountUsd")
    @Serializable(NearProviderDecimalSerializer::class)
    val depositedAmountUsd: BigDecimal? = null
) {
    init {
        requireNearProviderDecimals(
            amountIn, amountInFormatted, amountInUsd, amountOut, amountOutFormatted, amountOutUsd,
            refundedAmount, refundedAmountFormatted, refundedAmountUsd,
            depositedAmount, depositedAmountFormatted, depositedAmountUsd
        )
        require(listOfNotNull(amountIn, amountOut, depositedAmount, refundedAmount).all {
            it.stripTrailingZeros().scale() <= 0
        }) { "Swap status contains a fractional base-unit amount" }
    }
}

enum class SwapStatus(
    val apiValue: String
) {
    KNOWN_DEPOSIT_TX("KNOWN_DEPOSIT_TX"),
    PENDING_DEPOSIT("PENDING_DEPOSIT"),
    INCOMPLETE_DEPOSIT("INCOMPLETE_DEPOSIT"),
    PROCESSING("PROCESSING"),
    SUCCESS("SUCCESS"),
    REFUNDED("REFUNDED"),
    FAILED("FAILED"),
    EXPIRED("EXPIRED")
}
