package co.electriccoin.zcash.ui.common.model.near

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NearSignedQuoteEvidenceTest {
    @Test
    fun verifiesTheSdkMessageAndRetainsTheOriginalJsonWithoutLoggingIt() {
        val evidence = assertNotNull(verifiedNearQuoteEvidence(syntheticSignedQuote, SYNTHETIC_PUBLIC_KEY))
        assertEquals(syntheticSignedQuote, evidence.originalJson)
        assertFalse(evidence.toString().contains("synthetic-recipient"))

        // The SDK excludes depositMode, appFees and correlationId from the signed message.
        val withUnsignedChanges = syntheticSignedQuote
            .replace("SIMPLE", "MEMO")
            .replace("synthetic-correlation", "different-correlation")
            .replace("\"appFees\":[]", "\"appFees\":[{\"recipient\":\"unsigned\",\"fee\":4}]")
        assertNotNull(verifiedNearQuoteEvidence(withUnsignedChanges, SYNTHETIC_PUBLIC_KEY))
    }

    @Test
    fun skipsAlteredUnsupportedOrOversizedEvidence() {
        val unusable = listOf(
            "malformed json",
            boundedNearQuoteWire,
            syntheticSignedQuote.replace("synthetic-deposit", "other-deposit"),
            syntheticSignedQuote.replace("synthetic-recipient", "other-recipient"),
            syntheticSignedQuote.replace("\"timeEstimate\":120", "\"timeEstimate\":1.25"),
            syntheticSignedQuote.replace("\"dry\":false", "\"dry\":true"),
            syntheticSignedQuote.replace("ed25519:3Qg", "ed25519:0Qg"),
            syntheticSignedQuote + " ".repeat(MAX_QUOTE_EVIDENCE_BYTES),
            syntheticSignedQuote.replace("synthetic-correlation", "☃".repeat(MAX_QUOTE_EVIDENCE_BYTES / 2)),
        )
        unusable.forEach { assertNull(verifiedNearQuoteEvidence(it, SYNTHETIC_PUBLIC_KEY)) }
        assertNull(verifiedNearQuoteEvidence(syntheticSignedQuote))
    }

    private companion object {
        const val SYNTHETIC_PUBLIC_KEY = "ed25519:FVen3X669xLzsi6N2V91DoiyzHzg1uAgqiT8jZ9nS96Z"
    }
}

// Signed with the public RFC 8032 test seed and the SDK's canonicalization, using Node's Ed25519.
internal val syntheticSignedQuote = """
    {
        "correlationId":"synthetic-correlation",
        "timestamp":"2026-11-20T00:00:00Z",
        "signature":"ed25519:3QgHu3ed1n7VgJFPxajAZcrytsTocGyZmkwXLQq7F1aQvdV2pKtdSRyrudiAw3gVXvgyKs97Yoggn1LCmDUZfj6k",
        "quoteRequest":{
            "dry":false, "swapType":"EXACT_INPUT", "slippageTolerance":100,
            "originAsset":"origin", "destinationAsset":"destination", "amount":"100000000",
            "depositType":"ORIGIN_CHAIN", "refundType":"ORIGIN_CHAIN", "recipientType":"DESTINATION_CHAIN",
            "refundTo":"synthetic-refund", "recipient":"synthetic-recipient",
            "deadline":"2026-11-21T00:00:00Z", "quoteWaitingTimeMs":3000, "referral":"test/\"☃",
            "depositMode":"SIMPLE", "appFees":[]
        },
        "quote":{
            "depositAddress":"synthetic-deposit", "deadline":"2026-11-21T00:00:00Z",
            "amountIn":"100000000", "minAmountIn":"99000000", "amountInFormatted":"1", "amountInUsd":"3.5",
            "amountOut":"100000000", "minAmountOut":"99000000", "amountOutFormatted":"1", "amountOutUsd":"3.5",
            "refundFee":"4", "timeEstimate":120, "timeWhenInactive":"2026-11-21T01:00:00Z"
        }
    }
""".trimIndent()
