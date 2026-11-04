# Validation status

Implementation is incomplete until release build, privacy regressions, signature verification and automation acceptance all pass. No APK is certified by this status file.

Confirmed: stable baseline tag/commit/date, dedicated package identity, affiliate and JWT source changes, private GitHub repository, dedicated local signing identity, and configured GitHub signing/ntfy secrets.

Pending: Kotlin tests, lint, Detekt, APK build/signature/manifest/installability, live quote compatibility, update simulation, signed release publication and Android notification receipt.

Initial local build was interrupted by the resource guard's 64 KiB receipt serialization limit after supplying thousands of source files. Its Job processes exited and disposable output was removed. The next attempt uses a source hash manifest verified by the build driver, preserving complete transitive-input accounting with a compact guard receipt.

Upstream's stable tag references SDK 6.0.0-SNAPSHOT. Automatic publication must remain gated until the dependency can be reproduced with immutable artifact evidence or a compatible stable SDK.
