# Core acceptance coverage

Status: revision-14 candidate `d7471a007` passed real acceptance on 2026-11-08; signing is pending. Current exact-input evidence is in core-acceptance.json and core-validation-summary.json. Historical releases retain their own immutable evidence.

| Production failure | Evidence producer | Trigger and cost control |
|---|---|---|
| Quote dismissal poisons the next request; stale callbacks overwrite state | Existing deterministic cancellation tests and real unfunded production quote UI receipt | Reuse only while the relevant production source/build inputs match; short Tor session when invalidated. |
| Wallet cannot receive or spend; wrong recipient, amount, memo or fee | Installed testnet app graph, real faucet payment, app proposal creation/submission and sender/recipient history | One small transfer reused by the other core assertions; no exhaustive asset matrix. |
| Installed update loses wallet or transaction data | Pinned revision 13 testnet build updated in place to candidate; seed identity, funded balance and account behavior checked | Same isolated guest and funding fixture; dedicated disposable test certificate, separate permanent release checks. |
| Restart loses transaction state or recovery fails | Process restart followed by clean target-data erase and seed/birthday restoration into recipient wallet | Reuse the same test transaction; restoration cannot reuse the populated target database. |
| Tor interruption causes direct fallback or unrecoverable state | App-native Tor request during a controlled app-UID outage; dual-stack egress enforcement and rejection counters | One interruption before broadcast, with bounded recovery; retain deterministic failure/race tests for diagnosis. |
| Unvalidated or wrong candidate enters signing | Trusted controller independently checks current relevant-input fingerprint, receipt identity and artifact digests | Fast early status check prevents unnecessary release preparation; unsigned artifact promotion remains separate from secrets. |
| Upstream removes coverage or changes core behavior unnoticed | Maintained controller/patch, static gates and actual disposable integration tests | Discovery remains enabled; changed relevant inputs invalidate reusable acceptance and block publication pending validation. |

Testnet instrumentation uses real installed app dependencies and production wallet/proposal repositories. It does not exercise biometric UI approval or prove production NEAR settlement. Production quotes remain unfunded. Testnet/debug and production ARM64 release builds differ in network configuration, signing and optimization; report those boundaries with results.

The current core fingerprint conservatively includes retained runtime/build inputs and the core harness/policy. Version labels, documentation, images, theme-only code and unrelated test-source changes do not invalidate wallet transaction acceptance. Unknown retained code/configuration does. A receipt also binds the exact older build revision and executed APK; evidence reuse never relabels an older binary as the current binary.

The executed-build fingerprint and host-harness binding are separate. A host driver correction can rerun acceptance against the same immutable APK when its relevant compiled inputs still match. Changing instrumented Kotlin or its Gradle setup requires new instrumentation. An explicit core-runtime fingerprint excludes only the one private instrumentation Kotlin file; full acceptance and compiled-build fingerprints still bind it. This permits a new test APK against unchanged production inputs. Changing its Gradle setup still requires a new production candidate. The host guard independently binds the actual launcher, preparation helpers, SDK tools and private manifest used for that execution.

Candidate builds may finish while real acceptance is pending. Signing can attach a later controller-owned receipt only when the intervening Git diff contains the explicit receipt/document allowlist. Runtime source, build files, workflow and controller changes require a new candidate. The sole additional late-source exception is the explicitly bound private instrumentation Kotlin file, validated against matching runtime inputs and its new compiled APK. Both promotion and signing check that boundary; the signed release records the original build revision and the attesting controller revision. This permits overlap of required acceptance and release preparation without another APK build or access to signing secrets from build work.

Funding currently uses the Jino Labs TAZ faucet's documented proof-of-work flow, bounded to 30 seconds and difficulty 22. Its IP-bound challenge and claim share one TLS connection through a task-owned Tor SOCKS client. The app retains its separate native Tor path. The latest official stable Windows expert bundle is pinned to 16.0 (Tor 0.4.9.15); the newer 0.4.9.16 release was source-only on the official platform page at selection. No persistent installation or PATH change is needed.

The initially evaluated Valar faucet returned `failed: the wallet cannot pay this address` for the generated shielded address. No payment is automatically retried after an unknown result. Confirmation/spendability uses the wallet's normal policy; recipient recovery requires a mined transaction and records its actual app state, without waiting again for spendability that this recipient does not use.

Relevant ordinary test changes select deterministic regression checks and upstream review. They do not automatically require a fresh live transaction when the app and core acceptance producer remain unchanged. The private live producer itself is included in its receipt fingerprint.

No periodic job is enabled initially. Measure the first real run, including build/setup, Tor bootstrap, faucet wait, scanning, confirmation and active assertion time. Add a bounded drift schedule only after its reliability and cost support it. Public-chain or provider failures remain unverified acceptance, with a classified cause; they are not converted into passing app results.

Automated ARM hardware testing is outside scope. The user will exercise production on their phone. Keep that evidence separate from emulator execution and binary inspection.

The transaction producer launches the real app Activity and requires its foreground state. A headless process correctly stops Slipstream synchronization, so process initialization alone does not establish a usable wallet. Before any faucet claim, the current sender synchronizer must either catch up or demonstrate advancing scanned height against a non-null network tip within 120 seconds. Fresh-wallet catch-up then overlaps the faucet confirmation wait; the funding stage still requires the actual received transaction and normal spendability. Diagnostics retain status and heights, never seeds.

The emulator firewall allows current Running Tor relays plus the SDK 6.0.0 embedded Tor 0.43.0 bootstrap IP/port pairs. The latter are extracted from the published crate and checked against the SDK lockfile checksum; changing SDK version requires reviewing this inventory. The public testnet bulk endpoint on TCP443 and its normal DNS are permitted. Direct provider destinations and other app egress remain blocked. Sensitive metadata is checked separately while Tor is interrupted and direct bulk TLS remains reachable. Historical failed diagnostic runs are not passing acceptance.

Transport scope follows the user clarification of 2026-11-06. The earlier blanket DNS/egress denial blocked upstream’s intentional direct bulk path. The native SDK replacement and baseline Tor bridge were removed; production keeps the published SDK. `clearnet_fallback=false` describes sensitive requests, while `direct_bulk_downloads=true` explicitly records the permitted path. Historical failed runs and discarded native builds are diagnostic evidence only.

Use the core receipt's `network_enforcement` and `direct_bulk_downloads` fields for transport claims. The auxiliary emulator setup receipt retains a legacy `route: native SDK Tor only` selector label; it identifies the enabled SDK privacy transport and does not establish full-tunnel routing. Public bulk downloads and normal DNS remain permitted, as the actual firewall and core receipt record.

The runtime negative probe covers SDK endpoint validation (`getServerInfo` and `getLatestBlockHeight`) while the direct bulk TLS endpoint stays reachable. The firewall cannot classify encrypted RPC methods sharing that endpoint. Other sensitive transaction routing is inspected in the pinned SDK source and exercised with Tor enabled; no outage is injected during an ambiguous broadcast. Receipts state these separate evidence scopes.

Sender accounting follows the SDK's `abs(accountBalanceDelta)`: an external send debits the payment plus its fee. The sender assertion checks that total debit, fee and final balance. A clean recipient restoration independently checks the payment amount, memo and mined transaction identity. Submission is invoked once; a resubmittable SDK gRPC result can remain under observation, but only actual confirmed history satisfies acceptance. No manual rebroadcast is used.

Representative unit cases cover 0/6/8/18 decimal atomic units across all three quote modes, and exact recipient/refund pass-through for BTC, EVM, Solana and XRP address families in both directions. These are request-boundary tests, not execution of every asset or validation of every chain's address grammar. The current quote DTO has no separate destination memo/tag field; the live Zcash transfer covers the supported wallet memo path.

The bounded sandbox review is recorded in `core-provider-review.json`. Tidepool advertises a 1Click-compatible testnet service, but its detailed documentation described the TAZ bridge as disabled, and the published OpenAPI did not establish a shielded Zcash route. Tor compatibility and actual BTC/TAZ settlement were not verified. Adoption is deferred; this prerequisite does not claim testnet cross-chain settlement or a live provider refund.

Production Detekt retains its rules and existing baseline. Its release-only init excludes the procedural private instrumentation source, which is outside the shipped source sets and separately compiled by the core producer. That harness is checked through exact source/APK bindings, review and real execution. This does not exempt production Kotlin or the ordinary unit tests from analysis.

## Phase 2 revision 8 evidence

The current receipts supersede revision 7 evidence: testnet producer 42191766043, candidate 42207306770. Real transfer 3a660d0a3c8ef75f9301efd26fa29ed6b42794726703d7106d6e3badc0ce6765 mined at 4588498. Installed v6-to-v8 update, restart, recovery and scoped Tor outage passed. Three fresh unfunded production quote/dismiss cycles passed on the candidate x86_64 build. Production ARM execution and physical hardware execution remain outside this evidence. See core-validation-summary.json for costs and limitations.

## Foundation validation

The engine-independent JVM module compiles and tests exact precision/network identity, explicit fee knowledge, incompatible deposit capabilities, stale/cross-owner request rejection and an alternative memo-bearing composition with a 12-decimal asset and opaque recovery ID. This is structural reuse, not Monero or THORChain settlement evidence.

Current-product regressions cover view-only rejection before quote interpretation, cancellation navigation, legacy V2/V3 metadata migration/round trips, unknown provider rejection before network access, Zcash fee calculations and expiry immediately before software/Ledger submission. Keystone's equivalent final gate is inspected; physical hardware signing is outside automated acceptance.

The signing controller validates the source/digest-bound resolved foundation graph and rejects unexpected engine/product dependencies. Full candidate deterministic checks precede fresh real release acceptance. Superseded Phase 3 sessions remain separately identified and are not relabeled as final-candidate evidence.

## Workflow reliability revision 10

The native scenario uses the actual prefilled send form, Review, review cancellation and one final Send approval. It handles the conditional Orchard warning when the SDK proposal requires it; warning display is not separately attested. It checks rendered values and both amount parsers before faucet funding. Cancellation leaves submission state and balance unchanged; repeating the form produces a fresh proposal. One approved transfer supplies mined history, accounting, restart and clean seed recovery evidence. The unsecured isolated guest exercises the app's normal unsecured approval policy; it does not execute an OS biometric prompt.

The saved-reference refund polling test uses virtual time to establish resumption and terminal-stop behavior. It does not claim actual process death or production refund settlement. Existing serialization and provider-routing tests cover the adjacent boundaries. The two provider transport negative tests exercise tokens, quote, status and deposit submission with a direct-engine trap.

Fingerprint mutation tests reject stale receipts, unknown profiles, relevant source/host/workflow/build changes, deletion and file-mode changes. Committed-tree cases also prove fresh host-only acceptance can reuse matching compiled inputs while rejecting the old production candidate and any changed app, build, instrumentation or producer workflow. The controlled exception for one private instrumentation file preserves separate full-test, compiled-test and runtime fingerprints. Production quote inputs retain conservative selection. No overlapping live asset matrix or broadcast-outage retry scenario was added.

## Swap accuracy and clarity

Synthetic regressions cover raw-only execution, missing execution, fee omission, received-amount search, exact minimum/top-up amounts and computed token inputs at origin precision. Six normal/large-text quote layouts were visually reviewed. Live isolated Tor acceptance checks the included platform fee and minimum-output text. These checks do not establish funded production settlement or decrypt personal shielded notes. Isolated test seeds exist in the disposable guest and briefly in guarded-driver memory during recovery orchestration; no seeds are retained or published.

## Donation revision 13 acceptance

Production source `d12dea751a4dbab29671acab394777a7c613774c` passed 1,724 tests. The executed emulator/testnet build source was `686a9f10b80fd44b9467c092ace4cdcd80767b1a`; exact compiled-input fingerprints match because the later diff changes ordinary test fixtures only. Production Gradle took 892 seconds in one invocation; revision 12 took 905 seconds, with cache effects preventing a causal speed comparison.

The existing real transfer is now a shielded donation through Support development and the normal review/authentication path. Cancellation leaves no submission, then one explicit approval creates the observed transfer. The same transaction verifies sender accounting, installed-update preservation, restart, clean recipient recovery and empty identifying memo. Saved suggestion and cooldown persistence are checked after the real restart. The pinned SDK's two-payment proposal is constructed without signing; unsupported counts are then rejected by the wallet. Legacy incomplete review is established from source plus SDK acceptance; no hidden-output transaction is signed.

Three unfunded production quote/dismiss cycles and the zero-balance Support UI run separately over enabled Tor. Provider-success invitation gating is inspected and covered by retained status behavior; this work does not establish funded production provider settlement or donation conversion effectiveness. Production ARM64 is inspected, while x86_64 emulator binaries execute. No personal wallet, accepted swap or real funds are used.

Signed revision 13 preserves the validated unsigned payload, permanent certificate and package. No rebuild occurred during promotion. Exact seedless revision 13 fixture provenance is in the maintenance index; it retains the executed build source, distinct from the production source.
