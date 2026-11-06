# Privacy and network behavior

ZEC Private is an independent fork. It does not send diagnostics to Zodl support or enable Firebase collection in its FOSS release. Diagnostic exports are initiated by you; inspect them before sharing. They may expose wallet history, amounts, addresses, notes and device details. Never share a seed, viewing key or gift secret.

Wallet secrets and wallet databases are stored on the device. Back up your recovery information securely. Android sharing and browser actions hand data to another application you choose; that application has its own policies. The repository and release pages require GitHub access, and this private repository requires an authorized account.

When Tor is enabled, sensitive wallet metadata, transaction and swap-provider requests use Tor and must fail without a direct fallback. Upstream public compact-block downloads and their normal DNS use direct connections. This is not an all-traffic tunnel. Servers can observe connections and timing; Tor and shielded transactions do not eliminate every privacy risk.

The current product uses Zcash wallet infrastructure, NEAR 1Click for swaps/status, and CoinMarketCap for fiat conversion where configured. Gift redemption accepts compatible legacy Zodl and Vizor links. These are functional compatibility boundaries, not endorsements. Provider fees and exchange spreads may apply; a zero fork affiliate fee does not mean a free swap. Review the received amount and current provider conditions.

No automatic export of personal wallet data is authorized by this document. Required upstream and dependency copyright/license notices remain in the source and dependency notices.
