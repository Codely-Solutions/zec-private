# Security reports

Report vulnerabilities through [GitHub private vulnerability reporting](https://github.com/Sammy-Lastre/zec-private/security/advisories/new). Include the affected app version, impact and minimal reproduction steps. Keep recovery phrases, spending/viewing keys and personal wallet data out of reports.

Use the latest stable ZEC Private release. Ordinary bugs belong in [Issues](https://github.com/Sammy-Lastre/zec-private/issues/new/choose).

## Known certificate limitation

Current releases lack OkHttp's application-level blocklist for [CVE-2026-111943](https://www.cve.org/CVERecord?id=CVE-2026-111943). On systems that still trust the compromised eMudhra intermediates, direct-mode HTTPS requests can be impersonated. Keep Android and Google Play security updates current and restart the device; Play Services distributed a mitigation, but this does not establish protection on every AOSP/no-Play device. See [Google's notice](https://blog.google/security/protecting-chrome-and-android-users-from-compromised-emudhra-intermediate-ca-certificates/) and the [compatible-fix status](../.private/MAINTENANCE.md#dependency-update-policy).
