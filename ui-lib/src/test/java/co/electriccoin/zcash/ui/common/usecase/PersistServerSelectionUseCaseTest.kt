package co.electriccoin.zcash.ui.common.usecase

import android.app.Application
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.ServerValidation
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import co.electriccoin.zcash.ui.common.provider.WalletServerSelectionProvider
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        coEvery { getServerSelection() } returns WalletServerSelection(fastest, true)
        coEvery { storeServerSelection(any(), 12L, null, any()) } coAnswers {
            arg<() -> Unit>(3).invoke()
            true
        }
    }
    private val getAutomaticEndpoint = mockk<GetAutomaticEndpointUseCase>()
    private val transport = WalletTransportAccess()
    private val useCase =
        PersistServerSelectionUseCase(
            application = mockk<Application>(),
            walletServerSelectionProvider = storage,
            synchronizerProvider = synchronizerProvider,
            getAutomaticEndpoint = getAutomaticEndpoint,
            walletTransport = transport,
        )

    @BeforeTest
    fun setUp() {
        coEvery { synchronizerProvider.getSynchronizer() } returns synchronizer
        every { synchronizerProvider.synchronizer } returns MutableStateFlow(synchronizer)
    }

    @Test
    fun manualValidEndpointPersistsManualSelection() =
        runTest {
            coEvery { synchronizer.validateServerEndpoint(any(), endpoint) } returns ServerValidation.Valid

            useCase.persistManual(endpoint)

            coVerify(exactly = 1) {
                storage.storeServerSelection(WalletServerSelection(endpoint, false), 12L, null, any())
            }
        }

    @Test
    fun manualInvalidEndpointThrowsWithReasonAndDoesNotPersist() =
        runTest {
            coEvery { synchronizer.validateServerEndpoint(any(), endpoint) } returns
                ServerValidation.InValid(IllegalArgumentException("bad host"))

            val exception = assertFailsWith<PersistEndpointException> { useCase.persistManual(endpoint) }

            assertEquals("bad host", exception.message)
            coVerify(exactly = 0) { storage.storeServerSelection(any(), any(), any(), any()) }
        }

    @Test
    fun manualWhileValidationRunningThrowsAndDoesNotPersist() =
        runTest {
            coEvery { synchronizer.validateServerEndpoint(any(), endpoint) } returns ServerValidation.Running

            assertFailsWith<PersistEndpointException> { useCase.persistManual(endpoint) }

            coVerify(exactly = 0) { storage.storeServerSelection(any(), any(), any(), any()) }
        }

    @Test
    fun automaticPersistsAutomaticFlagAndChosenEndpoint() =
        runTest {
            coEvery { getAutomaticEndpoint() } returns fastest

            useCase.persistAutomatic()

            coVerify(exactly = 1) {
                storage.storeServerSelection(WalletServerSelection(fastest, true), 12L, null, any())
            }
        }
    @Test
    fun refusedWalletWriteIsASaveFailure() = runTest {
        coEvery { getAutomaticEndpoint() } returns fastest
        coEvery { storage.storeServerSelection(any(), 12L, null, any()) } returns false

        assertFailsWith<IOException> { useCase.persistAutomatic() }
    }

    @Test
    fun serverChangeWaitsForAnAdmittedWalletOperationAndRetiresTheOldSyncAfterCommit() = runTest {
        coEvery { synchronizer.validateServerEndpoint(any(), endpoint) } returns ServerValidation.Valid
        val reached = CompletableDeferred<Unit>()
        val drain = CompletableDeferred<Unit>()
        val pending = launch {
            transport.whileOperating {
                reached.complete(Unit)
                drain.await()
            }
        }
        reached.await()
        assertFailsWith<ServerSelectionWalletBusyException> { useCase.persistManual(endpoint) }
        assertFalse(transport.isRetired(synchronizer))
        coVerify(exactly = 0) { storage.storeServerSelection(any(), any(), any(), any()) }

        drain.complete(Unit)
        pending.join()
        useCase.persistManual(endpoint)

        assertTrue(transport.isRetired(synchronizer))
        coVerify(exactly = 1) {
            storage.storeServerSelection(WalletServerSelection(endpoint, false), 12L, null, any())
        }
    }

    @Test
    fun savingOnlyTheSelectionModeKeepsTheCurrentSynchronizer() = runTest {
        coEvery { synchronizer.validateServerEndpoint(any(), fastest) } returns ServerValidation.Valid

        useCase.persistManual(fastest)

        coVerify(exactly = 1) {
            storage.storeServerSelection(WalletServerSelection(fastest, false), 12L, null, any())
        }
        assertFalse(transport.isRetired(synchronizer))
    }

    @Test
    fun nativeServerValidationKeepsTheCurrentTransportUntilItReturns() = runTest {
        val validating = CompletableDeferred<Unit>()
        val validation = CompletableDeferred<ServerValidation>()
        coEvery { synchronizer.validateServerEndpoint(any(), endpoint) } coAnswers {
            validating.complete(Unit)
            validation.await()
        }

        val save = launch { useCase.persistManual(endpoint) }
        validating.await()
        assertNull(transport.ifIdleSwitch { true })
        validation.complete(ServerValidation.Valid)
        save.join()

        assertTrue(transport.isRetired(synchronizer))
    }
}
