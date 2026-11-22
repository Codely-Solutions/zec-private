package co.electriccoin.zcash.ui.screen.chooseserver

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.type.ServerValidation
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.component.EndpointTextFieldInnerState
import co.electriccoin.zcash.ui.common.component.ZashiEndpointTextFieldParser
import co.electriccoin.zcash.ui.common.model.FastestServersState
import co.electriccoin.zcash.ui.common.provider.IsServerSelectionAutomaticProvider
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.usecase.GetAutomaticEndpointUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedEndpointUseCase
import co.electriccoin.zcash.ui.common.usecase.IsServerAutomaticUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveFastestServersUseCase
import co.electriccoin.zcash.ui.common.usecase.PersistServerSelectionUseCase
import co.electriccoin.zcash.ui.common.usecase.RefreshFastestServersUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChooseServerVMTest {
    private val bundled = LightWalletEndpoint("bundled.example", 443, true)
    private val rejected = LightWalletEndpoint("rejected.example", 443, true)
    private val corrected = LightWalletEndpoint("corrected.example", 9067, true)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun rejectedCustomEndpointCanBeDismissedCorrectedAndSaved() = runTest {
        withScreen(initiallyAutomatic = true) { screen ->
            val retry = CompletableDeferred<ServerValidation>()
            coEvery { screen.synchronizer.validateServerEndpoint(any(), rejected) } returns
                ServerValidation.InValid(IllegalArgumentException("Server rejected the connection"))
            coEvery { screen.synchronizer.validateServerEndpoint(any(), corrected) } coAnswers { retry.await() }

            assertFalse(screen.state.saveButton.isEnabled)
            screen.state.connectionMode.manual.onClick()
            runCurrent()
            screen.state.custom.radioButtonState.onClick()
            runCurrent()
            assertTrue(screen.state.custom.isExpanded)
            screen.enter(rejected)
            runCurrent()
            assertTrue(screen.state.saveButton.isEnabled)
            screen.state.saveButton.onClick()
            runCurrent()

            val dialog = assertIs<ServerDialogState.Validation>(screen.state.dialogState)
            assertEquals(stringRes(R.string.choose_server_validation_dialog_error_title), dialog.state.title)
            assertEquals(stringRes("Server rejected the connection"), dialog.reason)
            assertEquals(bundled, screen.storedEndpoint.value)
            assertTrue(screen.automatic.value)
            coVerify(exactly = 0) { screen.automaticPreference.store(any()) }
            coVerify(exactly = 0) { screen.repository.updateWalletEndpoint(any()) }
            assertFalse(screen.state.saveButton.isLoading)

            assertNotNull(dialog.state.confirmButtonState).onClick()
            runCurrent()
            assertNull(screen.state.dialogState)
            assertEquals(rejected, screen.state.custom.newServerTextFieldState.innerState.endpoint)
            screen.enter(corrected)
            runCurrent()
            screen.state.saveButton.onClick()
            runCurrent()

            val saving = screen.state
            assertEquals(stringRes(R.string.choose_server_saving), saving.saveButton.text)
            assertTrue(saving.saveButton.isLoading)
            assertFalse(saving.saveButton.isEnabled)
            assertFalse(saving.custom.newServerTextFieldState.isEnabled)
            assertFalse(saving.fastest.retryButton.isEnabled)
            saving.connectionMode.automatic.onClick()
            saving.saveButton.onClick()
            screen.enter(rejected)
            runCurrent()
            assertTrue(screen.state.connectionMode.isManualSelected)
            assertEquals(corrected, screen.state.custom.newServerTextFieldState.innerState.endpoint)

            retry.complete(ServerValidation.Valid)
            runCurrent()
            assertEquals(corrected, screen.storedEndpoint.value)
            assertFalse(screen.automatic.value)
            assertNull(screen.state.dialogState)
            assertFalse(screen.state.saveButton.isLoading)
            assertFalse(screen.state.saveButton.isEnabled)
            assertTrue(screen.state.custom.newServerTextFieldState.isEnabled)
            assertEquals(stringRes(R.string.serverSetup_active), screen.state.custom.badge)
            coVerify(exactly = 1) { screen.synchronizer.validateServerEndpoint(any(), corrected) }
            coVerify(exactly = 1) { screen.repository.updateWalletEndpoint(corrected) }
        }
    }

    @Test
    fun aFailedEndpointWriteReturnsToEditableStateAndCanBeRetried() = runTest {
        assertEndpointWriteRecovery(publishBeforeFailure = false)
    }

    @Test
    fun aFailedEndpointWriteCanBeRetriedWhenTheValueWasPublishedInMemory() = runTest {
        assertEndpointWriteRecovery(publishBeforeFailure = true)
    }

    private suspend fun TestScope.assertEndpointWriteRecovery(publishBeforeFailure: Boolean) {
        withScreen(initiallyAutomatic = false) { screen ->
            coEvery { screen.synchronizer.validateServerEndpoint(any(), corrected) } returns ServerValidation.Valid
            coEvery { screen.repository.updateWalletEndpoint(corrected) } coAnswers {
                if (publishBeforeFailure) screen.storedEndpoint.value = corrected
                throw IOException("Cannot save endpoint")
            }
            screen.enter(corrected)
            runCurrent()
            screen.state.saveButton.onClick()
            runCurrent()

            val dialog = assertIs<ServerDialogState.Validation>(screen.state.dialogState)
            assertEquals(bundled, screen.durableEndpoint)
            assertEquals(if (publishBeforeFailure) corrected else bundled, screen.storedEndpoint.value)
            assertFalse(screen.state.saveButton.isLoading)
            assertTrue(screen.state.saveButton.isEnabled)
            assertTrue(screen.state.custom.newServerTextFieldState.isEnabled)

            assertNotNull(dialog.state.confirmButtonState).onClick()
            runCurrent()
            assertNull(screen.state.dialogState)
            coEvery { screen.repository.updateWalletEndpoint(corrected) } coAnswers {
                screen.saveEndpoint(corrected)
            }
            screen.state.saveButton.onClick()
            runCurrent()

            assertEquals(corrected, screen.storedEndpoint.value)
            assertEquals(corrected, screen.durableEndpoint)
            assertFalse(screen.state.saveButton.isEnabled)
            coVerify(exactly = 2) { screen.repository.updateWalletEndpoint(corrected) }
        }
    }

    private suspend fun TestScope.withScreen(initiallyAutomatic: Boolean, test: suspend (Screen) -> Unit) {
        val screen = Screen(initiallyAutomatic)
        val subscription = backgroundScope.launch { screen.vm.state.collect {} }
        try {
            runCurrent()
            test(screen)
        } finally {
            subscription.cancel()
            screen.vm.viewModelScope.cancel()
        }
    }

    private inner class Screen(initiallyAutomatic: Boolean) {
        val storedEndpoint = MutableStateFlow(bundled)
        var durableEndpoint = bundled
            private set
        val automatic = MutableStateFlow(initiallyAutomatic)
        val synchronizer = mockk<Synchronizer>()
        val automaticPreference = mockk<IsServerSelectionAutomaticProvider> {
            coEvery { store(any()) } coAnswers { automatic.value = firstArg() }
        }
        val repository = mockk<WalletRepository> {
            every { fastestEndpoints } returns MutableStateFlow(FastestServersState(listOf(bundled), false))
            coEvery { updateWalletEndpoint(any()) } coAnswers { saveEndpoint(firstArg()) }
        }
        private val selectedEndpoint = mockk<GetSelectedEndpointUseCase> {
            every { observe() } returns storedEndpoint
        }
        private val automaticMode = mockk<IsServerAutomaticUseCase> {
            every { observe() } returns automatic
        }
        private val endpoints = mockk<LightWalletEndpointProvider> {
            every { getEndpoints() } returns listOf(bundled)
            every { getDefaultEndpoint() } returns bundled
        }
        private val application = RuntimeEnvironment.getApplication()
        private val automaticEndpoint = GetAutomaticEndpointUseCase(repository, endpoints, selectedEndpoint)
        private val persist = PersistServerSelectionUseCase(
            application,
            repository,
            mockk<SynchronizerProvider> { coEvery { getSynchronizer() } returns this@Screen.synchronizer },
            automaticPreference,
            automaticEndpoint,
        )
        val vm = ChooseServerVM(
            application,
            ObserveFastestServersUseCase(repository),
            selectedEndpoint,
            automaticMode,
            endpoints,
            RefreshFastestServersUseCase(repository),
            persist,
            mockk(),
            automaticEndpoint,
        )
        val state: ChooseServerState get() = assertNotNull(vm.state.value)

        fun saveEndpoint(endpoint: LightWalletEndpoint) {
            durableEndpoint = endpoint
            storedEndpoint.value = endpoint
        }

        fun enter(endpoint: LightWalletEndpoint) {
            val text = "${endpoint.host}:${endpoint.port}"
            val parsed = assertNotNull(ZashiEndpointTextFieldParser.toEndpointOrNull(text))
            state.custom.newServerTextFieldState.onValueChange(EndpointTextFieldInnerState.fromEndpoint(parsed))
        }
    }

    private val ChooseServerState.custom: ServerState.Custom
        get() = other.servers.filterIsInstance<ServerState.Custom>().single()
}
