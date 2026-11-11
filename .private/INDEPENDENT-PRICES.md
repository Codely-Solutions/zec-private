# Independent quote reference

`KrakenPriceProvider` uses Kraken's public [Spread API](https://docs.kraken.com/api-reference/market-data/get-recent-spreads) without credentials. The current branch maps native ZEC, BTC, ETH, SOL, LTC, TRX, BNB, AVAX, XRP, DASH and BCH, plus Ethereum WBTC via its own `WBTCUSD` market.

## Coverage and routing policy

- Use the wallet's supported swap assets as the scope of the check. Resolve each asset to an independent market by verified identity, including network and contract where applicable. Maintain mappings against NEAR token metadata and Kraken [pair metadata](https://docs.kraken.com/api-reference/market-data/get-tradable-asset-pairs). Mapping entries identify source markets. When a source cannot establish a reliable match, report that the asset has no market reference.
- Follow the wallet's Tor setting for independent swap checks. Use Tor without direct fallback when enabled and the normal connection when disabled. The existing Tor-only currency-conversion feed is a separate feature with its own privacy commitment.
- Send only public market identifiers. Keep amounts, addresses and quote data local. Verify both setting states and confirm that a failed Tor request cannot switch routes.

The catalog review found Kraken USD markets for USDC, USDT, DAI and NEAR. Confirm the exact on-chain or wrapped identity before mapping each catalog entry. USDT0, BTC on NEAR, ZEC on Solana/NEAR and ETH on Base/Arbitrum need a verified match or an explicitly supported treatment of the network variant. A second source is most useful if it can price those exact identities with fresh independent data.

## Comparison and interaction

Value the reviewed input at the reference bid and the output at the ask. Add the separately known Zcash network fee for outgoing native ZEC. The reviewed quote input already includes any EXACT_OUTPUT allowance; quoted costs are counted once. Provider fiat estimates remain separate from this reference.

A difference of −15% or worse produces an inline warning and changes the existing action to **Swap anyway** or **Pay anyway**. Ordinary results stay in collapsed **Quote details** below the total. **No market reference for this asset** signals an unmapped asset. **Price check unavailable** signals a failed or rejected comparison. Both leave the quote usable. Small transfers can legitimately have large percentage costs, so the threshold is an advisory.

## Network and freshness

- Review waits up to eight seconds for the poor-rate check. At that deadline it shows an unavailable reference and enables confirmation while the cancelled request finishes cleanup. Late results cannot change the reviewed action. The provider serializes work, caches observations for 60 seconds, spaces cold requests by at least one second, and backs off failed requests for 60 seconds. Fresh cached comparisons can still run during backoff. Logs include bounded failure categories. See Kraken's [rate policy](https://support.kraken.com/articles/206548367-what-are-the-api-rate-limits-).
- Use timestamps from spread rows, not the response cursor. Accept observations younger than 120 seconds, received within 60 seconds, at most five seconds in the future and within 30 seconds of each other. Reject spreads over 2%, malformed identities and unbounded numeric/body inputs.
- Bind results and continuation callbacks to the exact quote and known fee. Cancel replaced checks and expire displayed comparisons. Source failures preserve ordinary payment review and authentication.

## Validation and maintenance

Run the affected `IndependentPricePolicyTest`, `KrakenPriceProviderTest`, `SwapQuoteVMTest` and mapper tests after changes. Exercise real public-source requests and unfunded provider quotes in the affected routing modes when mappings or transport change. Synthetic warning screenshots establish rendering; they do not establish live settlement or physical ARM behavior. Use the maintained development/capture procedures and the surrounding checkpoint for current evidence.

Review reference coverage when the wallet's supported assets change. Consider another source when a measured coverage or reliability gap justifies it. Reconsider the threshold with representative route evidence; use a separate decision for credentials, additional infrastructure or SDK ownership. Keep OHTTP deferred to a supported upstream release.
