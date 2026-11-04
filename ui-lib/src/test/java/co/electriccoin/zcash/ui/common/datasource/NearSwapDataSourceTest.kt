package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchException
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import co.electriccoin.zcash.ui.common.model.near.ErrorDto
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
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
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * [NearSwapDataSource] maps the 1Click API: de-duplicating supported tokens and mapping them to
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
    fun getSupportedTokensDeduplicatesAndMapsTokens() =
        runBlocking {
            coEvery { nearApiProvider.getSupportedTokens() } returns
                listOf(
                    token(assetId = "a1", symbol = "btc", blockchain = "btc", decimals = 8, price = "100000"),
                    // Duplicate symbol + blockchain + decimals -> dropped by distinctBy.
                    token(assetId = "a2", symbol = "btc", blockchain = "btc", decimals = 8, price = "100000"),
                    token(assetId = "e1", symbol = "eth", blockchain = "eth", decimals = 18, price = "3000"),
                )

            val result = dataSource.getSupportedTokens()

            assertEquals(2, result.size)
            // The first of the duplicate pair (assetId "a1") is kept, with the token's fields mapped through.
            val btc = result.single { it.tokenTicker == "btc" }
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
                    every { quoteRequest } returns mockk { every { swapType } returns SwapType.EXACT_OUTPUT }
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
                        every { quoteRequest } returns mockk { every { swapType } returns type }
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
    fun submitDepositTransactionForwardsTxHashAndAddressToProvider() =
        runBlocking {
            val request = slot<SubmitDepositTransactionRequest>()
            coEvery { nearApiProvider.submitDepositTransaction(capture(request)) } just Runs

            dataSource.submitDepositTransaction(txHash = "hash", depositAddress = "deposit")

            assertEquals("hash", request.captured.txHash)
            assertEquals("deposit", request.captured.depositAddress)
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
                    every { quoteRequest } returns
                        mockk {
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
