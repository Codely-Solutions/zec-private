# Last published release — revision 21

[ZEC Private — Release 21](https://github.com/Codely-Solutions/zec-private/releases/tag/v3.19.0-private.21) updates Gradle to 9.9.1 for upstream security fixes. The [independent verification](https://github.com/Codely-Solutions/zec-private/actions/runs/43017145106) checked the permanent certificate, package/version 3234021, ARM64-only packaging, 16 KiB ZIP alignment, unsigned payload and public updater discovery. The signing job promoted the original APK from run `43010608684`. The recovery run recorded it on protected main without rebuilding or signing again.

The guarded tests confirmed testnet funding and send, upgrade from revision 20, controlled Tor/updater interruption and recovery, restart, and seed restore. Three unfunded Tor quote cycles passed, including same-form recipient correction and market comparisons. The verified paired revision 21 fixture is selected for the next upgrade. Revision 20 remains the rollback fixture.

[Publication evidence](release-evidence/v3.19.0-private.21.json) records the executed checks and artifact identities. Production ARM64 was inspected. Physical hardware, biometric UI and funded production settlement remain outside this acceptance. Upstream discovery is active. The standing signing flag remains unset while hosted fixture access and unattended admission are open.
