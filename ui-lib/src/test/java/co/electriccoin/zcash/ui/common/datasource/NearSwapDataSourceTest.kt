package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchException
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import co.electriccoin.zcash.ui.common.model.near.AppFee
import co.electriccoin.zcash.ui.common.model.near.ErrorDto
import co.electriccoin.zcash.ui.common.model.near.SwapInputProvenanceFixture
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.NearSwapAsset
import co.electriccoin.zcash.ui.common.model.near.QuoteRequest
import co.electriccoin.zcash.ui.common.model.near.QuoteResponseDto
import co.electriccoin.zcash.ui.common.model.near.SubmitDepositTransactionRequest
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.model.near.SwapType
import co.electriccoin.zcash.ui.common.provider.BlockchainProvider
import co.electriccoin.zcash.ui.common.provider.NearApiProvider
import co.electriccoin.zcash.ui.common.provider.ResponseWithNearErrorException
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.TokenIconProvider
import co.electriccoin.zcash.ui.common.provider.TokenNameProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * [NearSwapDataSource] maps the 1Click API: preserving complete supported identities and mapping them to
 * `SwapAsset` models, building the quote request (slippage in bps, amount normalized to the asset's
 * decimals, asset ids/addresses), and translating NEAR error responses into typed exceptions.
 */
class NearSwapDataSourceTest {
    private val nearApiProvider = mockk<NearApiProvider>()
    private val tokenIconProvider = mockk<TokenIconProvider>(relaxed = true)
    private val tokenNameProvider = mockk<TokenNameProvider>(relaxed = true)
    private val blockchainProvider = mockk<BlockchainProvider>(relaxed = true)
    private val synchronizerProvider = mockk<SynchronizerProvider>(relaxed = true)
    private val dataSource =
        NearSwapDataSource(
            nearApiProvider,
            tokenIconProvider,
            tokenNameProvider,
            blockchainProvider,
            synchronizerProvider
        )

    private val origin = SwapAssetTestFixture.asset(tokenTicker = "btc", chainTicker = "btc")
    private val zec = SwapAssetTestFixture.zecAsset()

    @Test
    fun externalAddressFamiliesKeepTheirExactRecipientAndRefundEncoding() =
        runBlocking {
            val addresses = listOf(
                "btc" to "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                "eth" to "0x52908400098527886E0F7030069857D2E4169EE7",
                "sol" to "11111111111111111111111111111111",
                "xrp" to "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"
            )
            for ((chain, address) in addresses) {
                val asset = SwapAssetTestFixture.asset(tokenTicker = chain, chainTicker = chain)
                for (outgoing in listOf(false, true)) {
                    val request = slot<QuoteRequest>()
                    coEvery { nearApiProvider.requestQuote(capture(request)) } throws nearError("No quotes found")
                    assertFailsWith<QuoteLowAmountException> {
                        dataSource.requestQuote(
                            swapMode = if (outgoing) SwapMode.EXACT_OUTPUT else SwapMode.EXACT_INPUT,
                            amount = BigDecimal.ONE,
                            refundAddress = if (outgoing) "zec-refund" else address,
                            originAsset = if (outgoing) zec else asset,
                            destinationAddress = if (outgoing) address else "zec-recipient",
                            destinationAsset = if (outgoing) asset else zec,
                            slippage = BigDecimal("0.5")
                        )
                    }
                    assertEquals(if (outgoing) address else "zec-recipient", request.captured.recipient)
                    assertEquals(if (outgoing) "zec-refund" else address, request.captured.refundTo)
                }
            }
        }

    @Test
    fun quoteModesPreserveRepresentativeAtomicUnitPrecisions() =
        runBlocking {
            data class PrecisionCase(val decimals: Int, val value: String, val units: String)
            val cases = listOf(
                PrecisionCase(0, "7", "7"),
                PrecisionCase(6, "0.000001", "1"),
                PrecisionCase(8, "0.00000001", "1"),
                PrecisionCase(18, "1.000000000000000001", "1000000000000000001")
            )
            for (case in cases) {
                val asset =
                    SwapAssetTestFixture.asset(tokenTicker = "precision", chainTicker = "eth", decimals = case.decimals)
                for (mode in listOf(SwapMode.EXACT_INPUT, SwapMode.EXACT_OUTPUT, SwapMode.FLEX_INPUT)) {
                    val request = slot<QuoteRequest>()
                    coEvery { nearApiProvider.requestQuote(capture(request)) } throws nearError("No quotes found")
                    assertFailsWith<QuoteLowAmountException> {
                        dataSource.requestQuote(
                            swapMode = mode,
                            amount = BigDecimal(case.value),
                            refundAddress = "refund",
                            originAsset = if (mode == SwapMode.EXACT_OUTPUT) zec else asset,
                            destinationAddress = "destination",
                            destinationAsset = if (mode == SwapMode.EXACT_OUTPUT) asset else zec,
                            slippage = BigDecimal("0.5")
                        )
                    }
                    assertEquals(
                        0,
                        BigDecimal(case.units).compareTo(request.captured.amount),
                        "$mode with ${case.decimals} decimals"
                    )
                }
            }
        }

    @Test
    fun getSupportedTokensPreservesSameDisplayIdentitiesAndMapsTokens() =
        runBlocking {
            coEvery { nearApiProvider.getSupportedTokens() } returns
                listOf(
                    token(assetId = "a1", symbol = "btc", blockchain = "btc", decimals = 8, price = "100000"),
                    // A distinct exact identity retains its own recovery meaning.
                    token(assetId = "a2", symbol = "btc", blockchain = "btc", decimals = 8, price = "100000"),
                    token(assetId = "e1", symbol = "eth", blockchain = "eth", decimals = 18, price = "3000"),
                )

            val result = dataSource.getSupportedTokens()

            assertEquals(3, result.size)
            assertEquals(listOf("a1", "a2"), result.filter { it.tokenTicker == "btc" }.map { it.assetId })
            val btc = result.single { it.assetId == "a1" }
            assertEquals("a1", btc.assetId)
            assertEquals(8, btc.decimals)
            assertEquals(0, BigDecimal("100000").compareTo(btc.usdPrice))
            val eth = result.single { it.tokenTicker == "eth" }
            assertEquals("e1", eth.assetId)
            assertEquals(18, eth.decimals)
            assertEquals(0, BigDecimal("3000").compareTo(eth.usdPrice))
        }

    @Test
    fun requestQuoteBuildsNormalizedRequest() =
        runBlocking {
            val request = slot<QuoteRequest>()
            coEvery { nearApiProvider.requestQuote(capture(request)) } throws nearError("No quotes found")

            assertFailsWith<QuoteLowAmountException> {
                dataSource.requestQuote(
                    swapMode = SwapMode.EXACT_INPUT,
                    amount = BigDecimal("1.5"),
                    refundAddress = "refund",
                    originAsset = origin,
                    destinationAddress = "destination",
                    destinationAsset = zec,
                    slippage = BigDecimal("2")
                )
            }

            val captured = request.captured
            assertFalse(captured.dry)
            assertEquals(SwapType.EXACT_INPUT, captured.swapType)
            assertEquals(200, captured.slippageTolerance) // 2% -> 200 bps
            assertEquals(origin.assetId, captured.originAsset)
            assertEquals(zec.assetId, captured.destinationAsset)
            assertEquals("refund", captured.refundTo)
            assertEquals("destination", captured.recipient)
            // 1.5 normalized to the origin's 8 decimals.
            assertEquals(0, BigDecimal("150000000").compareTo(captured.amount))
            // Independent quotes carry no affiliate fee or referral.
            assertEquals(null, captured.referral)
            assertEquals(emptyList(), captured.appFees)
        }

    @Test
    fun freshQuoteBindsTheEchoToWhatWasPostedForEveryMode() = runBlocking {
        val deposit = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
        val synchronizer = mockk<Synchronizer> {
            coEvery { validateAddress(any()) } returns AddressType.Unified
        }
        coEvery { synchronizerProvider.getSynchronizer() } returns synchronizer
        for (mode in SwapMode.entries) {
            val response = SwapInputProvenanceFixture.status(
                origin, zec, deposit, SwapType.valueOf(mode.name), "{}",
            ).response.quoteResponse
            var differingEcho = true
            coEvery { nearApiProvider.requestQuote(any()) } coAnswers {
                val posted = firstArg<QuoteRequest>()
                val echoed = if (differingEcho) posted.amount + BigDecimal.ONE else posted.amount.setScale(2)
                response.copy(quoteRequest = posted.copy(amount = echoed))
            }
            val fixedAmount = if (mode == SwapMode.EXACT_OUTPUT) BigDecimal("2") else BigDecimal.ONE

            val rejection = assertFailsWith<SwapQuoteMismatchException.Rejected> {
                dataSource.requestQuote(mode, fixedAmount, "refund", origin, "recipient", zec, BigDecimal.ONE)
            }
            assertEquals(SwapQuoteMismatchType.REQUESTED_AMOUNT, rejection.type, mode.name)
            assertEquals(deposit, rejection.depositAddress)

            differingEcho = false
            val admitted = dataSource.requestQuote(
                mode, fixedAmount, "refund", origin, "recipient", zec, BigDecimal.ONE,
            )
            assertEquals(mode, admitted.mode)
        }
    }

    @Test
    fun incomingQuoteChecksTheExternalDepositAgainstTheOriginNetwork() = runBlocking {
        val returned = SwapInputProvenanceFixture.status(
            origin, zec, "t1wrongnetwork", SwapType.FLEX_INPUT, "{}",
        ).response.quoteResponse
        coEvery { nearApiProvider.requestQuote(any()) } coAnswers {
            returned.copy(quoteRequest = firstArg())
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            dataSource.requestQuote(
                SwapMode.FLEX_INPUT, BigDecimal.ONE, "refund", origin, "destination", zec, BigDecimal.ONE
            )
        }
        assertEquals("Swap quote deposit address does not match the selected network", failure.message)
    }

    @Test
    fun freshQuoteRefusesAnUnexpectedAppFee() = runBlocking {
        val returned = SwapInputProvenanceFixture.status(
            origin, zec, "synthetic-deposit", SwapType.FLEX_INPUT, "{}",
        ).response.quoteResponse
        coEvery { nearApiProvider.requestQuote(any()) } coAnswers {
            returned.copy(quoteRequest = firstArg<QuoteRequest>().copy(appFees = listOf(AppFee("fixture", 1))))
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            dataSource.requestQuote(
                SwapMode.FLEX_INPUT, BigDecimal.ONE, "refund", origin, "destination", zec, BigDecimal.ONE
            )
        }
        assertEquals("Unexpected swap app fee", failure.message)
    }

    @Test
    fun requestQuoteMapsAmountTooLowToOriginAssetForExactInput() =
        runBlocking {
            coEvery { nearApiProvider.requestQuote(any()) } throws
                nearError("Amount is too low for bridge, try at least 1000")

            val exception =
                assertFailsWith<QuoteLowAmountException> {
                    dataSource.requestQuote(
                        swapMode = SwapMode.EXACT_INPUT,
                        amount = BigDecimal("1"),
                        refundAddress = "refund",
                        originAsset = origin,
                        destinationAddress = "destination",
                        destinationAsset = zec,
                        slippage = BigDecimal("2")
                    )
                }

            assertEquals(origin, exception.asset)
            assertEquals(0, BigDecimal("1000").compareTo(exception.amount))
            // Formatted against the origin's 8 decimals.
            assertEquals(0, BigDecimal("0.00001").compareTo(exception.amountFormatted))
        }

    @Test
    fun requestQuoteMapsAmountTooLowToDestinationAssetForExactOutput() =
        runBlocking {
            val destination = SwapAssetTestFixture.asset(tokenTicker = "usdc", chainTicker = "eth", decimals = 6)
            coEvery { nearApiProvider.requestQuote(any()) } throws
                nearError("Amount is too low for bridge, try at least 1000")

            val exception =
                assertFailsWith<QuoteLowAmountException> {
                    dataSource.requestQuote(
                        swapMode = SwapMode.EXACT_OUTPUT,
                        amount = BigDecimal("1"),
                        refundAddress = "refund",
                        originAsset = zec,
                        destinationAddress = "destination",
                        destinationAsset = destination,
                        slippage = BigDecimal("2")
                    )
                }

            assertEquals(destination, exception.asset)
            assertEquals(0, BigDecimal("0.001").compareTo(exception.amountFormatted)) // 1000 / 10^6
        }

    @Test
    fun requestQuoteMapsNoQuotesFoundToNullAmounts() =
        runBlocking {
            coEvery { nearApiProvider.requestQuote(any()) } throws nearError("No quotes found")

            val exception =
                assertFailsWith<QuoteLowAmountException> {
                    dataSource.requestQuote(
                        swapMode = SwapMode.EXACT_INPUT,
                        amount = BigDecimal("1"),
                        refundAddress = "refund",
                        originAsset = origin,
                        destinationAddress = "destination",
                        destinationAsset = zec,
                        slippage = BigDecimal("2")
                    )
                }

            assertEquals(origin, exception.asset)
            assertNull(exception.amount)
            assertNull(exception.amountFormatted)
        }

    @Test
    fun requestQuoteRethrowsUnmappedNearError() {
        runBlocking {
            coEvery { nearApiProvider.requestQuote(any()) } throws nearError("Some unrelated error")

            assertFailsWith<ResponseWithNearErrorException> {
                dataSource.requestQuote(
                    swapMode = SwapMode.EXACT_INPUT,
                    amount = BigDecimal("1"),
                    refundAddress = "refund",
                    originAsset = origin,
                    destinationAddress = "destination",
                    destinationAsset = zec,
                    slippage = BigDecimal("2")
                )
            }
        }
    }

    /**
     * The rejection carries the quote id from the raw response — the only place it exists when the
     * quote itself never gets built.
     */
    @Test
    fun requestQuoteRejectsSwapTypeMismatchFromServer() {
        runBlocking {
            val response =
                mockk<QuoteResponseDto> {
                    every { quoteRequest } returns mockk {
                        every { dry } returns false
                        every { swapType } returns SwapType.EXACT_OUTPUT
                    }
                    every { quote } returns mockk { every { depositAddress } returns "deposit-address" }
                }
            coEvery { nearApiProvider.requestQuote(any()) } returns response

            val exception =
                assertFailsWith<SwapQuoteMismatchException> {
                    dataSource.requestQuote(
                        swapMode = SwapMode.EXACT_INPUT,
                        amount = BigDecimal("1"),
                        refundAddress = "refund",
                        originAsset = origin,
                        destinationAddress = "destination",
                        destinationAsset = zec,
                        slippage = BigDecimal("2")
                    )
                }

            assertEquals(SwapQuoteMismatchType.SWAP_TYPE, exception.type)
            assertEquals("deposit-address", exception.depositAddress)
        }
    }

    @Test
    fun outgoingQuotesRejectTexDepositsBeforeCreatingAProposal() =
        runBlocking {
            val synchronizer = mockk<Synchronizer>()
            coEvery { synchronizer.validateAddress("tex-deposit") } returns AddressType.Tex
            coEvery { synchronizerProvider.getSynchronizer() } returns synchronizer
            for ((mode, type) in listOf(
                SwapMode.EXACT_INPUT to SwapType.EXACT_INPUT,
                SwapMode.EXACT_OUTPUT to SwapType.EXACT_OUTPUT
            )) {
                val response =
                    mockk<QuoteResponseDto> {
                        every { quoteRequest } returns mockk {
                            every { dry } returns false
                            every { swapType } returns type
                        }
                        every { quote } returns mockk { every { depositAddress } returns "tex-deposit" }
                    }
                coEvery { nearApiProvider.requestQuote(any()) } returns response
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        dataSource.requestQuote(
                            swapMode = mode,
                            amount = BigDecimal.ONE,
                            refundAddress = "refund",
                            originAsset = zec,
                            destinationAddress = "destination",
                            destinationAsset = origin,
                            slippage = BigDecimal("2")
                        )
                    }
                assertEquals(true, error.message?.contains("TEX"))
            }
        }

    @Test
    fun requestQuoteBuildsExactOutputRequestNormalizedToDestinationDecimals() =
        runBlocking {
            val destination = SwapAssetTestFixture.asset(tokenTicker = "usdc", chainTicker = "eth", decimals = 6)
            val request = slot<QuoteRequest>()
            coEvery { nearApiProvider.requestQuote(capture(request)) } throws nearError("No quotes found")

            assertFailsWith<QuoteLowAmountException> {
                dataSource.requestQuote(
                    swapMode = SwapMode.EXACT_OUTPUT,
                    amount = BigDecimal("1.5"),
                    refundAddress = "refund",
                    originAsset = zec,
                    destinationAddress = "destination",
                    destinationAsset = destination,
                    slippage = BigDecimal("2")
                )
            }

            val captured = request.captured
            assertEquals(SwapType.EXACT_OUTPUT, captured.swapType)
            // EXACT_OUTPUT normalizes against the destination's 6 decimals, not the origin's.
            assertEquals(0, BigDecimal("1500000").compareTo(captured.amount))
        }

    @Test
    fun requestQuoteBuildsFlexInputRequestNormalizedToOriginDecimals() =
        runBlocking {
            val request = slot<QuoteRequest>()
            coEvery { nearApiProvider.requestQuote(capture(request)) } throws nearError("No quotes found")

            assertFailsWith<QuoteLowAmountException> {
                dataSource.requestQuote(
                    swapMode = SwapMode.FLEX_INPUT,
                    amount = BigDecimal("1.5"),
                    refundAddress = "refund",
                    originAsset = origin,
                    destinationAddress = "destination",
                    destinationAsset = zec,
                    slippage = BigDecimal("2")
                )
            }

            val captured = request.captured
            assertEquals(SwapType.FLEX_INPUT, captured.swapType)
            // FLEX_INPUT normalizes against the origin's 8 decimals.
            assertEquals(0, BigDecimal("150000000").compareTo(captured.amount))
        }

    @Test
    fun requestQuoteMapsAmountTooLowToOriginAssetForFlexInput() =
        runBlocking {
            coEvery { nearApiProvider.requestQuote(any()) } throws
                nearError("Amount is too low for bridge, try at least 1000")

            val exception =
                assertFailsWith<QuoteLowAmountException> {
                    dataSource.requestQuote(
                        swapMode = SwapMode.FLEX_INPUT,
                        amount = BigDecimal("1"),
                        refundAddress = "refund",
                        originAsset = origin,
                        destinationAddress = "destination",
                        destinationAsset = zec,
                        slippage = BigDecimal("2")
                    )
                }

            assertEquals(origin, exception.asset)
            assertEquals(0, BigDecimal("1000").compareTo(exception.amount))
        }

    @Test
    fun requestQuoteRethrowsAmountTooLowWhenAmountIsNotParsable() {
        runBlocking {
            // The trailing token isn't a number, so the error can't be mapped and is rethrown verbatim.
            coEvery { nearApiProvider.requestQuote(any()) } throws
                nearError("Amount is too low for bridge, try at least soon")

            assertFailsWith<ResponseWithNearErrorException> {
                dataSource.requestQuote(
                    swapMode = SwapMode.EXACT_INPUT,
                    amount = BigDecimal("1"),
                    refundAddress = "refund",
                    originAsset = origin,
                    destinationAddress = "destination",
                    destinationAsset = zec,
                    slippage = BigDecimal("2")
                )
            }
        }
    }

    @Test
    fun requestQuoteRethrowsAmountTooLowWithAnOversizedMinimumHint() {
        runBlocking {
            coEvery { nearApiProvider.requestQuote(any()) } throws
                nearError("Amount is too low for bridge, try at least 1E+10000")

            assertFailsWith<ResponseWithNearErrorException> {
                dataSource.requestQuote(
                    swapMode = SwapMode.EXACT_INPUT,
                    amount = BigDecimal.ONE,
                    refundAddress = "refund",
                    originAsset = origin,
                    destinationAddress = "destination",
                    destinationAsset = zec,
                    slippage = BigDecimal("2")
                )
            }
        }
    }

    @Test
    fun submitDepositTransactionForwardsTxHashAndAddressToProvider() =
        runBlocking {
            val request = slot<SubmitDepositTransactionRequest>()
            coEvery { nearApiProvider.submitDepositTransaction(capture(request)) } just Runs

            dataSource.submitDepositTransaction(txHash = "hash", depositAddress = "deposit")

            assertEquals("hash", request.captured.txHash)
            assertEquals("deposit", request.captured.depositAddress)
        }

    @Test
    fun incomingBitcoinStatusUsesExactIdentityWhenBothCatalogEntriesExist() = runBlocking {
        val btc = (origin as NearSwapAsset).copy(assetId = "nep141:btc.omft.near", tokenTicker = "BTC")
        val omni = btc.copy(assetId = "1cs_v1:btc:native:coin", tokenTicker = "BTC(OMNI)")
        val nativeZec = (zec as NearSwapAsset).copy(assetId = "nep141:zec.omft.near")
        val synchronizer = mockk<Synchronizer>()
        coEvery { synchronizer.validateAddress("synthetic-zec-recipient") } returns AddressType.Shielded
        coEvery { synchronizerProvider.getSynchronizer() } returns synchronizer
        for (catalog in listOf(listOf(btc, omni, nativeZec), listOf(nativeZec, omni, btc))) {
            for (selected in listOf(btc, omni)) {
                for (status in co.electriccoin.zcash.ui.common.model.near.SwapStatus.entries) {
                    coEvery { nearApiProvider.checkSwapStatus("synthetic-deposit") } returns
                        Json.decodeFromString<SwapStatusResponseDto>("""
                            {
                              "status":"${status.apiValue}", "updatedAt":"2026-11-20T00:00:00Z",
                              "quoteResponse": {
                                "timestamp":"2026-11-20T00:00:00Z",
                                "quoteRequest": {
                                  "dry":false, "swapType":"FLEX_INPUT", "slippageTolerance":100,
                                  "originAsset":"${selected.assetId}",
                                  "destinationAsset":"nep141:zec.omft.near", "amount":"100000",
                                  "refundTo":"synthetic-btc-refund", "recipient":"synthetic-zec-recipient",
                                  "deadline":"2026-11-21T00:00:00Z", "appFees":[]
                                },
                                "quote": {
                                  "depositAddress":"synthetic-deposit", "amountIn":"100000",
                                  "amountInFormatted":"0.001", "amountInUsd":"60", "minAmountIn":"99000",
                                  "amountOut":"10000000", "amountOutFormatted":"0.1",
                                  "amountOutUsd":"59", "minAmountOut":"9900000",
                                  "deadline":"2026-11-21T00:00:00Z"
                                }
                              }
                            }
                        """.trimIndent())
                    val result = dataSource.checkSwapStatus("synthetic-deposit", catalog)
                    assertEquals(selected, result.originAsset)
                    assertEquals(nativeZec, result.destinationAsset)
                    assertEquals(SwapMode.FLEX_INPUT, result.mode)
                    val expectedStatus = when (status) {
                        co.electriccoin.zcash.ui.common.model.near.SwapStatus.KNOWN_DEPOSIT_TX,
                        co.electriccoin.zcash.ui.common.model.near.SwapStatus.PENDING_DEPOSIT -> "PENDING"
                        else -> status.name
                    }
                    assertEquals(expectedStatus, result.status.name)
                    assertEquals(BigDecimal("0.1"), result.amountOutFormatted)
                }
            }
        }
    }

    @Test
    fun checkSwapStatusThrowsWhenOriginTokenNotSupported() {
        runBlocking {
            coEvery { nearApiProvider.checkSwapStatus(any()) } returns
                statusResponse(originAssetId = "missing", destinationAssetId = origin.assetId)

            val exception =
                assertFailsWith<AssetNotFoundException> {
                    dataSource.checkSwapStatus(depositAddress = "deposit", supportedTokens = listOf(origin))
                }
            assertEquals(true, exception.message?.contains("missing"))
        }
    }

    @Test
    fun checkSwapStatusThrowsWhenDestinationTokenNotSupported() {
        runBlocking {
            coEvery { nearApiProvider.checkSwapStatus(any()) } returns
                statusResponse(originAssetId = origin.assetId, destinationAssetId = "missing")

            val exception =
                assertFailsWith<AssetNotFoundException> {
                    dataSource.checkSwapStatus(depositAddress = "deposit", supportedTokens = listOf(origin))
                }
            // The origin resolves; the unsupported destination is what's reported missing.
            assertEquals(true, exception.message?.contains("missing"))
        }
    }

    private fun statusResponse(
        originAssetId: String,
        destinationAssetId: String
    ): SwapStatusResponseDto =
        mockk {
            every { quoteResponse } returns
                mockk {
                    every { quote } returns mockk { every { depositAddress } returns "deposit" }
                    every { quoteRequest } returns
                        mockk {
                            every { dry } returns false
                            every { originAsset } returns originAssetId
                            every { destinationAsset } returns destinationAssetId
                        }
                }
        }

    private fun nearError(message: String): ResponseWithNearErrorException =
        mockk(relaxed = true) {
            every { error } returns ErrorDto(message = message, timestamp = "", path = "")
        }

    private fun token(
        assetId: String,
        symbol: String,
        blockchain: String,
        decimals: Int,
        price: String
    ) = NearTokenDto(
        assetId = assetId,
        decimals = decimals,
        blockchain = blockchain,
        symbol = symbol,
        price = BigDecimal(price)
    )
}
