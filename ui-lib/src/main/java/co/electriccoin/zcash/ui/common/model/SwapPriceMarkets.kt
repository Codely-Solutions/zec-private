package co.electriccoin.zcash.ui.common.model

internal object SwapPriceMarkets {
    private data class Market(
        val symbol: String,
        val network: String,
        val decimals: Int,
        val contract: String?,
        val reference: String
    )

    // Contract identities come from the issuer or bridge. The catalog check also verifies the
    // provider's current asset ID and contract before release.
    @Suppress("MagicNumber")
    private val markets = mapOf(
        "nep141:zec.omft.near" to Market("ZEC", "zec", 8, null, "ZECUSD"),
        "nep141:btc.omft.near" to Market("BTC", "btc", 8, null, "XBTUSD"),
        "nep141:eth.omft.near" to Market("ETH", "eth", 18, null, "ETHUSD"),
        "nep141:arb.omft.near" to Market("ETH", "arb", 18, null, "ETHUSD"),
        "nep141:base.omft.near" to Market("ETH", "base", 18, null, "ETHUSD"),
        "nep141:sol.omft.near" to Market("SOL", "sol", 9, null, "SOLUSD"),
        "nep141:eth-0x6b175474e89094c44da98b954eedeac495271d0f.omft.near" to
            Market("DAI", "eth", 18, "0x6b175474e89094c44da98b954eedeac495271d0f", "DAIUSD"),
        "nep141:eth-0xdac17f958d2ee523a2206206994597c13d831ec7.omft.near" to
            Market("USDT", "eth", 6, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDTUSD"),
        "nep141:sol-c800a4bd850783ccb82c2b2c7e84175443606352.omft.near" to
            Market("USDT", "sol", 6, "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", "USDTUSD"),
        "nep141:tron-d28a265909efecdcee7c5028585214ea0b96f015.omft.near" to
            Market("USDT", "tron", 6, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDTUSD"),
        "nep141:usdt.tether-token.near" to Market("USDT", "near", 6, "usdt.tether-token.near", "USDTUSD"),
        "nep141:eth-0x2260fac5e5542a773aa44fbcfedf7c193bc2c599.omft.near" to
            Market("WBTC", "eth", 8, "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", "WBTCUSD"),
        "nep141:ltc.omft.near" to Market("LTC", "ltc", 8, null, "LTCUSD"),
        "nep141:tron.omft.near" to Market("TRX", "tron", 6, null, "TRXUSD"),
        "nep141:bnb.omdep.near" to Market("BNB", "bsc", 18, null, "BNBUSD"),
        "nep141:avax.omdep.near" to Market("AVAX", "avax", 18, null, "AVAXUSD"),
        "nep141:xrp.omft.near" to Market("XRP", "xrp", 6, null, "XRPUSD"),
        "nep141:dash.omft.near" to Market("DASH", "dash", 8, null, "DASHUSD"),
        "nep141:bch.omft.near" to Market("BCH", "bch", 8, null, "BCHUSD"),
        "nep141:wrap.near" to Market("wNEAR", "near", 24, "wrap.near", "NEARUSD"),
        "nep141:eth-0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48.omft.near" to
            Market("USDC", "eth", 6, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDCUSD"),
        "nep141:sol-5ce3bf3a31af18be40ba30f721101b4341690186.omft.near" to
            Market("USDC", "sol", 6, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", "USDCUSD"),
        "nep141:base-0x833589fcd6edb6e08f4c7c32d4f71b54bda02913.omft.near" to
            Market("USDC", "base", 6, "0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDCUSD"),
        "nep141:arb-0xaf88d065e77c8cc2239327c5edb3a432268e5831.omft.near" to
            Market("USDC", "arb", 6, "0xaf88d065e77c8cc2239327c5edb3a432268e5831", "USDCUSD"),
        "nep141:sui-c1b81ecaf27933252d31a963bc5e9458f13c18ce.omft.near" to
            Market(
                "USDC", "sui", 6,
                "0xdba34672e30cb065b1f93e3ab55318768fd6fef66c15942c9f7cb846e2f900e7::usdc::USDC", "USDCUSD",
            ),
        "nep141:17208628f84f5d6ad33f0da3bbbeb27ffcb398eac501a31bd6ad2011e36133a1" to
            Market("USDC", "near", 6, "17208628f84f5d6ad33f0da3bbbeb27ffcb398eac501a31bd6ad2011e36133a1", "USDCUSD"),
        "nep141:pol-0x3c499c542cef5e3811e1192ce70d8cc03d5c3359.omdep.near" to
            Market("USDC", "pol", 6, "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359", "USDCUSD"),
        "nep141:bnb-0x55d398326f99059ff775485246999027b3197955.omdep.near" to
            Market("USDT", "bsc", 18, "0x55d398326f99059ff775485246999027b3197955", "USDT-BSC"),
        "nep141:pol-0xc2132d05d31c914a87c6611c10748aeb04b58e8f.omdep.near" to
            Market("USDT", "pol", 6, "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", "USDT-POL"),
        "nep141:arb-0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9.omft.near" to
            Market("USDT0", "arb", 6, "0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", "USDT0-ARB"),
        "nep141:nbtc.bridge.near" to Market("BTC", "near", 8, "nbtc.bridge.near", "BTC-NEAR"),
        "1cs_v1:near:nep141:zec.omft.near" to Market("ZEC", "near", 8, "zec.omft.near", "ZEC-NEAR"),
        "1cs_v1:sol:spl:A7bdiYdS5GjqGFtxf17ppRHtDKPkkRqbKtR27dxvQXaS" to
            Market("ZEC", "sol", 8, "A7bdiYdS5GjqGFtxf17ppRHtDKPkkRqbKtR27dxvQXaS", "ZEC-SOL"),
    )

    fun reference(asset: SwapAsset): String? = markets[asset.assetId]?.takeIf {
        asset.tokenTicker.equals(it.symbol, ignoreCase = true) &&
            asset.chainTicker.equals(it.network, ignoreCase = true) && asset.decimals == it.decimals &&
            asset.contractAddress == it.contract
    }?.reference
}
