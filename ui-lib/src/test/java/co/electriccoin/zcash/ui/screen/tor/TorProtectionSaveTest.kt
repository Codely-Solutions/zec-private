package co.electriccoin.zcash.ui.screen.tor

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.global.WalletSdkTransportGate
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletSdkRead
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateAndTorUseCase
import co.electriccoin.zcash.ui.common.usecase.TorChangeWalletBusyException
import co.electriccoin.zcash.ui.screen.tor.optin.TorOptInVM
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsVM
import co.electriccoin.zcash.ui.screen.tor.settings.TorPreferenceRead
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TorProtectionSaveTest {
    @Test
    fun torSettingsCanRetryInitialAndLaterReadFailuresWithoutUsingTheOldForm() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val readFailsLater = CompletableDeferred<Unit>()
                var reads = 0
                val provider = mockk<IsTorEnabledStorageProvider> {
                    every { observe() } returns flow {
                        when (++reads) {
                            1 -> throw IOException("Synthetic initial read failure")
                            2 -> {
                                emit(true)
                                readFailsLater.await()
                                throw IOException("Synthetic observer failure")
                            }
                            else -> emit(false)
                        }
                    }
                }
                val save = mockk<OptInExchangeRateAndTorUseCase>(relaxed = true)
                val settings = TorSettingsVM(
                    IsTorEnabledUseCase(provider), mockk(relaxed = true), save, mockk(relaxed = true),
                )
                backgroundScope.launch { settings.state.collect { } }
                assertEquals(TorPreferenceRead.Unavailable, settings.preference.value)
                assertNull(settings.state.value.content)

                settings.retryRead()
                val prior = assertNotNull(settings.state.value.content)
                assertTrue(prior.isOptedIn)
                readFailsLater.complete(Unit)
                assertEquals(TorPreferenceRead.Unavailable, settings.preference.value)
                prior.onSaveClick(false)
                coVerify(exactly = 0) { save(any(), any()) }

                settings.retryRead()
                assertFalse(assertNotNull(settings.state.value.content).isOptedIn)
                assertEquals(3, reads)
                settings.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun nativeWorkFinishesBeforeCommitAndRecreatedSettingsCannotReadAStaleRoute() = runTest {
        val key = PreferenceKey("is_tor_Enabled")
        val stored = MutableStateFlow<String?>("true")
        val encrypted = mockk<PreferenceProvider> {
            every { observe(key) } returns stored
            coEvery { getString(key) } coAnswers { stored.value }
            coEvery { putStringWithRollback(key, "false") } coAnswers { stored.value = "false" }
        }
        val gate = WalletSdkTransportGate()
        val transport = WalletTransportAccess(gate)
        val preference = IsTorEnabledStorageProviderImpl(mockk<EncryptedPreferenceProvider> {
            coEvery { this@mockk() } returns encrypted
        }, gate)
        val navigation = mockk<NavigationRouter>(relaxed = true)
        val save = OptInExchangeRateAndTorUseCase(
            navigation,
            preference,
            AppAccessSession().apply { unlock() },
            WalletWriteAccess(),
            gate,
            transport,
        )
        val generation = MutableStateFlow(0L)
        val wallet = mockk<PersistableWallet>()
        val wallets = MutableStateFlow(WalletSdkRead(0L, wallet))
        val admitted = mutableListOf<Pair<PersistableWallet?, Boolean?>>()
        backgroundScope.launch {
            combine(
                gate.walletInput(gate.walletDecisions(wallets, generation), generation),
                gate.transportInput(preference.observe(), generation),
            ) { current, tor -> current to tor }.collect { admitted += it }
        }
        runCurrent()
        assertEquals(wallet to true, admitted.last())

        val nativeWorkEnded = CompletableDeferred<Unit>()
        val spend = launch { transport.whileOperating { nativeWorkEnded.await() } }
        runCurrent()
        assertFailsWith<TorChangeWalletBusyException> { save(false) }
        assertEquals(true, preference.get())
        assertFalse(gate.restartRequired.value)
        coVerify(exactly = 0) { encrypted.putStringWithRollback(key, any()) }

        nativeWorkEnded.complete(Unit)
        spend.join()
        save(false)
        runCurrent()

        coVerify(exactly = 1) { encrypted.putStringWithRollback(key, "false") }
        assertEquals("false", stored.value)
        assertTrue(gate.restartRequired.value)
        assertEquals(null to true, admitted.last())
        assertFailsWith<IllegalStateException> { preference.get() }
        assertFailsWith<kotlinx.coroutines.CancellationException> {
            transport.whileOperating { error("A retired engine cannot start a new spend") }
        }
        verify(exactly = 0) { navigation.back() }
    }

    @Test
    fun failedStorageKeepsBothTorScreensOpenAndRetryUsesTheAcknowledgedChoice() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val key = PreferenceKey("is_tor_Enabled")
                val stored = MutableStateFlow<String?>("true")
                var failWrite = true
                val encrypted = mockk<PreferenceProvider> {
                    every { observe(key) } returns stored
                    coEvery { getString(key) } coAnswers { stored.value }
                    coEvery { putStringWithRollback(key, any()) } coAnswers {
                        if (failWrite) throw IOException("Synthetic unacknowledged preference")
                        stored.value = secondArg()
                    }
                }
                val preference = IsTorEnabledStorageProviderImpl(mockk<EncryptedPreferenceProvider> {
                    coEvery { this@mockk() } returns encrypted
                })
                val navigation = mockk<NavigationRouter>(relaxed = true)
                val save = OptInExchangeRateAndTorUseCase(
                    navigation,
                    preference,
                    AppAccessSession().apply { unlock() },
                    WalletWriteAccess(),
                )
                val errors = ErrorMapperUseCase(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
                val settings = TorSettingsVM(IsTorEnabledUseCase(preference), navigation, save, errors)
                val optIn = TorOptInVM(save, navigation, errors)
                backgroundScope.launch { settings.state.collect { } }
                backgroundScope.launch { optIn.state.collect { } }

                assertNotNull(settings.state.value.content).onSaveClick(false)
                assertNotNull(optIn.state.value.content).onSkipClick()
                assertNotNull(settings.state.value.error)
                assertNotNull(optIn.state.value.error)
                assertEquals(true, preference.get())
                verify(exactly = 0) { navigation.back() }

                failWrite = false
                assertNotNull(settings.state.value.content).onSaveClick(false)
                assertNotNull(optIn.state.value.content).onEnableClick()
                assertNull(settings.state.value.error)
                assertNull(optIn.state.value.error)
                assertEquals(true, preference.get())
                verify(exactly = 2) { navigation.back() }
                coVerify(exactly = 3) { encrypted.putStringWithRollback(key, "false") }
                coVerify(exactly = 1) { encrypted.putStringWithRollback(key, "true") }
                settings.viewModelScope.cancel()
                optIn.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
