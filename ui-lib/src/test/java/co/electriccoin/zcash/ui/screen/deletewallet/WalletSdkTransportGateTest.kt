package co.electriccoin.zcash.ui.screen.deletewallet

import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.global.WalletSdkTransportGate
import co.electriccoin.zcash.global.WalletSdkTransportRead
import co.electriccoin.zcash.ui.common.provider.WalletSdkRead
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
    fun retryWithdrawsTheOldWalletBeforeEmittingTheNewTorChoice() = runTest {
        val gate = WalletSdkTransportGate()
        val oldWallet = mockk<PersistableWallet>()
        val newWallet = mockk<PersistableWallet>()
        val wallets = MutableStateFlow(WalletSdkRead(0, oldWallet))
        val generation = MutableStateFlow(0L)
        var useTor = false
        val sdkInputs = mutableListOf<Pair<PersistableWallet?, Boolean?>>()
        backgroundScope.launch {
            combine(
                gate.walletInput(gate.walletDecisions(wallets, generation)),
                gate.transportInput(flow { emit(useTor) }, generation),
            ) { wallet, tor -> wallet to tor }.collect { sdkInputs += it }
        }
        runCurrent()
        assertEquals(oldWallet to false, sdkInputs.last())

        useTor = true
        generation.value++
        runCurrent()
        assertEquals(null to true, sdkInputs.last())
        wallets.value = WalletSdkRead(generation.value, newWallet)
        runCurrent()

        assertEquals(newWallet to true, sdkInputs.last())
        assertFalse(sdkInputs.contains(oldWallet to true))
        assertFalse(sdkInputs.contains(newWallet to false))
    }

    @Test
    fun unreadableTransportWithholdsWalletAndRetryDeliversOnlyVerifiedChoice() = runTest {
        val gate = WalletSdkTransportGate()
        val wallet = mockk<PersistableWallet>()
        val wallets = MutableStateFlow(WalletSdkRead(0, wallet))
        val generation = MutableStateFlow(0L)
        var nextRead = Result.failure<Boolean?>(IllegalStateException("Synthetic unavailable preference holder"))
        val transportReads = flow { emit(nextRead.getOrThrow()) }
        val sdkInputs = mutableListOf<Pair<PersistableWallet?, Boolean?>>()

        backgroundScope.launch {
            combine(
                gate.walletInput(gate.walletDecisions(wallets, generation)),
                gate.transportInput(transportReads, generation),
            ) {
                sdkWallet, tor -> sdkWallet to tor
            }.collect { sdkInputs += it }
        }
        runCurrent()

        assertEquals(WalletSdkTransportRead(0L, verified = false), gate.readiness.value)
        assertTrue(sdkInputs.isEmpty())

        nextRead = Result.success(true)
        generation.value++
        runCurrent()

        assertEquals(WalletSdkTransportRead(1L, verified = true), gate.readiness.value)
        assertEquals(null to true, sdkInputs.last())
        wallets.value = WalletSdkRead(generation.value, wallet)
        runCurrent()
        assertEquals(wallet to true, sdkInputs.last())

        nextRead = Result.failure(IllegalStateException("Synthetic unavailable transport setting"))
        generation.value++
        runCurrent()

        assertEquals(WalletSdkTransportRead(2L, verified = false), gate.readiness.value)
        assertNull(sdkInputs.last().first)
        assertTrue(sdkInputs.all { it.second == true })

        nextRead = Result.success(false)
        generation.value++
        runCurrent()

        assertEquals(WalletSdkTransportRead(3L, verified = true), gate.readiness.value)
        assertEquals(null to false, sdkInputs.last())
        wallets.value = WalletSdkRead(generation.value, wallet)
        runCurrent()
        assertEquals(wallet to false, sdkInputs.last())
    }
}
