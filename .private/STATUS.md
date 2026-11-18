# Last published release — revision 27

[ZEC Private — Release 27](https://github.com/Codely-Solutions/zec-private/releases/latest) fixes the Home fiat balance refresh. The [public verifier](https://github.com/Codely-Solutions/zec-private/actions/runs/44319065749) confirmed updater discovery, SHA-256, the pinned signing certificate, version code 3234030, ARM64-only packaging, 16 KiB alignment and payload equality with the original unsigned APK after the Git history rewrite.

The [original acceptance run](https://github.com/Codely-Solutions/zec-private/actions/runs/43888077543) passed an installed testnet upgrade, shielded send, restart and seed restoration. It also passed repeated unfunded quote and dismissal cycles, same-form recipient correction and independent price verification. Wallet execution used an x86_64 Android guest. Production ARM64 was inspected.

The [release evidence](release-evidence/v3.19.0-private.27.json) records the original build commit and accepted APKs. The [authorship migration record](published-history-migration.json) binds the moved tag to the identical source tree. The [next hosted upgrade pair](hosted-fixture.json) retains its original artifact and build identities.
