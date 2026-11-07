# Wallet guide

## Installation and updates

Use the APK and `SHA256SUMS` from this repository's [Releases](https://github.com/Sammy-Lastre/zec-private/releases). Production releases support ARM64. Updates require the same signing key and a higher Android versionCode. Preserve your recovery material before troubleshooting an installation, and avoid uninstalling an existing wallet.

The release signing certificate SHA-256 is:

```text
41:01:74:E0:D6:80:63:8D:19:64:7F:46:E0:E4:B4:1E:A6:25:3C:23:51:AD:FA:84:C5:D9:B1:78:8D:2D:A1:68
```

Check the APK checksum against `SHA256SUMS` and its certificate with Android SDK `apksigner verify --print-certs`. A checksum establishes agreement with the downloaded checksum file; verify the expected certificate as well. Automatic phone installation is not configured.

## Swap costs

Quote requests use `appFees: []`, omit the upstream referral, and carry no partner JWT or personal API key. ZEC Private requests a 0% app/affiliate fee. Provider, bridge, execution and network costs, together with exchange pricing or spread, can affect the amount received.

NEAR's published anonymous API platform rate is 0.25% for non-`ANY_INPUT` quotes. Its authenticated 0.20% schedule is an alternative rate. NEAR also lists a 0.0001% protocol fee per transaction. An `ANY_INPUT` platform-fee exemption still leaves other costs. Consult the [fee schedule](https://docs.near-intents.org/resources/fees) and [authentication rules](https://docs.near-intents.org/integration/distribution-channels/1click-api/authentication) for current terms, and review the quoted received amount and wallet transaction fee before confirming.

## Privacy boundaries

NEAR sees quote assets, quantities, recipient, refund address, deposit address and timing. It may correlate requests and public settlement records. Public-chain counterparties and network observers may correlate amounts and timing across chains.

Incoming swaps request a fresh shielded recipient. Outgoing swaps request a fresh shielded refund address and use the provider's quote-specific deposit address. Address-generation failure cannot fall back to an existing wallet address. New quotes requiring a TEX deposit are rejected because SDK TEX sends can introduce a wallet-owned transparent intermediate. Existing status and refund recovery remain supported.

Tor protects sensitive wallet metadata, transaction and provider requests when enabled, without a direct fallback for those requests. Bulk compact-block downloads and their normal DNS remain direct. Normal DNS is permitted. Release HTTP body logging is disabled; the fork adds no analytics or quote proxy. Fresh addresses and Tor do not erase provider records or establish anonymity. See the upstream threat model linked in [the original README](UPSTREAM-README.md).

## Validation and support

[STATUS](../.private/STATUS.md) records current release evidence. Release validation distinguishes inspected ARM64 production packaging from x86_64 emulator execution. Testnet acceptance covers wallet operations, installed updates, restart, seed recovery and scoped Tor interruption. Unfunded production quote acceptance is separate from funded swap settlement. ARM hardware and biometric approval UI have separate acceptance limits.

Diagnostics open Android's share chooser. Choose the recipient yourself; exporting does not contact a staffed support team or automatically escalate a provider issue. Upstream's support mailbox is removed from this fork.


## Incoming swaps

Choose the source asset and network, amount and a valid refund address. Review the received amount, costs and expiry. Dismissing a quote does not fund it. After accepting, follow that swap's exact deposit instructions from your external wallet. Verify its network and amount before sending. Keep the swap record and monitor status; do not assume an accepted quote means settlement. This fork requests no affiliate fee, but provider, network and exchange costs remain possible.

## CrossPay

Choose the recipient asset/network, destination and target amount. Review the ZEC cost and destination before approving the wallet transaction. Check any required memo or tag; unsupported instructions must not be improvised. Follow progress in Activity. Never repeat a payment merely because a network response is delayed; inspect the existing transaction and swap status first. Provider terms apply.

## Ironwood migration

Keep the recovery phrase and birthday safe. Review the app's migration prompt, plan and privacy warnings before approving. Moving funds between pools can reveal amounts and timing; splitting transfers reduces some correlation risks. Immediate migration trades that scheduling for speed. The app shows required fees and signing steps, including hardware approval where applicable.

Scheduled Android transfers depend on connectivity and background execution. Follow the app's notification/battery guidance and reopen it to check pending work. Enabling Tor protects sensitive broadcasts; if Tor fails, resolve that connection before continuing. Public bulk block downloads remain direct. Do not reset wallet data to resolve an incomplete migration; inspect its status and recovery guidance.

Hardware firmware may call its compatible companion app “Zodl”; that device label is retained in pairing instructions. Never share the hardware recovery phrase.

## Gift redemption and recovery

Gift links contain spending secrets. Scan or paste them only into the intended redemption flow. Do not share them with support or put them in notes, diagnostics or public issue reports. External links require an in-app scan for safety. Keep unfinished redemption state until the app confirms completion; legacy gift formats remain supported.

## Guidance provenance

The migration and payment guidance was reviewed against the upstream [migration guide](https://support.zodl.com/article/42-moving-your-funds-to-ironwood), [CrossPay guide](https://support.zodl.com/article/24-using-crosspay-to-spend-zec) and [swap guide](https://support.zodl.com/article/26-swapping-into-zec) on 2026-11-06. Those pages describe upstream; this guide describes the retained fork flow and does not promise settlement timing or support services.
