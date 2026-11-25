package co.electriccoin.zcash.ui.screen.tor

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProviderImpl
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.usecase.ErrorMapperUseCase
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.OptInExchangeRateAndTorUseCase
import co.electriccoin.zcash.ui.screen.tor.optin.TorOptInVM
import co.electriccoin.zcash.ui.screen.tor.settings.TorSettingsVM
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TorProtectionSaveTest {
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
