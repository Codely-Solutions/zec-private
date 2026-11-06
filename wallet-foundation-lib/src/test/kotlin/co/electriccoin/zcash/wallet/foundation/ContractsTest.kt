package co.electriccoin.zcash.wallet.foundation

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContractsTest {
    private val asset = AssetId("example-mainnet", "coin")
    private val provider = ProviderId("example-provider")
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val amount = AssetAmount(asset, BigDecimal("1.25"), 2)
    private val instructions = DepositInstructions(
        amount = amount,
        address = "external-destination",
        recovery = RecoveryReference(provider, 1, "opaque-operation-id"),
        expiresAt = now.plusSeconds(60)
    )

    @Test
    fun identityAndPrecisionArePreserved() {
        assertNotEquals(asset, AssetId("example-testnet", "coin"))
        assertNotEquals(asset, AssetId("example-mainnet", "COIN"))
        assertEquals(amount, AssetAmount(asset, BigDecimal("1.2500"), 2))
        assertFailsWith<IllegalArgumentException> { AssetAmount(asset, BigDecimal("1.251"), 2) }
        assertFailsWith<IllegalArgumentException> { AssetAmount(asset, BigDecimal("-1"), 2) }
        assertFailsWith<IllegalArgumentException> { AssetAmount(asset, BigDecimal.ZERO, -1) }
        assertFailsWith<IllegalArgumentException> { AssetId(" mainnet", "coin") }
        assertFailsWith<IllegalArgumentException> { ProviderId("") }
        assertFailsWith<IllegalArgumentException> { RecoveryReference(provider, 0, "id") }
    }

    @Test
    fun feeKnowledgeIsExplicitIncludingKnownZero() {
        val zero = AssetAmount(asset, BigDecimal.ZERO, 2)
        val fees: Set<Fee> = setOf(Fee.Known(zero), Fee.Estimated(zero), Fee.Included(asset), Fee.Unknown(asset))
        assertEquals(4, fees.size)
        assertTrue(Fee.Known(zero).amount.value.signum() == 0)
    }

    @Test
    fun alternativeExternalFundingCompositionNeedsNoLocalWallet() {
        val composition = externalComposition()
        composition.validate(QuoteMode.EXACT_INPUT, FundingCapability.EXTERNAL_DEPOSIT, instructions, now)
        assertFailsWith<IllegalArgumentException> {
            composition.validate(QuoteMode.EXACT_INPUT, FundingCapability.LOCAL_TRANSACTION, instructions, now)
        }
        assertFailsWith<IllegalArgumentException> {
            composition.validate(QuoteMode.EXACT_OUTPUT, FundingCapability.EXTERNAL_DEPOSIT, instructions, now)
        }
    }

    @Test
    fun unsupportedInstructionsFailBeforeFunding() {
        val unsupported = listOf(
            instructions.copy(memo = "required-memo"),
            instructions.copy(tag = "required-tag"),
            instructions.copy(expiresAt = now),
            instructions.copy(recovery = RecoveryReference(ProviderId("other"), 1, "id")),
            instructions.copy(
                amount = AssetAmount(AssetId("example-testnet", "coin"), BigDecimal.ONE, 2),
                maximumAmount = AssetAmount(AssetId("example-testnet", "coin"), BigDecimal.ONE, 2)
            )
        )
        unsupported.forEach {
            assertFailsWith<IllegalArgumentException> {
                externalComposition().validate(QuoteMode.EXACT_INPUT, FundingCapability.EXTERNAL_DEPOSIT, it, now)
            }
        }
    }

    @Test
    fun flexibleRangesAndLocalMemoFundingAreExplicitCapabilities() {
        val range = instructions.copy(maximumAmount = AssetAmount(asset, BigDecimal("2.50"), 2), memo = "route")
        val local = FlowComposition(
            provider,
            setOf(QuoteMode.EXACT_INPUT, QuoteMode.FLEX_INPUT),
            setOf(asset),
            setOf(FundingCapability.LOCAL_TRANSACTION),
            supportsMemo = true
        )
        local.validate(QuoteMode.FLEX_INPUT, FundingCapability.LOCAL_TRANSACTION, range, now)
        assertFailsWith<IllegalArgumentException> {
            local.validate(QuoteMode.EXACT_INPUT, FundingCapability.LOCAL_TRANSACTION, range, now)
        }
        assertFailsWith<IllegalArgumentException> {
            instructions.copy(maximumAmount = AssetAmount(asset, BigDecimal.ONE, 2))
        }
    }

    private fun externalComposition() = FlowComposition(
        provider,
        setOf(QuoteMode.EXACT_INPUT),
        setOf(asset),
        setOf(FundingCapability.EXTERNAL_DEPOSIT)
    )
}
