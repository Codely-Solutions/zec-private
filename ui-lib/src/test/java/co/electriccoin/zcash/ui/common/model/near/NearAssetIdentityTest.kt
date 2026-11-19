package co.electriccoin.zcash.ui.common.model.near

import co.electriccoin.zcash.ui.common.model.SwapAsset
import co.electriccoin.zcash.ui.common.model.SwapQuoteMismatchType
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NearAssetIdentityTest {
    @Test
    fun exactContractIdentityRemainsDistinctAcrossNetworks() {
        val eth = asset("nep141:eth-usdc.omft.near")
        val base = asset("nep141:base-usdc.omft.near")
        assertSame(base, resolveNearAsset(listOf(eth, base), base.assetId))
    }

    @Test
    fun supportedNativeAliasesResolveToFullIdentity() {
        val btc = asset("nep141:btc.omft.near")
        val zec = asset("nep141:zec.omft.near")
        assertSame(btc, resolveNearAsset(listOf(zec, btc), "1cs_v1:btc:native:coin"))
        requireNearAssetIdentity(zec, zec.assetId, SwapQuoteMismatchType.DESTINATION_ASSET)
        assertEquals(btc.assetId, canonicalNearAssetId("1cs_v1:btc:native:coin"))
    }

    @Test
    fun unresolvedAndAmbiguousIdentitiesRemainUnavailable() {
        val btc = asset("nep141:btc.omft.near")
        assertNull(resolveNearAsset(listOf(btc), "1cs_v1:btc:unknown:coin"))
        assertNull(resolveNearAsset(listOf(btc, asset(btc.assetId)), btc.assetId))
    }

    private fun asset(id: String): SwapAsset = mockk { every { assetId } returns id }
}
