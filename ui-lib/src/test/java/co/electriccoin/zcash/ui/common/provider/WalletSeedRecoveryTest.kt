package co.electriccoin.zcash.ui.common.provider

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.global.WalletSdkTransportGate
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import com.zodl.slipstream.SlipstreamSynchronizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WalletSeedRecoveryTest {
    @get:Rule val directory = TemporaryFolder()

    private val network = ZcashNetwork.Testnet
    private val gate = WalletSdkTransportGate()
    private val selected = MutableStateFlow<Synchronizer?>(null)
    private val mismatch = MutableStateFlow(true)
    private val coordinator = mockk<WalletCoordinator> {
        every { synchronizer } returns selected
        every { isSeedMismatch } returns mismatch
    }
    private val context: Context = mockk {
        every { noBackupFilesDir } answers { directory.root }
        every { getDatabasePath(any()) } answers { File(directory.root, firstArg<String>()) }
    }
    private val recovery
        get() = WalletSeedRecovery(context, coordinator, gate, ResetProgressStore(context, DirectorySync {}))

    @BeforeTest
    fun setUp() {
        mockkObject(Synchronizer.Companion)
        mockkObject(SlipstreamSynchronizer.Companion)
    }

    @AfterTest
    fun tearDown() {
        unmockkObject(Synchronizer.Companion)
        unmockkObject(SlipstreamSynchronizer.Companion)
    }

    @Test
    fun retryWithdrawnInputStillErasesAndRestoresUnderTheGate() = runTest {
        backgroundScope.observeSdkWithdrawal()
        runCurrent()
        coEvery { SlipstreamSynchronizer.erase(context, network) } coAnswers {
            assertTrue(gate.inputSuspended)
            true
        }
        coEvery { Synchronizer.erase(context, network) } returns true
        val order = mutableListOf<String>()

        assertEquals(true, recovery.eraseAndRestore(network, { order += "enter" }) {
            assertTrue(gate.inputSuspended)
            order += "prepare"
            true
        })

        assertEquals(listOf("prepare", "enter"), order)
        assertFalse(gate.inputSuspended)
        verify(exactly = 0) { coordinator.deleteSdkDataFlow() }
    }

    @Test
    fun transportRestartDefersRecoveryUntilTheNextProcess() = runTest {
        val generation = MutableStateFlow(0L)
        val wallet = mockk<PersistableWallet>()
        val reads = MutableStateFlow(WalletSdkRead(0L, wallet))
        var admitted: PersistableWallet? = null
        backgroundScope.launch {
            gate.walletInput(gate.walletDecisions(reads, generation), generation).collect { admitted = it }
        }
        backgroundScope.launch { gate.transportInput(MutableStateFlow(false), generation).collect {} }
        runCurrent()
        assertEquals(wallet, admitted)
        gate.recordCommittedMode(usesTor = true)

        assertNull(recovery.eraseAndRestore(network, { error("erase entered") }) { error("restored") })

        assertTrue(gate.restartRequired.value)
        coVerify(exactly = 0) { SlipstreamSynchronizer.erase(any(), any()) }
    }

    @Test
    fun activeSdkThatDoesNotCloseDefinitivelyRefusesErasure() = runTest {
        selected.value = mockk()
        backgroundScope.observeSdkWithdrawal()
        runCurrent()

        assertEquals(false, recovery.eraseAndRestore(network, { error("erase entered") }) { error("restored") })

        assertFalse(gate.inputSuspended)
        coVerify(exactly = 0) { SlipstreamSynchronizer.erase(any(), any()) }
    }

    @Test
    fun inactiveApiRefusalRetainsThePreparedRestoreForRetry() = runTest {
        backgroundScope.observeSdkWithdrawal()
        runCurrent()
        coEvery { SlipstreamSynchronizer.erase(context, network) } returns false

        var prepared = false
        assertEquals(false, recovery.eraseAndRestore(network, { assertTrue(prepared) }) {
            prepared = true
            true
        })

        assertTrue(prepared)
        assertFalse(gate.inputSuspended)
        coVerify(exactly = 0) { Synchronizer.erase(any(), any()) }
    }

    @Test
    fun inactiveSdkIsLeftIntactWhenRestorePreparationIsRefused() = runTest {
        backgroundScope.observeSdkWithdrawal()
        runCurrent()

        assertNull(recovery.eraseAndRestore(network, { error("erase entered") }) { false })

        assertFalse(gate.inputSuspended)
        coVerify(exactly = 0) { SlipstreamSynchronizer.erase(any(), any()) }
    }

    @Test
    fun staleNullSynchronizerDoesNotConfirmCoordinatorWithdrawal() = runTest {
        assertEquals(false, recovery.eraseAndRestore(network, { error("erase entered") }) { error("restored") })

        assertFalse(gate.inputSuspended)
        coVerify(exactly = 0) { SlipstreamSynchronizer.erase(any(), any()) }
    }

    private fun CoroutineScope.observeSdkWithdrawal() = launch {
        val generation = MutableStateFlow(0L)
        val reads = MutableStateFlow(WalletSdkRead(0L, null))
        gate.walletInput(gate.walletDecisions(reads, generation), generation).collect { wallet ->
            if (wallet == null && gate.inputSuspended) mismatch.value = false
        }
    }
}
