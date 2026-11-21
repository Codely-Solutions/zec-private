# Bounded local development

Use [dev_session.py](dev_session.py) for related compilation/unit checks, with the existing Windows resource guard and [development-policy.json](development-policy.json). Keep a source checkout, Gradle project cache and incremental outputs at stable paths until the bounded session ends. SDK/JDK/dependency caches remain managed separately. Release preparation continues to use the release workflow.

## Run related checks

Write one task-owned manifest. `source` is an absolute repository path; each operation selects a full committed source SHA. Parameters are non-secret development inputs. For a change headed to release, include release-scoped Detekt alongside the first focused JVM tests in one Gradle invocation, before preview builds or hosted dispatch. Use the same release analysis script as hosted preflight. Example structure:

```json
{
  "source": "D:/Data/Crypto/zec-private/source",
  "seconds": 1700,
  "idle_seconds": 300,
  "operations": [{
    "source": "FULL_40_CHARACTER_COMMIT_SHA",
    "seconds": 600,
    "config": {
      "tasks": [":detektAll", ":ui-lib:testZcashmainnetFossDebugUnitTest"],
      "tests": ["co.electriccoin.zcash.ui.common.model.RecipientAddressPolicyTest"],
      "init_scripts": [".private/release-analysis.gradle", ".private/tor-ui-abi.gradle", ".private/robolectric-ci.gradle"]
    }
  }]
}
```

Run with the managed Python interpreter (or `python3` where configured):

```text
python3 -B .private/dev_session.py TASK/inputs.json --evidence TASK/checks
```

The evidence directory must be new. Debug build tasks, unit tests and `:detektAll` analysis are accepted; production release assembly/signing is outside this helper. `tests` filters apply to each explicitly requested JVM test task, directly after its task name; analysis and assembly tasks receive none. For production Detekt scope, include `.private/release-analysis.gradle`. Optional `jdk`, `sdk` and `gradle_home` paths select existing reviewed installations. `--policy` selects a reviewed guard override; the project default caps memory at 8 GiB and transient storage at 18 GiB. Admission still checks host headroom.

For checks added while editing, set an absolute task-owned `inbox`. Atomically rename a complete operation JSON to `0001.json`, `0002.json`, etc., counting initial operations too. Pin a new commit for each source change. `finish.json` ends an idle session; the idle and total deadlines also end it. One controller owns each session.

Each operation has immutable source/configuration/tool bindings, bounded process ownership, task outcomes and copied JUnit/timing evidence. JUnit reports are scoped to explicitly requested JVM tasks; other retained modules/variants are not copied into that operation. `UP-TO-DATE` results describe reused Gradle inputs, not fresh test execution. Changed source/configuration is handled by Gradle invalidation and receives a new binding. A failing command ends the session; correct its boundary before starting another. Interrupted runs retain bounded diagnostics. Completed session receipts are verified/archived locally before their own shared-ledger entry is retired.

For screen behavior, start with JVM ViewModel and mapper tests using the real state-building code, mocked I/O and virtual coroutine time. Exercise saved/loading, success, error, retry, cancellation and navigation where the screen supports them. Keep the decoder and resolver real when testing a provider boundary. Use representative catalog entries together, including coexisting aliases and exact IDs, changed or missing entries and genuine ambiguity. Mocking the resolver's answer cannot validate its mapping. Keep sanitized public contract fixtures beside the affected tests and record which live response shape informed them.

The existing session's `tests` filter can select `*VMTest` and `*MapperTest` for a broader screen-state pass without emulator startup. Add the affected adapter/use-case classes to cover the data path into those screens. The complete `ui-lib` release JVM suite runs in [release_checks.sh](release_checks.sh); controller preflight tests cover the release machinery. The [coverage inventory](core-coverage.md#fast-screen-and-provider-checks) records what these checks establish and the remaining gaps. Render changed layouts through the existing capture path after the state checks pass.

For catalog identity, units or detail recovery changes, select `*SwapCatalog*Test`, `*SwapDetailVMTest` and `*TransactionDetailVMTest` in that JVM task. These cases cover the full selectable catalog with fixed public metadata, synthetic amounts, real mapping/state code and mocked external I/O. They make no live API requests. Add the existing affected recipient, quote and status classes when changing their shared boundaries.

The [frozen catalog fixture](../ui-lib/src/test/resources/swap-catalog.json) contains only public asset IDs, symbols, networks, decimals and contracts. `SwapCatalogContractTest` requires it to match every curated wallet pair and validates each entry against the production market identity map. A changed catalog or identity requires reviewing the fixture against authoritative metadata and the existing live catalog drift check; preserve both the decoder cases and the fixture's independence from the runtime map. The [coverage inventory](core-coverage.md#fast-screen-and-provider-checks) distinguishes the per-asset matrix from representative status and precision cases. Keep live source freshness, routing and settlement acceptance separate.

For an explicit dependency-checksum bootstrap, set `config.write_verification_metadata` to `["sha256"]`. The controller passes Gradle's generation flag before startup, copies the resulting `gradle/verification-metadata.xml` into that operation's evidence directory with its SHA-256, and restores the checkout's original metadata bytes or original absence before the next operation. Metadata input and export are capped at 4 MiB. Review the exported XML and provenance before committing it. Normal validation omits this option and uses strict verification from the committed metadata. Generated checksums record the resolved bytes; independently establish publisher provenance for critical artifacts. For verification failures, use `-Dorg.gradle.dependency.verification.console=verbose` or retain Gradle's HTML report before teardown. See [Gradle dependency verification](https://docs.gradle.org/current/userguide/securing_dependencies.html).

For a cold CI metadata proposal, manually dispatch the existing `toolchain-review.yml` with `metadata_proposal=true`. Its default remains the strict configuration/resource-tool smoke. The opt-in mode runs Gradle's `dependencies` reports across retained projects and a representative library resource compilation (including host-specific AAPT2) with `--write-verification-metadata sha256 --refresh-dependencies`; it performs no app assembly, test, signing or acceptance task. The three-day artifact contains proposed XML (at most 4 MiB), source/run/hash/exit identities, a resolution-failure line count, up to 64 KiB of failure lines and the final 64 KiB of the log. Check the exit code and resolution diagnostics, review the XML diff and publisher provenance, then apply reviewed additions and rerun strict validation. Gradle may omit detached configurations created only during task execution; this proposal does not establish complete build coverage or acceptance. See [Gradle's generation and review limits](https://docs.gradle.org/current/userguide/dependency_verification.html).

All checkout/build/guest scratch is removed when its guard ends. There is no long-lived daemon or cross-session workspace store. On pause, finish the session and retain only named offline artifacts/evidence; the next session recreates missing state.

## Native access, storage and reset probes

Build the opt-in [core-wallet instrumentation](core-wallet/include.gradle) with the exact testnet debug app, then use one guarded, blank API35 emulator and private ADB session. Bind both original APK hashes, prepared execution-entry preservation, source and tool inputs. These probes reject production networks, non-debug apps and non-emulator devices. Pass `isolatedTestnet=valueless-coins-only`; they need no faucet or transaction.

`ProjectReviewStorageTest#encryptedContactsSurviveFailedWriteThenReplaceAtomically` uses the production encryptor, file provider and Android directory sync. It injects a partial encryption failure, verifies the original ciphertext and book survive, then verifies successful replacement and temporary-file cleanup. `CoreWalletAcceptance` with `stage=settingsReset` and the selected `transport` invokes the actual Settings reset action with metadata deletion, checks SDK/contact/note removal, then restores the same isolated wallet identity. Clear only this disposable app between independent wallet scenarios.

`ProjectReviewAppAccessTest#cancellationAndBackgroundRelock` additionally requires a fresh PIN-secured, unlocked guest, `securedGuest=disposable-emulator` and its six-digit `fixturePin`. It creates an unfunded wallet through the real foreground UI, cancels and retries Android credential entry, checks locked wallet accessibility, reveals recovery words after approval, backgrounds the same Activity for the genuine 15-minute timeout, and requires fresh app access and reveal approval on return. Compose labels can be non-clickable children; the probe resolves their nearest bounded, visible, enabled `ACTION_CLICK` ancestor, following the core UI selector's constraints. Budget at least 21 minutes for that instrumentation and 29 minutes for a combined capture/storage/reset/access session. Never capture screenshots or raw accessibility trees while real recovery words are revealed; the probe emits only phase names and elapsed time. Physical biometric and hardware-wallet behavior remain separate checks.

Inspect changed layouts using the synthetic production-component scenes in [ProjectReviewScenes.kt](style-comparison/src/co/electriccoin/zcash/ui/screen/donation/ProjectReviewScenes.kt) and [the capture procedure](UI-REVIEW.md). `contact-save-error`, `reset-pending`, `incoming-saved-pending` and `app-locked` establish their named presentation states; swap/CrossPay review scenes cover the shared fee/review layout. Inspect light, dark, OLED and large text, including expanded/scrolled reference details. Synthetic captures establish presentation, while the native probes establish their executed behaviors. Retire the guest, private ADB, ephemeral test key and APK copies after the last consumer; retain immutable results, inspected frames and verified guard receipts.

## Release controller checks

Run the affected Python suites offline with `PYTHONPATH=.private` from a coherent checkout. For hosted Linux validation, dispatch [automation-test.yml](../.github/workflows/automation-test.yml) on the reviewed branch. A non-main dispatch runs the offline controller suites and real disposable Git fixtures with read-only permissions. The release controllers are tested with synthetic metadata and mocked publication calls.

Use `patch_environment` from [test_support.py](test_support.py) for test environment changes. It restores empty Windows variables for subprocesses, including Git configuration overrides with empty values.

Set the validation workflow's optional `verify_existing_release` input to the current published tag when changing public verification or producer resolution. On a review branch this reads the public APK and exact original unsigned archive, checks their metadata, certificate and payload, and records the original producer job. It receives read-only access and performs no signing or publication. The original archive must still be retained; offline recovery fixtures cover expired or rejected identities.

## Kotlin plugin resolution

Plugin classpaths have their own repositories. Root, `buildSrc`, convention-build and bridge settings route the exact `org.jetbrains.kotlin` implementation group to Maven Central. Plugin marker groups retain the Portal and their existing checksum pins. Keep these scopes distinct when changing repositories; marker POMs can differ between publishers.

The emulator and quote bridge use [gradle_resolution.py](gradle_resolution.py). On a known Kotlin configuration miss, it checks exact committed coordinates, confirms POM availability and verifies the pinned POM or Gradle module metadata from Central, then refreshes the original command once. Healthy commands run Gradle once. Task, checksum, lock, unknown-coordinate, offline and mixed transport failures retain their failure. Keep dependency verification, command options and immutable artifact bindings intact.

For this boundary, dispatch `toolchain-review.yml` with `resolution_probe=true`. It runs the normal root configuration/resource check, standalone included-build/bridge configuration and a loopback Maven fixture. The root composite uses the checked-in verification file; standalone checks establish configuration only. The fixture records whether Gradle retains a miss or re-fetches it, rejects an invalid checksum, and removes its server and disposable state. It assembles no app APK and uses no signing or wallet credentials. [Gradle cache semantics](https://docs.gradle.org/current/userguide/dependency_caching.html) explain why repository identities and missing entries matter. Diagnose a failed probe before another run.

## Reuse artifacts and guest state

Use [prepared_apks.py](prepared_apks.py) for verified original/prepared APK identities and [UI review](UI-REVIEW.md) for capture-specific setup. For a prepared receipt retained by its registered consumer, set `CODEX_EMULATOR_PREPARED_RECEIPT` to its absolute path and `CODEX_EMULATOR_PREPARED_SHA256` to its verified digest. The runner checks the entire manifest binding, original/prepared hashes, payload preservation, ABI and test-signing compatibility before reuse. Keep the manifest unchanged while reusing that receipt; changed configuration needs fresh preparation. Keep the current candidate and required baseline until their final capture/acceptance consumer ends, then retire the binaries and preserve immutable evidence separately.

Use one existing `EmulatorSession` for compatible scenes/actions. Keep clean-wallet, recovery, network and identity boundaries explicit. Close guest/private ADB at session end or pause. The [two-worker runner](emulator-batch.md) remains available for independent required scenarios; shared state is not a reason to combine dependent or incompatible wallets.

The shared guard remains authoritative for job limits, ownership and teardown. App-managed tool services outside its process tree require their owning tool's supported release mechanism. Preserve and report unassigned services; a process name or timestamp alone does not establish ownership.
