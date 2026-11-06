# Swap amounts and costs

The quote is an estimate of output. Its minimum output is the provider's base-unit guarantee converted using the destination asset's decimals. The review shows that minimum for floating-output swaps. CrossPay retains its maximum-input slippage allowance.

Status output comes from the provider's settled amount when available, including raw base-unit output when the formatted field is absent. Otherwise the displayed value remains explicitly quoted. Provider output is not an independently measured wallet credit. The wallet's received transaction records its own scanned net value. A provider/bridge reporting difference must not be silently corrected with a guessed constant.

The fork submits no app fees. Current unauthenticated 1Click requests use EXACT_INPUT, EXACT_OUTPUT or FLEX_INPUT. NEAR documents a 0.25% platform charge on non-ANY_INPUT quotes, included in the returned quote. This is not the total swap cost. Protocol, route and bridge costs or favourable execution may also affect output. Do not subtract an included fee a second time or infer aggregate fees from independently priced fiat values.

The review omits a wallet fee for externally funded incoming swaps. Outgoing swaps retain the wallet proposal fee; completed outgoing details use the actual wallet transaction fee rather than historical aggregate metadata. Legacy stored fee fields remain readable for compatibility.

Official rules: https://docs.near-intents.org/resources/fees (reviewed 2026-11-07). Provider status is evidence of its report, not proof of shielded output value. Personal examples and raw provider responses stay outside this public repository.

Upstream integrations must preserve amount provenance, base-unit conversion, zero app fees, actual wallet fee presentation and history amount search. Deterministic regressions and real Tor quote acceptance cover these boundaries. Funded production settlement is outside automated acceptance.
