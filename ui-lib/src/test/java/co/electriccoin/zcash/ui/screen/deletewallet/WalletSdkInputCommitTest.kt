package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import android.content.SharedPreferences
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.global.hideResetWallet
import co.electriccoin.zcash.preference.AndroidPreferenceProvider
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProviderImpl
import co.electriccoin.zcash.ui.common.provider.StoredWalletAvailability
import co.electriccoin.zcash.ui.common.provider.StoredWalletDecodeException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalletSdkInputCommitTest {
    @get:Rule val directory = TemporaryFolder()

    private val candidate = PersistableWallet(
        network = ZcashNetwork.Mainnet,
        endpoint = LightWalletEndpoint("fixture.invalid", 443, true),
        birthday = null,
        seedPhrase = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "abandon" }),
        walletInitMode = WalletInitMode.NewWallet
    )

    @Test
    fun aFailedSeedCommitPublishesOnlyAfterAnAcknowledgedRetry() = runTest {
        val storage = CommitFixture()
        val walletProvider = provider(storage)
        val sdkWallets = observeSdkInput(walletProvider)

        assertFailsWith<IOException> { walletProvider.store(candidate) }
        assertUncommitted(walletProvider, sdkWallets, storage)

        storage.failCommit = false
        walletProvider.store(candidate)
        runCurrent()

        assertEquals(listOf(null, candidate.seedPhrase), sdkWallets.map { it?.seedPhrase })
        assertEquals(candidate.seedPhrase, walletProvider.getPersistableWallet()?.seedPhrase)
        assertEquals(candidate.seedPhrase, provider(CommitFixture(storage.durable)).getPersistableWallet()?.seedPhrase)
    }

    @Test
    fun aCancelledFailedCommitPublishesNoSeed() = runTest {
        val storage = CommitFixture()
        val walletProvider = provider(storage)
        val sdkWallets = observeSdkInput(walletProvider)
        val initialWrite = launch {
            try {
                walletProvider.store(candidate)
            } catch (_: IOException) {
                // The failed commit is allowed to surface while cancellation is propagating.
            }
        }
        storage.onCommit = {
            storage.onCommit = null
            initialWrite.cancel()
        }

        runCurrent()

        assertTrue(initialWrite.isCancelled)
        assertUncommitted(walletProvider, sdkWallets, storage)
    }

    @Test
    fun malformedWalletRecordLeavesOnlyASanitizedDecodeError() = runTest {
        val marker = "synthetic-sensitive-field"
        val serialized = "{\"seedPhrase\": \"" + marker
        val storage = CommitFixture(serialized)

        val error = assertFailsWith<StoredWalletDecodeException> { provider(storage).getPersistableWallet() }

        assertTrue(marker !in error.stackTraceToString())
        assertNull(error.cause)
        assertEquals(serialized, storage.durable)
    }

    @Test
    fun unreadableRecordWithholdsSdkInputUntilTheUserRetries() = runTest {
        val storage = CommitFixture("{synthetic-unreadable}")
        val walletProvider = provider(storage)
        val availability = mutableListOf<StoredWalletAvailability>()
        val sdkInputs = mutableListOf<PersistableWallet?>()
        backgroundScope.launch { walletProvider.availability.collect { availability += it } }
        backgroundScope.launch { walletProvider.persistableWallet.collect { sdkInputs += it } }
        runCurrent()

        assertEquals(listOf(StoredWalletAvailability.UNAVAILABLE), availability)
        assertEquals(listOf<PersistableWallet?>(null), sdkInputs)
        assertEquals("{synthetic-unreadable}", storage.durable)

        storage.replaceFixtureBytes(candidate.toJson().toString())
        walletProvider.retryRead()
        runCurrent()

        assertEquals(
            listOf(StoredWalletAvailability.UNAVAILABLE, StoredWalletAvailability.PRESENT),
            availability.filterNot { it == StoredWalletAvailability.CHECKING },
        )
        assertEquals(listOf(null, candidate.seedPhrase), sdkInputs.map { it?.seedPhrase })
    }

    @Test
    fun coldPendingResetSkipsUnreadableWalletHydration() = runTest {
        val context = mockk<Context> { every { noBackupFilesDir } returns directory.root }
        ResetProgressStore(context, DirectorySync {}).store(ResetStage.ERASE_SDK)
        val pending = ResetProgressStore(context, DirectorySync {})
        val storage = CommitFixture("{synthetic-unreadable}")
        val walletProvider = provider(storage, pending)

        assertTrue(pending.isPending.value)
        assertNull(walletProvider.persistableWallet.first())
        assertNull(walletProvider.getPersistableWallet())
        assertEquals("{synthetic-unreadable}", storage.durable)
    }

    @Test
    fun recoveredSdkInputUsesExplicitRestoreModeForTheCurrentWallet() = runTest {
        val storage = CommitFixture().apply { failCommit = false }
        val walletProvider = provider(storage)
        val sdkWallets = observeSdkInput(walletProvider)
        walletProvider.store(candidate)
        runCurrent()
        assertEquals(WalletInitMode.NewWallet, sdkWallets.last()?.walletInitMode)

        val original = walletProvider.requirePersistableWallet()
        val restored = original.copy(walletInitMode = WalletInitMode.RestoreWallet)
        assertTrue(walletProvider.storeIfCurrent(original, restored, walletProvider.writeVersion))
        walletProvider.retryRead(recoveredWallet = restored)
        // Another SDK model construction can replace the pinned SDK's process-global mode.
        candidate.copy(walletInitMode = WalletInitMode.ExistingWallet)
        runCurrent()
        assertEquals(WalletInitMode.RestoreWallet, sdkWallets.last()?.walletInitMode)

        val newWallet = candidate.copy(
            endpoint = LightWalletEndpoint("different.fixture.invalid", 443, true),
            seedPhrase = SeedPhrase(List(SeedPhrase.SEED_PHRASE_SIZE) { "ability" }),
            walletInitMode = WalletInitMode.NewWallet,
        )
        walletProvider.store(newWallet)
        runCurrent()
        assertEquals(newWallet.endpoint, sdkWallets.last()?.endpoint)
        assertEquals(WalletInitMode.NewWallet, sdkWallets.last()?.walletInitMode)
    }

    private suspend fun TestScope.assertUncommitted(
        walletProvider: PersistableWalletProviderImpl,
        sdkWallets: List<PersistableWallet?>,
        storage: CommitFixture,
    ) {
        runCurrent()
        assertEquals(listOf<PersistableWallet?>(null), sdkWallets)
        assertNull(walletProvider.getPersistableWallet())
        assertNull(provider(CommitFixture(storage.durable)).getPersistableWallet())
    }

    private fun TestScope.observeSdkInput(walletProvider: PersistableWalletProviderImpl): List<PersistableWallet?> {
        val context = mockk<Context> { every { noBackupFilesDir } returns directory.root }
        val resetProgress = ResetProgressStore(context, DirectorySync {})
        val sdkWallets = mutableListOf<PersistableWallet?>()
        backgroundScope.launch {
            walletProvider.persistableWallet.hideResetWallet(resetProgress).collect { sdkWallets += it }
        }
        runCurrent()
        assertEquals(listOf<PersistableWallet?>(null), sdkWallets)
        return sdkWallets
    }

    private fun TestScope.provider(
        storage: CommitFixture,
        resetProgressStore: ResetProgressStore? = null,
    ): PersistableWalletProviderImpl {
        val android = AndroidPreferenceProvider(storage.preferences, StandardTestDispatcher(testScheduler))
        val encryptedHolder = mockk<EncryptedPreferenceProvider> { coEvery { this@mockk.invoke() } returns android }
        return PersistableWalletProviderImpl(encryptedHolder, resetProgressStore = resetProgressStore)
    }

    /** Like SharedPreferences, publishes the new in-memory value even on a failed disk commit. */
    private class CommitFixture(initial: String? = null) {
        var durable = initial
            private set
        var failCommit = true
        var onCommit: (() -> Unit)? = null
        private var cached = initial
        private var staged = initial
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        fun replaceFixtureBytes(value: String) {
            cached = value
            durable = value
        }

        val preferences = mockk<SharedPreferences> {
            every { getString(any(), null) } answers { cached }
            every { registerOnSharedPreferenceChangeListener(any()) } answers { listeners.add(firstArg()) }
            every { unregisterOnSharedPreferenceChangeListener(any()) } answers { listeners.remove(firstArg()) }
        }

        init {
            val editor = mockk<SharedPreferences.Editor> {
                every { putString(any(), any()) } answers {
                    staged = secondArg()
                    this@mockk
                }
                every { commit() } answers {
                    cached = staged
                    onCommit?.invoke()
                    listeners.forEach { it.onSharedPreferenceChanged(preferences, "persistable_wallet") }
                    if (!failCommit) durable = staged
                    !failCommit
                }
            }
            every { preferences.edit() } returns editor
        }
    }
}
