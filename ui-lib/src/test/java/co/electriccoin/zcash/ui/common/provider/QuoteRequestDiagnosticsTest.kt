package co.electriccoin.zcash.ui.common.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class QuoteRequestDiagnosticsTest {
    @Test
    fun recordsStageElapsedAndClassWithoutExceptionMessage() =
        runTest {
            val events = mutableListOf<String>()
            var time = 0L
            val diagnostics = QuoteRequestDiagnostics(events::add) { time }
            val failure = IOException("private address and amount")

            val thrown = assertFailsWith<IOException> {
                diagnostics.trace(QuoteRequestStage.HTTP_REQUEST) {
                    time = 3_000_000L
                    throw failure
                }
            }

            assertSame(failure, thrown)
            assertEquals("stage=HTTP_REQUEST elapsedMs=3 exception=IOException status=none", events.single())
            assertFalse(events.single().contains(failure.message!!))
        }

    @Test
    fun preservesCancellationAndSuccessWhenSinkFails() =
        runTest {
            val diagnostics = QuoteRequestDiagnostics(sink = { error("unavailable diagnostic backend") })
            assertEquals("result", diagnostics.trace(QuoteRequestStage.CLIENT_CREATE) { "result" })
            val cancelled = CancellationException("private cancellation context")
            val thrown = assertFailsWith<CancellationException> {
                diagnostics.trace(QuoteRequestStage.HTTP_REQUEST) { throw cancelled }
            }
            assertSame(cancelled, thrown)
        }
}
