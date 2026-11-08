# Last published release — revision 15

Candidate `b286524d2d76c8c9a8d83454886a0cc993d935ee`, run `42439820603`, passed 1,749 unit tests, lint, Detekt, live Tor contracts and disposable upstream integration. [Revision 15](https://github.com/Sammy-Lastre/zec-private/releases/tag/v3.19.0-private.15) is signed and independently verified. Its payload matches the validated artifact; package, certificate and ARM64-only packaging are preserved.

Recovery copy preserves reveal/authentication protections, marks clipboard contents sensitive and conditionally expires its own entry. Donations reuse send amount entry and allow an exact reviewed optional message. Automatic spending remains absent.

Real isolated testnet acceptance covered revision 14 installed update, receive/confirmation, Tor interruption/recovery, manual donation review/cancel/one approval, restart and clean recipient restoration with the same message. Three unfunded Tor BTC→ZEC quote/dismiss cycles passed. Production is ARM64-only; executed guests are API 35/x86_64.

See [validation](core-validation-summary.json) and [coverage](core-coverage.md), including retained dependency-security limits. Physical ARM/biometric execution and funded production settlement remain untested. Clipboard cleanup cannot be guaranteed after process death.
