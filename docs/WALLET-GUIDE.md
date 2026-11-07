# Wallet guide

## Installation and updates

Download the APK and `SHA256SUMS` from [Releases](https://github.com/Sammy-Lastre/zec-private/releases). Releases support ARM64 Android devices. Install updates over the existing app; avoid uninstalling or clearing its data without securing your recovery phrase and birthday.

Check the APK against `SHA256SUMS`. To verify the signer, run Android SDK `apksigner verify --print-certs` and compare its certificate SHA-256 with:

```text
41:01:74:E0:D6:80:63:8D:19:64:7F:46:E0:E4:B4:1E:A6:25:3C:23:51:AD:FA:84:C5:D9:B1:78:8D:2D:A1:68
```

The package is `com.personal.zecprivate.foss`, so it can coexist with the official wallet.

## Swaps and CrossPay

For an incoming swap, choose the source asset/network, amount and refund address. Review the quote and minimum received. Dismissing the quote sends nothing. After accepting, follow its exact deposit instructions from your external wallet. Keep the swap record until settlement or refund completes.

For CrossPay, choose the recipient asset/network, destination and target amount. Check any required memo or tag, then review the ZEC total before approving. Follow progress in Activity. If a response is delayed, check the existing transaction and swap status before sending again.

ZEC Private adds no app or affiliate fee. Provider costs are included in the quote; the NEAR platform percentage is only part of those costs. See [NEAR's fee schedule](https://docs.near-intents.org/resources/fees) for its current terms.

The **Zcash network fee** pays for sending ZEC from your wallet. **Total ZEC to spend** includes it. For incoming swaps funded from another wallet, that sending wallet may charge its own network fee.

Quoted output is an estimate; execution can differ. Completed swap details show the provider's executed amount when available. Your wallet transaction shows the ZEC actually received.

## Recovery and migration

Keep your recovery phrase and wallet birthday secure. Never enter a hardware-wallet recovery phrase into this app or share it with anyone.

For Ironwood migration, review the plan, fees and privacy warnings before approving. Transfers between pools can reveal amounts and timing. Scheduled transfers need connectivity and Android background access; reopen the app to check pending work. If a migration stalls, inspect its status before resetting anything.

Hardware firmware may call its companion app “Zodl”; that label is used in pairing instructions.

## Gifts

Gift links contain spending secrets. Scan or paste them only in the redemption flow, and keep unfinished redemption state until completion. Never put gift links in diagnostics or public issue reports.

## Donations and diagnostics

[Support development](DONATIONS.md) is optional. Automatic swap donations start off. Enabling them authorizes the chosen percentage for future successful swaps, without another donation prompt. Payments whose network fee is at least the donation amount are skipped. One-time donations retain their payment review. In-app donations require a software wallet.

Diagnostics open Android's share chooser. Inspect the export and choose its recipient yourself; no staffed support inbox is configured. See [privacy](PRIVACY.md) before sharing wallet information.
