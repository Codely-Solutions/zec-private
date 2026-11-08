# Maintenance and releases

This fork tracks stable [Zodl Android](https://github.com/zodl-inc/zodl-android) releases. See [upstream.json](upstream.json) for the integrated identity, [STATUS.md](STATUS.md) for last-published evidence, and [excluded-features.json](excluded-features.json) for exclusions and compatibility exceptions.

## Find the procedure

Use this entry for the checkout; local task state lives in the surrounding workspace's `RESUME.md` when present. The source files and executable gates govern behavior; a completed report does not override them.

| Task | Maintained entry | Readiness and scope |
|---|---|---|
| Inspect UI / capture screenshots | [UI-REVIEW.md](UI-REVIEW.md) | Established capture/control components; artifact/state orchestration must match the current task. |
| Select quick checks and release checks | Build section below; [core coverage](core-coverage.md) | Coverage records identify their executed revision; current evidence must match inputs. |
| Run real wallet acceptance | [core coverage](core-coverage.md), [core_testnet_driver.py](core_testnet_driver.py), [acceptance_policy.py](acceptance_policy.py) | Requires a current prepared job, baseline and artifact bindings; not a screenshot prerequisite. |
| Overlap independent emulator checks | [emulator-batch.md](emulator-batch.md) | Prepared jobs only; one shared guard and separate workers. |
| Integrate upstream / refresh patch | Upstream integration below; [sync.py](sync.py), [refresh_source.py](refresh_source.py) | Discovery stays enabled; disposable integration and current acceptance remain gates. |
| Preserve next update fixture | [preserve_upgrade_fixture.py](preserve_upgrade_fixture.py) | Run in the bounded resource guard with `--tag vX.Y.Z-private.N`; requires published bound execution evidence. Verifies the uploaded seedless APK by downloading it again. |
| Promote/sign/publish | Signing below; [promotion.py](promotion.py), [publish.py](publish.py) | Requires explicit publication scope and accepted exact artifacts. |
| Clean Actions storage | [Actions artifact cleanup](actions_artifact_cleanup.py) (`--help`) | Inspect complete artifact references first; preserve required fixtures/evidence. |
| Locate prior local orchestration | Surrounding workspace's `records/INDEX.md` and `tools/extract-records.py` | Local private archives; recover selected files for inspection, not blind execution. |

`docs/Setup.md`, `docs/CI.md`, `docs/Deployment.md` and the upstream testing notes are inherited references, not this fork's release procedures. Parser-sensitive changelogs and attribution remain source inputs. [prepare_core_acceptance.py](prepare_core_acceptance.py) is a legacy preparer with a revision-6 baseline; the current baseline comes from [upgrade-baseline.json](upgrade-baseline.json) and [maintenance-assets.json](maintenance-assets.json). Single-worker admission helpers still expect root-level job files. Adapt those assumptions within an authorized task before use; their presence is not a claim that an old job remains runnable.

## Build and validation

Use the checked-in Gradle wrapper, JDK 21 and Android SDK 36. Preserve dependency locks and wrapper checksums. The fork uses stable Zcash SDK `6.0.0` in place of the upstream app tag's `6.0.0-SNAPSHOT`.

```sh
python3 .private/check.py
python3 .private/test_controls.py
./gradlew --no-daemon --no-watch-fs --max-workers=2 :ui-lib:testZcashmainnetFossReleaseUnitTest detektAll :app:lintZcashmainnetFossRelease :app:assembleZcashmainnetFossRelease
```

Windows project checks and emulator APK preparation use verified portable Temurin 21.0.13+9 at `%USERPROFILE%/.codex/cache/jdks/temurin-21.0.13+9`; `ZEC_PRIVATE_JDK` can select another reviewed JDK for emulator preparation. Set `JAVA_HOME` only in the child build environment. The cache receipt records the official package checksum; preserve machine-wide Java/PATH and unrelated JDK caches.

Use the applicable resource guard and project acceptance policy. Offline preflight also checks local links in the maintained documentation entry points. After reorganizing procedures, verify task-table destinations, script bindings and any archive-recovery examples; historical/generated links are outside that narrow automated check. Build each required variant once per candidate and promote its validated unsigned artifact after acceptance. Production APKs are ARM64-only; x86_64 is reserved for isolated emulator debug acceptance. Release debuggability and debug-key signing are disabled.

[Swap amount rules](SWAP-AMOUNTS.md) explain amount provenance and fee accounting for maintainers.

Full evidence binds the source, workflow run, artifact digests and applicable wallet/quote acceptance. Static checks and a successful build alone do not authorize signing. Manifest, certificate, provider authority, license, architecture and 16 KiB ZIP alignment are inspected separately; ZIP alignment alone does not establish native runtime compatibility.

## Prepare and verify acceptance evidence

Bind each prepared emulator job to its exact artifact ID/digest, successful producer job, source SHA, package/version, baseline and applicable fingerprints from `acceptance_policy.py`. The containing workflow may still be finishing independent jobs; publication requires its final successful result. Use the verified seedless baseline selected in `upgrade-baseline.json`, not a copied historical job. Read `core_testnet_driver.py` and the current UI scenario for their required manifest fields.

After a batch closes, require its accepted receipt, completed cleanup and zero owned survivors. Before assembling late attestations, hash each retained evidence file against `guestCleanup.retainedEvidence` in that receipt. Preserve original execution fields, APK/source identities and the producer run; validate current core/quote bindings through `acceptance_policy.py`. A historical JSON receipt is evidence of its original execution, not a template for a new pass.

Independently verify a publication inside the resource guard: download the exact tag's APK/checksum and compare asset sizes/digests, checksum and `release-evidence/<tag>.json`. Resolve the original unsigned artifact from that evidence's producer run and verify its source/digest and embedded proof. Compare all signed/unsigned ZIP-entry contents except signing metadata. Run `apksigner verify --print-certs`, check the permanent certificate defined in `publish.py`, and inspect package/version, debuggability, ARM64-only contents and 16 KiB ZIP alignment. Remove the downloads after retaining compact results; preserve the executed update fixture with the maintained tool above.

Synthetic layout capture is a separate claim from quote or transaction acceptance. A capture failure must retain its own diagnostic without relabeling a valid financial result. Keep only safe status/exception-class diagnostics when provider tracing is necessary; raw wallet/provider bodies are not public release evidence.

## Upstream integration

`sync-upstream.yml` discovers official stable releases every six hours and supports manual dispatch. Drafts and prereleases are excluded. `fork.patch` carries the reviewed source delta; `.private/` and trusted workflows are overlaid separately. Read [sync.py](sync.py) and [excluded-features.json](excluded-features.json) before regenerating the patch: separately sanitized provider content and removed project trees must stay out of it.

The controller resolves the exact upstream commit, checks critical differences, checks patch applicability and prepares an update branch. Relevant wallet, recovery, signing, swap, privacy and build-trust changes require review. Conflicts and failed acceptance preserve the last release. A reusable workflow explicitly chains build, validation, signing and publication.

Refresh `fork.patch` and normalized `source-state.json` together and validate an actual disposable integration candidate. Preserve excluded features through upstream updates. Review resolved dependencies after removals because transitive constraints can change retained library versions.

After reviewing changes and staging any newly added source files, run `python3 -B .private/refresh_source.py`. It regenerates metadata idempotently; it does not approve or commit the changes. Run offline preflight and candidate validation afterward.

## Signing and publication

Unsigned builds receive no signing secrets. The signing job uses the trusted controller, verifies the current run's artifact binding, hash, package and version, then invokes Android signing tools without running upstream Gradle scripts. Pull requests have no signing workflow. External Actions are pinned to immutable revisions.

Tags use `v<upstream-version>-private.<revision>`, with the revision in `release-revision.json`. Android versionCode must exceed published versions even if an upstream build counter resets. Published tags and assets are immutable. Publication precedes a normal fast-forward integration-state push; concurrent changes cause that push to fail.

Wallet releases contain the signed APK and its checksum. Detailed validation JSON is retained under `release-evidence/<tag>.json` in the integration-state commit. Seedless test APK baselines belong to the authenticated `maintenance-fixtures` draft, with exact asset IDs, sizes and SHA-256 digests in [maintenance-assets.json](maintenance-assets.json). They are not user downloads. After independent signed-release verification, preserve its already executed test APK with `preserve_upgrade_fixture.py`; retain its compact provenance and apply the returned maintenance index. Select the verified fixture in `upgrade-baseline.json` before the next candidate. This changes future acceptance inputs; keep completed release evidence unchanged. Preserve original bytes and verify the replacement before removing a maintenance asset from a wallet release. Never replace a published APK or checksum. Historical validation records remain immutable even when the next baseline changes.

For a new revision of the same upstream version, increase `private_revision` and `ZCASH_VERSION_CODE`, refresh patch/source-state metadata, and run the release workflow. Preserve the existing signing key. Rebuilding a published version refuses replacement. Normal stable upstream integration uses the discovery workflow.

Keep signing custody and notification credentials outside the repository. Routine updates require the existing keystore and alias `zec-private`; a replacement key needs a separate Android update-compatibility plan. Keep an encrypted offline backup independent of the working drive. Wallet seeds and spending keys must never become CI inputs.

Notifications contain build/release information and GitHub links. The ntfy topic is private credential material; service acceptance does not prove delivery to a phone. Actions, review issues and Releases remain available when delivery fails.

## Release descriptions

Add concise user-facing changes to `release-notes.json`, keyed by the exact private tag. Unlisted tags link to upstream changes. User help belongs in the [wallet guide](../docs/WALLET-GUIDE.md); test methodology and provenance belong in bound validation evidence. Preserve the `Android versionCode: N` line: the publisher uses it to reject non-increasing updates.

## Dependency update policy

Dependabot checks Gradle Wrapper and GitHub Actions weekly, allowing patch/minor proposals and excluding major proposals. It opens at most one PR per ecosystem and groups compatible action updates. Runtime wallet/cryptography/provider libraries are outside this automatic sweep; they need dependency-lock, privacy and acceptance review. GitHub Actions remain pinned to exact commits.

`toolchain-review.yml` runs without signing secrets or write permissions. Its bounded configuration smoke test exposes plugin/deprecation failures early; it does not establish release acceptance. Update `fork.patch` and `source-state.json` for wrapper changes before integration, then run the candidate's required deterministic and real acceptance. Merging and publication remain gated. Same-major updates can still break builds, alter transactions through plugins, or carry security regressions; a version label alone is insufficient approval.

Gradle 8.14.6 is the current maintenance choice. The latest Gradle 9/AGP 9 releases exceed the currently verified Kotlin support matrix. Guava 33.8.0-android addresses the known collection denial-of-service advisories; effective resolution is checked before release. Revision 15 validates Java/Kotlin output at 17, retiring obsolete compiler mode; the build runtime remains JDK 21. Do not suppress these warnings or switch targets just to clear a summary. Review primary release/compatibility/security notes when changing the toolchain; preserve detailed deprecation logs in the normal candidate build.

OkHttp 5.5.1 blocks compromised eMudhra intermediates ([upstream fix](https://github.com/square/okhttp/commit/6db98026dfb817a23c0282aa4e53d8187b309052)), but its Android AAR requires compileSdk 37. No compatible published backport was found on 2026-11-08; the JVM artifact is not an equivalent Android implementation. The retained supported toolchain leaves this CA mitigation unresolved for direct OkHttp traffic. Revisit with a compatible SDK 37 toolchain; inspect SDK-native Tor TLS separately rather than treating this as its fix.

## Donation UI review

`.private/donation-review/include.gradle` explicitly adds a debug-only activity for inspecting the production Home and donation components with synthetic activity. It uses the real local reminder policy but cannot submit a payment. Follow [UI-REVIEW.md](UI-REVIEW.md) for artifact-bound capture and inspection. Ordinary builds omit this activity. Emulator screenshots establish presentation and reminder interactions; they do not establish wallet transaction or release acceptance.
