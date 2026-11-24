package co.electriccoin.zcash.ui.common.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MigrationNotificationIdentityTest {
    @Test
    fun fullAccountActionDistinguishesLegacyHashCollision() {
        val first = "00000000000040008000000000000000"
        val second = "00000000000040008000000000002662"
        assertEquals(first.hashCode() and 0xFFFF, second.hashCode() and 0xFFFF)

        val progress = MigrationNotifier.EXTRA_OPEN_MIGRATION
        val runStep = MigrationNotifier.EXTRA_RUN_STEP
        assertNotEquals(migrationIntentAction(progress, first), migrationIntentAction(progress, second))
        assertNotEquals(migrationIntentAction(runStep, first), migrationIntentAction(progress, first))
        assertEquals("$runStep.$first", migrationIntentAction(runStep, first))
    }
}
