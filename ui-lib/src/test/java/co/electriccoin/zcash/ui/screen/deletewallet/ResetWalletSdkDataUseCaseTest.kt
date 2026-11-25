package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResetWalletSdkDataUseCaseTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun delayedResultSurvivesTimeoutAndRetryJoinsTheSameErase() = runTest {
        val result = CompletableDeferred<Boolean>()
        val coordinator = mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flow { emit(result.await()) }
        }
        val reset = sdkDataResetFixture(directory.root, coordinator).also { it.scope = backgroundScope }

        assertFailsWith<IllegalStateException> { reset() }
        assertFailsWith<IllegalStateException> { reset() }
        result.complete(true)
        reset()

        verify(exactly = 1) { coordinator.deleteSdkDataFlow() }
    }

    @Test
    fun completedEraseWithAClassicSidecarCanBeRetried() = runTest {
        val coordinator = mockk<WalletCoordinator> { every { deleteSdkDataFlow() } returns flowOf(true) }
        val reset = sdkDataResetFixture(directory.root, coordinator)
        val sidecar = File(directory.root, "ZcashSdk_${ZcashNetwork.Testnet.networkName}_Data.db-wal")
        sidecar.writeText("synthetic residue")

        assertFailsWith<IllegalStateException> { reset() }
        assertTrue(sidecar.delete())
        reset()

        verify(exactly = 2) { coordinator.deleteSdkDataFlow() }
    }
}

internal fun sdkDataResetFixture(directory: File, coordinator: WalletCoordinator): ResetWalletSdkDataUseCase {
    val context = mockk<Context> {
        every { noBackupFilesDir } returns directory
        every { getDatabasePath(any()) } answers { File(directory, firstArg<String>()) }
    }
    val walletProvider = mockk<PersistableWalletProvider> {
        coEvery { getPersistableWallet() } returns mockk { every { network } returns ZcashNetwork.Testnet }
    }
    return ResetWalletSdkDataUseCase(context, coordinator, walletProvider)
}
