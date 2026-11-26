package co.electriccoin.zcash.ui.common.model.migration.sim

import cash.z.ecc.android.sdk.NetworkPrivacyOptions
import cash.z.ecc.android.sdk.TransferAttemptOutcome
import cash.z.ecc.android.sdk.TransferResult
import co.electriccoin.zcash.work.BroadcastPreflight
import co.electriccoin.zcash.work.decideBroadcastPreflight
import co.electriccoin.zcash.work.nextDueUnsentIsPreparation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Simulates proving a preparation layer and its dependent crossing. Each submission waits for
 * a separate quiet window because the pinned SDK can select its next candidate at entry.
 *
 * Layout: two layer-0 preps (one layer = no one-at-a-time rule) funding one transfer.
 */
class MigrationPrepFastTrackScenarioTest {
    private companion object {
        const val START_TIP: Long = 1_000L
        const val PREP_A: Long = 0L
        const val PREP_B: Long = 1L
        const val TRANSFER: Long = 8L
        const val TRANSFER_BOUNDARY: Long = 1_036L
        const val PRIVACY_BUFFER_SECONDS: Long = 180L
    }

    private fun seededDriver(): MigrationSimDriver {
        val driver = MigrationSimDriver()
        driver.seedPlan(
            preparations =
                listOf(
                    MigrationSimDriver.SimPrep(id = PREP_A, layer = 0, scheduledHeight = START_TIP + 3),
                    MigrationSimDriver.SimPrep(id = PREP_B, layer = 0, index = 1, scheduledHeight = START_TIP + 3),
                ),
            transfers =
                listOf(
                    MigrationSimDriver.SimTransfer(
                        id = TRANSFER,
                        scheduledHeight = TRANSFER_BOUNDARY + 10,
                        anchorBoundary = TRANSFER_BOUNDARY,
                        dependsOn = listOf(PREP_A, PREP_B),
                    ),
                ),
            startTip = START_TIP,
        )
        return driver
    }

    private fun preflight(secondsSinceActivity: Long): BroadcastPreflight {
        val nowEpoch = 10_000L
        return decideBroadcastPreflight(
            synchronizerSyncing = false,
            nowEpochSeconds = nowEpoch,
            lastNetworkActivityEpochSeconds = nowEpoch - secondsSinceActivity,
            privacyBufferSeconds = PRIVACY_BUFFER_SECONDS,
        )
    }

    @Test
    fun `ready preparations and a dependent crossing each require a quiet window`() =
        runTest {
            val driver = seededDriver()
            val sdk = driver.sdk
            val opts = NetworkPrivacyOptions(useTor = false)

            // Chain reaches the prep layer's schedule; a sync run proves BOTH preps in one pass.
            driver.advanceTip(blocks = 5)
            assertEquals(2, sdk.finalizeReadyTransfers(), "both layer-0 preps prove together")

            assertEquals(BroadcastPreflight.DEFER, preflight(5))
            assertEquals(BroadcastPreflight.BROADCAST, preflight(PRIVACY_BUFFER_SECONDS))
            val first = sdk.executeNextPendingTransfer(opts, useEstimatedTip = true)
            assertTrue(first is TransferAttemptOutcome.Executed && first.result is TransferResult.Success)
            assertTrue(sdk.txById(PREP_A)!!.sent)

            assertTrue(
                nextDueUnsentIsPreparation(sdk.getMigrationTransferStates(), sdk.estimatedChainTip()),
                "wake selection recognizes a due preparation",
            )
            assertEquals(BroadcastPreflight.DEFER, preflight(5))
            assertEquals(BroadcastPreflight.BROADCAST, preflight(PRIVACY_BUFFER_SECONDS))
            val second = sdk.executeNextPendingTransfer(opts, useEstimatedTip = true)
            assertTrue(second is TransferAttemptOutcome.Executed && second.result is TransferResult.Success)
            assertTrue(sdk.txById(PREP_B)!!.sent)

            // ── Transfer: proof yes, broadcast NO while the ceremony is unmet ──
            driver.mine(id = PREP_A, height = START_TIP + 10)
            driver.mine(id = PREP_B, height = START_TIP + 12)
            driver.setTip(TRANSFER_BOUNDARY + 20) // boundary settled + transfer due
            assertEquals(1, sdk.finalizeReadyTransfers(), "the transfer PROVES as soon as its boundary settles")
            assertTrue(sdk.txById(TRANSFER)!!.proved)

            assertFalse(
                nextDueUnsentIsPreparation(sdk.getMigrationTransferStates(), sdk.estimatedChainTip()),
                "the next due pending transaction is a crossing — no fast-track",
            )
            assertEquals(
                BroadcastPreflight.DEFER,
                preflight(5),
                "a crossing must keep the full ceremony: proved and due, yet it WAITS out the quiet gap",
            )
            assertFalse(sdk.txById(TRANSFER)!!.sent, "no broadcast happened for the transfer")

            // ── Ceremony satisfied → the transfer finally broadcasts ───────────
            assertEquals(BroadcastPreflight.BROADCAST, preflight(PRIVACY_BUFFER_SECONDS))
            val third = sdk.executeNextPendingTransfer(opts, useEstimatedTip = true)
            assertTrue(third is TransferAttemptOutcome.Executed && third.result is TransferResult.Success)
            assertTrue(sdk.txById(TRANSFER)!!.sent)
        }
}
