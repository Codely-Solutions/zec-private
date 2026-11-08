# Inspect screens and capture screenshots

Use this procedure for rendered UI inspection. Select the smallest diagnostic path that reaches the required screen. Wallet transaction acceptance and production quote acceptance have separate gates in [core coverage](core-coverage.md).

## Existing capture path

| Component | Purpose |
|---|---|
| [run_tor_emulator.py](run_tor_emulator.py) | Verify/download an immutable APK artifact and choose the UI driver. |
| [emulator_session.py](emulator_session.py) | Own isolated guest state, private ADB/ports, deadlines and teardown. |
| [tor_ui_driver.py](tor_ui_driver.py) | Bounded interactive UI inspection, hierarchy snapshots and screenshots. |
| [tor_resident_ui_driver.py](tor_resident_ui_driver.py), `ui-bridge/` | Resident UiAutomation control used by quote acceptance. |
| [maintenance-assets.json](maintenance-assets.json) | Immutable maintained bridge/baseline identities. |

The launcher reads the explicit `CODEX_EMULATOR_MANIFEST` path; its default assumes an old root-level manifest. Prepared admission jobs must bind the current artifact, digest, source, package, configuration and output directory. Use the shared resource guard. Single-worker admission helpers still expect root-level job/policy JSON; inspect and adapt those assumptions before launching. [Batch admission](emulator-batch.md) accepts prepared independent worker specifications.

The basic driver writes `ui-state.json` and `ui.png`. Its `ui-command.json` protocol uses increasing `sequence` values and actions including `snapshot`, `screenshot`, `tap`, `text`, `back`, `swipe`, `assert_text` and `finish`. Read its implementation for payload fields and coordinate limits. Basic inspection can finish without running quote acceptance. The resident driver's `finish` requires its quote-cycle acceptance conditions; select the basic path for screenshot-only work unless resident control is actually needed. Never manufacture acceptance results to bypass that distinction.

[ui_step.py](ui_step.py) currently permits only the old `zec-private-tor-retry` and `zec-private-phase1` owners. It is not a generic command-line client for new task owners. Verify the current guard identity and protocol before adapting it.

## Prepare state, then capture

1. Identify the last successful relevant procedure and exact artifact evidence. Verify current assumptions before creating another host or capture tool. Historical evidence proves its original inputs only.
2. Prepare the target state separately from screenshot capture. Prefer the real app; use a debug-only host only when isolated state cannot reasonably be reached otherwise, and label synthetic states.
3. Verify one launch and one actual target screen before attempting a sequence. Check package/activity, visible text and hierarchy. On a crash, retain bounded private launch/logcat diagnostics and stop the sequence at that boundary.
4. Capture while the session is alive. `with EmulatorSession(...) as session` yields a session; use `adb = session.adb`. The capture primitive is `adb exec-out screencap -p` through that bound private ADB callable, or the driver's screenshot action. Verify the image depicts the intended screen rather than the launcher/crash dialog.
5. For light/dark coverage, set and verify the app/guest theme, wait for the screen/frame to settle, then capture. For a transient hint, inspect a bounded frame sequence: activity launch completion alone does not establish popup visibility. Capture the screen before slow diagnostic commands. Keep original screenshot bytes and bind them to the APK/source and inspected state. Redact secrets before sharing.
6. Close the guarded session, verify owned descendants exited, and retain only the candidate/evidence needed for review.

## Historical orchestration and experimental work

In the surrounding workspace, `records/INDEX.md` locates archived `donations_ui_adapter.py`, `donations_ui_scenario.py` and `donations_acceptance.py`. They contain prior artifact checks, theme switching, settled-frame capture and navigation. Recover selected files for inspection. Their automatic-donation assumptions and root-relative paths require review; they are historical examples, not current launch commands.

`.private/donation-review/include.gradle` adds an opt-in debug review activity with production components and synthetic activity state. Use the same post-splash window theme as the real app. Rendering has been exercised on an isolated emulator; current artifact identities and selected frames belong in the owning checkpoint. The isolated CI UI artifact opts in, so rendered review and normal-app quote acceptance can share one debug APK. Production and ordinary debug builds omit this host. The `recovery` scene uses visibly synthetic words; it establishes layout, not authentication or real key exposure. Screenshot inspection does not establish wallet submission, recovery or release acceptance.

## Experimental in-place styling comparison

On `design/material-foundation-preview`, opt in with `.private/style-comparison/include.gradle`. Set `STYLE_PREVIEW_PYTHON` to the verified Python interpreter. This init script generates build-local copies of shared style definitions and excludes their originals from that invocation; ordinary and release builds omit it. Never use this review overlay for release artifacts.

Launch `co.electriccoin.zcash.ui.screen.donation.StyleComparisonActivity` with `screen` (`home`, `send`, `donation`, `donation-message`, `home-warning`), `dark` and `standard` extras. Both variants use the same actual production screen components and synthetic state. The proposed style maps existing tokens to Material roles, changes font families and removes selected button decoration; it retains existing type sizes and input behavior. No wallet/network/payment dependencies are attached. These captures establish layout only, not payment or complete state coverage. Keep exact execution source, APK identity and capture configuration in the owning checkpoint.

The earlier replacement-screen prototype was retired: it changed information hierarchy and omitted controls, so it was unsuitable for evaluating a styling-only change. Its source remains in Git history at `19269f4687ec367f6ae54bbca0bccce0a61e9a8c`.
