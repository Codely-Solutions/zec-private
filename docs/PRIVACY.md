# ZEC Private privacy: shielded Zcash and Tor

Software-wallet keys and databases are stored on your device. Keep your recovery information secure. The FOSS release does not enable Firebase collection.

Android may include encrypted contacts and wallet metadata, such as transaction notes and swap records, in system backup or device transfer according to your Android settings. The app's backup rules exclude wallet keys and the recovery phrase. Keep your recovery phrase and wallet birthday separately for wallet recovery.

Reset lets you keep these encrypted local backups. Turning that option off removes the app's local contact and metadata backups for every wallet, including backups kept during earlier resets. Reset cannot recall copies already held by an external backup service.

## Network connections

When Tor is enabled, sensitive wallet metadata, transaction and swap-provider requests use Tor and fail if it is unavailable. Public compact-block downloads and normal DNS use direct connections; Tor is not a device-wide tunnel.

NEAR receives swap assets, amounts, recipient/refund addresses and timing. Public-chain transactions and amounts can be correlated. Incoming swaps use fresh shielded recipients, and outgoing swaps use fresh shielded refund addresses. Tor and fresh addresses reduce exposure but cannot erase provider records or guarantee anonymity.

Independent swap-price checks send public market and pool identifiers to exchanges and blockchain RPCs. They follow your Tor setting and exclude wallet addresses, swap amounts and quote data.

Fiat conversion uses CoinMarketCap where enabled. Opening a browser or sharing data hands it to the application you select, under that application's policies.

## Sharing diagnostics

Inspect diagnostic reports before sharing. Keep recovery phrases, viewing keys and gift secrets out of ordinary support messages. Choose your own sharing recipient.

**Export Private Data** shares the full wallet database, including the history and viewing keys of the accounts it holds. A recipient can use those keys to see past and future wallet activity. Share this file only with someone you trust with that access.
