# ZEC Private: Android Zcash wallet guide

## Installation and updates

Download the APK and `SHA256SUMS` from [Releases](https://github.com/Codely-Solutions/zec-private/releases). Releases support ARM64 devices running Android 8.1 or later. Install updates over the existing app; avoid uninstalling or clearing its data without securing your recovery phrase and birthday.

Releases 17–18 may fail their in-app update check after the repository moved to Codely-Solutions. Use Obtainium or the Releases link above for the next update; install it over the existing app.

Check the APK against `SHA256SUMS`. To verify the signer, run Android SDK `apksigner verify --print-certs` and compare its certificate SHA-256 with:

```text
41:01:74:E0:D6:80:63:8D:19:64:7F:46:E0:E4:B4:1E:A6:25:3C:23:51:AD:FA:84:C5:D9:B1:78:8D:2D:A1:68
```

The package is `com.personal.zecprivate.foss`, so it can coexist with the official wallet.

### Automatic updates

[Obtainium](https://obtainium.imranr.dev/) can check GitHub and install new releases. [Add ZEC Private to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522com.personal.zecprivate.foss%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252FCodely-Solutions%252Fzec-private%2522%252C%2522author%2522%253A%2522Codely-Solutions%2522%252C%2522name%2522%253A%2522ZEC%2520Private%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Afalse%252C%255C%2522includeDrafts%255C%2522%253Afalse%252C%255C%2522fallbackToOlderReleases%255C%2522%253Afalse%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255Ezec-private-latest%255B.%255Dapk%2524%255C%2522%252C%255C%2522autoApkFilterByArch%255C%2522%253Atrue%252C%255C%2522trackOnly%255C%2522%253Afalse%257D%2522%257D). Drafts, prereleases and non-production assets are excluded. Enable background updates in Obtainium; Android may require installation permission or confirmation.

The wallet also checks for updates while open, at most once a day. Use **About → Check for updates** to check sooner. If the check fails, retry there to see a current update offer. Dismissing a home notice hides that version. Update links open Obtainium or your browser; those apps use their own connection settings. Install over the existing wallet to preserve its data.

## Swaps and CrossPay

ZEC Private holds Zcash (ZEC); cross-chain swaps use external wallets for other assets. For example, swap Bitcoin (BTC) into shielded ZEC, or use CrossPay to pay a Bitcoin address from ZEC. Available assets and quotes depend on the provider.

For an incoming swap, choose the source asset/network, amount and refund address. Review the quote and minimum received. Dismissing the quote sends nothing. After accepting, follow its exact deposit instructions from your external wallet. Keep the swap record until settlement or refund completes.

For CrossPay, choose the recipient asset/network, destination and target amount. Confirm that the destination accepts the selected asset and network; separate destination memo/tag fields are not supported. Review the ZEC total before approving. Follow progress in Activity. If a response is delayed, check the existing transaction and swap status before sending again.

**Funding unconfirmed** in Activity means the wallet saved the swap or payment before funding appeared in wallet history. Open the entry or tap **Check status** to see the provider’s status.

**Funding not sent** means the wallet stopped before broadcasting the transaction. The swap reference stays in Activity. **Funding status** describes the wallet’s funding result.

**Pending** after Send, Swap or Pay means the wallet can still submit the original transaction. Check Activity for its final status before sending again.

Contacts are filtered by the selected network. Changing networks keeps your recipient visible; correct any inline mismatch before requesting a quote.

ZEC Private adds no app or affiliate fee. Provider costs are included in the quote; the NEAR platform percentage is only part of those costs. See [NEAR's fee schedule](https://docs.near-intents.org/resources/fees) for its current terms.

The **Zcash network fee** pays for sending ZEC from your wallet. **Total ZEC to spend** includes it. For incoming swaps funded from another wallet, that sending wallet may charge its own network fee.

Quoted output is an estimate; execution can differ. Completed swap details show the provider's executed amount when available. Your wallet transaction shows the ZEC actually received.

**Quote details** shows an independent price comparison and its sources. All supported swap assets have a reference. A quote at least 15% below its reference shows an inline warning with **Swap anyway** or **Pay anyway**. If a fresh price cannot be verified, **Price check unavailable** keeps the quote usable.

## Recovery and migration

Keep your recovery phrase and wallet birthday secure. Never enter a hardware-wallet recovery phrase into this app or share it with anyone.

When app lock is enabled, canceling Android authentication keeps the wallet locked. Tap the key icon to try again. Recovery words and viewing keys require fresh approval after the app locks.

Reset is complete when the app returns to onboarding. If it asks you to finish resetting the wallet, tap **Try again** to complete the pending reset.

For Ironwood migration, review the plan, fees and privacy warnings before approving. Transfers between pools can reveal amounts and timing. Scheduled transfers need connectivity and Android background access. Reopen the app to check pending work. Home offers to finish scheduling if Keystone signatures are still available in the app. If the app closed before storing them, return to the migration to finish signing.

Hardware firmware may call its companion app “Zodl”; that label is used in pairing instructions.

## Gifts

Gift links contain spending secrets. Open **More → Redeem Gift Card** to scan the QR code or paste the link. Android may open a tapped link in your browser. Keep unfinished redemption state until completion and keep gift links out of diagnostics and public issue reports.

## Donations and diagnostics

To [support development](DONATIONS.md), choose an amount and review the payment before sending. In-app donations require a software wallet.

Diagnostics open Android's share chooser. Inspect the export and choose its recipient yourself; no staffed support inbox is configured. See [privacy](PRIVACY.md) before sharing wallet information.
