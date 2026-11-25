package co.electriccoin.zcash.ui.screen.hotfix.ephemeral

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.PendingNavigation
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.usecase.FixEphemeralAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.DiscoveryOutcome
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import io.mockk.coEvery
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EphemeralHotfixVMTest {
    @Test
    fun completionFromARevokedGrantDisappearsFromTheRetainedDialog() {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val owner = MutableStateFlow(true)
                val tor = mockk<IsTorEnabledStorageProvider> { every { observe() } returns flowOf(true) }
                val recover = mockk<FixEphemeralAddressUseCase> {
                    coEvery { this@mockk.invoke(any(), "discover") } returns
                        DiscoveryOutcome.Confirmed({ owner.value }, owner)
                }
                val pending = PendingNavigation()
                val navigation = mockk<NavigationRouter>(relaxed = true) {
                    every { activeEntry } returns MutableStateFlow(ActiveNavigationEntry("discover", "Discover"))
                    every { pendingNavigation } returns pending
                }
                val vm = EphemeralHotfixVM(
                    EphemeralHotfixArgs("synthetic-address"), tor, navigation, recover, "discover",
                )
                backgroundScope.launch { vm.state.collect { } }
                assertNotNull(vm.state.value).button.onClick()
                val completed = assertNotNull(vm.state.value)
                assertEquals(stringRes(R.string.ephemeral_recovery_success), completed.info)
                assertFalse(completed.address.isEnabled)
                completed.button.onClick()
                assertNotNull(pending.commands.value["discover"])
                verify(exactly = 0) { navigation.back() }

                owner.value = false
                val reopened = assertNotNull(vm.state.value)
                assertNull(reopened.info)
                assertTrue(reopened.address.isEnabled)
                assertEquals(stringRes("Recover Funds"), reopened.button.text)
                vm.viewModelScope.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

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
