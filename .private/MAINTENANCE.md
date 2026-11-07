# Maintenance and releases

This fork tracks stable [Zodl Android](https://github.com/zodl-inc/zodl-android) releases. See `upstream.json` for the integrated identity, `STATUS.md` for current evidence, and `excluded-features.json` for exclusions and compatibility exceptions.

## Build and validation

Use the checked-in Gradle wrapper, JDK 21 and Android SDK 36. Preserve dependency locks and wrapper checksums. The fork uses stable Zcash SDK `6.0.0` in place of the upstream app tag's `6.0.0-SNAPSHOT`.

```sh
python3 .private/check.py
python3 .private/test_controls.py
./gradlew --no-daemon --no-watch-fs --max-workers=2 --continue :ui-lib:testZcashmainnetFossReleaseUnitTest detektAll :app:lintZcashmainnetFossRelease :app:assembleZcashmainnetFossRelease
```

Use the applicable resource guard and project acceptance policy. Build each required variant once per candidate and promote its validated unsigned artifact after acceptance. Production APKs are ARM64-only; x86_64 is reserved for isolated emulator debug acceptance. Release debuggability and debug-key signing are disabled.

Full evidence binds the source, workflow run, artifact digests and applicable wallet/quote acceptance. Static checks and a successful build alone do not authorize signing. Manifest, certificate, provider authority, license, architecture and 16 KiB ZIP alignment are inspected separately; ZIP alignment alone does not establish native runtime compatibility.

## Upstream integration

`sync-upstream.yml` discovers official stable releases every six hours and supports manual dispatch. Drafts and prereleases are excluded. `fork.patch` carries the reviewed source delta; `.private/` and trusted workflows are overlaid separately. Read `sync.py` and `excluded-features.json` before regenerating the patch: separately sanitized provider content and removed project trees must stay out of it.

The controller resolves the exact upstream commit, checks critical differences, checks patch applicability and prepares an update branch. Relevant wallet, recovery, signing, swap, privacy and build-trust changes require review. Conflicts and failed acceptance preserve the last release. A reusable workflow explicitly chains build, validation, signing and publication.

Refresh `fork.patch` and normalized `source-state.json` together and validate an actual disposable integration candidate. Preserve excluded features through upstream updates. Review resolved dependencies after removals because transitive constraints can change retained library versions.

After reviewing changes and staging any newly added source files, run `python3 -B .private/refresh_source.py`. It regenerates metadata idempotently; it does not approve or commit the changes. Run offline preflight and candidate validation afterward.

## Signing and publication

Unsigned builds receive no signing secrets. The signing job uses the trusted controller, verifies the current run's artifact binding, hash, package and version, then invokes Android signing tools without running upstream Gradle scripts. Pull requests have no signing workflow. External Actions are pinned to immutable revisions.

Tags use `v<upstream-version>-private.<revision>`, with the revision in `release-revision.json`. Android versionCode must exceed published versions even if an upstream build counter resets. Published tags and assets are immutable. Publication precedes a normal fast-forward integration-state push; concurrent changes cause that push to fail.

Wallet releases contain the signed APK and its checksum. Detailed validation JSON is retained under `release-evidence/<tag>.json` in the integration-state commit. Seedless test APK baselines belong to the authenticated `maintenance-fixtures` draft, with exact asset IDs, sizes and SHA-256 digests in `maintenance-assets.json`. They are not user downloads. Preserve original bytes and verify the replacement before removing a maintenance asset from a wallet release. Never replace a published APK or checksum. Historical validation records remain immutable even when the next baseline changes.

For a new revision of the same upstream version, increase `private_revision` and `ZCASH_VERSION_CODE`, refresh patch/source-state metadata, and run the release workflow. Preserve the existing signing key. Rebuilding a published version refuses replacement. Normal stable upstream integration uses the discovery workflow.

Keep signing custody and notification credentials outside the repository. Routine updates require the existing keystore and alias `zec-private`; a replacement key needs a separate Android update-compatibility plan. Keep an encrypted offline backup independent of the working drive. Wallet seeds and spending keys must never become CI inputs.

Notifications contain build/release information and GitHub links. The ntfy topic is private credential material; service acceptance does not prove delivery to a phone. Actions, review issues and Releases remain available when delivery fails.

## Release descriptions

Add concise user-facing changes to `release-notes.json`, keyed by the exact private tag. Unlisted tags link to upstream changes. Recurring fees, privacy boundaries and acceptance methodology belong in the [wallet guide](../docs/WALLET-GUIDE.md) and bound validation evidence. Preserve the `Android versionCode: N` line: the publisher uses it to reject non-increasing updates.

## Dependency update policy

Dependabot checks Gradle Wrapper and GitHub Actions weekly, allowing patch/minor proposals and excluding major proposals. It opens at most one PR per ecosystem and groups compatible action updates. Runtime wallet/cryptography/provider libraries are outside this automatic sweep; they need dependency-lock, privacy and acceptance review. GitHub Actions remain pinned to exact commits.

`toolchain-review.yml` runs without signing secrets or write permissions. Its bounded configuration smoke test exposes plugin/deprecation failures early; it does not establish release acceptance. Update `fork.patch` and `source-state.json` for wrapper changes before integration, then run the candidate's required deterministic and real acceptance. Merging and publication remain gated. Same-major updates can still break builds, alter transactions through plugins, or carry security regressions; a version label alone is insufficient approval.

Gradle 8.14.6 is the current maintenance choice. Gradle 9.9.0 exceeds the fully supported range of the retained Kotlin plugin, and production reports deprecated Gradle APIs. Java/Kotlin bytecode target 8 remains intentional compatibility policy while the build runtime is JDK 21. Do not suppress these warnings or switch targets just to clear a summary. Review primary release/compatibility/security notes when changing the toolchain; preserve detailed deprecation logs in the normal candidate build.
