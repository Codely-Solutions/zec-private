package co.electriccoin.zcash.ui.common.model

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwapRecipientValidationTest {
    private val btc = "1BpEi6DfDAUFd7GtittLSdBeYJvcoaVggu"
    private val evm = "0xde709f2102306220921060314715629080e2fb77"

    @Test
    fun rawEvmUsesSelectedNetworkAndTokenWithoutInferringEither() {
        assertTrue(validateSwapRecipient(evm, "eth", "eth").isValid)
        assertTrue(validateSwapRecipient(evm, "eth", "wbtc").isValid)
        assertTrue(validateSwapRecipient(evm, "base", "usdc").isValid)
        assertFalse(validateSwapRecipient(evm, "btc", "btc").isValid)
    }

    @Test
    fun savedContactNetworkRemainsAuthoritativeAcrossSharedAddressFormats() {
        val mismatch = validateSwapRecipient(evm, "base", "eth", contactNetwork = "eth")
        assertEquals(SwapRecipientError.NETWORK, mismatch.error)
        assertFalse(mismatch.isValid)
        assertTrue(validateSwapRecipient(evm, "eth", "wbtc", contactNetwork = "eth").isValid)
    }

    @Test
    fun uriRetainsNetworkAssetAndNativeAmount() {
        val value = "bitcoin:$btc?amount=0.5"
        assertEquals(SwapRecipientError.NETWORK, validateSwapRecipient(value, "eth", "eth").error)
        assertEquals(SwapRecipientError.ASSET, validateSwapRecipient(value, "btc", "wbtc").error)
        val valid = validateSwapRecipient(value, "btc", "btc")
        assertTrue(valid.isValid)
        assertEquals(btc, valid.input?.address)
        assertEquals(BigDecimal("0.5"), valid.input?.amount)
    }

    @Test
    fun chainlessEvmPaymentUsesSelectedNativeAssetAndBindsExplicitChains() {
        val nativeAssets = listOf(
            Triple("eth", "ETH", 1), Triple("bsc", "BNB", 56), Triple("avax", "AVAX", 43114),
            Triple("arb", "ETH", 42161), Triple("base", "ETH", 8453), Triple("pol", "POL", 137),
        )
        for ((chain, native, chainId) in nativeAssets) {
            for (suffix in listOf("", "@$chainId")) {
                val uri = "ethereum:$evm$suffix?value=2.014e18"
                val result = validateSwapRecipient(uri, chain, native)
                assertTrue(result.isValid, uri)
                assertEquals(chain, result.input?.network)
                assertEquals(0, result.input?.amount?.compareTo(BigDecimal("2.014")))
                assertEquals(SwapRecipientError.ASSET, validateSwapRecipient(uri, chain, "W$native").error)
                assertEquals(
                    SwapRecipientError.ASSET,
                    validateSwapRecipient(uri, chain, native, tokenContract = evm).error,
                )
            }
            assertTrue(validateSwapRecipient(evm, chain, "W$native", tokenContract = evm).isValid)
        }
        assertEquals(SwapRecipientError.NETWORK, validateSwapRecipient("ethereum:$evm@1?value=1", "bsc", "BNB").error)
        for (invalid in listOf("@", "@0", "@99999", "@x")) {
            assertEquals(SwapRecipientError.URI, validateSwapRecipient("ethereum:$evm$invalid", "eth", "ETH").error)
        }
    }

    @Test
    fun unsupportedRequiredFieldsAndRoutingDisableQuote() {
        for (query in listOf("req-extra=1", "dt=4", "amount.1=2")) {
            val result = validateSwapRecipient("bitcoin:$btc?$query", "btc", "btc")
            assertFalse(result.isValid)
            assertEquals(SwapRecipientError.URI, result.error)
        }
    }

    @Test
    fun mismatchedInputRemainsInvalidUntilCorrectedInSameNetworkContext() {
        assertFalse(validateSwapRecipient(evm, "btc", "btc", "eth").isValid)
        assertTrue(validateSwapRecipient(evm, "eth", "wbtc", "eth").isValid)
        assertTrue(validateSwapRecipient(btc, "btc", "btc").isValid)
    }

    @Test
    fun paymentAmountRequiresFormWithMatchingOutputMeaning() {
        val request = "bitcoin:$btc?amount=0.5"
        val rejected = validateSwapRecipient(request, "btc", "btc", allowPaymentAmount = false)
        assertFalse(rejected.isValid)
        assertEquals(SwapRecipientError.PAYMENT_AMOUNT, rejected.error)
        assertTrue(validateSwapRecipient(request, "btc", "btc", allowPaymentAmount = true).isValid)
    }
}
