# ZEC Private — Zcash wallet for Android

ZEC Private is an open-source, self-custodial Zcash (ZEC) wallet for Android, with shielded payments, cross-chain swaps through NEAR Intents, and Tor support.

## Install

Download **zec-private-latest.apk** from the [latest release](https://github.com/Codely-Solutions/zec-private/releases/latest). Requires **Android 8.1 or later on an ARM64 device**. See the [wallet guide](docs/WALLET-GUIDE.md#installation-and-updates) to verify the download.

**Automatic updates:** [Add ZEC Private to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%257B%2522id%2522%253A%2522com.personal.zecprivate.foss%2522%252C%2522url%2522%253A%2522https%253A%252F%252Fgithub.com%252FCodely-Solutions%252Fzec-private%2522%252C%2522author%2522%253A%2522Codely-Solutions%2522%252C%2522name%2522%253A%2522ZEC%2520Private%2522%252C%2522additionalSettings%2522%253A%2522%257B%255C%2522includePrereleases%255C%2522%253Afalse%252C%255C%2522includeDrafts%255C%2522%253Afalse%252C%255C%2522fallbackToOlderReleases%255C%2522%253Afalse%252C%255C%2522apkFilterRegEx%255C%2522%253A%255C%2522%255Ezec-private-latest%255B.%255Dapk%2524%255C%2522%252C%255C%2522autoApkFilterByArch%255C%2522%253Atrue%252C%255C%2522trackOnly%255C%2522%253Afalse%257D%2522%257D). [Install Obtainium](https://obtainium.imranr.dev/) if needed.

Install updates over the existing app. Keep your recovery phrase and birthday safe before troubleshooting; uninstalling can erase your wallet data.

## Use the wallet

- **Shielded payments:** Send and receive ZEC, recover your wallet with its recovery phrase and birthday, and redeem gift links.
- **Bitcoin and cross-chain swaps:** Swap Bitcoin (BTC) and other supported assets into shielded ZEC, or pay external addresses from ZEC with CrossPay. Other assets use external wallets.
- **No app or affiliate fees:** Network and swap-provider costs still apply; review the amounts before confirming.
- **Tor privacy:** Sensitive wallet and provider requests use Tor when enabled. Public block downloads remain direct. See [privacy](docs/PRIVACY.md) for what providers can see.
- **Hardware and view-only wallets:** Ledger and Keystone integrations, plus accounts without spending keys.

Read the [wallet guide](docs/WALLET-GUIDE.md) for payments, swaps and recovery, or [support development](docs/DONATIONS.md).

## Development

[Build and maintain the fork](.private/MAINTENANCE.md) · [Last published validation](.private/STATUS.md) · [Report a vulnerability](.github/SECURITY.md)

This fork tracks stable [Zodl Android](https://github.com/zodl-inc/zodl-android) releases. Upstream copyright and the [MIT license](LICENSE) are preserved. The [original README](docs/UPSTREAM-README.md) is retained for reference.
