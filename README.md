# ZEC Private Android

A personal Android Zcash wallet fork with shielded payments, swaps through NEAR Intents, and Keystone hardware-wallet support.

## Download and install

Download **zec-private-latest.apk** from the [latest release](https://github.com/Sammy-Lastre/zec-private/releases/latest). Releases support **ARM64 Android devices**. Check the accompanying `SHA256SUMS` before installing; Android may ask you to allow installation from the app opening the APK.

Install updates over the existing wallet. Keep your recovery material secure, and never uninstall the wallet to resolve an update failure without securing it first. The package is `com.personal.zecprivate.foss`, so it can coexist with the official wallet.

## Features

- Send, receive and recover Zcash; use hardware and view-only wallets.
- Swap supported assets with no app or affiliate fee. Provider, network and exchange costs remain; review the quoted amount before confirming.
- Use Tor for sensitive wallet and provider requests. Bulk compact-block downloads use a direct connection.
- Keep gift redemption and existing swap recovery, with advertisements, referrals, Flexa merchant spending and coinholder voting removed.

## Privacy and verification

Swap providers can see quote details, addresses, amounts and timing. Public-chain transactions can be correlated. Fresh shielded addresses and Tor reduce some exposure; they do not guarantee anonymity.

Read the [wallet guide](docs/WALLET-GUIDE.md) for fees, privacy boundaries and signature verification. [Validation status](.private/STATUS.md) records the latest release evidence and limits. Automated acceptance uses an isolated testnet emulator; production ARM hardware, biometric approval and funded production swap settlement require separate verification.

## Development and attribution

See [maintenance and builds](.private/MAINTENANCE.md) for upstream updates, release controls and signing. This fork tracks stable [Zodl Android](https://github.com/zodl-inc/zodl-android) releases. Upstream copyright and the [MIT license](LICENSE) are preserved; the original documentation is in [docs](docs/UPSTREAM-README.md).
