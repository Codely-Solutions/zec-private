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
