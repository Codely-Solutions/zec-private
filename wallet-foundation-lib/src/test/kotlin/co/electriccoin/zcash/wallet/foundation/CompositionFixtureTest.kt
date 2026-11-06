package co.electriccoin.zcash.wallet.foundation

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Structural reuse only: these adapters have no network, signing, custody or settlement implementation. */
class CompositionFixtureTest {
    @Test
    fun anotherProductCanComposeMemoFundingWithoutZcashOrNear() {
        val asset = AssetId("fixture-network", "fixture-asset")
        val provider = MemoProvider(asset)
        val funding = FundingRecorder()
        val composition = FlowComposition(
            provider.id, setOf(QuoteMode.EXACT_INPUT), setOf(asset),
            setOf(FundingCapability.LOCAL_TRANSACTION), supportsMemo = true
        )
        val quote = provider.quote()

        composition.validate(QuoteMode.EXACT_INPUT, FundingCapability.LOCAL_TRANSACTION, quote, provider.now)
        // A product owns this explicit approval boundary; validation itself never funds anything.
        assertEquals(0, funding.calls)
        funding.approve(quote)
        assertEquals("memo-is-required", funding.approved?.memo)
        assertEquals(1, funding.calls)

        val incompatible = FlowComposition(
            provider.id, setOf(QuoteMode.EXACT_INPUT), setOf(asset),
            setOf(FundingCapability.EXTERNAL_DEPOSIT)
        )
        val rejected = assertFailsWith<FlowRejected> {
            incompatible.validate(QuoteMode.EXACT_INPUT, FundingCapability.EXTERNAL_DEPOSIT, quote, provider.now)
            funding.approve(quote)
        }
        assertEquals(FlowFailure.UNSUPPORTED_MEMO, rejected.reason)
        assertEquals(1, funding.calls)
    }

    private class MemoProvider(private val asset: AssetId) {
        val id = ProviderId("memo-provider-fixture")
        val now = Instant.parse("2026-11-06T12:00:00Z")

        fun quote() = DepositInstructions(
            AssetAmount(asset, BigDecimal("1.234567890123"), 12),
            "fixture-destination", RecoveryReference(id, 2, "opaque-id-not-a-deposit-address"),
            memo = "memo-is-required", expiresAt = now.plusSeconds(60)
        )
    }

    private class FundingRecorder {
        var calls = 0
        var approved: DepositInstructions? = null

        fun approve(instructions: DepositInstructions) {
            calls++
            approved = instructions
        }
    }
}
