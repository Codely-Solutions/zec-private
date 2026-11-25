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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WalletSdkTransportGateTest {
    @Test
    fun walletlessSetupCanSwitchTheVerifiedTorChoiceBeforeFirstWalletAdmission() = runTest {
        val gate = WalletSdkTransportGate()
        val newWallet = mockk<PersistableWallet>()
        val wallets = MutableStateFlow(WalletSdkRead(0, null))
        val generation = MutableStateFlow(0L)
        val useTor = MutableStateFlow(false)
        val sdkInputs = mutableListOf<Pair<PersistableWallet?, Boolean?>>()
        backgroundScope.launch {
            combine(
                gate.walletInput(gate.walletDecisions(wallets, generation)),
                gate.transportInput(useTor, generation),
            ) { wallet, tor -> wallet to tor }.collect { sdkInputs += it }
        }
        runCurrent()
        assertEquals(null to false, sdkInputs.last())

        useTor.value = true
        runCurrent()
        assertEquals(null to true, sdkInputs.last())
        assertTrue(sdkInputs.all { it.first == null })
        wallets.value = WalletSdkRead(generation.value, newWallet)
        runCurrent()

        assertEquals(newWallet to true, sdkInputs.last())
        assertFalse(sdkInputs.contains(newWallet to false))
    }

    @Test
    fun restoredWalletCannotReachSdkBeforeTheDurablyChosenModeArrives() = runTest {
        val gate = WalletSdkTransportGate()
        val generation = MutableStateFlow(0L)
        val wallets = MutableStateFlow(WalletSdkRead(0L, null))
        val preference = MutableStateFlow(false)
        val observed = mutableListOf<Pair<PersistableWallet?, Boolean?>>()
        backgroundScope.launch {
            combine(
                gate.walletInput(gate.walletDecisions(wallets, generation)),
                gate.transportInput(preference, generation),
            ) { wallet, tor -> wallet to tor }.collect { observed += it }
        }
        runCurrent()
        assertEquals(null to false, observed.last())

        val restored = mockk<PersistableWallet>()
        gate.requireWalletMode(true)
        wallets.value = WalletSdkRead(0L, restored)
        runCurrent()
        assertTrue(observed.all { it.first == null })

        preference.value = true
        runCurrent()
        assertEquals(restored to true, observed.last())
        assertFalse(gate.restartRequired.value)
    }

    @Test
    fun resetAndOppositeModeRestoreCannotReuseTheOldEngine() = runTest {
        for (original in listOf(false, true)) {
            val gate = WalletSdkTransportGate()
            val generation = MutableStateFlow(0L)
            val previous = mockk<PersistableWallet>()
            val current = mockk<PersistableWallet>()
            val wallets = MutableStateFlow(WalletSdkRead(0L, previous))
            val preference = MutableStateFlow(original)
            val observed = mutableListOf<Pair<PersistableWallet?, Boolean?>>()
            val collector = backgroundScope.launch {
                combine(
                    gate.walletInput(gate.walletDecisions(wallets, generation)),
                    gate.transportInput(preference, generation),
                ) { wallet, tor -> wallet to tor }.collect { observed += it }
            }
            runCurrent()
            assertEquals(previous to original, observed.last())
            wallets.value = WalletSdkRead(0L, null)
            runCurrent()

            // The wallet store can notify before the independent preferences observer.
            gate.requireWalletMode(!original)
            wallets.value = WalletSdkRead(0L, current)
            runCurrent()
            assertEquals(null to original, observed.last())
            preference.value = !original
            gate.recordCommittedMode(!original)
            runCurrent()
            assertTrue(gate.restartRequired.value)
            assertTrue(observed.none { it.first === current })
            collector.cancel()
        }
    }

    @Test
    fun committedModeChangeWithdrawsTheOldEngineUntilARealNewProcess() = runTest {
        val gate = WalletSdkTransportGate()
        val wallet = mockk<PersistableWallet>()
        val wallets = MutableStateFlow(WalletSdkRead(0, wallet))
        val generation = MutableStateFlow(0L)
        val stored = MutableStateFlow(false)
        val sdkInputs = mutableListOf<Pair<PersistableWallet?, Boolean?>>()
        fun inputs(owner: WalletSdkTransportGate) = combine(
            owner.walletInput(owner.walletDecisions(wallets, generation)),
            owner.transportInput(stored, generation),
        ) { savedWallet, tor -> savedWallet to tor }
        backgroundScope.launch { inputs(gate).collect { sdkInputs += it } }
        runCurrent()
        assertEquals(wallet to false, sdkInputs.last())

        stored.value = true
        runCurrent()
        assertEquals(wallet to false, sdkInputs.last())
        assertFalse(gate.restartRequired.value)
        assertFailsWith<IllegalStateException> { gate.torForRequest(true) }
        stored.value = false
        runCurrent()
        assertFalse(gate.torForRequest(false))

        stored.value = true
        gate.recordCommittedMode(true)
        runCurrent()
        assertTrue(gate.restartRequired.value)
        assertEquals(null to false, sdkInputs.last())
        assertFailsWith<IllegalStateException> { gate.torForRequest(false) }
        assertFailsWith<IllegalStateException> { gate.torForRequest(true) }

        generation.value++
        wallets.value = WalletSdkRead(generation.value, wallet)
        runCurrent()
        assertEquals(null to false, sdkInputs.last())
        assertTrue(sdkInputs.none { it == wallet to true })
        val replacement = WalletSdkTransportGate()
        assertEquals(wallet to true, inputs(replacement).first { it.first != null })
        assertFalse(replacement.restartRequired.value)
        assertTrue(replacement.torForRequest(true))
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

        nextRead = Result.success(true)
        generation.value++
        runCurrent()

        assertEquals(WalletSdkTransportRead(3L, verified = true), gate.readiness.value)
        assertEquals(null to true, sdkInputs.last())
        wallets.value = WalletSdkRead(generation.value, wallet)
        runCurrent()
        assertEquals(wallet to true, sdkInputs.last())
    }
}
