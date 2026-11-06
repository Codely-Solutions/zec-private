package co.electriccoin.zcash.wallet.foundation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestGenerationTest {
    @Test
    fun replacingRequestRejectsLateCompletion() {
        val requests = RequestGeneration()
        val first = requests.advance()
        val second = requests.advance()
        var displayed: String? = null
        fun complete(request: RequestId, value: String) {
            if (requests.isCurrent(request)) displayed = value
        }

        complete(second, "new quote")
        complete(first, "late quote")

        assertEquals("new quote", displayed)
        assertFalse(requests.isCurrent(first))
        assertTrue(requests.isCurrent(second))
    }

    @Test
    fun clearingInvalidatesTheOutstandingRequestWithoutNeedingItsCancellation() {
        val requests = RequestGeneration()
        val pending = requests.advance()
        requests.advance()

        assertFalse(requests.isCurrent(pending))
        val replacement = requests.advance()
        assertTrue(requests.isCurrent(replacement))
        assertFalse(requests.isCurrent(pending))
    }

    @Test
    fun anotherOwnerCannotPublishEvenWithTheSameGeneration() {
        val first = RequestGeneration()
        val second = RequestGeneration()
        val firstRequest = first.advance()
        val secondRequest = second.advance()

        assertTrue(first.isCurrent(firstRequest))
        assertTrue(second.isCurrent(secondRequest))
        assertFalse(first.isCurrent(secondRequest))
        assertFalse(second.isCurrent(firstRequest))
    }
}
