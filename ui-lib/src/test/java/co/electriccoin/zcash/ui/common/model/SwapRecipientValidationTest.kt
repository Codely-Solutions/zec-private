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
