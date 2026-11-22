package co.electriccoin.zcash.ui.screen.chooseserver

import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.type.ServerValidation
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.preference.AndroidPreferenceProvider
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.component.EndpointTextFieldInnerState
import co.electriccoin.zcash.ui.common.component.ZashiEndpointTextFieldParser
import co.electriccoin.zcash.ui.common.model.FastestServersState
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProviderImpl
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletServerSelection
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.repository.WalletRepository
import co.electriccoin.zcash.ui.common.usecase.GetAutomaticEndpointUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedEndpointUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveFastestServersUseCase
import co.electriccoin.zcash.ui.common.usecase.PersistServerSelectionUseCase
import co.electriccoin.zcash.ui.common.usecase.RefreshFastestServersUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        withScreen(PreferenceDisk(bundled, true)) { screen ->
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
            assertEquals(WalletServerSelection(bundled, true), screen.walletProvider.getServerSelection())
            assertEquals(0, screen.disk.attempts)
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
            assertEquals(WalletServerSelection(corrected, false), screen.walletProvider.getServerSelection())
            assertNull(screen.state.dialogState)
            assertFalse(screen.state.saveButton.isLoading)
            assertFalse(screen.state.saveButton.isEnabled)
            assertTrue(screen.state.custom.newServerTextFieldState.isEnabled)
            assertEquals(stringRes(R.string.serverSetup_active), screen.state.custom.badge)
            coVerify(exactly = 1) { screen.synchronizer.validateServerEndpoint(any(), corrected) }
            assertEquals(1, screen.disk.attempts)
        }
    }

    @Test
    fun failedAutomaticToManualSaveKeepsPreviousSelectionThroughBackAndRetry() = runTest {
        assertFailedSaveRecovery(initiallyAutomatic = true)
    }

    @Test
    fun failedManualToAutomaticSaveKeepsPreviousSelectionThroughBackAndRetry() = runTest {
        assertFailedSaveRecovery(initiallyAutomatic = false)
    }

    @Test
    fun failedRollbackDoesNotFlushTheRejectedSelectionWithAnotherPreferenceWrite() = runTest {
        val previous = initialSelection(isAutomatic = true)
        val next = initialSelection(isAutomatic = false)
        val disk = PreferenceDisk(previous.endpoint, true)
        withScreen(disk) { screen ->
            selectOtherMode(screen, initiallyAutomatic = true)
            disk.commitsToFail = 2
            screen.state.saveButton.onClick()
            runCurrent()

            assertIs<ServerDialogState.SaveFailure>(screen.state.dialogState)
            assertEquals(2, disk.attempts)
            assertEquals(previous, screen.walletProvider.getServerSelection())
            screen.torPreference.store(true)
        }
        withScreen(disk) { reopened ->
            assertEquals(true, reopened.torPreference.get())
            reopened.assertSelected(previous)
            selectOtherMode(reopened, initiallyAutomatic = true)
            reopened.state.saveButton.onClick()
            runCurrent()

            reopened.assertSelected(next)
            assertEquals(4, disk.attempts)
        }
        withScreen(disk) { reopened ->
            reopened.assertSelected(next)
            assertEquals(true, reopened.torPreference.get())
        }
    }

    @Test
    fun failedManualEndpointChangeCanBeRetried() = runTest {
        val disk = PreferenceDisk(bundled, false)
        withScreen(disk) { screen ->
            disk.commitsToFail = 1
            screen.enter(corrected)
            runCurrent()
            screen.state.saveButton.onClick()
            runCurrent()

            assertIs<ServerDialogState.SaveFailure>(screen.state.dialogState)
            assertEquals(WalletServerSelection(bundled, false), screen.walletProvider.getServerSelection())
            assertTrue(screen.state.saveButton.isEnabled)
            screen.dismissError()
            screen.state.saveButton.onClick()
            runCurrent()

            assertEquals(WalletServerSelection(corrected, false), screen.walletProvider.getServerSelection())
            assertEquals(3, disk.attempts)
            assertFalse(screen.state.saveButton.isEnabled)
        }
    }

    @Test
    fun cancellingBeforeWriteAdmissionKeepsAutomaticSelection() = runTest {
        assertCancellationRecovery(initiallyAutomatic = true)
    }

    @Test
    fun cancellingBeforeWriteAdmissionKeepsManualSelection() = runTest {
        assertCancellationRecovery(initiallyAutomatic = false)
    }

    @Test
    fun resetDuringValidationRefusesTheOldWalletSelection() = runTest {
        val disk = PreferenceDisk(bundled, true)
        withScreen(disk) { screen ->
            val validation = CompletableDeferred<ServerValidation>()
            coEvery { screen.synchronizer.validateServerEndpoint(any(), corrected) } coAnswers { validation.await() }
            selectOtherMode(screen, initiallyAutomatic = true)
            screen.state.saveButton.onClick()
            runCurrent()
            screen.writeAccess.beginReset()
            screen.writeAccess.finishReset()
            screen.walletProvider.store(wallet(bundled))

            validation.complete(ServerValidation.Valid)
            runCurrent()

            assertIs<ServerDialogState.SaveFailure>(screen.state.dialogState)
            assertEquals(WalletServerSelection(bundled, true), screen.walletProvider.getServerSelection())
            assertEquals(1, disk.attempts)
        }
    }

    private suspend fun TestScope.assertFailedSaveRecovery(initiallyAutomatic: Boolean) {
        val previous = initialSelection(initiallyAutomatic)
        val next = initialSelection(!initiallyAutomatic)
        val disk = PreferenceDisk(previous.endpoint, initiallyAutomatic)
        withScreen(disk) { screen ->
            screen.assertSelected(previous)
            selectOtherMode(screen, initiallyAutomatic)
            disk.commitsToFail = 1
            screen.state.saveButton.onClick()
            runCurrent()

            val dialog = assertIs<ServerDialogState.SaveFailure>(screen.state.dialogState)
            assertEquals(stringRes(R.string.choose_server_save_error_title), dialog.state.title)
            assertEquals(previous, screen.walletProvider.getServerSelection())
            assertTrue(screen.state.saveButton.isEnabled)
            assertFalse(screen.state.saveButton.isLoading)
            screen.dismissError()
            runCurrent()
            screen.state.onBack()
            verify(exactly = 1) { screen.navigation.back() }
        }

        withScreen(disk) { reopened ->
            reopened.assertSelected(previous)
            selectOtherMode(reopened, initiallyAutomatic)
            reopened.state.saveButton.onClick()
            runCurrent()

            reopened.assertSelected(next)
            assertEquals(3, disk.attempts)
        }
        withScreen(disk) { it.assertSelected(next) }
    }

    private suspend fun TestScope.assertCancellationRecovery(initiallyAutomatic: Boolean) {
        val previous = initialSelection(initiallyAutomatic)
        val next = initialSelection(!initiallyAutomatic)
        val disk = PreferenceDisk(previous.endpoint, initiallyAutomatic)
        withScreen(disk) { screen ->
            val release = CompletableDeferred<Unit>()
            val pendingWrite = launch {
                screen.writeAccess.write(screen.writeAccess.token()) { release.await() }
            }
            runCurrent()
            selectOtherMode(screen, initiallyAutomatic)
            screen.state.saveButton.onClick()
            runCurrent()
            assertTrue(screen.state.saveButton.isLoading)
            screen.vm.viewModelScope.cancel()
            release.complete(Unit)
            pendingWrite.join()
            runCurrent()

            assertEquals(0, disk.attempts)
            assertEquals(previous, screen.walletProvider.getServerSelection())
        }
        withScreen(disk) { reopened ->
            reopened.assertSelected(previous)
            selectOtherMode(reopened, initiallyAutomatic)
            reopened.state.saveButton.onClick()
            runCurrent()
            reopened.assertSelected(next)
        }
        withScreen(disk) { it.assertSelected(next) }
    }

    private fun TestScope.selectOtherMode(screen: Screen, initiallyAutomatic: Boolean) {
        if (initiallyAutomatic) {
            screen.state.connectionMode.manual.onClick()
            runCurrent()
            screen.state.custom.radioButtonState.onClick()
            runCurrent()
            screen.enter(corrected)
        } else {
            screen.state.connectionMode.automatic.onClick()
        }
        runCurrent()
        assertTrue(screen.state.saveButton.isEnabled)
    }

    private fun initialSelection(isAutomatic: Boolean) =
        WalletServerSelection(if (isAutomatic) bundled else corrected, isAutomatic)

    private suspend fun TestScope.withScreen(disk: PreferenceDisk, test: suspend (Screen) -> Unit) {
        val screen = Screen(disk)
        val subscription = backgroundScope.launch { screen.vm.state.collect {} }
        try {
            runCurrent()
            test(screen)
        } finally {
            subscription.cancel()
            screen.vm.viewModelScope.cancel()
        }
    }

    private inner class Screen(val disk: PreferenceDisk) {
        val writeAccess = WalletWriteAccess()
        val navigation = mockk<NavigationRouter>(relaxed = true)
        val synchronizer = mockk<Synchronizer> {
            coEvery { validateServerEndpoint(any(), corrected) } returns ServerValidation.Valid
        }
        private val encryptedPreferences = disk.open()
        val walletProvider = PersistableWalletProviderImpl(encryptedPreferences, writeAccess)
        val torPreference = IsTorEnabledStorageProviderImpl(encryptedPreferences)
        private val repository = mockk<WalletRepository> {
            every { fastestEndpoints } returns MutableStateFlow(FastestServersState(listOf(bundled), false))
        }
        private val endpoints = mockk<LightWalletEndpointProvider> {
            every { getEndpoints() } returns listOf(bundled)
            every { getDefaultEndpoint() } returns bundled
        }
        private val application = RuntimeEnvironment.getApplication()
        private val automaticEndpoint =
            GetAutomaticEndpointUseCase(repository, endpoints, GetSelectedEndpointUseCase(walletProvider))
        private val persist = PersistServerSelectionUseCase(
            application,
            walletProvider,
            mockk<SynchronizerProvider> { coEvery { getSynchronizer() } returns this@Screen.synchronizer },
            automaticEndpoint,
        )
        val vm = ChooseServerVM(
            application,
            ObserveFastestServersUseCase(repository),
            walletProvider,
            endpoints,
            RefreshFastestServersUseCase(repository),
            persist,
            navigation,
            automaticEndpoint,
        )
        val state: ChooseServerState get() = assertNotNull(vm.state.value)

        suspend fun assertSelected(selection: WalletServerSelection) {
            assertEquals(selection, walletProvider.getServerSelection())
            assertEquals(selection.isAutomatic == false, state.connectionMode.isManualSelected)
            val badge = if (selection.endpoint == bundled) state.fastest.servers.single().badge else state.custom.badge
            assertEquals(stringRes(R.string.serverSetup_active), badge)
            assertFalse(state.saveButton.isEnabled)
            assertNull(state.dialogState)
        }

        fun dismissError() {
            val dialog = assertIs<ServerDialogState.SaveFailure>(state.dialogState)
            assertNotNull(dialog.state.confirmButtonState).onClick()
        }

        fun enter(endpoint: LightWalletEndpoint) {
            val text = "${endpoint.host}:${endpoint.port}"
            val parsed = assertNotNull(ZashiEndpointTextFieldParser.toEndpointOrNull(text))
            state.custom.newServerTextFieldState.onValueChange(EndpointTextFieldInnerState.fromEndpoint(parsed))
        }
    }

    private inner class PreferenceDisk(endpoint: LightWalletEndpoint, isAutomatic: Boolean) {
        private val durable = mutableMapOf(
            "persistable_wallet" to wallet(endpoint).toJson().toString(),
            "is_server_selection_automatic" to isAutomatic.toString(),
        )
        var attempts = 0
            private set
        var commitsToFail = 0

        fun open(): EncryptedPreferenceProvider {
            val cache = durable.toMutableMap()
            val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
            val preferences = mockk<SharedPreferences>()
            every { preferences.getString(any(), null) } answers { cache[firstArg()] }
            every { preferences.registerOnSharedPreferenceChangeListener(any()) } answers {
                listeners.add(firstArg())
                Unit
            }
            every { preferences.unregisterOnSharedPreferenceChangeListener(any()) } answers {
                listeners.remove(firstArg())
                Unit
            }
            every { preferences.edit() } answers {
                val updates = mutableMapOf<String, String?>()
                val editor = mockk<SharedPreferences.Editor>()
                every { editor.putString(any(), any()) } answers {
                    updates[firstArg()] = secondArg()
                    editor
                }
                every { editor.commit() } answers {
                    attempts++
                    updates.forEach { (key, value) ->
                        if (value == null) cache.remove(key) else cache[key] = value
                        listeners.forEach { it.onSharedPreferenceChanged(preferences, key) }
                    }
                    val succeeded = commitsToFail == 0
                    if (succeeded) {
                        durable.clear()
                        durable.putAll(cache)
                    } else {
                        commitsToFail--
                    }
                    succeeded
                }
                editor
            }
            val provider = AndroidPreferenceProvider(preferences, Dispatchers.Main)
            return mockk<EncryptedPreferenceProvider> { coEvery { invoke() } returns provider }
        }
    }

    private fun wallet(endpoint: LightWalletEndpoint) =
        PersistableWallet(
            network = ZcashNetwork.Mainnet,
            endpoint = endpoint,
            birthday = null,
            seedPhrase = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "abandon" }),
            walletInitMode = WalletInitMode.ExistingWallet
        )

    private val ChooseServerState.custom: ServerState.Custom
        get() = other.servers.filterIsInstance<ServerState.Custom>().single()
}
