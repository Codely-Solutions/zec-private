# Build integrity

The build pins dependency versions, verifies downloaded dependencies with [Gradle SHA-256 metadata](../gradle/verification-metadata.xml), and locks selected Gradle configurations. The [wrapper distribution](../gradle/wrapper/gradle-wrapper.properties) has its own SHA-256 pin. GitHub Actions use immutable commit references.

Use the [maintenance procedure](../.private/MAINTENANCE.md#dependency-update-policy) to review version, lock and checksum changes before running candidate acceptance. Dependency graph submission provides advisory coverage for resolved packages. Embedded native SDK dependencies require separate review.
