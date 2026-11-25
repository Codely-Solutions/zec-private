package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import io.ktor.client.engine.okhttp.OkHttp
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun malformedTorPreferenceCannotCreateAnyHttpClientOrDisplayOff() = runTest {
        val key = PreferenceKey("is_tor_Enabled")
        var stored: String? = "synthetic invalid preference"
        val preferences = mockk<PreferenceProvider> {
            coEvery { getString(key) } coAnswers { stored }
            every { observe(key) } returns flowOf(null)
        }
        val holder = mockk<EncryptedPreferenceProvider> {
            coEvery { this@mockk() } returns preferences
        }
        val preference = IsTorEnabledStorageProviderImpl(holder)
        val synchronizer = mockk<SynchronizerProvider>()
        val clients = HttpClientProviderImpl(synchronizer, preference)
        val display = IsTorEnabledUseCase(preference)

        mockkObject(OkHttp)
        try {
            every { OkHttp.create(any()) } throws AssertionError("Direct HTTP must be withheld")
            val failure = assertFailsWith<IllegalStateException> { clients.create() }
            assertEquals("Could not read the Tor protection setting", failure.message)
            assertNull(display.observe().first())
            verify(exactly = 0) { OkHttp.create(any()) }
        } finally {
            unmockkObject(OkHttp)
        }

        stored = "true"
        assertEquals(true, display.observe().first())
        stored = "false"
        assertEquals(false, display.observe().first())
        stored = null
        assertNull(preference.get())
        assertEquals(false, display.observe().first())
    }

    @Test
    fun aTorOnlyRecoveryStatusRequestCannotFollowTheWalletToTheDirectRoute() = runTest {
        val preference = mockk<IsTorEnabledStorageProvider> {
            coEvery { get() } returns false
        }
        val synchronizer = mockk<Synchronizer> {
            coEvery { getTorHttpClient(any()) } throws IOException("Synthetic Tor route unavailable")
        }
        val provider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } returns synchronizer
        }
        val api = KtorNearApiProvider(HttpClientProviderImpl(provider, preference))
        mockkObject(OkHttp)
        try {
            every { OkHttp.create(any()) } throws AssertionError("A recovery status request must stay over Tor")
            withContext(TorOnlyRequest) {
                assertFailsWith<IOException> { api.checkSwapStatus("isolated-unfunded-reference") }
            }
            verify(exactly = 0) { OkHttp.create(any()) }
        } finally {
            unmockkObject(OkHttp)
        }
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
            val requests = listOf<suspend () -> Unit>(
                { api.getSupportedTokens() },
                { api.requestQuote(mockk()) },
                { api.checkSwapStatus("isolated-unfunded-reference") },
                { api.submitDepositTransaction(mockk()) }
            )
            for (request in requests) {
                val actual = assertFailsWith<IOException> { request() }
                assertEquals(failure.message, actual.message)
                // Coroutine stack recovery may copy the exception but retains its cause.
                assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
            }
            verify(exactly = 0) { OkHttp.create(any()) }
        } finally {
            unmockkObject(OkHttp)
        }
    }
}
