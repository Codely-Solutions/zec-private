# Independent quote reference

The wallet maps each selectable asset by its exact NEAR asset ID, chain, symbol, decimals and contract or mint. [Release preflight](RELEASE-WORKFLOW.md#shared-release-preflight) runs the [catalog check](../scripts/check-swap-price-catalog.py) against the public catalog for each new candidate. Run `python -B scripts/check-swap-price-catalog.py` from the repository root when editing the mappings. An unknown or changed identity has no reference until it is reviewed.

| Reference | Assets and networks |
|---|---|
| Kraken USD markets | ZEC (Zcash), BTC (Bitcoin), ETH (Ethereum, Arbitrum One, Base), SOL (Solana), DAI and WBTC (Ethereum), LTC (Litecoin), TRX (Tron), BNB (BNB Smart Chain), AVAX (Avalanche), XRP (XRP Ledger), DASH (Dash), BCH (Bitcoin Cash), wNEAR (NEAR) |
| Kraken USDC/USD | Issuer-native USDC on Ethereum, Solana, Base, Arbitrum One, Sui, NEAR and Polygon |
| Kraken USDT/USD | Issuer-native USDT on Ethereum, Solana, Tron and NEAR |
| Public chain RPC with Kraken USD anchor | ZEC on Solana (Orca, USDC), Binance-Peg USDT (PancakeSwap, WBNB), Polygon USDT (Uniswap, WETH), and Arbitrum USDT0 (Uniswap, WETH) |
| Rhea Finance pools with Kraken NEAR/USD anchor | BTC and ZEC on NEAR, priced against wNEAR on finalized NEAR state |

Confirm exact identities using the [NEAR catalog](https://1click.chaindefuser.com/v0/tokens), [Circle contracts](https://developers.circle.com/stablecoins/usdc-contract-addresses), [Tether protocols](https://tether.to/en/supported-protocols/), and [USDT0 deployments](https://docs.usdt0.to/technical-documentation/developer). [wNEAR](https://github.com/near/core-contracts/tree/master/w-near) wraps NEAR one for one. The bridged references above observe the specific token market and can therefore reflect a deviation from the underlying asset.

## Comparison and interaction

Value the reviewed input at the reference bid and output at the ask. Include the separately known Zcash network fee for outgoing native ZEC. The reviewed EXACT_OUTPUT input already includes its allowance. Provider fiat estimates are separate from the independent comparison.

A difference of −15% or worse shows an inline warning and uses the existing **Swap anyway** or **Pay anyway** action. Ordinary results and their sources appear in collapsed **Quote details**. **Price check unavailable** signals a timed-out, stale or rejected comparison. **No market reference for this asset** signals an unknown identity. Both leave the quote usable.

## Sources and freshness

- Use the wallet's selected Tor or normal transport for all sources. Requests carry only public market or pool identifiers and fixed Rhea sample sizes. The separate currency-conversion feed follows its own Tor-only policy.
- Use Kraken's [spread history](https://docs.kraken.com/api-reference/market-data/get-recent-spreads). For USDT and USDC, try the [order book](https://docs.kraken.com/api-reference/market-data/get-order-book) first and use the older of its two best-level timestamps. Try spread history if the book is stale or too wide.
- Use pinned pools on [PublicNode RPC](https://www.publicnode.com/). EVM references check token identities and liquidity at the same block as spot and [two-minute oracle prices](https://developers.uniswap.org/docs/sdks/v3/guides/price-oracle). The Solana reference verifies the [Orca account](https://github.com/orca-so/whirlpools/blob/main/programs/whirlpool/src/state/whirlpool.rs), mints, liquidity and block time. Convert the observed token rates with independent Kraken anchors.
- For Rhea, use public [NEAR RPC](https://docs.near.org/api/rpc/contracts) and the documented [exchange view](https://github.com/ref-finance/docs/blob/main/contracts/ref-exchange.md). Price small fixed buys and sells on the same finalized block. These are read-only simulations. A request contains no transaction, wallet address or user amount.
- Reject observations aged 120 seconds or more, receipts aged 60 seconds or more and bands wider than 2%. Kraken-only comparisons require observations within 30 seconds of one another. For an on-chain market, each observation and its anchor must still meet the 120-second limit.
- Review waits up to eight seconds. Cancel replaced checks and expire completed assessments. Space Kraken calls by at least one second, reuse fresh observations, and back off failed markets for 60 seconds without suppressing unrelated markets.

Use focused policy, transport and quote mapper tests during development. Verify live public prices, unfunded app quotes and the relevant Tor modes before changing the source or mapping. Recheck publication if the live catalog changes between validation and dispatch.
