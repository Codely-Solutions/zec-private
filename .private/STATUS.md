# Last published release — revision 31

[ZEC Private — Release 31](https://github.com/Codely-Solutions/zec-private/releases/tag/v3.19.0-private.31) retries interrupted update checks and refreshes the inline QR from current data after fullscreen closes. The [independent verifier](https://github.com/Codely-Solutions/zec-private/actions/runs/45675591613/job/139443031852) confirmed public updater discovery, the APK checksum, the pinned signing certificate, Android version code 3234034, ARM64 packaging, ZIP alignment and byte equality with the accepted unsigned payload.

The [hosted release](https://github.com/Codely-Solutions/zec-private/actions/runs/45675591613) passed release checks, the isolated testnet upgrade from Release 30 through restart and seed restore, and three unfunded quote reviews and dismissals with independent price comparisons. The quote scenario corrected a recipient-network mismatch without leaving the form. Wallet and quote acceptance used the direct app route. The unsigned-build gate exercised the public NEAR contract over Tor.

The [release evidence](release-evidence/v3.19.0-private.31.json) binds the source and original signed and unsigned artifacts. Signing promoted the validated production APK. The [next hosted upgrade pair](hosted-fixture.json) preserves the independently verified Release 31 testnet APK and matching instrumentation.
