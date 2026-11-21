package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.global.hideErasedWallet
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletCoordinatorResetGateTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun persistedEraseKeepsAnUnclearedWalletOutOfTheSdkAcrossRelaunch() = runTest {
        val preferences = MutableStateFlow<PersistableWallet?>(mockk())
        val wallet = preferences.value
        val store = progressStore()
        val sdkWallet = preferences.hideErasedWallet(store)

        store.store(ResetStage.ERASE_SDK)
        assertEquals(wallet, sdkWallet.first())
        store.store(ResetStage.CLEAR_PREFERENCES)
        assertNull(sdkWallet.first())

        val resumed = progressStore()
        val resumedSdkWallet = preferences.hideErasedWallet(resumed)
        assertTrue(resumed.isPending.value)
        assertNull(resumedSdkWallet.first())

        preferences.value = null
        resumed.store(null)
        val nextWallet = mockk<PersistableWallet>()
        preferences.value = nextWallet
        assertEquals(nextWallet, resumedSdkWallet.first())
    }

    @Test
    fun failedEraseCheckpointSyncKeepsTheSdkClosedAndRetryCanPersistTheCheckpoint() = runTest {
        var allowSync = true
        val store = progressStore {
            if (!allowSync) throw IOException("Synthetic reset marker flush failure")
        }
        val preferences = MutableStateFlow<PersistableWallet?>(mockk())
        val sdkWallet = preferences.hideErasedWallet(store)
        store.store(ResetStage.ERASE_SDK)
        allowSync = false

        assertFailsWith<IOException> { store.store(ResetStage.CLEAR_PREFERENCES) }
        assertEquals(ResetStage.CLEAR_PREFERENCES, store.getStage())
        assertNull(sdkWallet.first())
        assertTrue(progressStore().isPending.value)

        allowSync = true
        store.store(ResetStage.CLEAR_PREFERENCES)
        assertEquals(ResetStage.CLEAR_PREFERENCES, progressStore().getStage())
        assertNull(preferences.hideErasedWallet(progressStore()).first())
    }

    private fun progressStore(sync: DirectorySync = DirectorySync {}): ResetProgressStore =
        ResetProgressStore(
            context = mockk<Context> { every { noBackupFilesDir } returns directory.root },
            directorySync = sync
        )
}
