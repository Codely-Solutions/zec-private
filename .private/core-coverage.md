# Core acceptance coverage

Status: implementation in progress. A test producer or successful build is not a passing transaction receipt.

| Production failure | Evidence producer | Trigger and cost control |
|---|---|---|
| Quote dismissal poisons the next request; stale callbacks overwrite state | Existing deterministic cancellation tests and real unfunded production quote UI receipt | Reuse only while the relevant production source/build inputs match; short Tor session when invalidated. |
| Wallet cannot receive or spend; wrong recipient, amount, memo or fee | Installed testnet app graph, real faucet payment, app proposal creation/submission and sender/recipient history | One small transfer reused by the other core assertions; no exhaustive asset matrix. |
| Installed update loses wallet or transaction data | Prior revision 6 testnet build updated in place to candidate; seed identity, funded balance and account behavior checked | Same isolated guest and funding fixture; dedicated disposable test certificate, separate permanent release checks. |
| Restart loses transaction state or recovery fails | Process restart followed by clean target-data erase and seed/birthday restoration into recipient wallet | Reuse the same test transaction; restoration cannot reuse the populated target database. |
| Tor interruption causes direct fallback or unrecoverable state | App-native Tor request during a controlled app-UID outage; dual-stack egress enforcement and rejection counters | One interruption before broadcast, with bounded recovery; retain deterministic failure/race tests for diagnosis. |
| Unvalidated or wrong candidate enters signing | Trusted controller independently checks current relevant-input fingerprint, receipt identity and artifact digests | Fast early status check prevents unnecessary release preparation; unsigned artifact promotion remains separate from secrets. |
| Upstream removes coverage or changes core behavior unnoticed | Maintained controller/patch, static gates and actual disposable integration tests | Discovery remains enabled; changed relevant inputs invalidate reusable acceptance and block publication pending validation. |

Testnet instrumentation uses real installed app dependencies and production wallet/proposal repositories. It does not exercise biometric UI approval or prove production NEAR settlement. Production quotes remain unfunded. Testnet/debug and production ARM64 release builds differ in network configuration, signing and optimization; report those boundaries with results.

The current core fingerprint conservatively includes retained runtime/build inputs and the core harness/policy. Version labels, documentation, images, theme-only code and unrelated test-source changes do not invalidate wallet transaction acceptance. Unknown retained code/configuration does. A receipt also binds the exact older build revision and executed APK; evidence reuse never relabels an older binary as the current binary.

The executed-build fingerprint and host-harness binding are separate. A host driver correction can rerun acceptance against the same immutable APK when its relevant compiled inputs still match. Changing instrumented Kotlin or its Gradle setup requires new instrumentation. The host guard independently binds the actual launcher, preparation helpers, SDK tools and private manifest used for that execution.

No periodic job is enabled initially. Measure the first real run, including build/setup, Tor bootstrap, faucet wait, scanning, confirmation and active assertion time. Add a bounded drift schedule only after its reliability and cost support it. Public-chain or provider failures remain unverified acceptance, with a classified cause; they are not converted into passing app results.

Automated ARM hardware testing is outside scope. The user will exercise production on their phone. Keep that evidence separate from emulator execution and binary inspection.
