package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.model.SwapCatalogTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.near.NearSwapAsset
import co.electriccoin.zcash.ui.common.model.near.NearTokenDto
import co.electriccoin.zcash.ui.common.model.near.SwapStatusResponseDto
import co.electriccoin.zcash.ui.common.model.near.reviewedIdentity
import co.electriccoin.zcash.ui.common.provider.NearApiProvider
import co.electriccoin.zcash.ui.common.provider.SwapAssetCacheProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.common.repository.SwapRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.TransactionSwapMetadata
import co.electriccoin.zcash.ui.common.usecase.GetSwapStatusUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SwapRecoveryBoundaryTest {
    private val fixture = SwapCatalogTestFixture
    private val zec = fixture.nativeZec
    private val eth = fixture.externalAssets.single { it.assetId == "nep141:eth.omft.near" }
    private val api = mockk<NearApiProvider>()
    private val sync = mockk<Synchronizer> {
        coEvery { validateAddress("synthetic-zec-recipient") } returns AddressType.Shielded
        coEvery { validateAddress("another-synthetic-zec-recipient") } returns AddressType.Shielded
    }
    private val syncProvider = mockk<SynchronizerProvider> {
        coEvery { getSynchronizer() } returns sync
    }
    private val dataSource = NearSwapDataSource(
        api, fixture.tokenIcons, fixture.tokenNames, fixture.blockchains, syncProvider
    )
    private val cache = mockk<SwapAssetCacheProvider>(relaxed = true) {
        coEvery { get() } returns emptyList()
    }
    private val metadata = mockk<MetadataRepository>(relaxed = true) {
        coEvery { updateSwap(any(), any()) } coAnswers { firstArg() }
    }
    private val stored = TransactionSwapMetadata(
        depositAddress = "tracked-deposit",
        provider = "near",
        totalFees = Zatoshi(0),
        totalFeesUsd = BigDecimal.ZERO,
        lastUpdated = java.time.Instant.EPOCH,
        origin = fixture.simple(eth),
        destination = fixture.simple(zec),
        mode = SwapMode.FLEX_INPUT,
        recordedStatus = SwapStatus.PENDING,
        amountOutFormatted = BigDecimal.ONE
    )

    @Test
    fun samePairResponseForAnotherDepositPreservesSavedStateWithoutSideEffects() = runBlocking {
        coEvery { api.getSupportedTokens() } returns listOf(eth.dto(), zec.dto())
        val result = readStatus(echoedAddress = "another-deposit", scope = this)

        assertIs<IllegalArgumentException>(result.error)
        assertNull(result.status)
        assertEquals(stored, result.savedMetadata)
        coVerify(exactly = 0) { metadata.updateSwap(any(), any()) }
    }

    @Test
    fun aCatalogPriceOutageStillAllowsValidSettlement() = runBlocking {
        coEvery { api.getSupportedTokens() } returns listOf(eth.dto(), zec.dto())
        assertEquals(SwapStatus.SUCCESS, readStatus(scope = this).status?.status)

        coEvery { api.getSupportedTokens() } returns listOf(eth.dto().copy(price = BigDecimal.ZERO), zec.dto())
        val unpriced = readStatus(scope = this)
        assertNull(unpriced.error)
        assertEquals(SwapStatus.SUCCESS, unpriced.status?.status)
        assertEquals(stored, unpriced.savedMetadata)
        coVerify(exactly = 2) { api.checkSwapStatus("tracked-deposit") }
        coVerify(exactly = 2) { metadata.updateSwap(any(), any()) }
    }

    @Test
    fun aSameDisplayIdentityCannotHideTheExactHistoricalIdInEitherOrder() = runBlocking {
        val twin = eth.dto().copy(assetId = "synthetic:twin")
        coEvery { api.getSupportedTokens() } returns listOf(eth.dto(), twin, zec.dto())
        assertEquals(SwapStatus.SUCCESS, readStatus(scope = this).status?.status)

        coEvery { api.getSupportedTokens() } returns listOf(twin, eth.dto(), zec.dto())
        val shadowed = readStatus(scope = this)
        assertNull(shadowed.error)
        assertEquals(SwapStatus.SUCCESS, shadowed.status?.status)
        assertEquals(stored, shadowed.savedMetadata)
        coVerify(exactly = 2) { metadata.updateSwap(any(), any()) }
    }

    @Test
    fun duplicateExactIdentityIsRejectedWithoutChangingSavedTracking() = runBlocking {
        coEvery { api.getSupportedTokens() } returns listOf(eth.dto(), eth.dto(), zec.dto())
        val rejected = readStatus(scope = this)
        assertIs<AssetNotFoundException>(rejected.error)
        assertNull(rejected.status)
        assertEquals(stored, rejected.savedMetadata)
        coVerify(exactly = 0) { metadata.updateSwap(any(), any()) }
    }

    @Test
    fun missingLiveIdentityUsesCachedRecoveryMetadataAndWrongReferenceCanRetry() = runBlocking {
        coEvery { cache.get() } returns listOf(eth.copy(usdPrice = null), zec.copy(usdPrice = null))
        coEvery { api.getSupportedTokens() } returns listOf(zec.dto())
        val rejected = readStatus(echoedAddress = "another-deposit", scope = this)
        assertIs<IllegalArgumentException>(rejected.error)
        assertEquals(stored, rejected.savedMetadata)
        val recovered = readStatus(scope = this)
        assertNull(recovered.error)
        assertEquals(SwapStatus.SUCCESS, recovered.status?.status)
        coVerify(exactly = 1) { metadata.updateSwap(any(), any()) }
    }

    @Test
    fun localZecSelectionUsesPinnedIdentityInEitherOrderAndRejectsDuplicateExactIds() = runBlocking {
        val twin = zec.dto().copy(assetId = "synthetic:zec-twin")
        for (order in listOf(listOf(twin, zec.dto()), listOf(zec.dto(), twin))) {
            coEvery { api.getSupportedTokens() } returns order
            val repository = SwapRepositoryImpl(dataSource, cache).also { it.scope = this }
            repository.requestRefreshAssetsOnce()
            assertEquals(zec.assetId, repository.assets.value.zecAsset?.assetId)
            assertEquals(2, repository.assets.value.recoveryAssets?.size)
        }
        coEvery { api.getSupportedTokens() } returns listOf(zec.dto(), zec.dto())
        val repository = SwapRepositoryImpl(dataSource, cache).also { it.scope = this }
        repository.requestRefreshAssetsOnce()
        assertNull(repository.assets.value.zecAsset)
    }

    @Test
    fun unverifiedCachedNativeTwinCannotSupplyTheLocalQuoteLeg() = runBlocking {
        coEvery { cache.get() } returns listOf(zec.copy(assetId = "synthetic:zec-twin", usdPrice = null))
        coEvery { api.getSupportedTokens() } throws java.io.IOException("Synthetic catalog outage")
        val repository = SwapRepositoryImpl(dataSource, cache).also { it.scope = this }
        repository.requestRefreshAssetsOnce()
        assertNull(repository.assets.value.zecAsset)
        assertEquals(1, repository.assets.value.recoveryAssets?.size)
        kotlin.test.assertTrue(repository.assets.value.error is java.io.IOException)
    }

    @Test
    fun aPartialOldCacheCanRecoverWhenCatalogConnectivityReturns() = runBlocking {
        coEvery { cache.get() } returns listOf(zec.copy(usdPrice = null))
        coEvery { api.getSupportedTokens() } throws java.io.IOException("Synthetic catalog outage")
        coEvery { api.checkSwapStatus("tracked-deposit") } returns response("tracked-deposit")
        coEvery { metadata.getSwapMetadata("tracked-deposit") } returns stored
        val repository = SwapRepositoryImpl(dataSource, cache).also { it.scope = this }
        val status = GetSwapStatusUseCase(metadata, repository)
        val offline = status("tracked-deposit")
        assertIs<AssetNotFoundException>(offline.error)
        assertEquals(stored, offline.savedMetadata)
        coVerify(exactly = 0) { metadata.updateSwap(any(), any()) }

        coEvery { api.getSupportedTokens() } returns listOf(zec.dto(), eth.dto())
        val recovered = status("tracked-deposit")
        assertNull(recovered.error)
        assertEquals(SwapStatus.SUCCESS, recovered.status?.status)
        coVerify(exactly = 1) { metadata.updateSwap(any(), any()) }
    }

    @Test
    fun reviewedIdentityRejectsDecodedSubstitutionsWhileHistoricalRecordsRemainReadable() = runBlocking {
        val twin = eth.dto().copy(assetId = "synthetic:same-display-twin")
        coEvery { api.getSupportedTokens() } returns listOf(eth.dto(), twin, zec.dto())
        val original = response("tracked-deposit")
        val bound = stored.copy(reviewedIdentity = original.quoteResponse.reviewedIdentity())
        val repository = SwapRepositoryImpl(dataSource, cache).also { it.scope = this }
        val useCase = GetSwapStatusUseCase(metadata, repository)
        val cases = listOf(
            response("tracked-deposit", originId = twin.assetId),
            response("tracked-deposit", recipient = "another-synthetic-zec-recipient"),
            response("tracked-deposit", refund = "another-synthetic-external-refund"),
            response("tracked-deposit", swapType = "EXACT_INPUT"),
        )
        for (different in cases) {
            coEvery { metadata.getSwapMetadata("tracked-deposit") } returns stored
            coEvery { api.checkSwapStatus("tracked-deposit") } returns different
            assertEquals(SwapStatus.SUCCESS, useCase("tracked-deposit").status?.status)

            coEvery { metadata.getSwapMetadata("tracked-deposit") } returns bound
            val rejected = useCase("tracked-deposit")
            assertIs<IllegalArgumentException>(rejected.error)
            assertEquals(bound, rejected.savedMetadata)
            assertNull(rejected.status)
        }
        coVerify(exactly = 0) { metadata.updateSwap(bound, any()) }
        coEvery { api.checkSwapStatus("tracked-deposit") } returns original
        assertEquals(SwapStatus.SUCCESS, useCase("tracked-deposit").status?.status)
        coVerify(exactly = 1) { metadata.updateSwap(bound, any()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun zeroPriceDuplicateCannotEnterAnOfferThenFailStatusInEitherOrder() = runTest {
        val conflicting = eth.dto().copy(price = BigDecimal.ZERO, decimals = eth.decimals - 1)
        for (rows in listOf(listOf(eth.dto(), conflicting, zec.dto()), listOf(conflicting, eth.dto(), zec.dto()))) {
            coEvery { api.getSupportedTokens() } returns rows
            coEvery { api.checkSwapStatus("tracked-deposit") } returns response("tracked-deposit")
            coEvery { metadata.getSwapMetadata("tracked-deposit") } returns stored
            val repository = SwapRepositoryImpl(dataSource, cache).also {
                it.scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
            }
            repository.requestRefreshAssetsOnce()
            assertEquals(emptyList(), repository.assets.value.data)
            assertEquals(3, repository.assets.value.recoveryAssets?.size)
            repository.requestFlexInputIntoZec(BigDecimal.ONE, "synthetic-external-refund",
                "synthetic-zec-recipient", eth, BigDecimal.ONE)
            assertIs<SwapQuoteData.Error>(repository.quote.value)
            coVerify(exactly = 0) { api.requestQuote(any()) }
            val rejected = GetSwapStatusUseCase(metadata, repository)("tracked-deposit")
            assertIs<AssetNotFoundException>(rejected.error)
            assertEquals(stored, rejected.savedMetadata)
            coVerify(exactly = 0) { metadata.updateSwap(any(), any()) }

            // A unique catalog recovers both quote admission and status using the real decoder/resolver.
            coEvery { api.getSupportedTokens() } returns listOf(eth.dto(), zec.dto())
            coEvery { api.requestQuote(any()) } returns response("tracked-deposit").quoteResponse
            repository.requestRefreshAssetsOnce()
            repository.requestFlexInputIntoZec(BigDecimal.ONE, "synthetic-external-refund",
                "synthetic-zec-recipient", eth, BigDecimal.ONE)
            assertIs<SwapQuoteData.Success>(repository.quote.value)
            val recovered = GetSwapStatusUseCase(metadata, repository)("tracked-deposit")
            assertEquals(SwapStatus.SUCCESS, recovered.status?.status)
            io.mockk.clearMocks(api, metadata, answers = false)
        }
    }

    @Test
    fun newQuotesRejectWhitespaceInProviderDepositInstructions() = runTest {
        coEvery { api.getSupportedTokens() } returns listOf(eth.dto(), zec.dto())
        val repository = SwapRepositoryImpl(dataSource, cache).also {
            it.scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        }
        repository.requestRefreshAssetsOnce()
        val valid = "0x76b4c56085ED136a8744D52bE956396624a730E8"
        for (address in listOf("", "deposit with spaces", "deposit\\nnext", valid)) {
            coEvery { api.requestQuote(any()) } returns response(address).quoteResponse
            repository.requestFlexInputIntoZec(BigDecimal.ONE, "synthetic-external-refund",
                "synthetic-zec-recipient", eth, BigDecimal.ONE)
            if (address == valid) assertIs<SwapQuoteData.Success>(repository.quote.value)
            else assertIs<SwapQuoteData.Error>(repository.quote.value)
        }
    }

    private suspend fun readStatus(echoedAddress: String = "tracked-deposit", scope: CoroutineScope) =
        SwapRepositoryImpl(dataSource, cache).also { it.scope = scope }.let { repository ->
            coEvery { metadata.getSwapMetadata("tracked-deposit") } returns stored
            coEvery { api.checkSwapStatus("tracked-deposit") } returns response(echoedAddress)
            GetSwapStatusUseCase(metadata, repository)("tracked-deposit")
        }

    private fun NearSwapAsset.dto() = NearTokenDto(assetId, decimals, chainTicker, tokenTicker, BigDecimal.ONE)

    private fun response(
        address: String,
        originId: String = eth.assetId,
        recipient: String = "synthetic-zec-recipient",
        refund: String = "synthetic-external-refund",
        swapType: String = "FLEX_INPUT",
    ): SwapStatusResponseDto = Json.decodeFromString(
        """
        {
            "status": "SUCCESS",
            "updatedAt": "2026-11-20T00:00:00Z",
            "quoteResponse": {
                "timestamp": "2026-11-19T23:00:00Z",
                "quoteRequest": {
                    "dry": false,
                    "swapType": "$swapType",
                    "slippageTolerance": 100,
                    "originAsset": "$originId",
                    "depositType": "ORIGIN_CHAIN",
                    "depositMode": "SIMPLE",
                    "destinationAsset": "${zec.assetId}",
                    "amount": "1000000000000000000",
                    "refundTo": "$refund",
                    "refundType": "ORIGIN_CHAIN",
                    "recipient": "$recipient",
                    "recipientType": "DESTINATION_CHAIN",
                    "deadline": "2026-11-20T00:00:00Z",
                    "appFees": []
                },
                "quote": {
                    "depositAddress": "$address",
                    "amountIn": "1000000000000000000",
                    "amountInFormatted": "1",
                    "amountInUsd": "2",
                    "minAmountIn": "1000000000000000000",
                    "amountOut": "200000000",
                    "amountOutFormatted": "2",
                    "amountOutUsd": "2",
                    "minAmountOut": "200000000",
                    "deadline": "2026-11-20T00:00:00Z"
                }
            },
            "swapDetails": { "amountOut": "200000001" }
        }
        """.trimIndent()
    )
}
