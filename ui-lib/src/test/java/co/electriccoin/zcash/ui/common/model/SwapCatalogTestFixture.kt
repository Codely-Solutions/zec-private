package co.electriccoin.zcash.ui.common.model

import co.electriccoin.zcash.ui.common.model.near.NearSwapAsset
import co.electriccoin.zcash.ui.common.provider.BlockchainProvider
import co.electriccoin.zcash.ui.common.provider.SimpleSwapAssetProviderImpl
import co.electriccoin.zcash.ui.common.provider.TokenIconProvider
import co.electriccoin.zcash.ui.common.provider.TokenNameProvider
import co.electriccoin.zcash.ui.design.util.imageRes
import co.electriccoin.zcash.ui.design.util.stringRes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

/** Frozen selectable asset identities. Prices and display resources are synthetic. */
object SwapCatalogTestFixture {
    val blockchains: BlockchainProvider = object : BlockchainProvider {
        override fun getBlockchain(ticker: String) =
            SwapBlockchain(
                chainTicker = ticker,
                chainName = stringRes(ticker),
                chainIcon = imageRes(drawableId("chain:$ticker")),
            )

        override fun getZcashBlockchain() = getBlockchain(ZEC_TICKER)
    }

    val tokenIcons: TokenIconProvider = object : TokenIconProvider {
        override fun getIcon(ticker: String) = imageRes(drawableId("token:$ticker"))
    }

    val tokenNames: TokenNameProvider = object : TokenNameProvider {
        override fun getName(ticker: String) = stringRes(ticker)
    }

    private val simpleAssets = SimpleSwapAssetProviderImpl(tokenIcons, tokenNames, blockchains)

    val assets: List<NearSwapAsset> by lazy {
        val catalog = checkNotNull(javaClass.getResourceAsStream("/swap-catalog.json"))
            .bufferedReader()
            .use { it.readText() }
        Json.decodeFromString<List<AssetEntry>>(catalog).map { entry ->
            NearSwapAsset(
                tokenTicker = entry.symbol,
                tokenName = tokenNames.getName(entry.symbol),
                tokenIcon = tokenIcons.getIcon(entry.symbol),
                usdPrice = BigDecimal.ONE,
                assetId = entry.assetId,
                decimals = entry.decimals,
                blockchain = blockchains.getBlockchain(entry.blockchain),
                contractAddress = entry.contractAddress,
            )
        }
    }

    val nativeZec: NearSwapAsset by lazy { assets.single { it.isZCashAsset } }
    val externalAssets: List<NearSwapAsset> by lazy { assets.filter { it != nativeZec } }

    fun simple(asset: SwapAsset): SimpleSwapAsset = simpleAssets.get(asset.tokenTicker, asset.chainTicker)

    private fun drawableId(key: String): Int = (key.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

    @Serializable
    private data class AssetEntry(
        val assetId: String,
        val symbol: String,
        val blockchain: String,
        val decimals: Int,
        val contractAddress: String?,
    )
}
