# Bounded local development

Use [dev_session.py](dev_session.py) for related compilation/unit checks, with the existing Windows resource guard and [development-policy.json](development-policy.json). Keep a source checkout, Gradle project cache and incremental outputs at stable paths until the bounded session ends. SDK/JDK/dependency caches remain managed separately. Release preparation continues to use the release workflow.

## Run related checks

Write one task-owned manifest. `source` is an absolute repository path; each operation selects a full committed source SHA. Parameters are non-secret development inputs. Example structure:

```json
{
  "source": "D:/Data/Crypto/zec-private/source",
  "seconds": 1700,
  "idle_seconds": 300,
  "operations": [{
    "source": "FULL_40_CHARACTER_COMMIT_SHA",
    "seconds": 600,
    "config": {
      "tasks": [":ui-lib:testZcashmainnetFossDebugUnitTest"],
      "tests": ["co.electriccoin.zcash.ui.common.model.RecipientAddressPolicyTest"],
      "init_scripts": [".private/tor-ui-abi.gradle", ".private/robolectric-ci.gradle"]
    }
  }]
}
```

Run with the managed Python interpreter (or `python3` where configured):

```text
python3 -B .private/dev_session.py TASK/inputs.json --evidence TASK/checks
```

The evidence directory must be new. Debug build tasks, unit tests and `:detektAll` analysis are accepted; production release assembly/signing is outside this helper. Optional `jdk`, `sdk` and `gradle_home` paths select existing reviewed installations. `--policy` selects a reviewed guard override; the project default caps memory at 8 GiB and transient storage at 18 GiB. Admission still checks host headroom.

For checks added while editing, set an absolute task-owned `inbox`. Atomically rename a complete operation JSON to `0001.json`, `0002.json`, etc., counting initial operations too. Pin a new commit for each source change. `finish.json` ends an idle session; the idle and total deadlines also end it. One controller owns each session.

Each operation has immutable source/configuration/tool bindings, bounded process ownership, task outcomes and copied JUnit/timing evidence. `UP-TO-DATE` results describe reused Gradle inputs, not fresh test execution. Changed source/configuration is handled by Gradle invalidation and receives a new binding. A failing command ends the session; correct its boundary before starting another. Interrupted runs retain bounded diagnostics. Completed session receipts are verified/archived locally before their own shared-ledger entry is retired.

For an explicit dependency-checksum bootstrap, set `config.write_verification_metadata` to `["sha256"]`. The controller passes Gradle's generation flag before startup, copies the resulting `gradle/verification-metadata.xml` into that operation's evidence directory with its SHA-256, and restores the checkout's original metadata bytes or original absence before the next operation. Metadata input and export are capped at 4 MiB. Review the exported XML and provenance before committing it. Normal validation omits this option and uses strict verification from the committed metadata. Generated checksums record the resolved bytes; independently establish publisher provenance for critical artifacts. See [Gradle dependency verification](https://docs.gradle.org/current/userguide/securing_dependencies.html).

All checkout/build/guest scratch is removed when its guard ends. There is no long-lived daemon or cross-session workspace store. On pause, finish the session and retain only named offline artifacts/evidence; the next session recreates missing state.

## Reuse artifacts and guest state

Use [prepared_apks.py](prepared_apks.py) for verified original/prepared APK identities and [UI review](UI-REVIEW.md) for capture-specific setup. For a prepared receipt retained by its registered consumer, set `CODEX_EMULATOR_PREPARED_RECEIPT` to its absolute path and `CODEX_EMULATOR_PREPARED_SHA256` to its verified digest. The runner checks the entire manifest binding, original/prepared hashes, payload preservation, ABI and test-signing compatibility before reuse. Keep the manifest unchanged while reusing that receipt; changed configuration needs fresh preparation. Keep the current candidate and required baseline until their final capture/acceptance consumer ends, then retire the binaries and preserve immutable evidence separately.

Use one existing `EmulatorSession` for compatible scenes/actions. Keep clean-wallet, recovery, network and identity boundaries explicit. Close guest/private ADB at session end or pause. The [two-worker runner](emulator-batch.md) remains available for independent required scenarios; shared state is not a reason to combine dependent or incompatible wallets.

The shared guard remains authoritative for job limits, ownership and teardown. App-managed tool services outside its process tree require their owning tool's supported release mechanism. Preserve and report unassigned services; a process name or timestamp alone does not establish ownership.
