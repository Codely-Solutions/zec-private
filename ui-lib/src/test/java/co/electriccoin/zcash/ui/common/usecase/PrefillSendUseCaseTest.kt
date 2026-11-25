package co.electriccoin.zcash.ui.common.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrefillSendUseCaseTest {
    @Test
    fun anOlderSubmissionCanDiscardItsPrefillWithoutDrainingTheNextReview() = runBlocking {
        val prefill = PrefillSendUseCase()
        val abandoned = prefill.request(PrefillSendData.FromAddressScan("synthetic-old"))
        val beforeSubmission = prefill.pendingThrough()
        val successor = PrefillSendData.FromAddressScan("synthetic-successor")
        val sender = prefill.request(successor)

        prefill.clearThrough(beforeSubmission)

        try {
            withTimeout(5_000) {
                abandoned.join()
                assertTrue(abandoned.isCancelled)
                assertEquals(successor, prefill().first())
                sender.join()
            }
        } finally {
            prefill.clear()
        }
    }
}
