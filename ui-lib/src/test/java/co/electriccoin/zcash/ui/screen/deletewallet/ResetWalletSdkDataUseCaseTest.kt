package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import com.zodl.slipstream.SlipstreamSynchronizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResetWalletSdkDataUseCaseTest {
    @get:Rule val directory = TemporaryFolder()

    private val network = ZcashNetwork.Testnet
    private val synchronizer = MutableStateFlow<Synchronizer?>(null)
    private val coordinator = mockk<WalletCoordinator> {
        every { synchronizer } returns this@ResetWalletSdkDataUseCaseTest.synchronizer
    }
    private val walletProvider = mockk<PersistableWalletProvider> {
        coEvery { getPersistableWallet() } returns mockk { every { network } returns ZcashNetwork.Testnet }
    }

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
    fun resetAcceptsAlreadyAbsentClassicFilesAfterEngineErasure() = runBlocking {
        val context = fileContext()
        coEvery { SlipstreamSynchronizer.erase(context, network) } returns true
        coEvery { Synchronizer.erase(context, network) } returns false

        ResetWalletSdkDataUseCase(context, coordinator, walletProvider)()

        coVerify(exactly = 1) { Synchronizer.erase(context, network) }
    }

    @Test
    fun absentClassicFilesCannotMaskEngineFailure() = runBlocking {
        val context = fileContext()
        coEvery { SlipstreamSynchronizer.erase(context, network) } returns false

        assertFailsWith<IllegalStateException> {
            ResetWalletSdkDataUseCase(context, coordinator, walletProvider)()
        }

        coVerify(exactly = 0) { Synchronizer.erase(context, network) }
    }

    @Test
    fun resetWaitsForAnActiveSynchronizerToCloseBeforeErasing() = runTest {
        synchronizer.value = mockk()
        val context = fileContext()

        assertFailsWith<IllegalStateException> {
            ResetWalletSdkDataUseCase(context, coordinator, walletProvider)()
        }

        coVerify(exactly = 0) { SlipstreamSynchronizer.erase(context, network) }
        coVerify(exactly = 0) { Synchronizer.erase(context, network) }
    }

    @Test
    fun survivingClassicSidecarBlocksCompletionAndRetryCanRecheck() = runBlocking {
        val context = fileContext()
        coEvery { SlipstreamSynchronizer.erase(context, network) } returns true
        coEvery { Synchronizer.erase(context, network) } returns true
        val reset = ResetWalletSdkDataUseCase(context, coordinator, walletProvider)
        val sidecar = File(directory.root, "ZcashSdk_${network.networkName}_Data.db-wal")
        sidecar.writeText("synthetic residue")

        assertFailsWith<IllegalStateException> { reset() }
        assertTrue(sidecar.delete())
        reset()

        coVerify(exactly = 2) { Synchronizer.erase(context, network) }
    }

    private fun fileContext(): Context = mockk {
        every { noBackupFilesDir } returns directory.root
        every { getDatabasePath(any()) } answers { File(directory.root, firstArg<String>()) }
    }
}
