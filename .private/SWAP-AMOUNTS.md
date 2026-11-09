# Swap amounts and costs

The quote is an estimate of output. Its minimum output is the provider's base-unit guarantee converted using the destination asset's decimals. Token amounts, deposit totals and that minimum retain exact asset precision; compact fiat formatting is not suitable for a token guarantee because it may round upward. The review shows the minimum for floating-output swaps.

For EXACT_OUTPUT, `amountIn` is the deposit including the input allowance; `minAmountIn` is the minimum input, not a maximum. Both must be positive integer atomic amounts, with `minAmountIn <= amountIn` and `minAmountIn >= floor(amountIn × (10000 − requestedBps) / 10000)`. The included allowance is their difference. Its ZEC and fiat values share the quote's input valuation; the explanatory row never increases the funded total. Fund the exact amount reviewed. See the provider's [quote contract](https://docs.near-intents.org/api-reference/oneclick/get-quote).

Status output comes from the provider's settled amount when available, including raw base-unit output when the formatted field is absent. Otherwise the displayed value remains explicitly quoted. Provider output is not an independently measured wallet credit. The wallet's received transaction records its own scanned net value. A provider/bridge reporting difference must not be silently corrected with a guessed constant.

The fork submits no app fees. Current unauthenticated 1Click requests use EXACT_INPUT, EXACT_OUTPUT or FLEX_INPUT. NEAR documents a 0.25% platform charge on non-ANY_INPUT quotes, included in the returned quote. This is not the total swap cost. Protocol, route and bridge costs or favourable execution may also affect output. Do not subtract an included fee a second time or infer aggregate fees from independently priced fiat values.

The review omits a wallet fee for externally funded incoming swaps. Outgoing swaps retain the wallet proposal fee; completed outgoing details use the actual wallet transaction fee rather than historical aggregate metadata. Legacy stored fee fields remain readable for compatibility.

The signed **Value difference** compares received USD with total USD to fund, including known separately paid wallet fees. Quoted costs are already reflected once. It uses the coherent quote snapshot, includes an EXACT_OUTPUT allowance and assumes no future refund. It excludes unknown external-wallet fees, hides on missing/non-positive values or quote expiry, and is not independent market-price verification.

Show only positive provider-reported refunds, including successful swaps. Never derive a refund from deposit minus execution. The provider reports origin/destination transaction lists without a dedicated refund-hash field; retain neutral chain labels for ambiguous origin transactions, preserve the wallet-known deposit separately, and keep copy-ID for unsupported explorer chains.

Official rules: https://docs.near-intents.org/resources/fees (reviewed 2026-11-07). Provider status is evidence of its report, not proof of shielded output value. Personal examples and raw provider responses stay outside this public repository.

Upstream integrations must preserve amount provenance, base-unit conversion, zero app fees, actual wallet fee presentation and history amount search. Deterministic regressions and real Tor quote acceptance cover these boundaries. Funded production settlement is outside automated acceptance.
