# Validation status

Revision 1 (`3234001`) built and published successfully, but the user found a quote-sheet runtime crash on Android 17. It is affected and must not be described as working. The literal `0.25% platform` in a formatted Android string caused `UnknownFormatConversionException`. The first release is immutable and prominently marked with this issue.

Revision 2 (`3234002`) escapes the literal percent as `%%`, adds three Compose quote-row rendering tests across swap modes, and adds a static regression gate. Its build, signing, publication and ntfy submission passed: https://github.com/Sammy-Lastre/zec-private/actions/runs/41932146968. All 1,386 tests passed with zero failures, errors or skips, including the three Compose quote-row regressions. Local guarded verification accepted the signature, manifest, checksum, license, provider authority and 16 KiB ZIP alignment. SHA-256: b3e1cb0baa4442191fc998ec0dfb876bb571306f9693974ef38ea52347e6522e. Full synthetic upgrade acceptance and corrected-device retest remain pending.

The initial release passed 1,383 unit tests, lint, Detekt, live unfunded API contracts, signature and manifest checks. These checks missed actual quote text rendering. The new regression tests address that coverage gap. No funded transaction or wallet secret has been used.

The prior full synthetic upgrade was canceled after the device crash report and cleaned its disposable artifacts. It is being rerun on the corrected revision at https://github.com/Sammy-Lastre/zec-private/actions/runs/41936825170, including an explicit negative-control reproduction of the original resource crash. Real stable polling after the first release passed without creating a duplicate release: https://github.com/Sammy-Lastre/zec-private/actions/runs/41931770657.

Signing secrets were explicitly authorized and uploaded. The dedicated local key, password, backup and ntfy subscription details are outside Git under `D:\Data\Crypto\zec-private\signing`. Phone delivery and an independent offline key backup still require the user's setup.

The stable baseline is 3.19.0-3234, a57ea1455840a0d97eb0b2a3d2557480dfe5bda7, published 2026-10-29T16:12:07Z. SDK 6.0.0 is pinned from Maven Central and the official source tag, replacing upstream's mutable 6.0.0-SNAPSHOT. Source compilation and the initial test suite passed against that stable SDK.

Local Gradle attempts failed the shared resource guard's receipt-size/inventory handling before a compiler result. Their Job processes exited and disposable caches were removed. GitHub-hosted release validation succeeded. The initial APK passed an independent guarded Windows signature, manifest, checksum and ZIP-alignment check, but its later device crash supersedes any claim of runtime acceptance.
