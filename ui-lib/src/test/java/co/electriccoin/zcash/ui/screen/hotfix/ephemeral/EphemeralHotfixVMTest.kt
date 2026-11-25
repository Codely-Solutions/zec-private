package co.electriccoin.zcash.ui.screen.hotfix.ephemeral

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.usecase.FixEphemeralAddressUseCase
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EphemeralHotfixVMTest {
    @Test
    fun failedTorObservationKeepsAReadOnlyRetryActionInTheSheet() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                var readable = false
                val tor = mockk<IsTorEnabledStorageProvider> {
                    every { observe() } returns flow {
                        check(readable) { "Synthetic unavailable Tor setting" }
                        emit(true)
                    }
                }
                val recover = mockk<FixEphemeralAddressUseCase>()
                val vm = EphemeralHotfixVM(EphemeralHotfixArgs("synthetic-address"), tor, mockk(), recover)
                backgroundScope.launch { vm.state.collect { } }
                val failed = assertNotNull(vm.state.value)
                assertFalse(failed.address.isEnabled)
                assertTrue(failed.button.isEnabled)
                assertEquals(stringRes(R.string.wallet_initialization_retry), failed.button.text)

                readable = true
                failed.button.onClick()
                val verified = assertNotNull(vm.state.value)
                assertTrue(verified.address.isEnabled)
                assertTrue(verified.button.isEnabled)
                coVerify(exactly = 0) { recover(any(), any()) }
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }
}
