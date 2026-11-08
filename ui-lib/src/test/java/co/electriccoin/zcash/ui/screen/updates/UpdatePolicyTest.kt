package co.electriccoin.zcash.ui.screen.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatePolicyTest {
    @Test
    fun stableReleaseUsesFixedRepositoryLinks() {
        val release = UpdatePolicy.parse(metadata())
        assertEquals(42, release.versionCode)
        assertEquals("$REPOSITORY_URL/releases/tag/v2.5.0-private.16", release.releasePage)
        assertEquals("sha256:" + "a".repeat(64), release.digest)
    }

    @Test
    fun rejectsUntrustedReleaseIdentityAndIncompleteAssets() {
        listOf(
            metadata().replace("\"draft\":false", "\"draft\":true"),
            metadata().replace("\"prerelease\":false", "\"prerelease\":true"),
            metadata().replace("\"draft\":false", "\"draft\":\"false\""),
            metadata().replace("v2.5.0-private.16", "../latest"),
            metadata().replace("https://github.com/", "https://attacker.example/"),
            metadata().replace(APK_NAME, "wallet.apk"),
            metadata().replace("\"size\":1024", "\"size\":0"),
            metadata().replace("\"size\":1024", "\"size\":157286401"),
            metadata().replace("sha256:", "sha512:"),
            metadata().replace("Android versionCode: 42", "Android versionCode: 0"),
            metadata().replace("Android versionCode: 42", "Android versionCode: 2147483648"),
            metadata().replace("Android versionCode: 42", "Android versionCode: 42\\nAndroid versionCode: 43"),
            metadata().replace("Android versionCode: 42", "Android versionCode: 42 extra")
        ).forEach { invalid -> assertFailsWith<IllegalArgumentException> { UpdatePolicy.parse(invalid) } }
    }

    @Test
    fun digestIsOptionalButMalformedDigestIsRejected() {
        assertNull(UpdatePolicy.parse(metadata().replace("\"sha256:${"a".repeat(64)}\"", "null")).digest)
        assertFailsWith<IllegalArgumentException> {
            UpdatePolicy.parse(metadata().replace("sha256:${"a".repeat(64)}", "sha256:abc"))
        }
    }

    @Test
    fun automaticCooldownSurvivesFailuresAndManualBypassesIt() {
        assertTrue(UpdatePolicy.shouldCheck(100, null, false))
        assertFalse(UpdatePolicy.shouldCheck(101, 100, false))
        assertFalse(UpdatePolicy.shouldCheck(100 + UPDATE_INTERVAL_MILLIS - 1, 100, false))
        assertTrue(UpdatePolicy.shouldCheck(100 + UPDATE_INTERVAL_MILLIS, 100, false))
        assertTrue(UpdatePolicy.shouldCheck(101, 100, true))
    }

    @Test
    fun clockRollbackAllowsOneCheckAndThenUsesTheResetTimestamp() {
        assertTrue(UpdatePolicy.shouldCheck(99, 100, false))
        assertFalse(UpdatePolicy.shouldCheck(100, 99, false))
    }

    @Test
    fun dismissalOnlyHidesThatVersionAndInstalledUpdatesRetireCachedNotice() {
        val release = UpdatePolicy.parse(metadata())
        assertEquals(release, UpdatePolicy.notice(release, 41, 0))
        assertNull(UpdatePolicy.notice(release, 41, 42))
        assertEquals(release, UpdatePolicy.notice(release, 41, 42, manual = true))
        assertEquals(release, UpdatePolicy.notice(release, 41, 40))
        assertNull(UpdatePolicy.notice(release, 42, 0))
        assertNull(UpdatePolicy.notice(release, 42, 42, manual = true))
        assertNull(UpdatePolicy.notice(release, 43, 0))
    }

    @Test
    fun dismissalChangesAvailabilityStateWithoutClaimingInstalledWalletIsCurrent() {
        val release = UpdatePolicy.parse(metadata())
        val visible = UpdatePolicy.availability(release, 41, 0)
        val dismissed = UpdatePolicy.availability(release, 41, 42)
        assertEquals(release, visible?.release)
        assertEquals(false, visible?.dismissed)
        assertEquals(release, dismissed?.release)
        assertEquals(true, dismissed?.dismissed)
        assertFalse(visible == dismissed)
        assertNull(UpdatePolicy.availability(release, 42, 42))
        assertNull(UpdatePolicy.availability(release, 43, 42))
    }

    private fun metadata(): String =
        """
        {"draft":false,"prerelease":false,"tag_name":"v2.5.0-private.16","body":"Android versionCode: 42",
        "assets":[{"name":"$APK_NAME","size":1024,"digest":"sha256:${"a".repeat(64)}",
        "browser_download_url":"$REPOSITORY_URL/releases/download/v2.5.0-private.16/$APK_NAME"}]}
        """.trimIndent()
}
