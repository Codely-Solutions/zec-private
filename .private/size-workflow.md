# Size and release workflow cleanup

Production APKs contain only arm64-v8a. Android devices running a 32-bit OS can
no longer install updates. Package identity, signing certificate and wallet
storage paths are unchanged. Debug emulator builds contain x86_64 only.

Use `build-release.yml` with `validation_only=true` and `build_emulator=true`
when a candidate needs emulator acceptance. This runs the full release gates
and assembles each variant once in one finite Gradle invocation. Use the APKs
from that run for acceptance; do not dispatch a separate debug build.

After acceptance, dispatch the same workflow with `candidate_run=RUN_ID`.
It resolves a successful main-branch validation run, downloads its immutable
unsigned artifact and signs it without a new Gradle build. The source must still
equal the current controller revision. Intervening changes require review and
a new candidate. The signing job independently checks APK identity, digest,
architecture, license, contract and runtime evidence before using signing secrets.
Candidate build code never receives those secrets.

Normal upstream integrations retain discovery and the existing critical-change
gates. They run the consolidated release checks and then sign that run's artifact.
The maintained patch preserves ARM64 packaging; static and actual-APK checks
reject architecture drift. Phase 1's duplicate builder is retired.

Keep the existing bounded Gradle worker and heap limits. Reuse the managed Gradle
cache; avoid adding configuration-cache assumptions until compatibility is
measured. Record runner duration separately from cumulative job time, cache
effects and emulator acceptance. Full layout and Kotlin checks remain release
gates; packaging-only iterations use targeted controls first.

Revision 5 baseline: signed APK 111930635 bytes, including 43943000 bytes of
32-bit native libraries. Production run 42072171381 took approximately 22m11s;
its four Gradle steps totalled 19m23s. The earlier separate acceptance/debug
preparation runs are additional costs and must not be conflated with that
production-run baseline. ARM64 execution requires an ARM device; an x86_64
emulator validates debug wallet behavior, not production ARM native execution.

## Measured revision 6 result

Signed APK: 67930887 bytes versus 111930635 bytes in revision 5 (39.31% smaller).
Successful candidate: 31m01s unsigned job, including both variants; the consolidated build step took
29m15s (Gradle logged 29m13s) in one invocation. Earlier combined preparation took 46m22s, with a
separate duplicate debug build and later release preparation also run then.
Revision 5's production-only run was 22m11s. These scopes and cache states differ;
no controlled production-only speedup is claimed. Promotion used no Gradle build.
The failed candidate cost 948 cumulative job seconds before its
exception-provenance assertion was corrected. Unit suite durations total
176.106s cumulatively, versus 85.048s before; this is not wall time.
Compilation, static analysis, dexing and R8 remain the substantial build work.

Real fresh-wallet Tor acceptance passed three request/review/dismiss cycles.
An initial attempt received an explicit upstream 25000ms quote-exchange timeout.
The harness now records and bounds retries for that observed failure; the
successful run needed none. No application swap behavior changed.

The controller caller now explicitly grants actions:read for artifact download.
The obsolete Phase 1 builder no longer produces duplicate APKs. Diagnostic and
negative-mutation workflows remain available for their distinct checks.
