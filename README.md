# ZEC Private Android

Personal fork of [Zodl Android](https://github.com/zodl-inc/zodl-android), based on stable release `3.19.0-3234`, commit `a57ea1455840a0d97eb0b2a3d2557480dfe5bda7`, published October 29, 2026. Upstream copyright and MIT license remain in LICENSE. Original setup and security documentation remain in docs/.

This project is under validation. A committed workflow or passing static check does not establish that a usable APK exists. See `.private/STATUS.md` for verified results and blockers before installing or funding a wallet.

## Differences

The mainnet FOSS release is named ZEC Private. Its installed package is `com.personal.zecprivate.foss`, which can coexist with official Zodl. The launcher uses original geometric artwork. Fork support is through this private repository; the upstream support mailbox is removed.

NEAR quote requests send `appFees: []`, omit the Zodl referral, and contain no Zodl partner JWT or personal API key. The affiliate-specific argument, constants and display calculations are removed. This does not remove NEAR platform, bridge, execution, liquidity or blockchain network costs. [NEAR authentication documentation](https://docs.near-intents.org/integration/distribution-channels/1click-api/authentication) documents an additional 25 basis points for unauthenticated ordinary quotes. Review the quoted received amount before signing. Affiliate fee displays represent the fork's fee, not a complete breakdown of provider costs embedded in the exchange quote.

Wallet encryption, recovery, transaction construction, local signing, Tor selection and swap refund paths are inherited. Incoming swap orchestration requests a fresh shielded recipient; outgoing swap orchestration requests a fresh shielded refund address and uses the quote-specific provider deposit. These paths have not yet been established by a completed device acceptance test. No real funds, wallet seeds or spending keys are needed by CI.

## Privacy boundaries

NEAR sees the quote's assets, quantities, recipient, refund address, deposit address and timing. It may correlate requests and public settlement records. The BTC side is public. Counterparties and network observers may correlate amounts and timing across chains. Fresh shielded addresses reduce address reuse; they do not establish anonymity or erase provider records. The SDK's address derivation and shielded sending are security-critical dependencies.

The existing Tor preference is preserved. When Tor is enabled, the shared HTTP provider constructs the Tor client without a direct fallback. Swap traffic otherwise follows the user's existing setting; this fork does not force a new global Tor default. Release HTTP body logging remains disabled. No analytics or quote proxy is added. Read the upstream wallet threat model linked in `docs/UPSTREAM-README.md`.

## Building and installing

Use the checked-in Gradle wrapper, JDK 21 toolchain and Android SDK 36. Keep dependency locks and wrapper checksums intact. This fork pins `com.zodl.android` SDK `6.0.0`, the current stable release verified from Maven Central and the official source tag. Upstream's app tag used `6.0.0-SNAPSHOT`; the stable replacement still requires successful build and behavioral validation.

Run `python3 .private/check.py` and `python3 .private/test_controls.py`, then `./gradlew --no-daemon --no-watch-fs --max-workers=2 :ui-lib:testZcashmainnetFossReleaseUnitTest detektAll :app:lintZcashmainnetFossRelease :app:assembleZcashmainnetFossRelease`. Release debuggability and debug-key signing are disabled. Supply release-signing Gradle properties from a private environment only, or sign the unsigned APK in a separate process using Android apksigner.

Download signed APKs and SHA256SUMS from this private repository's GitHub Releases. Check the checksum and signing certificate before installing. Android may require enabling installation for the application used to open the APK. Updates must use the same signing key and a higher versionCode. Never uninstall an existing wallet to resolve an update failure without independently securing its recovery material. Automatic phone installation is not configured.

## Upstream updates

`sync-upstream.yml` polls the official GitHub Releases API every six hours and supports Run workflow. Drafts and prereleases are excluded. `.private/upstream.json` records the last integrated upstream identity. `.private/fork.patch` is the exact fork delta; `.private/` and trusted workflows are overlaid separately from upstream.

The controller fetches the exact upstream tag, resolves its commit, checks critical differences, applies the patch with `git apply --check`, and prepares an update branch. A reusable workflow explicitly chains build, test, signing and publication; it does not depend on GITHUB_TOKEN pushes triggering another workflow. Critical wallet/swap/privacy/build-trust changes stop automation and open a review issue. Patch conflicts and failed tests preserve the last release. This conservative gate can require review for normal upstream releases when critical code changes.

Unsigned builds receive no signing secrets. The signing job checks out the controller revision, downloads only the current run's validated artifact, verifies its bound source SHA, hash, package and version, and invokes SDK signing tools without running upstream Gradle scripts. Pull requests have no signing workflow. All external Actions are pinned to official immutable revisions. Upstream workflows are disabled in this fork.

Private release tags use `v<upstream-version>-private.1`. Android versionCode uses upstream build number times 1000 plus a private revision; the publisher requires it to exceed previous releases. Published tags and assets are never replaced. Previous releases are retained. After publication, an exact lease advances main to the validated candidate and records integration; concurrent modifications cause the push to fail instead of being overwritten. The signing job needs a fresh signing credential for every future key rotation, and Android update compatibility must be handled separately.

For a manual build use Actions → Build and publish ZEC Private → Run workflow. Rebuilding an already published version intentionally refuses to replace it; increase the private revision/versionCode through reviewed changes when a new release is needed.

## Signing custody and notifications

The dedicated RSA-4096 signing certificate SHA-256 is `41:01:74:E0:D6:80:63:8D:19:64:7F:46:E0:E4:B4:1E:A6:25:3C:23:51:AD:FA:84:C5:D9:B1:78:8D:2D:A1:68`. Signing secrets were authorized separately and stored as Actions secrets. Wallet secrets are never CI inputs.

The local signing files are outside this repository at `D:\Data\Crypto\zec-private\signing\`: `release.p12`, `release-backup.p12`, and `password.txt`. Copy the backup and password to separate encrypted offline storage; two files on one drive are not an independent disaster-recovery backup. Restore the exact keystore and alias `zec-private`, then restore the matching Actions secrets. Never generate a replacement key for a routine update.

The ntfy URL is in Actions secret `NTFY_URL` and the private local file `D:\Data\Crypto\zec-private\signing\ntfy-url.txt`. Install ntfy on Android, add a subscription using that file's server/topic, and allow Android notifications and background delivery. Keep the topic private: possession permits subscribing and publishing on the public ntfy service. Push acceptance by the service does not prove delivery to your phone. Notification text contains only build/release information and GitHub links. GitHub Actions, review issues and Releases remain available if push delivery fails.
