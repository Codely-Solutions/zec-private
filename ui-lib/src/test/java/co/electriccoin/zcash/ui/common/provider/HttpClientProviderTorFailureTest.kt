package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import io.ktor.client.engine.okhttp.OkHttp
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A selected Tor failure must reach the caller without constructing a direct HTTP engine. */
class HttpClientProviderTorFailureTest {
    @Test
    fun unavailableTorClientStopsSwapApiWithoutDirectFallback() =
        runTest {
            val failure = IOException("Isolated Tor client unavailable")
            val synchronizer = mockk<Synchronizer>()
            val synchronizerProvider = mockk<SynchronizerProvider>()
            coEvery { synchronizerProvider.getSynchronizer() } returns synchronizer
            coEvery { synchronizer.getTorHttpClient(any()) } throws failure
            assertTorFailure(synchronizerProvider, failure)
        }

    @Test
    fun unavailableSynchronizerStopsSwapApiWithoutDirectFallback() =
        runTest {
            val failure = IOException("Isolated synchronizer unavailable")
            val synchronizerProvider = mockk<SynchronizerProvider>()
            coEvery { synchronizerProvider.getSynchronizer() } throws failure
            assertTorFailure(synchronizerProvider, failure)
        }

    private suspend fun assertTorFailure(
        synchronizerProvider: SynchronizerProvider,
        failure: IOException,
    ) {
        val preference = mockk<IsTorEnabledStorageProvider>()
        coEvery { preference.get() } returns true
        mockkObject(OkHttp)
        try {
            every { OkHttp.create(any()) } throws AssertionError("Direct HTTP engine is forbidden in this test")
            val api = KtorNearApiProvider(HttpClientProviderImpl(synchronizerProvider, preference))
            val actual = assertFailsWith<IOException> { api.getSupportedTokens() }
            assertEquals(failure.message, actual.message)
            // Coroutine stack recovery can copy an exception while retaining its original cause.
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
            verify(exactly = 0) { OkHttp.create(any()) }
        } finally {
            unmockkObject(OkHttp)
        }
    }
}
