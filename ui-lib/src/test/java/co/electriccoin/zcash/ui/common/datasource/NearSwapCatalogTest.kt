package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.model.DynamicSwapAddress
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchException
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.ZcashShieldedSwapAddress
import co.electriccoin.zcash.ui.common.model.near.NearSwapAsset
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.NearSwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.provider.NearApiProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import co.electriccoin.zcash.ui.common.model.near.SwapStatus as ProviderSwapStatus

class NearSwapCatalogTest {
    private val catalog = SwapCatalogTestFixture.assets
    private val zec = SwapCatalogTestFixture.nativeZec
    private val externalAssets = SwapCatalogTestFixture.externalAssets
    private val bitcoin = externalAssets.single { it.assetId == "nep141:btc.omft.near" }
    private val omni = bitcoin.copy(assetId = "1cs_v1:btc:native:coin", tokenTicker = "BTC(OMNI)")
    private val nearApiProvider = mockk<NearApiProvider>()
    private val synchronizer = mockk<Synchronizer>()
    private val synchronizerProvider = mockk<SynchronizerProvider>()
    private val dataSource = NearSwapDataSource(
        nearApiProvider,
        SwapCatalogTestFixture.tokenIcons,
        SwapCatalogTestFixture.tokenNames,
        SwapCatalogTestFixture.blockchains,
        synchronizerProvider
    )

    init {
        coEvery { synchronizerProvider.getSynchronizer() } returns synchronizer
        coEvery { synchronizer.validateAddress(ZEC_DEPOSIT) } returns AddressType.Shielded
        coEvery { synchronizer.validateAddress(ZEC_RECIPIENT) } returns AddressType.Shielded
        coEvery { synchronizer.validateAddress(ZEC_REFUND) } returns AddressType.Shielded
    }

    @Test
    fun catalogDecoderAndMapperPreserveEverySupportedIdentityWithExtraProviderEntries() = runBlocking {
        val frozen = checkNotNull(javaClass.getResourceAsStream("/swap-catalog.json"))
            .bufferedReader().use { it.readText() }
        val entries = Json.decodeFromString<List<NearTokenDto>>(frozen)
        val btc = entries.single { it.assetId == bitcoin.assetId }
        val extra = listOf(
            btc.copy(assetId = omni.assetId, symbol = "BTC(OMNI)"),
            btc.copy(assetId = "test:other-token", symbol = "OTHER"),
        )
        for (order in listOf(entries + extra, (entries + extra).reversed())) {
            coEvery { nearApiProvider.getSupportedTokens() } returns order
            val mapped = dataSource.getSupportedTokens()
            for (asset in catalog) {
                val selected = mapped.single { it.assetId == asset.assetId }
                assertEquals(asset.copy(usdPrice = null), selected, asset.assetId)
            }
            assertEquals(omni.assetId, mapped.single { it.tokenTicker == "BTC(OMNI)" }.assetId)
        }
    }

    @Test
    fun quoteRequestsPreserveCatalogIdentityAndNormalizeTheFixedSide() =
        runBlocking {
            for (asset in externalAssets) {
                assertQuoteRequest(Route(asset, zec, SwapType.FLEX_INPUT), SwapMode.FLEX_INPUT)
                assertQuoteRequest(Route(zec, asset, SwapType.EXACT_INPUT), SwapMode.EXACT_INPUT)
                assertQuoteRequest(Route(zec, asset, SwapType.EXACT_OUTPUT), SwapMode.EXACT_OUTPUT)
            }
        }

    @Test
    fun quoteRejectsInvalidMinimumsAndFractionalAtomicAmounts() = runBlocking {
        val route = Route(bitcoin, zec, SwapType.FLEX_INPUT)
        val quote = quoteJson(route)
        val cases = listOf(
            quote.replace(
                "\"minAmountOut\": \"${route.destinationAmount.atomic}\"",
                "\"minAmountOut\": \"${route.destinationAmount.executedAtomic}\""
            ),
            quote.replace(
                "\"amountOut\": \"${route.destinationAmount.atomic}\"",
                "\"amountOut\": \"${route.destinationAmount.atomic}.5\""
            ).replace(
                "\"amountOutFormatted\": \"${route.destinationAmount.formatted}\"",
                "\"amountOutFormatted\": \"${route.destinationAmount.formatted}5\""
            ),
            quote.replace("\"minAmountIn\": \"${route.originAmount.atomic}\"", "\"minAmountIn\": \"0\""),
            quote.replace("\"minAmountIn\": \"${route.originAmount.atomic}\"", "\"minAmountIn\": \"0.5\""),
        )
        for (invalid in cases) {
            coEvery { nearApiProvider.requestQuote(any()) } returns Json.decodeFromString<QuoteResponseDto>(invalid)
            assertFailsWith<SwapQuoteMismatchException.Rejected> { requestQuote(route, SwapMode.FLEX_INPUT) }
        }
    }

    @Test
    fun quoteRejectsUnsupportedFundingInstructionsInDecodedResponses() = runBlocking {
        val route = Route(bitcoin, zec, SwapType.FLEX_INPUT)
        val plain = quoteJson(route)
        val cases = listOf(
            plain.replace("\"depositAddress\":", "\"depositMemo\": \"123\", \"depositAddress\":"),
            plain.replace(
                "\"depositAddress\":",
                "\"chainDepositAddresses\": [{\"address\": \"${route.deposit}\", \"memo\": \"123\"}], " +
                    "\"depositAddress\":"
            ),
            plain.replace("\"depositMode\": \"SIMPLE\"", "\"depositMode\": \"MEMO\""),
            plain.replace("\"depositType\": \"ORIGIN_CHAIN\"", "\"depositType\": \"INTENTS\""),
            plain.replace("\"refundType\": \"ORIGIN_CHAIN\"", "\"refundType\": \"INTENTS\""),
            plain.replace("\"recipientType\": \"DESTINATION_CHAIN\"", "\"recipientType\": \"CONFIDENTIAL_INTENTS\""),
        )
        for (invalid in cases) {
            coEvery { nearApiProvider.requestQuote(any()) } returns Json.decodeFromString<QuoteResponseDto>(invalid)
            assertFailsWith<IllegalArgumentException> { requestQuote(route, SwapMode.FLEX_INPUT) }
        }
    }

    @Test
    fun simpleOriginQuoteIgnoresMemoForASeparateAddress() = runBlocking {
        val route = Route(bitcoin, zec, SwapType.FLEX_INPUT)
        val response = quoteJson(route).replace(
            "\"depositAddress\":",
            "\"chainDepositAddresses\": [{\"blockchain\": \"stellar\", \"address\": \"other\", \"memo\": \"123\"}], " +
                "\"depositAddress\":"
        )
        coEvery { nearApiProvider.requestQuote(any()) } returns Json.decodeFromString<QuoteResponseDto>(response)
        assertEquals(route.deposit, requestQuote(route, SwapMode.FLEX_INPUT).depositAddress.address)
    }

    @Test
    fun successfulStatusesPreserveAmountsAndAssetIdentityInEitherCatalogOrder() =
        runBlocking {
            val statusCatalog = catalog + omni
            val orders = listOf(statusCatalog, statusCatalog.reversed())
            for (asset in externalAssets + omni) {
                assertSuccessfulStatus(Route(asset, zec, SwapType.FLEX_INPUT), orders)
                assertSuccessfulStatus(Route(zec, asset, SwapType.EXACT_INPUT), orders)
            }
        }

    @Test
    fun outgoingStatusesMapEachProviderState() =
        runBlocking {
            val route = Route(zec, bitcoin, SwapType.EXACT_INPUT)
            val cases = mapOf(
                ProviderSwapStatus.KNOWN_DEPOSIT_TX to SwapStatus.PENDING,
                ProviderSwapStatus.PENDING_DEPOSIT to SwapStatus.PENDING,
                ProviderSwapStatus.INCOMPLETE_DEPOSIT to SwapStatus.INCOMPLETE_DEPOSIT,
                ProviderSwapStatus.PROCESSING to SwapStatus.PROCESSING,
                ProviderSwapStatus.SUCCESS to SwapStatus.SUCCESS,
                ProviderSwapStatus.REFUNDED to SwapStatus.REFUNDED,
                ProviderSwapStatus.FAILED to SwapStatus.FAILED,
                ProviderSwapStatus.EXPIRED to SwapStatus.EXPIRED
            )
            assertEquals(ProviderSwapStatus.entries.toSet(), cases.keys)
            for ((providerStatus, expectedStatus) in cases) {
                coEvery { nearApiProvider.checkSwapStatus(route.deposit) } returns statusResponse(route, providerStatus)
                val result = dataSource.checkSwapStatus(route.deposit, catalog)
                assertEquals(expectedStatus, result.status, "${route.label}, $providerStatus")
                assertEquals(SwapMode.EXACT_INPUT, result.mode, route.label)
            }
        }

    @Test
    fun statusRejectsMissingOrDuplicateAssetIdentities() =
        runBlocking {
            // Use a non-aliased asset so the Bitcoin legacy identity cannot replace the missing entry.
            val asset = externalAssets.single { it.assetId == "nep141:eth.omft.near" }
            for (route in listOf(Route(asset, zec, SwapType.FLEX_INPUT), Route(zec, asset, SwapType.EXACT_OUTPUT))) {
                coEvery { nearApiProvider.checkSwapStatus(route.deposit) } returns
                    statusResponse(route, ProviderSwapStatus.PENDING_DEPOSIT)
                for (supported in listOf(catalog - asset, catalog + asset.copy())) {
                    val exception = assertFailsWith<AssetNotFoundException>(route.label) {
                        dataSource.checkSwapStatus(route.deposit, supported)
                    }
                    assertTrue(exception.message.orEmpty().contains(asset.assetId), route.label)
                }
            }
        }

    @Test
    fun quoteRejectsAnEchoWithADifferentAssetIdentity() =
        runBlocking {
            val bridgedBitcoin = externalAssets.single { it.assetId == "nep141:nbtc.bridge.near" }
            val incoming = Route(bitcoin, zec, SwapType.FLEX_INPUT)
            val outgoing = Route(zec, bitcoin, SwapType.EXACT_OUTPUT)
            coEvery { nearApiProvider.requestQuote(any()) } returns
                Json.decodeFromString<QuoteResponseDto>(quoteJson(incoming.copy(origin = bridgedBitcoin)))
            val rejectedOrigin = assertFailsWith<SwapQuoteMismatchException.Rejected>(incoming.label) {
                requestQuote(incoming, SwapMode.FLEX_INPUT)
            }
            assertEquals(SwapQuoteMismatchType.ORIGIN_ASSET, rejectedOrigin.type, incoming.label)

            coEvery { nearApiProvider.requestQuote(any()) } returns
                Json.decodeFromString<QuoteResponseDto>(quoteJson(outgoing.copy(destination = bridgedBitcoin)))
            val rejectedDestination = assertFailsWith<SwapQuoteMismatchException.Rejected>(outgoing.label) {
                requestQuote(outgoing, SwapMode.EXACT_OUTPUT)
            }
            assertEquals(SwapQuoteMismatchType.DESTINATION_ASSET, rejectedDestination.type, outgoing.label)
        }

    private suspend fun assertSuccessfulStatus(route: Route, orders: List<List<NearSwapAsset>>) {
        val response = statusResponse(route, ProviderSwapStatus.SUCCESS)
        coEvery { nearApiProvider.checkSwapStatus(route.deposit) } returns response
        for ((order, supported) in orders.withIndex()) {
            val label = "${route.label}, catalog order $order"
            val result = assertIs<NearSwapQuoteStatus>(dataSource.checkSwapStatus(route.deposit, supported), label)
            assertEquals(SwapStatus.SUCCESS, result.status, label)
            assertEquals(route.origin, result.originAsset, label)
            assertEquals(route.destination, result.destinationAsset, label)
            assertEquals(route.originAmount.atomic, result.quote.amountIn, label)
            assertEquals(route.destinationAmount.atomic, result.quote.amountOut, label)
            assertEquals(route.originAmount.formatted, result.quote.amountInFormatted, label)
            assertEquals(route.destinationAmount.formatted, result.quotedAmountOut, label)
            assertEquals(route.originAmount.formatted, result.depositedAmountFormatted, label)
            assertEquals(route.originAmount.formatted, result.executedAmountIn, label)
            assertEquals(route.destinationAmount.afterExecution, result.executedAmountOut, label)
            assertEquals(route.destinationAmount.afterExecution, result.amountOutFormatted, label)
            assertEquals(route.deposit, result.depositAddress.address, label)
            assertEquals(route.recipient, result.destinationAddress.address, label)
            assertEquals(route.refund, result.refundAddress.address, label)
            if (route.outgoing) {
                assertIs<ZcashShieldedSwapAddress>(result.depositAddress, label)
                assertIs<DynamicSwapAddress>(result.destinationAddress, label)
                assertIs<ZcashShieldedSwapAddress>(result.refundAddress, label)
            } else {
                assertIs<DynamicSwapAddress>(result.depositAddress, label)
                assertIs<ZcashShieldedSwapAddress>(result.destinationAddress, label)
                assertIs<DynamicSwapAddress>(result.refundAddress, label)
            }
        }
    }

    private suspend fun assertQuoteRequest(route: Route, mode: SwapMode) {
        val request = slot<QuoteRequest>()
        coEvery { nearApiProvider.requestQuote(capture(request)) } returns
            Json.decodeFromString<QuoteResponseDto>(quoteJson(route))

        val quote = requestQuote(route, mode)

        assertEquals(route.type, request.captured.swapType, route.label)
        assertEquals(route.origin.assetId, request.captured.originAsset, route.label)
        assertEquals(route.destination.assetId, request.captured.destinationAsset, route.label)
        assertEquals(0, route.fixedAmount.atomic.compareTo(request.captured.amount), route.label)
        assertEquals(route.refund, request.captured.refundTo, route.label)
        assertEquals(route.recipient, request.captured.recipient, route.label)
        assertEquals("SIMPLE", request.captured.depositMode, route.label)
        assertEquals(SLIPPAGE_BPS, request.captured.slippageTolerance, route.label)
        assertEquals(mode, quote.mode, route.label)
        assertEquals(route.origin, quote.originAsset, route.label)
        assertEquals(route.destination, quote.destinationAsset, route.label)
        assertEquals(route.originAmount.atomic, quote.amountIn, route.label)
        assertEquals(route.destinationAmount.atomic, quote.amountOut, route.label)
    }

    private suspend fun requestQuote(route: Route, mode: SwapMode) =
        dataSource.requestQuote(
            swapMode = mode,
            amount = ENTERED_AMOUNT,
            refundAddress = route.refund,
            originAsset = route.origin,
            destinationAddress = route.recipient,
            destinationAsset = route.destination,
            slippage = BigDecimal.ONE
        )

    private fun statusResponse(route: Route, status: ProviderSwapStatus): SwapStatusResponseDto {
        val details = if (status == ProviderSwapStatus.SUCCESS) {
            """
            ,"swapDetails": {
                "depositedAmount": "${route.originAmount.atomic}",
                "depositedAmountFormatted": "${route.originAmount.formatted.toPlainString()}",
                "amountIn": "${route.originAmount.atomic}",
                "amountOut": "${route.destinationAmount.executedAtomic}"
            }
            """.trimIndent()
        } else {
            ""
        }
        return Json.decodeFromString(
            """
            {
                "status": "${status.apiValue}",
                "updatedAt": "2026-11-20T00:00:00Z",
                "quoteResponse": ${quoteJson(route)}$details
            }
            """.trimIndent()
        )
    }

    private fun quoteJson(route: Route): String =
        """
        {
            "timestamp": "2026-11-20T00:00:00Z",
            "quoteRequest": {
                "dry": false,
                "swapType": "${route.type.apiValue}",
                "slippageTolerance": $SLIPPAGE_BPS,
                "originAsset": "${route.origin.assetId}",
                "depositType": "ORIGIN_CHAIN",
                "depositMode": "SIMPLE",
                "destinationAsset": "${route.destination.assetId}",
                "amount": "${route.fixedAmount.atomic}",
                "refundTo": "${route.refund}",
                "refundType": "ORIGIN_CHAIN",
                "recipient": "${route.recipient}",
                "recipientType": "DESTINATION_CHAIN",
                "deadline": "2026-11-21T00:00:00Z",
                "appFees": []
            },
            "quote": {
                "depositAddress": "${route.deposit}",
                "amountIn": "${route.originAmount.atomic}",
                "amountInFormatted": "${route.originAmount.formatted.toPlainString()}",
                "amountInUsd": "60",
                "minAmountIn": "${route.originAmount.atomic}",
                "amountOut": "${route.destinationAmount.atomic}",
                "amountOutFormatted": "${route.destinationAmount.formatted.toPlainString()}",
                "amountOutUsd": "59",
                "minAmountOut": "${route.destinationAmount.atomic}",
                "deadline": "2026-11-21T00:00:00Z"
            }
        }
        """.trimIndent()

    private data class Route(val origin: NearSwapAsset, val destination: NearSwapAsset, val type: SwapType) {
        val outgoing = type != SwapType.FLEX_INPUT
        val label = "${origin.assetId} to ${destination.assetId} ($type)"
        val deposit = if (outgoing) ZEC_DEPOSIT else "synthetic-external-deposit"
        val recipient = if (outgoing) "synthetic-external-recipient" else ZEC_RECIPIENT
        val refund = if (outgoing) ZEC_REFUND else "synthetic-external-refund"
        val originAmount =
            Amount(if (type == SwapType.EXACT_OUTPUT) COUNTER_AMOUNT else ENTERED_AMOUNT, origin.decimals)
        val destinationAmount =
            Amount(if (type == SwapType.EXACT_OUTPUT) ENTERED_AMOUNT else COUNTER_AMOUNT, destination.decimals)
        val fixedAmount = if (type == SwapType.EXACT_OUTPUT) destinationAmount else originAmount
    }

    private class Amount(value: BigDecimal, decimals: Int) {
        val formatted: BigDecimal = value.setScale(decimals, RoundingMode.DOWN)
        val atomic: BigDecimal = formatted.movePointRight(decimals)
        val executedAtomic: BigDecimal = atomic.add(BigDecimal.ONE)
        val afterExecution: BigDecimal = executedAtomic.movePointLeft(decimals)
    }

    private companion object {
        val ENTERED_AMOUNT = BigDecimal("1.1234567890123456789012349")
        val COUNTER_AMOUNT = BigDecimal("2.2345678901234567890123459")
        const val SLIPPAGE_BPS = 100
        const val ZEC_DEPOSIT = "synthetic-zec-deposit"
        const val ZEC_RECIPIENT = "synthetic-zec-recipient"
        const val ZEC_REFUND = "synthetic-zec-refund"
    }
}
