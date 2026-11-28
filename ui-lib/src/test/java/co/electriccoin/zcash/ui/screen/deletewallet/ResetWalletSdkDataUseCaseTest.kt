package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.SdkEncryptedPreferenceRecoveryProvider
import com.zodl.slipstream.SlipstreamSynchronizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResetWalletSdkDataUseCaseTest {
    @get:Rule val directory = TemporaryFolder()

    private val network = ZcashNetwork.Testnet
    private val synchronizer = MutableStateFlow<Synchronizer?>(null)
    private val coordinator = mockk<WalletCoordinator> {
        every { synchronizer } returns this@ResetWalletSdkDataUseCaseTest.synchronizer
    }
    private val preferenceRecovery = mockk<SdkEncryptedPreferenceRecoveryProvider>(relaxUnitFun = true)

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

        useCase(context)()

        coVerifyOrder {
            preferenceRecovery.ensureReadable()
            SlipstreamSynchronizer.erase(context, network)
            Synchronizer.erase(context, network)
        }
    }

    @Test
    fun absentClassicFilesCannotMaskEngineFailure() = runBlocking {
        val context = fileContext()
        coEvery { SlipstreamSynchronizer.erase(context, network) } returns false

        assertFailsWith<IllegalStateException> {
            useCase(context)()
        }

        coVerify(exactly = 0) { Synchronizer.erase(context, network) }
    }

    @Test
    fun resetWaitsForAnActiveSynchronizerToCloseBeforeErasing() = runTest {
        synchronizer.value = mockk()
        val context = fileContext()

        assertFailsWith<IllegalStateException> {
            useCase(context)()
        }

        coVerify(exactly = 0) { SlipstreamSynchronizer.erase(context, network) }
        coVerify(exactly = 0) { Synchronizer.erase(context, network) }
    }

    @Test
    fun resetDeletesKnownOrphanSidecarsAfterBothSdkErasers() = runBlocking {
        val context = fileContext()
        coEvery { SlipstreamSynchronizer.erase(context, network) } returns true
        coEvery { Synchronizer.erase(context, network) } returns false
        val oldRoot = directory.newFolder("co.electricoin.zcash")
        val orphaned = listOf(
            File(directory.root, "ZcashSdk_${network.networkName}_Data.db-wal"),
            File(directory.root, "ZcashSdk_${network.networkName}_Cache.db-journal"),
            File(oldRoot, "zcash_sdk_${network.networkName}_cache.sqlite3-shm"),
        )
        orphaned.forEach { it.writeText("synthetic residue") }
        val unrelated = File(directory.root, "another-account.db-wal").apply { writeText("synthetic sentinel") }

        useCase(context)()

        orphaned.forEach { assertFalse(it.exists()) }
        assertTrue(unrelated.isFile)
        coVerify(exactly = 1) { Synchronizer.erase(context, network) }
    }

    @Test
    fun oldBaseDatabaseUsesTheSdkAgainAfterItErasesThePreferredDatabase() = runBlocking {
        val context = fileContext()
        coEvery { SlipstreamSynchronizer.erase(context, network) } returns true
        val preferred = File(
            directory.newFolder("co.electricoin.zcash"),
            "zcash_sdk_${network.networkName}_data.sqlite3"
        ).apply { writeText("current synthetic database") }
        val legacy = File(directory.root, "ZcashSdk_${network.networkName}_Data.db")
            .apply { writeText("old synthetic database") }
        coEvery { Synchronizer.erase(context, network) } coAnswers {
            if (preferred.exists()) preferred.delete() else legacy.delete()
        }

        useCase(context)()

        assertFalse(preferred.exists())
        assertFalse(legacy.exists())
        coVerify(exactly = 2) { Synchronizer.erase(context, network) }
    }

    @Test
    fun unexpectedClassicSidecarDirectoryBlocksCompletionAndRetryCanRecheck() = runBlocking {
        val context = fileContext()
        coEvery { SlipstreamSynchronizer.erase(context, network) } returns true
        coEvery { Synchronizer.erase(context, network) } returns true
        val reset = useCase(context)
        val sidecar = File(directory.root, "ZcashSdk_${network.networkName}_Data.db-wal")
        assertTrue(sidecar.mkdir())

        assertFailsWith<IllegalStateException> { reset() }
        assertTrue(sidecar.delete())
        reset()

        coVerify(exactly = 2) { Synchronizer.erase(context, network) }
    }

    @Test
    fun preferenceRepairFailureLeavesErasureForAnExplicitRetry() = runBlocking {
        val context = fileContext()
        coEvery { preferenceRecovery.ensureReadable() } throws IllegalStateException("Synthetic unavailable store")

        assertFailsWith<IllegalStateException> { useCase(context)() }

        coVerify(exactly = 0) { SlipstreamSynchronizer.erase(context, network) }
        coVerify(exactly = 0) { Synchronizer.erase(context, network) }
    }

    private suspend fun useCase(context: Context): ResetWalletSdkDataUseCase {
        val progress = ResetProgressStore(context, DirectorySync {})
        progress.store(ResetStage.ERASE_SDK)
        progress.suppressSdkInput()
        return ResetWalletSdkDataUseCase(context, coordinator, preferenceRecovery, progress)
    }

    private fun fileContext(): Context = mockk {
        every { noBackupFilesDir } returns directory.root
        every { resources } returns mockk {
            every { getBoolean(cash.z.ecc.sdk.ext.R.bool.zcash_is_testnet) } returns true
        }
        every { getDatabasePath(any()) } answers { File(directory.root, firstArg<String>()) }
    }
}
