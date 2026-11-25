package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import cash.z.ecc.android.sdk.model.PersistableWallet
import co.electriccoin.zcash.global.WalletMigrationSyncGate
import co.electriccoin.zcash.global.hideResetWallet
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WalletCoordinatorResetGateTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun persistedEraseKeepsAnUnclearedWalletOutOfTheSdkAcrossRelaunch() = runTest {
        val preferences = MutableStateFlow<PersistableWallet?>(mockk())
        val wallet = preferences.value
        val store = progressStore()
        val sdkWallet = preferences.hideResetWallet(store)

        store.store(ResetStage.ERASE_SDK)
        assertEquals(wallet, sdkWallet.first())
        assertNull(preferences.hideResetWallet(progressStore()).first())

        store.suppressSdkInput()
        assertNull(sdkWallet.first())
        assertEquals(ResetStage.ERASE_SDK, store.getStage())
        store.store(ResetStage.CLEAR_PREFERENCES)
        assertNull(sdkWallet.first())

        val resumed = progressStore()
        val resumedSdkWallet = preferences.hideResetWallet(resumed)
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
        val sdkWallet = preferences.hideResetWallet(store)
        store.store(ResetStage.ERASE_SDK)
        allowSync = false

        assertFailsWith<IOException> { store.store(ResetStage.CLEAR_PREFERENCES) }
        assertEquals(ResetStage.CLEAR_PREFERENCES, store.getStage())
        assertNull(sdkWallet.first())
        assertTrue(progressStore().isPending.value)

        allowSync = true
        store.store(ResetStage.CLEAR_PREFERENCES)
        assertEquals(ResetStage.CLEAR_PREFERENCES, progressStore().getStage())
        assertNull(preferences.hideResetWallet(progressStore()).first())
    }

    @Test
    fun releasingTheGateReadsStorageBeforeAnyDelayedPreferenceNotification() = runTest {
        val store = progressStore()
        val original = mockk<PersistableWallet>()
        var storedWallet: PersistableWallet? = original
        val revision = MutableStateFlow(0)
        val preferences = flow { revision.collect { emit(storedWallet) } }
        val observed = Channel<PersistableWallet?>(Channel.UNLIMITED)
        backgroundScope.launch { preferences.hideResetWallet(store).collect { observed.send(it) } }

        assertSame(original, observed.receive())
        store.store(ResetStage.CLEAR_PREFERENCES)
        assertNull(observed.receive())

        storedWallet = null
        store.store(null)
        // Opening the gate must read the cleared store despite the delayed notification.
        assertNull(observed.receive())

        val restored = mockk<PersistableWallet>()
        storedWallet = restored
        revision.value += 1
        assertSame(restored, observed.receive())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun explicitErasureWaitsForMigrationPreferenceAccessToFinishAfterSuppression() = runTest {
        val store = progressStore()
        val preferences = MutableStateFlow<PersistableWallet?>(mockk())
        val entered = CompletableDeferred<Unit>()
        val draining = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        val states = Channel<Boolean>(Channel.UNLIMITED)
        val syncBlock = WalletMigrationSyncGate().observe(preferences.hideResetWallet(store), store) {
            flow {
                try {
                    entered.complete(Unit)
                    emit(true)
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        draining.complete(Unit)
                        drained.await()
                    }
                }
            }
        }
        backgroundScope.launch { syncBlock.collect { states.send(it) } }
        assertTrue(states.receive())
        entered.await()

        store.store(ResetStage.ERASE_SDK)
        store.suppressSdkInput()
        try {
            draining.await()
            val erase = async { store.withMigrationInputStopped { true } }
            runCurrent()
            assertFalse(erase.isCompleted)
            drained.complete(Unit)
            assertTrue(erase.await())
            assertFalse(states.receive())
        } finally {
            drained.complete(Unit)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun migrationPreferenceFailurePausesSyncAndClearsAfterAnAuthoritativeResult() = runTest {
        val store = progressStore()
        val wallet = MutableStateFlow<PersistableWallet?>(mockk())
        val gate = WalletMigrationSyncGate()
        var opens = 0
        val states = Channel<Boolean>(Channel.UNLIMITED)
        backgroundScope.launch {
            gate.observe(wallet.hideResetWallet(store), store) {
                if (++opens == 1) throw IOException("Synthetic unavailable store")
                flowOf(false)
            }.collect { states.send(it) }
        }

        assertTrue(states.receive())
        runCurrent()
        assertSame(SynchronizerError.MigrationStateUnavailable, gate.error.value)
        assertFalse(states.receive())
        assertNull(gate.error.value)
        assertEquals(2, opens)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistentMigrationReadFailureStaysPausedUntilTheWalletIsWithdrawn() = runTest {
        val store = progressStore()
        val wallet = MutableStateFlow<PersistableWallet?>(mockk())
        val gate = WalletMigrationSyncGate()
        var opens = 0
        val states = Channel<Boolean>(Channel.UNLIMITED)
        backgroundScope.launch {
            gate.observe(wallet.hideResetWallet(store), store) {
                opens += 1
                flow { throw IOException("Synthetic unavailable store") }
            }.collect { states.send(it) }
        }

        assertTrue(states.receive())
        advanceTimeBy(10.seconds)
        assertEquals(3, opens)
        assertSame(SynchronizerError.MigrationStateUnavailable, gate.error.value)
        assertTrue(states.tryReceive().isFailure)

        store.store(ResetStage.ERASE_SDK)
        store.suppressSdkInput()
        assertFalse(states.receive())
        assertNull(gate.error.value)
    }

    private fun progressStore(sync: DirectorySync = DirectorySync {}): ResetProgressStore =
        ResetProgressStore(
            context = mockk<Context> { every { noBackupFilesDir } returns directory.root },
            directorySync = sync
        )
}
