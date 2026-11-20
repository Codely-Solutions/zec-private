package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.SwapPriceMarkets
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchException
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProviderImpl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant

class SwapCatalogContractTest {
    private val fixture = SwapCatalogTestFixture
    private val assets = fixture.assets

    @Test
    fun fixtureCoversEveryCuratedWalletPairExactlyOnce() {
        val provider = SimpleSwapAssetProviderImpl(fixture.tokenIcons, fixture.tokenNames, fixture.blockchains)
        val curated = provider.getCuratedSwapAssets().map { it.tokenTicker to it.chainTicker }
        val frozen = assets.map { it.tokenTicker to it.chainTicker }

        assertEquals(curated.toSet(), frozen.toSet())
        assertEquals(curated.size, frozen.size)
        assertEquals(frozen.size, frozen.toSet().size, "Duplicate selectable pair")
        assertEquals(assets.size, assets.map { it.assetId }.toSet().size, "Duplicate provider identity")
    }

    @Test
    fun exactIdentityResolvesWithDistractorsInEitherOrder() {
        val btc = assets.single { it.tokenTicker == "BTC" && it.chainTicker == "btc" }
        val omni = btc.copy(assetId = "1cs_v1:btc:native:coin", tokenTicker = "BTC(OMNI)")
        val unrelated = btc.copy(
            assetId = "test:unrelated",
            tokenTicker = "TEST",
            blockchain = fixture.blockchains.getBlockchain("test"),
        )
        val sameDisplayPair = btc.copy(assetId = "test:same-btc-pair")
        val supported = listOf(omni, sameDisplayPair) + assets + unrelated

        supported.forEach { asset ->
            assertSame(asset, resolveNearAsset(supported, asset.assetId), asset.label)
            assertSame(asset, resolveNearAsset(supported.asReversed(), asset.assetId), asset.label)
            requireNearAssetIdentity(asset, asset.assetId, SwapQuoteMismatchType.ORIGIN_ASSET)
        }
    }

    @Test
    fun duplicateAndUnknownIdsFailClosed() {
        assets.forEach { asset ->
            assertNull(resolveNearAsset(assets + asset.copy(), asset.assetId), asset.label)
            assertNull(resolveNearAsset(assets, "test:unknown:${asset.assetId}"), asset.label)
        }
    }

    @Test
    fun exactQuoteIdentitySeparatesNetworksSharingASymbol() {
        assets.groupBy { it.tokenTicker }.values.forEach { bySymbol ->
            bySymbol.forEach { expected ->
                bySymbol.filter { it.assetId != expected.assetId }.forEach { other ->
                    assertIdentityMismatch(expected, other)
                }
            }
        }
    }

    @Test
    fun exactQuoteIdentitySeparatesWrappedAndNativeAssets() {
        val btc = assets.single { it.tokenTicker == "BTC" && it.chainTicker == "btc" }
        val wbtc = assets.single { it.tokenTicker == "WBTC" }
        val wrappedNear = assets.single { it.tokenTicker == "wNEAR" }
        val syntheticNativeNear = wrappedNear.copy(
            assetId = "test:near:native",
            tokenTicker = "NEAR",
            contractAddress = null,
        )

        assertIdentityMismatch(btc, wbtc)
        assertIdentityMismatch(wbtc, btc)
        assertIdentityMismatch(wrappedNear, syntheticNativeNear)
        assertIdentityMismatch(syntheticNativeNear, wrappedNear)
    }

    @Test
    fun independentPriceMarketRequiresPinnedMetadata() {
        assets.forEach { asset ->
            assertNotNull(SwapPriceMarkets.reference(asset), asset.label)
            val otherNetwork = assets.first { it.chainTicker != asset.chainTicker }.blockchain
            assertNull(SwapPriceMarkets.reference(asset.copy(blockchain = otherNetwork)), "${asset.label} network")
            assertNull(SwapPriceMarkets.reference(asset.copy(decimals = asset.decimals + 1)), "${asset.label} decimals")
            val changedContract = asset.copy(contractAddress = "test:other-contract")
            assertNull(SwapPriceMarkets.reference(changedContract), "${asset.label} contract")
            val otherContract = assets.firstOrNull {
                it.chainTicker == asset.chainTicker && it.contractAddress != null &&
                    it.contractAddress != asset.contractAddress
            }
            otherContract?.let {
                val swappedContract = asset.copy(contractAddress = it.contractAddress)
                assertNull(SwapPriceMarkets.reference(swappedContract), "${asset.label} another supported contract")
            }
            if (asset.contractAddress != null) {
                assertNull(SwapPriceMarkets.reference(asset.copy(contractAddress = null)), "${asset.label} missing contract")
            }
        }
    }

    @Test
    fun quotePreservesOneBaseUnitAndLargeAmountsAtCatalogPrecision() {
        val representatives = assets.distinctBy { it.decimals }
        val paired = representatives.drop(1) + representatives.first()
        representatives.zip(paired).forEach { (origin, destination) ->
            listOf(BigDecimal.ONE, LARGE_BASE_UNITS).forEach { units ->
                val response = response(origin, destination, units)
                val quote = quote(origin, destination, response)
                assertEquals(units, quote.amountIn, origin.label)
                assertEquals(units.movePointLeft(origin.decimals), quote.amountInFormatted, origin.label)
                assertEquals(units, quote.amountOut, destination.label)
                assertEquals(units.movePointLeft(destination.decimals), quote.amountOutFormatted, destination.label)
            }
        }
    }

    @Test
    fun quoteRejectsAtomicMismatchAndSubatomicAmountsAtCatalogPrecision() {
        assets.distinctBy { it.decimals }.forEach { asset ->
            val origin = fixture.nativeZec
            val response = response(origin, asset, LARGE_BASE_UNITS)
            val details = response.quote
            val badOutputs = listOf(
                "one base unit mismatch" to details.copy(amountOut = details.amountOut + BigDecimal.ONE),
                "fractional raw base unit" to details.copy(amountOut = details.amountOut + HALF_BASE_UNIT),
                "subatomic formatted amount" to details.copy(
                    amountOutFormatted = details.amountOutFormatted + HALF_BASE_UNIT.movePointLeft(asset.decimals)
                ),
            )
            badOutputs.forEach { (case, altered) ->
                val label = "${asset.label} at ${asset.decimals} decimals, $case"
                val error = assertFailsWith<SwapQuoteMismatchException.AmountInconsistency>(label) {
                    quote(origin, asset, response.copy(quote = altered))
                }
                assertEquals(SwapQuoteMismatchType.OUTPUT_AMOUNT, error.type, label)
                assertEquals(asset.decimals, error.decimals, label)
            }
        }
    }

    @Test
    fun quoteDetailsRejectMalformedRawAndFormattedAmounts() {
        val asset = fixture.externalAssets.first()
        val details = response(fixture.nativeZec, asset, LARGE_BASE_UNITS).quote
        val serializer = QuoteDetails.serializer()
        val encoded = Json.encodeToJsonElement(serializer, details).jsonObject

        listOf("amountIn", "amountInFormatted", "amountOut", "amountOutFormatted").forEach { field ->
            val malformed = JsonObject(encoded + (field to JsonPrimitive("1.2.3")))
            assertFailsWith<IllegalArgumentException>(field) {
                Json.decodeFromJsonElement(serializer, malformed)
            }
        }
    }

    private fun assertIdentityMismatch(expected: NearSwapAsset, other: NearSwapAsset) {
        val label = "${expected.label} vs ${other.label}"
        val error = assertFailsWith<SwapQuoteMismatchException>(label) {
            requireNearAssetIdentity(expected, other.assetId, SwapQuoteMismatchType.DESTINATION_ASSET)
        }
        assertEquals(SwapQuoteMismatchType.DESTINATION_ASSET, error.type, label)
    }

    private fun response(origin: NearSwapAsset, destination: NearSwapAsset, units: BigDecimal) =
        QuoteResponseDto(
            timestamp = EPOCH,
            quoteRequest = QuoteRequest(
                dry = true,
                swapType = SwapType.EXACT_INPUT,
                slippageTolerance = 0,
                originAsset = origin.assetId,
                destinationAsset = destination.assetId,
                amount = units,
                refundTo = REFUND_ADDRESS,
                recipient = DESTINATION_ADDRESS,
                deadline = EPOCH,
                appFees = emptyList(),
            ),
            quote = QuoteDetails(
                depositAddress = DEPOSIT_ADDRESS,
                amountIn = units,
                amountInFormatted = units.movePointLeft(origin.decimals),
                amountInUsd = BigDecimal.ZERO,
                minAmountIn = units,
                amountOut = units,
                amountOutFormatted = units.movePointLeft(destination.decimals),
                amountOutUsd = BigDecimal.ZERO,
                minAmountOut = units,
                deadline = EPOCH,
            ),
        )

    private fun quote(origin: NearSwapAsset, destination: NearSwapAsset, response: QuoteResponseDto) =
        NearSwapQuote(
            response = response,
            originAsset = origin,
            destinationAsset = destination,
            depositAddress = DynamicSwapAddress(DEPOSIT_ADDRESS),
            destinationAddress = DynamicSwapAddress(DESTINATION_ADDRESS),
            refundAddress = DynamicSwapAddress(REFUND_ADDRESS),
            expectedSlippageToleranceBps = 0,
        )

    private val NearSwapAsset.label: String
        get() = "$tokenTicker/$chainTicker ($assetId)"

    private companion object {
        const val DEPOSIT_ADDRESS = "test-deposit"
        const val DESTINATION_ADDRESS = "test-destination"
        const val REFUND_ADDRESS = "test-refund"
        val EPOCH = Instant.fromEpochSeconds(0)
        val HALF_BASE_UNIT = BigDecimal("0.5")
        val LARGE_BASE_UNITS = BigDecimal("9007199254740993000000000000000001")
    }
}
