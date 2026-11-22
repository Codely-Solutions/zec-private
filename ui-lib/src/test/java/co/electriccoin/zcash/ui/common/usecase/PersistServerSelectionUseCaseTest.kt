package co.electriccoin.zcash.ui.common.usecase

import android.app.Application
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.ServerValidation
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import co.electriccoin.zcash.ui.common.provider.WalletServerSelectionProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Persisting the user's server selection (MOB-1144): a manual selection is validated before it is
 * stored (invalid/running validation aborts without persisting); a valid manual selection stores the
 * flag as manual and pins the endpoint; automatic stores the flag as automatic and the chosen
 * fastest endpoint.
 */
class PersistServerSelectionUseCaseTest {
    private val endpoint = LightWalletEndpoint(host = "manual.example.com", port = 443, isSecure = true)
    private val fastest = LightWalletEndpoint(host = "fastest.example.com", port = 443, isSecure = true)

    private val synchronizer = mockk<Synchronizer>()
    private val synchronizerProvider = mockk<SynchronizerProvider>()
    private val storage = mockk<WalletServerSelectionProvider> {
        every { writeVersion } returns 12L
        coEvery { storeServerSelection(any(), 12L, null) } returns true
    }
    private val getAutomaticEndpoint = mockk<GetAutomaticEndpointUseCase>()
    private val useCase =
        PersistServerSelectionUseCase(
            application = mockk<Application>(),
            walletServerSelectionProvider = storage,
            synchronizerProvider = synchronizerProvider,
            getAutomaticEndpoint = getAutomaticEndpoint
        )

    @BeforeTest
    fun setUp() {
        coEvery { synchronizerProvider.getSynchronizer() } returns synchronizer
    }

    @Test
    fun manualValidEndpointPersistsManualSelection() =
        runTest {
            coEvery { synchronizer.validateServerEndpoint(any(), endpoint) } returns ServerValidation.Valid

            useCase.persistManual(endpoint)

            coVerify(exactly = 1) { storage.storeServerSelection(WalletServerSelection(endpoint, false), 12L, null) }
        }

    @Test
    fun manualInvalidEndpointThrowsWithReasonAndDoesNotPersist() =
        runTest {
            coEvery { synchronizer.validateServerEndpoint(any(), endpoint) } returns
                ServerValidation.InValid(IllegalArgumentException("bad host"))

            val exception = assertFailsWith<PersistEndpointException> { useCase.persistManual(endpoint) }

            assertEquals("bad host", exception.message)
            coVerify(exactly = 0) { storage.storeServerSelection(any(), any(), any()) }
        }

    @Test
    fun manualWhileValidationRunningThrowsAndDoesNotPersist() =
        runTest {
            coEvery { synchronizer.validateServerEndpoint(any(), endpoint) } returns ServerValidation.Running

            assertFailsWith<PersistEndpointException> { useCase.persistManual(endpoint) }

            coVerify(exactly = 0) { storage.storeServerSelection(any(), any(), any()) }
        }

    @Test
    fun automaticPersistsAutomaticFlagAndChosenEndpoint() =
        runTest {
            coEvery { getAutomaticEndpoint() } returns fastest

            useCase.persistAutomatic()

            coVerify(exactly = 1) { storage.storeServerSelection(WalletServerSelection(fastest, true), 12L, null) }
        }
    @Test
    fun refusedWalletWriteIsASaveFailure() = runTest {
        coEvery { getAutomaticEndpoint() } returns fastest
        coEvery { storage.storeServerSelection(any(), 12L, null) } returns false

        assertFailsWith<IOException> { useCase.persistAutomatic() }
    }
}
