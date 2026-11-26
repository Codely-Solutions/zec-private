package co.electriccoin.zcash.ui.common.provider

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.global.WalletSdkTransportGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WalletSeedRecoveryTest {
    private val gate = WalletSdkTransportGate()
    private val engine = MutableStateFlow<Synchronizer?>(null)
    private val mismatch = MutableStateFlow(true)
    private val coordinator = mockk<WalletCoordinator> {
        every { synchronizer } returns engine
        every { isSeedMismatch } returns mismatch
    }
    private val recovery = WalletSeedRecovery(coordinator, gate)

    @Test
    fun acknowledgedWithdrawalRereadsExistingWalletWithoutDeletingSdkData() = runTest {
        backgroundScope.observeWithdrawal()
        runCurrent()
        var reread = false

        assertEquals(true, recovery.reopen({ true }) {
            assertTrue(gate.inputSuspended)
            reread = true
        })

        assertTrue(reread)
        assertFalse(gate.inputSuspended)
        verify(exactly = 0) { coordinator.deleteSdkDataFlow() }
    }

    @Test
    fun anExistingTransportRestartDefersRetryToTheNextProcess() = runTest {
        val generation = MutableStateFlow(0L)
        val wallet = mockk<PersistableWallet>()
        val reads = MutableStateFlow(WalletSdkRead(0L, wallet))
        backgroundScope.launch { gate.walletInput(gate.walletDecisions(reads, generation), generation).collect {} }
        backgroundScope.launch { gate.transportInput(MutableStateFlow(false), generation).collect {} }
        runCurrent()
        gate.recordCommittedMode(usesTor = true)

        assertNull(recovery.reopen({ true }) { error("Unexpected read during mode restart") })
        assertTrue(gate.restartRequired.value)
    }

    @Test
    fun activeEngineOrMissingWithdrawalCannotAdmitRetry() = runTest {
        assertEquals(false, recovery.reopen({ true }) { error("Stale null was accepted") })
        assertFalse(gate.inputSuspended)
        engine.value = mockk()
        backgroundScope.observeWithdrawal()
        runCurrent()

        assertEquals(false, recovery.reopen({ true }) { error("Active engine was accepted") })
        assertFalse(gate.inputSuspended)
    }

    @Test
    fun changedWalletOwnerCancelsRetryAfterWithdrawal() = runTest {
        backgroundScope.observeWithdrawal()
        runCurrent()

        assertNull(recovery.reopen({ !gate.inputSuspended }) { error("Old wallet triggered a read") })
        assertFalse(gate.inputSuspended)
    }

    private fun CoroutineScope.observeWithdrawal() = launch {
        val generation = MutableStateFlow(0L)
        val reads = MutableStateFlow(WalletSdkRead(0L, null))
        gate.walletInput(gate.walletDecisions(reads, generation), generation).collect { wallet ->
            if (wallet == null && gate.inputSuspended) mismatch.value = false
        }
    }
}
