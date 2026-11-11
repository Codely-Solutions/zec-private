# Maintenance and releases

This fork tracks stable [Zodl Android](https://github.com/zodl-inc/zodl-android) releases. See [upstream.json](upstream.json) for the integrated identity, [STATUS.md](STATUS.md) for last-published evidence, and [excluded-features.json](excluded-features.json) for exclusions and compatibility exceptions.

## Find the procedure

Use this entry for the checkout; local task state lives in the surrounding workspace's `RESUME.md` when present. The source files and executable gates govern behavior; a completed report does not override them.

| Task | Maintained entry | Readiness and scope |
|---|---|---|
| Design or change UI | [UI-STANDARDS.md](UI-STANDARDS.md) | Component choices, hierarchy, wording and accessibility defaults. |
| Inspect UI / capture screenshots | [UI-REVIEW.md](UI-REVIEW.md) | Established capture/control components; artifact/state orchestration must match the current task. |
| Reuse local development state | [DEVELOPMENT.md](DEVELOPMENT.md), [dev_session.py](dev_session.py) | Related pinned checks in one bounded isolated workspace; no production release assembly. |
| Prepare/admit/assemble release acceptance | [RELEASE-WORKFLOW.md](RELEASE-WORKFLOW.md) | Verified producers, paired baseline, explicit ownership and selective execution; no signing. |
| Select quick checks and release checks | Build section below; [core coverage](core-coverage.md) | Coverage records identify their executed revision; current evidence must match inputs. |
| Maintain independent quote checks | [INDEPENDENT-PRICES.md](INDEPENDENT-PRICES.md) | Exact native-asset coverage, freshness, Tor behavior and advisory thresholds. |
| Run real wallet acceptance | [core coverage](core-coverage.md), [core_testnet_driver.py](core_testnet_driver.py), [acceptance_policy.py](acceptance_policy.py) | Requires a current prepared job, baseline and artifact bindings. Use [development artifact reuse](DEVELOPMENT.md#reuse-artifacts-and-guest-state) and the funding procedure below. |
| Overlap independent emulator checks | [emulator-batch.md](emulator-batch.md) | Prepared jobs only; one shared guard and separate workers. |
| Integrate upstream / refresh patch | Upstream integration below; [sync.py](sync.py), [refresh_source.py](refresh_source.py) | Discovery stays enabled; disposable integration and current acceptance remain gates. |
| Preserve next update fixture | [preserve_upgrade_fixture.py](preserve_upgrade_fixture.py) | Run in the bounded resource guard with `--tag vX.Y.Z-private.N`; requires published bound execution evidence. Verifies the uploaded seedless APK by downloading it again. |
| Promote/sign/publish | Signing below; [promotion.py](promotion.py), [publish.py](publish.py) | Requires explicit publication scope and accepted exact artifacts. |
| Clean Actions storage | [Actions artifact cleanup](actions_artifact_cleanup.py) (`--help`) | Inspect complete artifact references first; preserve required fixtures/evidence. |
| Locate prior local orchestration | Surrounding workspace's `records/INDEX.md` and `tools/extract-records.py` | Local private archives; recover selected files for inspection, not blind execution. |

`docs/Setup.md`, `docs/CI.md`, `docs/Deployment.md` and the upstream testing notes are inherited references, not this fork's release procedures. Parser-sensitive changelogs and attribution remain source inputs. Use [current preparation](RELEASE-WORKFLOW.md), which reads [upgrade-baseline.json](upgrade-baseline.json) and [maintenance-assets.json](maintenance-assets.json). The obsolete revision-6 preparer was retired; its source remains in Git history. Single-worker admission helpers still expect root-level job files. Adapt those assumptions within an authorized task before use; their presence is not a claim that an old job remains runnable.

## Reuse and cleanup

Choose lifetimes by the next consumer. At check completion, stop check-only workers and remove outputs with no remaining consumer. Related checks may share checkout, incremental state, APKs or a guest within one registered session with a storage budget, deadline and shutdown path.

| Resource | Reuse boundary | Retirement |
|---|---|---|
| Pinned SDK/JDK, Gradle dependencies, verified Android system image | Managed tool cache; verify selected versions and integrity | Managed cache policy, not each check |
| Isolated development checkout, project cache and incremental outputs | Related edits/checks at stable paths within one bounded development session | End of session; retain offline for an explicit resume consumer only |
| Built/downloaded APK and emulator-prepared copy | Exact artifact/source/configuration identity; verify digest and preparation provenance | After the last capture/acceptance consumer; retain only current candidate and required baseline |
| Emulator userdata and isolated test wallet | Compatible scenarios in the same bounded session | Stop guest/private ADB and remove disposable disks when that session ends; clean-wallet/recovery assertions get the required fresh state |
| Helper processes and tool sessions | Active registered consumer, with exact process/session identity | Shutdown and verify exit at session end or pause; use the owning tool's release mechanism for app-managed services |
| JUnit, captures, receipts and fixtures | Named review, acceptance, recovery or release-history consumer | Keep canonical evidence; retire superseded attempts and orchestration after preserving needed findings |

Reusable build state is an optimization, not acceptance evidence. Bind each check to its source and configuration, label executed versus reused results, and keep release artifacts immutable. Stop processes on pause even when offline caches are retained. Preserve unassigned services until ownership is established and record that unresolved scope accurately.

Use the [development session](DEVELOPMENT.md) for incremental checks and the [prepared APK/capture path](UI-REVIEW.md) for UI work. Both retain the existing guard's teardown guarantees. Task configuration selects source/scenes; maintained helpers own orchestration. The disposable release/integration path remains separate. Tor routing behavior is unchanged.


## Build and validation

Use the checked-in Gradle 9.9.0 wrapper, AGP 9.5.1, Kotlin 2.4.21, Temurin JDK 25.0.5+8 and Android SDK platform 37 (`platforms;android-37.0`). Keep Android target SDK 36, minimum SDK 27 and Java/Kotlin output 17. Preserve dependency locks and wrapper checksums. The fork uses stable Zcash SDK `6.0.0` in place of the upstream app tag's `6.0.0-SNAPSHOT`.

```sh
python3 .private/check.py
python3 .private/test_controls.py
./gradlew --no-daemon --no-watch-fs --max-workers=2 :ui-lib:testZcashmainnetFossReleaseUnitTest detektAll :app:lintZcashmainnetFossRelease :app:assembleZcashmainnetFossRelease
```

Windows checks and emulator APK preparation use checksum-verified portable Temurin 25.0.5+8 at `%USERPROFILE%/.codex/cache/jdks/temurin-25.0.5+8`; `ZEC_PRIVATE_JDK` can select another reviewed JDK for emulator preparation. Set `JAVA_HOME` only in the child build environment. The cache receipt records the official package checksum; preserve machine-wide Java/PATH and unrelated JDK caches. The Windows x64 [official package](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.5%2B8) is pinned by SHA-256 `636bdc13dc649ddc7d5921c5c7d23cca6e44fd19d662b45cc9c02135a6c96c42`. Retain Temurin 21.0.13+9 for the selected prior-release baseline build. The optional baseline job reads its source SHA from the current controller's `upgrade-baseline.json`; its default remains disabled.

Use the applicable resource guard and project acceptance policy. Offline preflight also checks local links in the maintained documentation entry points. After reorganizing procedures, verify task-table destinations, script bindings and any archive-recovery examples; historical/generated links are outside that narrow automated check. Build each required variant once per candidate and promote its validated unsigned artifact after acceptance. Production APKs are ARM64-only; x86_64 is reserved for isolated emulator debug acceptance. Release debuggability and debug-key signing are disabled.

[Swap amount rules](SWAP-AMOUNTS.md) explain amount provenance and fee accounting for maintainers.

Full evidence binds the source, workflow run, artifact digests and applicable wallet/quote acceptance. Static checks and a successful build alone do not authorize signing. Manifest, certificate, provider authority, license, architecture and 16 KiB ZIP alignment are inspected separately; ZIP alignment alone does not establish native runtime compatibility.

## Prepare and verify acceptance evidence

Bind each prepared emulator job to its exact artifact ID/digest, successful producer job, source SHA, package/version, baseline and applicable fingerprints from `acceptance_policy.py`. The containing workflow may still be finishing independent jobs; publication requires its final successful result. Use the seedless baseline and its matching instrumentation selected in `upgrade-baseline.json`. Prepare `upgradeBaselineInstrumentation` from its archive, source and APK hashes; new instrumentation may call APIs absent from the old app. The runner verifies the installed pair before each stage and replaces both APKs at upgrade. Future fixture archives preserve both original APKs. Read `core_testnet_driver.py` and the current UI scenario for other required manifest fields.

After a batch closes, require its accepted receipt, completed cleanup and zero owned survivors. Before assembling late attestations, hash each retained evidence file against `guestCleanup.retainedEvidence` in that receipt. Preserve original execution fields, APK/source identities and the producer run; validate current core/quote bindings through `acceptance_policy.py`. A historical JSON receipt is evidence of its original execution, not a template for a new pass.

Independently verify a publication inside the resource guard: download the exact tag's APK/checksum and compare asset sizes/digests, checksum and `release-evidence/<tag>.json`. Resolve the original unsigned artifact from that evidence's producer run and verify its source/digest and embedded proof. Compare all signed/unsigned ZIP-entry contents except signing metadata. Run `apksigner verify --print-certs`, check the permanent certificate defined in `publish.py`, and inspect package/version, debuggability, ARM64-only contents and 16 KiB ZIP alignment. Remove the downloads after retaining compact results; preserve the executed update fixture with the maintained tool above.

The release workflow runs `verify-public-release.yml` in a fresh read-only job before notifying subscribers. It checks the unauthenticated latest-release endpoint and matches the public APK against its recorded source and original unsigned artifact. Run it manually while the original artifact remains available when independently reconciling a publication.

Synthetic layout capture is a separate claim from quote or transaction acceptance. A capture failure must retain its own diagnostic without relabeling a valid financial result. Keep only safe status/exception-class diagnostics when provider tracing is necessary; raw wallet/provider bodies are not public release evidence.

### Testnet funding and failed boundaries

The maintained [funding helper](core_faucet.py) uses the documented shielded PoW faucet through pinned owned Tor; [faucet_pow.cjs](faucet_pow.cjs) requires an existing Node runtime with built-in Argon2 support (Node 24.7 or newer). The lifecycle checks testnet, wallet health and spendable balance before wallet creation, keeping the same owned Tor process for later funding. It rechecks status and reviewed work bounds before a single claim. No credential is needed. A successful status response alone does not prove a payout or wallet receipt.

After changing that helper or its worker/runtime, run `test_core_faucet.py` with child environment `CORE_FAUCET_TEST_POW=1` for the quick local work check. Ordinary CI checks the transport and claim rules without requiring a newer Node than its build runner. Native core admission verifies Argon2 availability and binds the worker and Node executable before guest startup.

Assign `CORE_FAUCET_LEDGER` an absolute task-owned path when a claim must survive a session boundary. Otherwise the ledger lives in the guard output and the admission retains it as `funding-claim-ledger.json`. Preserve it after any POST, along with its claim ID or uncertain outcome. Poll the existing claim and reconcile wallet history; a missing transaction ID or failed request does not authorize another claim. Diagnose an unavailable or incompatible faucet before another lifecycle. Use a documented compatible alternative only after resolving the previous outcome; keep sensitive setup requests on Tor.

SDK error 3200 is a wrapper, not a root cause: it also represents an explicit server rejection. Retain the safe stage, exception/cause, SDK code, elapsed time and any server rejection code. Separate the app graph from a public-only SDK/HTTP control before attributing failures to the app, SDK, emulator, VPN or endpoint. A request that succeeds later establishes changed readiness, not a retrospective diagnosis. Keep original assertions and distinguish diagnostics from passing acceptance.

## Upstream integration

`sync-upstream.yml` discovers official stable releases every six hours and supports manual dispatch. Drafts and prereleases are excluded. `fork.patch` carries the reviewed source delta; `.private/` and trusted workflows are overlaid separately. Read [sync.py](sync.py) and [excluded-features.json](excluded-features.json) before regenerating the patch: separately sanitized provider content and removed project trees must stay out of it.

The controller resolves the exact upstream commit, checks critical differences, checks patch applicability and prepares an update branch. Relevant wallet, recovery, signing, swap, privacy and build-trust changes require review. Conflicts and failed acceptance preserve the last release. A reusable workflow explicitly chains build, validation, signing and publication.

Refresh `fork.patch` and normalized `source-state.json` together and validate an actual disposable integration candidate. Preserve excluded features through upstream updates. Review resolved dependencies after removals because transitive constraints can change retained library versions.

The NEAR provider is excluded from patch hunks to avoid retaining the upstream credential. Preparation verifies the exact upstream provider, then installs the credential-free fork provider bound by `source-state.json`. Keep that reviewed implementation authoritative; credential stripping alone would lose its Tor and quote fixes. An upstream provider change requires review before replacement.

After reviewing and staging source changes, run `python3 -B .private/refresh_source.py`. It generates the patch from canonical indexed bytes and refuses unstaged source edits. Preserve embedded line endings in `fork.patch`; Git text normalization would corrupt Windows-wrapper hunks. Stage the regenerated metadata, then run offline preflight and candidate validation.

## Signing and publication

Unsigned builds receive no signing secrets. The signing job uses the trusted controller, verifies the current run's artifact binding, hash, package and version, then invokes Android signing tools without running upstream Gradle scripts. Pull requests have no signing workflow. External Actions are pinned to immutable revisions.

Scope the three release credentials to `production-signing`, with an explicit `main` branch deployment rule and no tag rules. Both publication and simulated-upgrade signing use this environment. Verify the environment and remove any repository-wide copies before admitting maintenance proposals. The controller verifies that its checkout and remote `main` still match before signing and again before publication. Prepared upstream descendants can update their candidate metadata and source inventory. The controller rejects changes to workflows, signing code and acceptance policy in those descendants.

Keep the repository variable `PRODUCTION_SIGNING_READY` unset during signing setup. Upstream discovery and validation-only builds remain available. Set it to `true` only after verifying the credential scope, trusted controller and signing path. The variable enables existing signing jobs; automatic maintenance publication still requires independently verified proposal and acceptance controls.

Keep the cloud repair connector scoped to the separate proposal repository. Verify its installation scope, protected controller ownership and job permissions independently before enabling automatic publication. An environment branch rule controls secret admission. The proposal import and integration policy must also control which source can enter `main`.

After changing signing credentials, run `verify-signing-environment.yml` on `main` with the default scope check enabled. It verifies private-key access and the pinned certificate, and a separate ordinary job checks that the three credentials are absent outside the environment.

Tags use `v<upstream-version>-private.<revision>`, with the revision in `release-revision.json`. Android versionCode must exceed published versions even if an upstream build counter resets. Published tags and assets are immutable. Publication precedes a normal fast-forward integration-state push; concurrent changes cause that push to fail.

Wallet releases contain the signed APK and its checksum. Detailed validation JSON is retained under `release-evidence/<tag>.json` in the integration-state commit. Seedless test APK baselines belong to the authenticated `maintenance-fixtures` draft, with exact asset IDs, sizes and SHA-256 digests in [maintenance-assets.json](maintenance-assets.json). They are not user downloads. After independent signed-release verification, preserve its already executed test APK with `preserve_upgrade_fixture.py`; retain its compact provenance and apply the returned maintenance index. Select the verified fixture in `upgrade-baseline.json` before the next candidate. This changes future acceptance inputs; keep completed release evidence unchanged. Preserve original bytes and verify the replacement before removing a maintenance asset from a wallet release. Never replace a published APK or checksum. Historical validation records remain immutable even when the next baseline changes.

For a new revision of the same upstream version, increase `private_revision` and `ZCASH_VERSION_CODE`, refresh patch/source-state metadata, and run the release workflow. Preserve the existing signing key. Rebuilding a published version refuses replacement. Normal stable upstream integration uses the discovery workflow.

Keep signing custody and notification credentials outside the repository. Routine updates require the existing keystore and alias `zec-private`; a replacement key needs a separate Android update-compatibility plan. Keep an encrypted offline backup independent of the working drive. Wallet seeds and spending keys must never become CI inputs.

Notifications contain build/release information and GitHub links. The ntfy topic is private credential material; service acceptance does not prove delivery to a phone. Actions, review issues and Releases remain available when delivery fails.

## Public repository information

The canonical repository is `Codely-Solutions/zec-private`. After an ownership change, update active app/API links, Obtainium JSON and encoded imports, security/funding links, maintenance download helpers and Git remotes together. Verify release/fixture IDs, Actions permissions and signing-secret names without reading secret values; preserve historical evidence. The updater deliberately requires canonical asset URLs, so an installed build pinned to an old owner can reject redirected metadata. Provide a manual/Obtainium update route until a signed build containing the new owner ships. Run control checks and disposable upstream integration before considering migration complete; metadata checks do not establish signing or installed-device execution.

Keep the README product definition, GitHub About description and repository topics aligned with the shipping wallet. Use concrete user terms such as Zcash/ZEC wallet for Android, self-custody, shielded payments and cross-chain swaps, with accurate fee and privacy qualifications. Update GitHub metadata separately when capabilities change; source integration preserves the fork README and user guides.

## Release descriptions

Add concise user-facing changes to `release-notes.json`, keyed by the exact private tag. Unlisted tags link to upstream changes. User help belongs in the [wallet guide](../docs/WALLET-GUIDE.md); test methodology and provenance belong in bound validation evidence. Preserve the `Android versionCode: N` line: the publisher uses it to reject non-increasing updates.

## Dependency update policy

`dependency-graph.yml` resolves the Gradle graph with a read-only token and submits the snapshot in a separate job without checking out build code. Main-branch changes to Gradle inputs trigger it. When integration uses `GITHUB_TOKEN`, invoke graph refresh from the trusted controller because its pushes do not trigger another workflow. Snapshot coverage complements advisory review for embedded native SDK code.

`maintenance-watch.yml` checks upstream schedule freshness and classifies collaborator-only Dependabot proposals without running their code. Workflow and policy edits require review. This shadow check reports candidates for validation and grants no merge or signing authority.

`gradle/verification-metadata.xml` pins SHA-256 checksums for dependency artifacts and their metadata; normal Gradle runs use strict verification. Generated hashes establish an integrity baseline, not independent publisher authentication; signature verification is not enabled. Review unexpected coordinates and hash changes against trusted publisher evidence before accepting them, and keep unknown artifacts failing strictly. Use the bounded [bootstrap procedure](DEVELOPMENT.md#run-related-checks) to export proposed metadata without leaving the development checkout modified. Cover the candidate's unit-test, app, instrumentation, lint and release dependency graphs; metadata collected before a task fails establishes resolution, not build acceptance. Retain source/configuration and exported-file hashes, actual coverage and bootstrap provenance limits with the task's evidence; keep generated origins intact.

Target the latest stable compatible combination of dependencies and build tools, with LTS runtimes where appropriate. Verify releases, security status and compatibility from primary sources when selecting versions. Evaluate Gradle, AGP, Kotlin, Compose, Android SDK and plugins together; assess coordinated migrations when existing pins prevent current dependencies. Keep runtime, compile SDK, target SDK, minimum Android version and bytecode targets as separate decisions.

When compatibility is uncertain, inspect the affected APIs and use a bounded disposable configuration/compilation check before expensive acceptance. Distinguish a documented tested range from a hard incompatibility. Resolve ordinary migration work within the authorized maintenance scope. For each retained older version, record the concrete constraint or unresolved evidence, affected scope, next action and recheck trigger here; distinguish an unattempted migration from a demonstrated blocker. Keep validated pins until replacement acceptance passes.

Dependabot checks Gradle Wrapper and GitHub Actions weekly, allowing patch/minor proposals and excluding major proposals. It opens at most one PR per ecosystem and groups compatible action updates. Runtime wallet/cryptography/provider libraries are outside this automatic sweep; they need dependency-lock, privacy and acceptance review. GitHub Actions remain pinned to exact commits.

`toolchain-review.yml` runs without signing secrets or write permissions. Its bounded configuration smoke test exposes plugin/deprecation failures early; it does not establish release acceptance. Update `fork.patch` and `source-state.json` for wrapper changes before integration, then run the candidate's required deterministic and real acceptance. Merging and publication remain gated. Same-major updates can still break builds, alter transactions through plugins, or carry security regressions; a version label alone is insufficient approval.

The pinned toolchain includes the Gradle security fixes for [GHSA-mvvg-497x-hmj8](https://github.com/gradle/gradle/security/advisories/GHSA-mvvg-497x-hmj8). Kotlin 2.4.21's published full-support table ends at Gradle 9.7.0 and AGP 9.3.1; validate the pinned combination through this project's required checks and recheck upstream support during toolchain updates. Guava 33.8.0-android addresses the known collection denial-of-service advisories; verify effective resolution before release.

Retain the existing Biometric 1.4.0-alpha05 implementation pending authentication runtime coverage for a stable-base replacement. Unused biometric-ktx wiring is removed. The used APIs exist in stable 1.1.0, but static API compatibility does not establish equivalent prompt, credential-fallback and cancellation behavior. Reassess this existing preview exception when stable replacements or the required device coverage become available.

Keep deprecation diagnostics visible. Current builds retain Detekt/Kover Gradle API deprecations and JVM test-tooling warnings from Byte Buddy/Conscrypt. Recheck before Gradle 10/11 or a JDK that enforces the announced removals. Optional Play-publishing API deprecations also remain. Preserve detailed diagnostics; these warnings alone do not establish a shipping-wallet security defect.

App update discovery uses the existing HTTP provider and a fixed public GitHub endpoint. It announces releases; it does not authenticate or install APKs. Native SDK 6.0.0 returns a whole JNI response byte array, so a Ktor channel does not provide bounded-memory Tor APK streaming. Installation remains with Obtainium/Android or the release download flow. Revisit an in-app installer when a supported bounded streaming transport is available; do not add another native Tor stack solely for this feature.

SDK Tor HTTP cancellation is cooperative: a coroutine deadline cannot interrupt its blocking JNI request or disposal. Keep client creation, reads and disposal on an I/O dispatcher. For interruption checks, observe the blocked window and signal host restoration before joining native calls. Verify changed transport instrumentation with the unfunded `transportProbe` stage before claiming faucet coins; it exercises the same recovery flow and checks Main-thread responsiveness.

Ktor 3.6.1 resolves fixed upstream OkHttp 5.5.1, including the compromised eMudhra intermediate blocklist ([upstream fix](https://github.com/square/okhttp/commit/6db98026dfb817a23c0282aa4e53d8187b309052)). Preserve this effective Android dependency and its real TLS regression: an otherwise trusted compromised chain must be rejected before an HTTP request is sent. Valid-chain and hostname-mismatch controls distinguish the blocklist from a general connection failure.

SDK-native Tor TLS uses a separate trust path. OkHttp's blocklist test establishes no protection for that path; assess its native dependency provenance, trust-store updates and affected chain behavior separately. Exposure depends on whether that path trusts the compromised intermediates and lacks an effective independent mitigation.

[Android certificate transparency](https://developer.android.com/privacy-and-security/security-config#CertificateTransparency) is another platform protection: opt-in on API 36, default on API 37, unavailable on older versions. Assess its effective client coverage and live TLS compatibility before changing policy; it does not establish protection for older devices or the native SDK's separate TLS stack.

## Donation UI review

`.private/donation-review/include.gradle` explicitly adds a debug-only activity for inspecting the production Home and donation components with synthetic activity. It uses the real local reminder policy but cannot submit a payment. Follow [UI-REVIEW.md](UI-REVIEW.md) for artifact-bound capture and inspection. Ordinary builds omit this activity. Emulator screenshots establish presentation and reminder interactions; they do not establish wallet transaction or release acceptance.
