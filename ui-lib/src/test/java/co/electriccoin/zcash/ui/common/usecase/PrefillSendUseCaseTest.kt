package co.electriccoin.zcash.ui.common.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrefillSendUseCaseTest {
    @Test
    fun anOlderSubmissionCanDiscardItsPrefillWithoutDrainingTheNextReview() = runTest {
        val prefill = PrefillSendUseCase()
        val abandoned = prefill.forNewSend(PrefillSendData.FromAddressScan("synthetic-old")) { true }
        val beforeSubmission = prefill.pendingThrough()
        val successor = PrefillSendData.FromAddressScan("synthetic-successor")
        val next = prefill.forNewSend(successor) { true }

        prefill.clearThrough(beforeSubmission)

        try {
            withTimeout(5_000) {
                assertNull(withTimeoutOrNull(50) { prefill.observe("old-entry", abandoned).first() })
                assertEquals(successor, prefill.observe("next-entry", next).first())
            }
        } finally {
            prefill.clear()
        }
    }

    @Test
    fun anAbandonedDraftDoesNotReachAnotherFormAndOnlyItsOwnerCanConsumeIt() = runTest {
        val prefill = PrefillSendUseCase()
        val draft = PrefillSendData.FromAddressScan("fixture-owned")
        val route = prefill.forNewSend(draft) { true }
        assertNull(withTimeoutOrNull(50) { prefill.observe("other-entry", null).first() })
        assertEquals(draft, withTimeout(500) { prefill.observe("owned-entry", route).first() })
        assertNull(withTimeoutOrNull(50) { prefill.observe("owned-entry", route).first() })
    }

    @Test
    fun aScanReplacesOnlyItsFormAndAccessIsRecheckedOnDelivery() = runTest {
        val prefill = PrefillSendUseCase()
        var permitted = true
        val stale = prefill.forNewSend(PrefillSendData.FromAddressScan("fixture-stale")) { permitted }
        permitted = false
        assertNull(withTimeoutOrNull(50) { prefill.observe("restored-entry", stale).first() })

        val initial = prefill.forNewSend(PrefillSendData.FromAddressScan("fixture-initial")) { true }
        val scan = PrefillSendData.FromAddressScan("fixture-new")
        prefill.forExistingSend("own-entry", scan) { true }
        assertNull(withTimeoutOrNull(50) { prefill.observe("other-entry", null).first() })
        assertEquals(scan, withTimeout(500) { prefill.observe("own-entry", initial).first() })
        assertNull(withTimeoutOrNull(50) { prefill.observe("own-entry", initial).first() })
    }
}
