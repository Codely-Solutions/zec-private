package co.electriccoin.zcash.ui.common.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryClipboardLeaseTest {
    @Test
    fun clearsOnlyAfterSixtySeconds() {
        val lease = RecoveryClipboardLease()
        var clears = 0
        lease.start("fake-owner", 100)
        assertFalse(lease.expire(60_099, "fake-owner") { clears++ })
        assertTrue(lease.expire(60_100, "fake-owner") { clears++ })
        assertEquals(1, clears)
        assertNull(lease.expiresAt)
    }

    @Test
    fun sameTextReplacementWithDifferentOwnershipIsPreserved() {
        val lease = RecoveryClipboardLease()
        lease.start("original-fake-owner", 0)
        assertTrue(lease.expire(60_000, "external-owner") { error("must preserve replacement") })
        assertNull(lease.expiresAt)
    }

    @Test
    fun replacementWithoutMetadataIsPreserved() {
        val lease = RecoveryClipboardLease()
        lease.start("fake-owner", 0)
        assertTrue(lease.expire(60_000, "") { error("must preserve replacement") })
    }

    @Test
    fun deniedBackgroundReadDefersUntilForegroundAccessReturns() {
        val lease = RecoveryClipboardLease()
        var clears = 0
        lease.start("fake-owner", 0)
        assertFalse(lease.expire(60_000, null) { clears++ })
        assertTrue(lease.expire(90_000, "fake-owner") { clears++ })
        assertEquals(1, clears)
    }

    @Test
    fun secondCopyGetsItsOwnFullLease() {
        val lease = RecoveryClipboardLease()
        lease.start("first", 0)
        lease.start("second", 50_000)
        assertFalse(lease.expire(60_000, "second") { error("too early") })
        assertTrue(lease.expire(110_000, "second") {})
    }
    @Test
    fun deniedClearRetainsOwnershipForRetry() {
        val lease = RecoveryClipboardLease()
        lease.start("fake-owner", 0)
        assertFailsWith<SecurityException> {
            lease.expire(60_000, "fake-owner") { throw SecurityException("fake denial") }
        }
        assertEquals(60_000L, lease.expiresAt)
        assertTrue(lease.expire(90_000, "fake-owner") {})
    }
}
