# Independent quote reference

`KrakenPriceProvider` checks native BTC, ETH, SOL and ZEC using Kraken's public [Spread API](https://docs.kraken.com/api-reference/market-data/get-recent-spreads). It needs no credential, extra library or SDK modification. Asset IDs, network, symbol and decimals must all match `IndependentPricePolicy`; wrapped assets remain uncovered. Maintain mappings against NEAR token metadata and Kraken [pair metadata](https://docs.kraken.com/api-reference/market-data/get-tradable-asset-pairs).

## Comparison and interaction

Value the reviewed input at the reference bid and the output at the ask. Add the separately known Zcash network fee for outgoing native ZEC. The reviewed quote input already includes any EXACT_OUTPUT allowance; quoted costs are counted once. Provider fiat estimates remain separate from this reference.

A difference of −15% or worse produces an inline warning and changes the existing action to **Swap anyway** or **Pay anyway**. Ordinary results stay in collapsed **Quote details** below the total, using the existing label/value layout for the reference and difference. Missing, stale, unsupported or failed comparisons show **Price check unavailable** in that details area and keep the quote usable. The threshold is a conservative advisory: small transfers can legitimately have large percentage costs. It does not certify a fair or executable rate, nor establish settlement output.

## Network and freshness

- Always use the existing native Tor HTTP client; send only public pair names. No wallet addresses, amounts or provider quote data go to Kraken. There is no direct fallback.
- Bound a check to eight seconds. Coalesce concurrent work, cache observations for 60 seconds, space cold requests by at least one second, and back off failures for 60 seconds. Shared Tor exits can still encounter rate limits. See Kraken's [rate policy](https://support.kraken.com/articles/206548367-what-are-the-api-rate-limits-).
- Use timestamps from spread rows, not the response cursor. Accept observations younger than 120 seconds, received within 60 seconds, at most five seconds in the future and within 30 seconds of each other. Reject spreads over 2%, malformed identities and unbounded numeric/body inputs.
- Bind results and continuation callbacks to the exact quote and known fee. Cancel replaced checks and expire displayed comparisons. Source failures preserve ordinary payment review and authentication.

## Validation and maintenance

Run the affected `IndependentPricePolicyTest`, `KrakenPriceProviderTest`, `SwapQuoteVMTest` and mapper tests after changes. Exercise real public-source requests and unfunded provider quotes through native Tor when mappings or transport change. Synthetic warning screenshots establish rendering; they do not establish live settlement or physical ARM behavior. Use the maintained development/capture procedures and the surrounding checkpoint for current evidence.

Revisit coverage when exact supported identities change. Reconsider the threshold with representative route evidence; use a separate decision for credentials, additional infrastructure or SDK ownership. Keep OHTTP deferred to a supported upstream release.
