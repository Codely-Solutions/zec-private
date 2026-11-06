package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.wallet.foundation.FundingCapability
import java.math.BigDecimal
import java.time.Instant as JavaInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ZecSwapCompositionTest {
    private val now = JavaInstant.parse("2026-11-06T12:00:00Z")
    private val future = Instant.parse("2026-11-06T12:01:00Z")
    private val zec = SwapAssetTestFixture.zecAsset()
    private val btc = SwapAssetTestFixture.asset()

    @Test
    fun outgoingModesRequireLocalFundingAndAcceptZecOrigin() {
        for (mode in listOf(SwapMode.EXACT_INPUT, SwapMode.EXACT_OUTPUT)) {
            assertEquals(FundingCapability.LOCAL_TRANSACTION, ZecSwapComposition.funding(mode))
            ZecSwapComposition.validate(quote(mode, zec, btc), now)
        }
    }

    @Test
    fun incomingFlexibleQuoteUsesExternalFundingAndItsOwnPrecision() {
        val usdc = SwapAssetTestFixture.asset(tokenTicker = "usdc", chainTicker = "eth", decimals = 6)
        assertEquals(FundingCapability.EXTERNAL_DEPOSIT, ZecSwapComposition.funding(SwapMode.FLEX_INPUT))
        ZecSwapComposition.validate(quote(SwapMode.FLEX_INPUT, usdc, zec, amount = "12.345678"), now)
    }

    @Test
    fun rejectsAssetDirectionsThatCannotBeFundedByTheSelectedFlow() {
        for (mode in listOf(SwapMode.EXACT_INPUT, SwapMode.EXACT_OUTPUT)) {
            assertFailsWith<IllegalArgumentException> {
                ZecSwapComposition.validate(quote(mode, btc, zec), now)
            }
        }
        for ((origin, destination) in listOf(zec to btc, btc to btc, zec to zec)) {
            assertFailsWith<IllegalArgumentException> {
                ZecSwapComposition.validate(quote(SwapMode.FLEX_INPUT, origin, destination), now)
            }
        }
    }

    @Test
    fun zecTickerOnAnotherChainCannotSupplyLocalWalletFunding() {
        val externalZec = SwapAssetTestFixture.asset(tokenTicker = "zec", chainTicker = "eth")
        assertFailsWith<IllegalArgumentException> {
            ZecSwapComposition.validate(quote(SwapMode.EXACT_INPUT, externalZec, btc), now)
        }
    }

    @Test
    fun rejectsUnsupportedProviderAndDeadlineAtOrBeforeFundingTime() {
        assertFailsWith<IllegalArgumentException> {
            ZecSwapComposition.validate(quote(SwapMode.EXACT_INPUT, zec, btc, provider = "other"), now)
        }
        for (deadline in listOf("2026-11-06T12:00:00Z", "2026-11-06T11:59:59Z")) {
            assertFailsWith<IllegalArgumentException> {
                ZecSwapComposition.validate(
                    quote(SwapMode.EXACT_INPUT, zec, btc, deadline = Instant.parse(deadline)),
                    now
                )
            }
        }
    }

    @Test
    fun validatesFormattedInputAmountAtOriginPrecisionWithoutRounding() {
        val usdc = SwapAssetTestFixture.asset(tokenTicker = "usdc", chainTicker = "eth", decimals = 6)
        assertFailsWith<IllegalArgumentException> {
            ZecSwapComposition.validate(quote(SwapMode.FLEX_INPUT, usdc, zec, amount = "12.3456789"), now)
        }
        assertFailsWith<IllegalArgumentException> {
            ZecSwapComposition.validate(quote(SwapMode.EXACT_INPUT, zec, btc, amount = "-1"), now)
        }
    }

    private fun quote(
        mode: SwapMode,
        origin: SwapAsset,
        destination: SwapAsset,
        amount: String = "1.23456789",
        provider: String = "near",
        deadline: Instant = future
    ): SwapQuote {
        val formatted = BigDecimal(amount)
        return FakeSwapQuote(
            originAsset = origin,
            destinationAsset = destination,
            mode = mode,
            amountIn = formatted.movePointRight(origin.decimals),
            amountInFormatted = formatted,
            amountOutFormatted = BigDecimal("99.01"),
            depositAddress = "synthetic-deposit",
            destinationAddress = "synthetic-destination",
            refundAddress = "synthetic-refund",
            provider = provider,
            deadline = deadline
        )
    }
}
