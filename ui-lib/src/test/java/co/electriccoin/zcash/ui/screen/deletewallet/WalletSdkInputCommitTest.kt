package co.electriccoin.zcash.ui.screen.deletewallet

import android.content.Context
import android.content.SharedPreferences
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.global.WalletSdkTransportGate
import co.electriccoin.zcash.global.hideResetWallet
import co.electriccoin.zcash.preference.AndroidPreferenceProvider
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.model.OnboardingState
import co.electriccoin.zcash.ui.common.provider.DirectorySync
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProviderImpl
import co.electriccoin.zcash.ui.common.provider.StoredWalletAvailability
import co.electriccoin.zcash.ui.common.provider.StoredWalletDecodeException
import co.electriccoin.zcash.ui.common.provider.WalletProvisioning
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertFalse
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
        val cold = provider(CommitFixture(storage.durable)).sdkWalletReads.first().wallet
        assertEquals(candidate.seedPhrase, cold?.seedPhrase)
        assertEquals(WalletInitMode.NewWallet, cold?.walletInitMode)
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
        val restored = original.copy(
            birthday = original.network.saplingActivationHeight,
            walletInitMode = WalletInitMode.RestoreWallet,
        )
        assertTrue(walletProvider.storeIfCurrent(original, restored, walletProvider.writeVersion))
        walletProvider.retryRead()
        // Another SDK model construction can replace the pinned SDK's process-global mode.
        candidate.copy(walletInitMode = WalletInitMode.ExistingWallet)
        runCurrent()
        assertEquals(WalletInitMode.RestoreWallet, sdkWallets.last()?.walletInitMode)
        val cold = provider(CommitFixture(storage.durable)).sdkWalletReads.first().wallet
        assertEquals(WalletInitMode.RestoreWallet, cold?.walletInitMode)
        assertEquals(restored.birthday, cold?.birthday)

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

    @Test
    fun onlyTheExpectedNativeAccountCompletesProvisioningForFutureReads() = runTest {
        val storage = CommitFixture().apply { failCommit = false }
        val walletProvider = provider(storage)
        val sdkWallets = observeSdkInput(walletProvider)
        walletProvider.store(candidate)
        runCurrent()
        val transport = WalletSdkTransportGate()
        val walletTransport = WalletTransportAccess(transport)
        val provisioning = WalletProvisioning(walletProvider, transport, walletTransport)
        val engine = mockk<Synchronizer> { every { network } returns candidate.network }
        val wrongAccount = mockk<Account> {
            every { hdAccountIndex } returns Zip32AccountIndex.new(0)
            every { keySource } returns "zashi"
            every { ufvk } returns "different-test-key"
        }
        val account = mockk<Account> {
            every { hdAccountIndex } returns Zip32AccountIndex.new(0)
            every { keySource } returns "zashi"
            every { ufvk } returns "matching-test-key"
        }
        fun coldStore() = provider(CommitFixture(storage.durable))
        provisioning.confirm(engine, listOf(account), null, { true }) { error("unidentified account source") }
        provisioning.confirm(engine, listOf(wrongAccount), "zashi", { true }) { "matching-test-key" }
        assertEquals(WalletInitMode.NewWallet, coldStore().sdkWalletReads.first().wallet?.walletInitMode)

        var current = true
        provisioning.confirm(engine, listOf(account), "zashi", { current }) {
            current = false
            "matching-test-key"
        }
        assertEquals(WalletInitMode.NewWallet, coldStore().sdkWalletReads.first().wallet?.walletInitMode)

        storage.failCommit = true
        assertFailsWith<IOException> {
            provisioning.confirm(engine, listOf(account), "zashi", { true }) { "matching-test-key" }
        }
        assertFalse(walletTransport.isRetired(engine))
        assertEquals(WalletInitMode.NewWallet, coldStore().sdkWalletReads.first().wallet?.walletInitMode)
        storage.failCommit = false

        val release = CompletableDeferred<Unit>()
        val active = launch { walletTransport.whileOperating { release.await() } }
        runCurrent()
        val confirmation = launch {
            provisioning.confirm(engine, listOf(account), "zashi", { true }) { "matching-test-key" }
        }
        runCurrent()
        assertFalse(confirmation.isCompleted)
        assertEquals(WalletInitMode.NewWallet, sdkWallets.last()?.walletInitMode)
        release.complete(Unit)
        active.join()
        confirmation.join()
        runCurrent()
        assertTrue(walletTransport.isRetired(engine))
        assertEquals(
            listOf(null, WalletInitMode.NewWallet, WalletInitMode.ExistingWallet),
            sdkWallets.map { it?.walletInitMode },
        )
        assertEquals(WalletInitMode.ExistingWallet, coldStore().sdkWalletReads.first().wallet?.walletInitMode)
        assertEquals(WalletInitMode.NewWallet, coldStore().getSetupMode())
        val staleUpdate = candidate.copy(endpoint = LightWalletEndpoint("other.fixture.invalid", 443, true))
        assertFalse(walletProvider.storeIfCurrent(candidate, staleUpdate, walletProvider.writeVersion))
        assertEquals(candidate.endpoint, coldStore().sdkWalletReads.first().wallet?.endpoint)
    }

    @Test
    fun legacyIncompleteWalletWaitsForTheAcknowledgedRestoreAtTheSameBirthday() = runTest {
        val legacy = candidate.copy(
            birthday = candidate.network.saplingActivationHeight,
            walletInitMode = WalletInitMode.ExistingWallet,
        )
        val storage = CommitFixture(legacy.toJson().toString()).apply { failCommit = false }
        val onboarding = MutableStateFlow(OnboardingState.NONE.toNumber().toString())
        val raw = mockk<PreferenceProvider> {
            every { observe(StandardPreferenceKeys.ONBOARDING_STATE.key) } returns onboarding
            coEvery { getString(StandardPreferenceKeys.ONBOARDING_STATE.key) } answers { onboarding.value }
        }
        val prefs = mockk<StandardPreferenceProvider> { coEvery { this@mockk.invoke() } returns raw }
        val walletProvider = provider(storage, standardPreferences = prefs)
        val opens = mutableListOf<WalletInitMode?>()
        backgroundScope.launch { walletProvider.sdkWalletReads.collect { opens += it.wallet?.walletInitMode } }
        runCurrent()
        assertEquals(StoredWalletAvailability.PRESENT, walletProvider.availability.first())
        assertEquals(legacy.birthday, walletProvider.requirePersistableWallet().birthday)
        assertEquals(listOf<WalletInitMode?>(null), opens)
        val restore = legacy.copy(walletInitMode = WalletInitMode.RestoreWallet)
        assertTrue(walletProvider.storeIfCurrent(legacy, restore, walletProvider.writeVersion))
        runCurrent()
        assertEquals(listOf<WalletInitMode?>(null, WalletInitMode.RestoreWallet), opens)
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
        standardPreferences: StandardPreferenceProvider? = null,
    ): PersistableWalletProviderImpl {
        val android = AndroidPreferenceProvider(storage.preferences, StandardTestDispatcher(testScheduler))
        val encryptedHolder = mockk<EncryptedPreferenceProvider> { coEvery { this@mockk.invoke() } returns android }
        return PersistableWalletProviderImpl(
            encryptedHolder, resetProgressStore = resetProgressStore, standardPreferences = standardPreferences,
        )
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
