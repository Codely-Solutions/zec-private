# Security reports

Report vulnerabilities through [GitHub private vulnerability reporting](https://github.com/Sammy-Lastre/zec-private/security/advisories/new). Include the affected app version, impact and minimal reproduction steps. Keep recovery phrases, spending/viewing keys and personal wallet data out of reports.

Use the latest stable ZEC Private release. Ordinary bugs belong in [Issues](https://github.com/Sammy-Lastre/zec-private/issues/new/choose).

## Compromised certificate intermediates

Revision 16 and earlier lack OkHttp's application-level blocklist for [CVE-2026-111943](https://www.cve.org/CVERecord?id=CVE-2026-111943). Without effective platform protection, forged certificates from the compromised eMudhra intermediates can impersonate direct HTTPS servers. Keep Android and Google Play security updates current and restart the device; platform mitigation is not established on every AOSP/no-Play device. See [Google's notice](https://blog.google/security/protecting-chrome-and-android-users-from-compromised-emudhra-intermediate-ca-certificates/).

Revision 17 uses fixed upstream OkHttp 5.5.1 through Ktor 3.6.1. Its local TLS regression rejects an otherwise trusted compromised chain before sending an HTTP request. This mitigation applies to requests handled by OkHttp.

SDK-native Tor TLS has a separate trust path whose compromised-chain protection is not established by the OkHttp test. See the [maintenance policy](../.private/MAINTENANCE.md#dependency-update-policy) for its provenance and validation requirements.
