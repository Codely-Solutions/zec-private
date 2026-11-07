# Revision 12 — toolchain and release maintenance validated

Candidate 42355250478 at `4e4bff9cf1b52d9322000ef181a7c8be5577a802` uses Gradle 8.14.6. All 1705 required tests, release analysis, packaging and actual disposable upstream integration passed. One real isolated testnet lifecycle took 715.9 seconds and reused a transfer for installed-update preservation, restart and recovery. Three unfunded production quote/dismiss cycles ran over Tor. Revision 11's exact seedless baseline came from authenticated maintenance storage with source and digest verification.

Wallet identity, permanent certificate, ARM64-only production and x86_64 emulator support remain. The validated production artifact is promoted without rebuilding. Production ARM64 execution, biometric approval and funded production swap settlement are not established by emulator acceptance.

README and release notes are concise. Wallet releases expose the APK and checksum; GitHub also generates source archives. Detailed validation remains in `.private/release-evidence`, while seedless test fixtures are in an authenticated maintenance draft. Weekly patch/minor proposals exclude majors and preserve review, exact-source acceptance and signing isolation. Upstream discovery remains enabled. Donations and Phase 4 remain outside this release.
