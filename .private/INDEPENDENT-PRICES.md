# Independent quote reference

`KrakenPriceProvider` uses Kraken's public [Spread API](https://docs.kraken.com/api-reference/market-data/get-recent-spreads) without credentials. It covers native BTC, SOL, ZEC, LTC, TRX, BNB, AVAX, XRP, DASH and BCH, ETH on Ethereum, Arbitrum One and Base, WBTC and DAI on Ethereum, and issuer-backed USDT on Ethereum, Solana and Tron.

Match the exact NEAR asset ID, network, symbol and decimals in `IndependentPricePolicy`. Check issuer contracts for tokens and verify fresh, usable spreads for each market. USDC's USD spread was over 20 hours old in the Release 23 review. Recheck freshness before adding a mapping. Use the [NEAR catalog](https://1click.chaindefuser.com/v0/tokens) and [Kraken pair metadata](https://docs.kraken.com/api-reference/market-data/get-tradable-asset-pairs).

## Comparison and interaction

Value the reviewed input at the reference bid and the output at the ask. Add the separately known Zcash network fee for outgoing native ZEC. The reviewed quote input already includes any EXACT_OUTPUT allowance; quoted costs are counted once. Provider fiat estimates remain separate from this reference.

A difference of −15% or worse produces an inline warning and changes the existing action to **Swap anyway** or **Pay anyway**. Ordinary results stay in collapsed **Quote details** below the total. **No market reference for this asset** signals an unmapped asset. **Price check unavailable** signals a failed or rejected comparison. Both leave the quote usable. Small transfers can legitimately have large percentage costs, so the threshold is advisory.

## Network and freshness

- Follow the wallet's Tor setting for swap market checks. Use Tor without direct fallback when enabled and the normal connection when disabled. Send only public market identifiers. The currency-conversion feed has its own Tor-only policy.
- Review waits up to eight seconds for the market check. At the deadline it shows an unavailable reference and enables confirmation. Late results cannot change the reviewed action. Cache observations for 60 seconds, space cold requests by at least one second, and back off failures for 60 seconds. See Kraken's [rate policy](https://support.kraken.com/articles/206548367-what-are-the-api-rate-limits-).
- Use timestamps from spread rows, not the response cursor. Accept observations younger than 120 seconds, received within 60 seconds, at most five seconds in the future and within 30 seconds of each other. Reject spreads over 2%, malformed identities and unbounded numeric/body inputs.
- Bind results and continuation callbacks to the exact quote and known fee. Cancel replaced checks and expire displayed comparisons. Source failures preserve ordinary payment review and authentication.

## Validation and maintenance

Run the affected `IndependentPricePolicyTest`, `KrakenPriceProviderTest`, `SwapQuoteVMTest` and mapper tests after changes. Exercise real public-source requests and unfunded provider quotes in the affected routing modes when mappings or transport change. Use the maintained development and capture procedures for rendered behavior.

Revisit coverage when exact supported identities change. Reconsider the threshold with representative route evidence; use a separate decision for credentials, additional infrastructure or SDK ownership. Keep OHTTP deferred to a supported upstream release.
