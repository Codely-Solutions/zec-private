# Independent quote reference

`KrakenPriceProvider` uses Kraken's public [Spread API](https://docs.kraken.com/api-reference/market-data/get-recent-spreads). The shipped implementation maps native BTC, ETH, SOL and ZEC and always uses Tor. It needs no credentials, extra library or SDK modification.

## Coverage and routing policy

- Use the wallet's supported swap assets as the scope of the check. Resolve each asset to an independent market by verified identity, including network and contract where applicable. Maintain mappings against NEAR token metadata and Kraken [pair metadata](https://docs.kraken.com/api-reference/market-data/get-tradable-asset-pairs). Mapping entries identify source markets. When a source cannot establish a reliable match, report that the asset has no market reference.
- Follow the wallet's Tor setting for independent swap checks. Use Tor without direct fallback when enabled and the normal connection when disabled. The existing Tor-only currency-conversion feed is a separate feature with its own privacy commitment.
- Send only public market identifiers. Keep amounts, addresses and quote data local. Verify both setting states and confirm that a failed Tor request cannot switch routes.

## Comparison and interaction

Value the reviewed input at the reference bid and the output at the ask. Add the separately known Zcash network fee for outgoing native ZEC. The reviewed quote input already includes any EXACT_OUTPUT allowance; quoted costs are counted once. Provider fiat estimates remain separate from this reference.

A difference of −15% or worse produces an inline warning and changes the existing action to **Swap anyway** or **Pay anyway**. Ordinary results stay in collapsed **Quote details** below the total, using the existing label/value layout for the reference and difference. The shipped UI shows **Price check unavailable** for both missing market matches and failed checks. Distinguish those states in **Quote details** when updating coverage and keep the quote usable in both cases. Small transfers can legitimately have large percentage costs, so the threshold is an advisory.

## Network and freshness

- The current check has an eight-second deadline. It coalesces concurrent work, caches observations for 60 seconds, spaces cold requests by at least one second, and backs off failures for 60 seconds. Shared Tor exits can still encounter rate limits. See Kraken's [rate policy](https://support.kraken.com/articles/206548367-what-are-the-api-rate-limits-).
- Use timestamps from spread rows, not the response cursor. Accept observations younger than 120 seconds, received within 60 seconds, at most five seconds in the future and within 30 seconds of each other. Reject spreads over 2%, malformed identities and unbounded numeric/body inputs.
- Bind results and continuation callbacks to the exact quote and known fee. Cancel replaced checks and expire displayed comparisons. Source failures preserve ordinary payment review and authentication.

## Validation and maintenance

Run the affected `IndependentPricePolicyTest`, `KrakenPriceProviderTest`, `SwapQuoteVMTest` and mapper tests after changes. Exercise real public-source requests and unfunded provider quotes in the affected routing modes when mappings or transport change. Synthetic warning screenshots establish rendering; they do not establish live settlement or physical ARM behavior. Use the maintained development/capture procedures and the surrounding checkpoint for current evidence.

Review reference coverage when the wallet's supported assets change. Consider another source when a measured coverage or reliability gap justifies it. Reconsider the threshold with representative route evidence; use a separate decision for credentials, additional infrastructure or SDK ownership. Keep OHTTP deferred to a supported upstream release.
