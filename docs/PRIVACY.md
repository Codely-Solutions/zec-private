# ZEC Private privacy: shielded Zcash and Tor

Software-wallet keys and databases are stored on your device. Keep your recovery information secure. The FOSS release does not enable Firebase collection.

## Network connections

When Tor is enabled, sensitive wallet metadata, transaction and swap-provider requests use Tor and fail if it is unavailable. Public compact-block downloads and normal DNS use direct connections; Tor is not a device-wide tunnel.

NEAR receives swap assets, amounts, recipient/refund addresses and timing. Public-chain transactions and amounts can be correlated. Incoming swaps use fresh shielded recipients, and outgoing swaps use fresh shielded refund addresses. Tor and fresh addresses reduce exposure but cannot erase provider records or guarantee anonymity.

Independent swap-price checks send public market and pool identifiers to exchanges and blockchain RPCs. They follow your Tor setting and exclude wallet addresses, swap amounts and quote data.

Fiat conversion uses CoinMarketCap where enabled. Opening a browser or sharing data hands it to the application you select, under that application's policies.

## Sharing diagnostics

Exports may contain wallet history, addresses, amounts, notes and device details. Inspect them before sharing. Never include a recovery phrase, viewing key or gift secret. Exports are not sent automatically to Zodl or a support service.
