package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.global.WalletSdkTransportGate
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WalletSdkTransportGateTest {
    @Test
    fun unreadableTransportWithholdsWalletAndRetryDeliversOnlyVerifiedChoice() = runTest {
        val gate = WalletSdkTransportGate()
        val wallet = mockk<PersistableWallet>()
        val wallets = MutableStateFlow<PersistableWallet?>(wallet)
        val generation = MutableStateFlow(0L)
        var nextRead = Result.failure<Boolean?>(IllegalStateException("Synthetic unavailable preference holder"))
        val transportReads = flow { emit(nextRead.getOrThrow()) }
        val sdkInputs = mutableListOf<Pair<PersistableWallet?, Boolean?>>()

        backgroundScope.launch {
            combine(gate.walletInput(wallets, generation), gate.transportInput(transportReads, generation)) {
                sdkWallet, tor -> sdkWallet to tor
            }.collect { sdkInputs += it }
        }
        runCurrent()

        assertTrue(gate.unavailable.value)
        assertTrue(sdkInputs.isEmpty())

        nextRead = Result.success(true)
        generation.value++
        runCurrent()

        assertFalse(gate.unavailable.value)
        assertEquals(wallet to true, sdkInputs.last())

        nextRead = Result.failure(IllegalStateException("Synthetic unavailable transport setting"))
        generation.value++
        runCurrent()

        assertTrue(gate.unavailable.value)
        assertNull(sdkInputs.last().first)
        assertTrue(sdkInputs.all { it.second == true })

        nextRead = Result.success(false)
        generation.value++
        runCurrent()

        assertFalse(gate.unavailable.value)
        assertEquals(wallet to false, sdkInputs.last())
    }
}
