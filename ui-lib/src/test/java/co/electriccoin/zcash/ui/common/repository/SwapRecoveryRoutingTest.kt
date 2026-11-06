package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.datasource.SwapDataSource
import co.electriccoin.zcash.ui.common.model.SwapAssetTestFixture
import co.electriccoin.zcash.ui.common.model.SwapMode
import co.electriccoin.zcash.ui.common.model.SwapStatus
import co.electriccoin.zcash.ui.common.model.metadata.MetadataV3
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProvider
import co.electriccoin.zcash.ui.common.serialization.METADATA_SERIALIZATION_V1_V2
import co.electriccoin.zcash.ui.common.serialization.METADATA_SERIALIZATION_V3
import co.electriccoin.zcash.ui.common.serialization.metadata.MetadataSerializer
import co.electriccoin.zcash.wallet.foundation.ProviderId
import co.electriccoin.zcash.wallet.foundation.RecoveryReference
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SwapRecoveryRoutingTest {
    private val serializer = MetadataSerializer()
    private val assets = mockk<SimpleSwapAssetProvider> {
        every { get("zec", "zec") } returns SwapAssetTestFixture.zecSimpleAsset()
        every { get("btc", "btc") } returns SwapAssetTestFixture.simpleAsset()
    }

    @Test
    fun legacyV2MigrationKeepsNearDepositAndBusinessDefaults() {
        assertLegacyRecovery(2)
    }

    @Test
    fun legacyV3KeepsNearDepositAndBusinessDefaults() {
        assertLegacyRecovery(3)
    }

    @Test
    fun unknownPersistedProvidersFailBeforeNetworkAndKeepSavedRecord() = runTest {
        for (version in listOf(2, 3)) {
            val persisted = serializer.deserialize(fixture(version, "future-provider").byteInputStream())
            val before = encode(persisted)
            val business = persisted.accountMetadata.swaps.swapIds.single().toBusinessObject(assets)
            val dataSource = mockk<SwapDataSource>()
            val repository = SwapRepositoryImpl(dataSource, mockk(relaxed = true)).apply {
                scope = backgroundScope
            }

            val failure = assertFailsWith<UnsupportedSwapRecoveryException> {
                repository.checkSwapStatus(business)
            }

            assertTrue(failure.message.orEmpty().contains("saved record has been preserved"))
            coVerify(exactly = 0) { dataSource.getSupportedTokens() }
            coVerify(exactly = 0) { dataSource.checkSwapStatus(any(), any()) }
            assertEquals("future-provider", business.provider)
            assertEquals(before, encode(persisted))
            assertEquals(persisted, serializer.deserialize(before.byteInputStream()))
        }
    }

    private fun assertLegacyRecovery(version: Int) {
        val persisted = serializer.deserialize(fixture(version, "near").byteInputStream())
        val business = persisted.accountMetadata.swaps.swapIds.single().toBusinessObject(assets)

        assertEquals(RecoveryReference(ProviderId("near"), 1, "synthetic-deposit"), business.requireNearRecovery())
        assertEquals(SwapMode.EXACT_INPUT, business.mode)
        assertEquals(SwapStatus.SUCCESS, business.status)
        assertEquals(BigDecimal.ZERO, business.amountOutFormatted)
        assertEquals("zec", business.origin.tokenTicker)
        assertEquals("btc", business.destination.tokenTicker)
        assertEquals(BigDecimal("0.25"), business.totalFeesUsd)
        assertEquals(1000L, business.totalFees.value)
        val roundTrip = serializer.deserialize(encode(persisted).byteInputStream())
        assertEquals(business, roundTrip.accountMetadata.swaps.swapIds.single().toBusinessObject(assets))
    }

    private fun encode(metadata: MetadataV3): String =
        ByteArrayOutputStream().also { serializer.serialize(it, metadata) }.toString(Charsets.UTF_8)

    private fun fixture(version: Int, provider: String): String {
        // The V2 model retained the original persisted version number, 1.
        val persistedVersion = if (version == 2) METADATA_SERIALIZATION_V1_V2 else METADATA_SERIALIZATION_V3
        val route = if (version == 2) {
            """"provider":"$provider.btc.btc""""
        } else {
            """"provider":"$provider","fromAsset":"zec.zec","toAsset":"btc.btc",""" +
                """"exactInput":null,"status":null,"amountOutFormatted":null"""
        }
        return """
            {"version":$persistedVersion,"lastUpdated":1000,"accountMetadata":{
              "bookmarked":[],"read":[],"annotations":[],"swaps":{
                "swapIds":[{"depositAddress":"synthetic-deposit","lastUpdated":1000,
                  "totalFees":1000,"totalFeesUsd":"0.25",$route}],"lastUsedAssetHistory":[]}}}
        """.trimIndent()
    }
}
