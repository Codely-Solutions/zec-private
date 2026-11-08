# Revision 16 accepted for isolated signing

Candidate `a6ea7cf81986f6f74db1847a513dd14b0bb4d649`, run `42473584723`, passed 1,749 reported unit tests, lint, Detekt, live Tor contracts and disposable upstream integration. Three focused Material role tests also passed. Revision 15 remains published until the existing candidate is signed and verified.

Existing screens now use shared Material 3 roles, system colors and OLED black. Shared error hints use the error role; ordinary hints and entered text retain their own roles. Real rendered light/dark/OLED and enlarged-text scrolling checks passed.

Real isolated testnet acceptance covered revision 15 installed update, receive/confirmation, Tor interruption/recovery, manual donation review/cancel/one approval, restart and clean recipient restoration with the same message. Three unfunded Tor BTC→ZEC quote/dismiss cycles passed. Production is ARM64-only; executed guests are API 35/x86_64.

See [validation](core-validation-summary.json) and [coverage](core-coverage.md), including retained dependency-security limits. Physical ARM/biometric execution and funded production settlement remain untested. Clipboard cleanup cannot be guaranteed after process death.
