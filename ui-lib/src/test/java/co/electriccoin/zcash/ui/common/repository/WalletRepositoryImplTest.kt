package co.electriccoin.zcash.ui.common.repository

import android.app.Application
import android.content.res.Resources
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.SeedPhrase
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import co.electriccoin.zcash.configuration.model.map.Configuration
import co.electriccoin.zcash.crash.android.GlobalCrashReporter
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.common.datasource.RestoreTimestampDataSource
import co.electriccoin.zcash.ui.common.model.OnboardingState
import co.electriccoin.zcash.ui.common.model.WalletRestoringState
import co.electriccoin.zcash.ui.common.provider.IsIronwoodAnnouncementShownStorageProvider
import co.electriccoin.zcash.ui.common.provider.LightWalletEndpointProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SdkEncryptedPreferenceRecoveryProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletBackupFlagStorageProvider
import co.electriccoin.zcash.ui.common.provider.WalletRestoringStateProvider
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import co.electriccoin.zcash.ui.common.usecase.RestoreWalletUseCase
import co.electriccoin.zcash.ui.common.viewmodel.SecretState
import co.electriccoin.zcash.ui.common.viewmodel.WalletViewModel
import co.electriccoin.zcash.ui.preference.StandardPreferenceKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for MOB-1836: the onboarding flag ([OnboardingState]) and the stored wallet
 * live in separate preference files and can diverge when the encrypted store is recreated after
 * provable corruption. [resolveSecretState] and [WalletRepositoryImpl.init]'s self-heal must keep
 * a wallet-less app from ever presenting [SecretState.READY].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletRepositoryImplTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setUp() {
        mockkObject(PersistableWallet.Companion)
        coEvery { PersistableWallet.new(any(), any(), any(), any()) } returns mockk(relaxed = true)

        mockkObject(Synchronizer.Companion)
    }

    @AfterTest
    fun tearDown() {
        scopes.forEach { it.cancel() }
        scopes.clear()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `resolveSecretState combines the onboarding flag and wallet presence`() {
        assertEquals(SecretState.READY, resolveSecretState(true, OnboardingState.READY, true))
        assertEquals(SecretState.NONE, resolveSecretState(true, OnboardingState.READY, false))
        assertEquals(SecretState.NONE, resolveSecretState(true, OnboardingState.NONE, false))
        assertEquals(SecretState.NONE, resolveSecretState(true, OnboardingState.NONE, true))
        assertEquals(SecretState.NONE, resolveSecretState(true, OnboardingState.NEEDS_WARN, true))
        assertEquals(SecretState.NONE, resolveSecretState(true, OnboardingState.NEEDS_BACKUP, true))
        assertEquals(SecretState.LOADING, resolveSecretState(false, OnboardingState.READY, true))
    }

    @Test
    fun secretStateFlowResolvesNoneWhenWalletIsMissing() =
        runTest {
            val (repository, _) = newRepository(onboardingState = OnboardingState.READY, initialWallet = null)

            assertEquals(SecretState.NONE, repository.secretState.first { it != SecretState.LOADING })
        }

    @Test
    fun secretStateFlowResolvesReadyWhenWalletIsPresent() =
        runTest {
            val (repository, _) =
                newRepository(onboardingState = OnboardingState.READY, initialWallet = mockk(relaxed = true))

            assertEquals(SecretState.READY, repository.secretState.first { it != SecretState.LOADING })
        }

    @Test
    fun secretStateDoesNotWaitOnTheWalletStoreWhenFlagIsNotReady() =
        runTest {
            val neverEmittingWalletProvider =
                mockk<PersistableWalletProvider> {
                    every { persistableWallet } returns MutableSharedFlow()
                }

            val (repository, _) =
                newRepository(
                    onboardingState = OnboardingState.NONE,
                    initialWallet = null,
                    persistableWalletProviderOverride = neverEmittingWalletProvider,
                )

            assertEquals(SecretState.NONE, repository.secretState.first { it != SecretState.LOADING })
        }

    @Test
    fun createNewWalletStoresWalletBeforeMarkingOnboardingReady() =
        runTest {
            val (repository, harness) = newRepository(onboardingState = OnboardingState.NONE, initialWallet = null)
            repository.useTestScope(testScheduler)

            repository.createNewWallet()
            advanceUntilIdle()

            assertEquals(listOf("wallet", "restoring=INITIATING", "onboarding=3"), harness.events)
        }

    @Test
    fun repeatedCreateTriggersKeepTheFirstAcceptedWallet() =
        runTest {
            val (repository, harness) = newRepository(onboardingState = OnboardingState.NONE, initialWallet = null)
            repository.useTestScope(testScheduler)

            repeat(3) { repository.createNewWallet() }
            advanceUntilIdle()

            assertEquals(1, harness.events.count { it == "wallet" })
            coVerify(exactly = 1) { PersistableWallet.new(any(), any(), any(), any()) }
        }

    @Test
    fun repositoryRecreationOffersContinueAndPreservesTheStoredWallet() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val (previous, previousHarness) = newRepository(OnboardingState.NONE, null)
            previous.useTestScope(testScheduler)
            coEvery { previousHarness.restoringProvider.store(any()) } throws
                IOException("Interrupted after wallet write")
            assertFailsWith<IllegalStateException> { previous.restoreFixture().await() }
            val storedWallet = previousHarness.walletProvider.requirePersistableWallet()
            val (repository, harness) = newRepository(OnboardingState.NONE, storedWallet)
            repository.useTestScope(testScheduler)
            val viewModel = WalletViewModel(mockk { every { synchronizer } returns MutableStateFlow(null) }, repository)
            scopes += viewModel.viewModelScope
            assertEquals(
                WalletInitializationState.INCOMPLETE,
                viewModel.walletInitialization.first { it != WalletInitializationState.CHECKING }
            )

            viewModel.createNewWallet()
            advanceUntilIdle()
            assertTrue(harness.events.isEmpty())
            assertEquals("0", harness.fakePrefs.getString(StandardPreferenceKeys.ONBOARDING_STATE.key))

            repeat(3) { viewModel.continueWalletSetup() }
            advanceUntilIdle()

            val recovered = harness.walletProvider.requirePersistableWallet()
            assertTrue(recovered.seedPhrase == storedWallet.seedPhrase)
            assertEquals(storedWallet.network, recovered.network)
            assertEquals(WalletInitMode.RestoreWallet, recovered.walletInitMode)
            assertEquals(listOf("wallet", "restoring=RESTORING", "onboarding=3"), harness.events)
            coVerify(exactly = 0) { PersistableWallet.new(any(), any(), any(), any()) }
            coVerify(exactly = 0) { harness.backupProvider.store(any()) }
        }

    @Test
    fun walletSetupRemainsCheckingUntilEncryptedWalletIsRead() =
        runTest {
            val walletLoaded = CompletableDeferred<PersistableWallet?>()
            val provider = mockk<PersistableWalletProvider> {
                every { persistableWallet } returns flow { emit(walletLoaded.await()) }
            }
            val (repository, _) = newRepository(OnboardingState.NONE, null, provider)
            assertEquals(WalletInitializationState.CHECKING, repository.walletInitialization.value)
            walletLoaded.complete(fixtureWallet())
            assertEquals(
                WalletInitializationState.INCOMPLETE,
                repository.walletInitialization.first { it != WalletInitializationState.CHECKING }
            )
        }

    @Test
    fun repositoryRecreationRejectsConflictingRestoreAndKeepsExistingWallet() =
        runTest {
            val storedWallet = fixtureWallet()
            val (repository, harness) = newRepository(OnboardingState.NONE, storedWallet)
            repository.useTestScope(testScheduler)

            assertFailsWith<WalletInitializationConflictException> {
                repository.restoreWallet(
                    ZcashNetwork.Mainnet,
                    storedWallet.seedPhrase,
                    WalletFixture.Alice.getBirthday(ZcashNetwork.Mainnet)
                ).await()
            }
            assertEquals(emptyList(), harness.events)
            assertFailsWith<WalletInitializationConflictException> { repository.restoreFixture().await() }
            assertTrue(harness.events.isEmpty())
            repository.continueWalletSetup()
            advanceUntilIdle()

            val recovered = harness.walletProvider.requirePersistableWallet()
            assertTrue(recovered.seedPhrase == storedWallet.seedPhrase)
            assertEquals(ZcashNetwork.Testnet, recovered.network)
            coVerify(exactly = 0) { harness.backupProvider.store(any()) }
            coVerify(exactly = 0) { PersistableWallet.new(any(), any(), any(), any()) }
        }

    @Test
    fun failedExistingWalletRecoveryRetriesWithoutGeneratingAnotherWallet() =
        runTest {
            val storedWallet = fixtureWallet()
            val (repository, harness) = newRepository(OnboardingState.NONE, storedWallet)
            repository.useTestScope(testScheduler)
            coEvery { harness.restoringProvider.store(any()) } throws IllegalStateException("test failure")
            repository.continueWalletSetup()
            advanceUntilIdle()
            repository.walletInitialization.first { it == WalletInitializationState.FAILED }

            coEvery { harness.restoringProvider.store(any()) } returns Unit
            repository.retryWalletInitialization()
            advanceUntilIdle()
            assertTrue(harness.walletProvider.requirePersistableWallet().seedPhrase == storedWallet.seedPhrase)
            repository.walletInitialization.first { it == WalletInitializationState.IDLE }
            coVerify(exactly = 0) { PersistableWallet.new(any(), any(), any(), any()) }
            coVerify(exactly = 0) { harness.backupProvider.store(any()) }
        }

    private fun fixtureWallet() = PersistableWallet(
        network = ZcashNetwork.Testnet,
        birthday = WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet),
        endpoint = LightWalletEndpoint("zec.rocks", 443, true),
        seedPhrase = SeedPhrase.new(WalletFixture.Alice.seedPhrase),
        walletInitMode = WalletInitMode.ExistingWallet,
    )

    @Test
    fun competingCreateAndRestoreKeepTheFirstAcceptedOperation() =
        runTest {
            val (repository, harness) = newRepository(OnboardingState.NONE, null)
            repository.useTestScope(testScheduler)

            repository.createNewWallet()
            repository.restoreFixture()
            advanceUntilIdle()
            repository.restoreFixture()
            advanceUntilIdle()

            assertEquals(listOf("wallet", "restoring=INITIATING", "onboarding=3"), harness.events)
            coVerify(exactly = 1) { PersistableWallet.new(any(), any(), any(), any()) }
        }

    @Test
    fun repeatedRestoreTriggersDoNotQueueAnotherWallet() =
        runTest {
            val (repository, harness) = newRepository(OnboardingState.NONE, null)
            repository.useTestScope(testScheduler)

            repository.restoreFixture()
            repository.createNewWallet()
            repository.restoreFixture()
            advanceUntilIdle()

            assertEquals(listOf("wallet", "restoring=RESTORING", "onboarding=3"), harness.events)
            coVerify(exactly = 0) { PersistableWallet.new(any(), any(), any(), any()) }
        }

    @Test
    fun failedSeedWriteBlocksReadinessAndRetriesTheAcceptedWallet() =
        runTest {
            val (repository, harness) = newRepository(OnboardingState.NONE, null)
            repository.useTestScope(testScheduler)
            val walletProvider = harness.walletProvider as FakePersistableWalletProvider
            walletProvider.failWrites = true

            repository.createNewWallet()
            advanceUntilIdle()

            repository.walletInitialization.first { it == WalletInitializationState.FAILED }
            assertEquals(SecretState.NONE, repository.secretState.first { it != SecretState.LOADING })
            assertEquals("0", harness.fakePrefs.getString(StandardPreferenceKeys.ONBOARDING_STATE.key))
            coVerify(exactly = 0) { harness.restoringProvider.store(any()) }
            val accepted = walletProvider.requirePersistableWallet()

            // A new process has no failed in-memory write and must remain in onboarding.
            val (restarted, _) = newRepository(OnboardingState.NONE, null)
            assertEquals(SecretState.NONE, restarted.secretState.first { it != SecretState.LOADING })

            walletProvider.failWrites = false
            repository.createNewWallet()
            advanceUntilIdle()

            assertEquals(accepted, walletProvider.requirePersistableWallet())
            repository.walletInitialization.first { it == WalletInitializationState.IDLE }
            assertEquals("3", harness.fakePrefs.getString(StandardPreferenceKeys.ONBOARDING_STATE.key))
            coVerify(exactly = 1) { PersistableWallet.new(any(), any(), any(), any()) }
            assertEquals(listOf("wallet", "restoring=INITIATING", "onboarding=3"), harness.events)
        }

    @Test
    fun failedRestoreSeedWritePreservesTheAcceptedRestoreForRetry() =
        runTest {
            val (repository, harness) = newRepository(OnboardingState.NONE, null)
            repository.useTestScope(testScheduler)
            val walletProvider = harness.walletProvider as FakePersistableWalletProvider
            walletProvider.failWrites = true

            repository.restoreFixture()
            advanceUntilIdle()
            val accepted = walletProvider.requirePersistableWallet()
            repository.walletInitialization.first { it == WalletInitializationState.FAILED }
            assertEquals("0", harness.fakePrefs.getString(StandardPreferenceKeys.ONBOARDING_STATE.key))
            coVerify(exactly = 0) { harness.backupProvider.store(any()) }

            walletProvider.failWrites = false
            repository.retryWalletInitialization()
            advanceUntilIdle()

            assertEquals(accepted, walletProvider.requirePersistableWallet())
            repository.walletInitialization.first { it == WalletInitializationState.IDLE }
            assertEquals("3", harness.fakePrefs.getString(StandardPreferenceKeys.ONBOARDING_STATE.key))
            coVerify(exactly = 0) { PersistableWallet.new(any(), any(), any(), any()) }
            coVerify(exactly = 1) { harness.backupProvider.store(true) }
        }

    @Test
    fun failedMetadataWriteRetriesTheAcceptedWallet() =
        runTest {
            val (repository, harness) = newRepository(OnboardingState.NONE, null)
            repository.useTestScope(testScheduler)
            coEvery { harness.restoringProvider.store(any()) } throws IllegalStateException("test failure")

            repository.createNewWallet()
            advanceUntilIdle()
            repository.walletInitialization.first { it == WalletInitializationState.FAILED }
            val accepted = harness.walletProvider.getPersistableWallet()

            coEvery { harness.restoringProvider.store(any()) } returns Unit
            repository.retryWalletInitialization()
            advanceUntilIdle()

            assertEquals(accepted, harness.walletProvider.getPersistableWallet())
            assertEquals("3", harness.fakePrefs.getString(StandardPreferenceKeys.ONBOARDING_STATE.key))
            repository.walletInitialization.first { it == WalletInitializationState.IDLE }
            coVerify(exactly = 1) { PersistableWallet.new(any(), any(), any(), any()) }
            coVerify { harness.restoringProvider.store(WalletRestoringState.INITIATING) }
            coVerify(exactly = 0) { harness.restoringProvider.store(WalletRestoringState.RESTORING) }
        }

    @Test
    fun failedRestoreMetadataRetriesTheAcceptedRestore() =
        runTest {
            val (repository, harness) = newRepository(OnboardingState.NONE, null)
            repository.useTestScope(testScheduler)
            coEvery { harness.restoringProvider.store(any()) } throws IllegalStateException("test failure")
            repository.restoreFixture()
            advanceUntilIdle()
            val acceptedSeed = harness.walletProvider.requirePersistableWallet().seedPhrase

            coEvery { harness.restoringProvider.store(any()) } returns Unit
            repository.retryWalletInitialization()
            advanceUntilIdle()

            assertTrue(harness.walletProvider.requirePersistableWallet().seedPhrase == acceptedSeed)
            repository.walletInitialization.first { it == WalletInitializationState.IDLE }
            coVerify(exactly = 1) { harness.backupProvider.store(true) }
            coVerify(exactly = 0) { PersistableWallet.new(any(), any(), any(), any()) }
        }

    @Test
    fun cancellationBeforeLaunchReleasesAdmissionForRetry() =
        runTest {
            val (repository, harness) = newRepository(OnboardingState.NONE, null)
            repository.useTestScope(testScheduler)
            repository.scope.cancel()

            repository.createNewWallet()
            advanceUntilIdle()
            repository.walletInitialization.first { it == WalletInitializationState.FAILED }
            assertEquals(emptyList(), harness.events)

            repository.useTestScope(testScheduler)
            repository.createNewWallet()
            advanceUntilIdle()
            assertEquals(1, harness.events.count { it == "wallet" })
            repository.walletInitialization.first { it == WalletInitializationState.IDLE }
        }

    @Test
    fun cancellationAfterWalletWriteRetriesWithoutGeneratingAReplacement() =
        runTest {
            val (repository, harness) = newRepository(OnboardingState.NONE, null)
            val metadataStarted = CompletableDeferred<Unit>()
            coEvery { harness.restoringProvider.store(any()) } coAnswers {
                metadataStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
            repository.useTestScope(testScheduler)
            repository.createNewWallet()
            runCurrent()
            metadataStarted.await()
            val accepted = harness.walletProvider.getPersistableWallet()

            repository.scope.cancel()
            advanceUntilIdle()
            repository.walletInitialization.first { it == WalletInitializationState.FAILED }

            coEvery { harness.restoringProvider.store(any()) } returns Unit
            repository.useTestScope(testScheduler)
            repository.createNewWallet()
            advanceUntilIdle()
            assertEquals(accepted, harness.walletProvider.getPersistableWallet())
            repository.walletInitialization.first { it == WalletInitializationState.IDLE }
            coVerify(exactly = 1) { PersistableWallet.new(any(), any(), any(), any()) }
        }

    @Test
    fun pendingCreationWaitsForStartupRecoveryWithoutQueueingDuplicates() =
        runTest {
            val recoveryStarted = CompletableDeferred<Unit>()
            val finishRecovery = CompletableDeferred<Unit>()
            val (repository, harness) = newRepository(OnboardingState.READY, null)
            coEvery { harness.recoveryProvider.ensureReadable() } coAnswers {
                recoveryStarted.complete(Unit)
                finishRecovery.await()
            }
            repository.useTestScope(testScheduler)
            repository.init()
            runCurrent()
            recoveryStarted.await()

            repeat(3) { repository.createNewWallet() }
            runCurrent()
            repository.walletInitialization.first { it == WalletInitializationState.RUNNING }
            assertEquals(0, harness.events.count { it == "wallet" })

            finishRecovery.complete(Unit)
            advanceUntilIdle()
            assertEquals(
                listOf("onboarding=0", "erase", "wallet", "restoring=INITIATING", "onboarding=3"), harness.events
            )
        }

    @Test
    fun conflictingRestoreIsRejectedAndTheOriginalRequestCanBeRetried() =
        runTest {
            val (repository, harness) = newRepository(OnboardingState.NONE, null)
            repository.useTestScope(testScheduler)
            val resources = mockk<Resources> {
                every { getBoolean(any()) } returns true
            }
            val context = mockk<Application> {
                every { this@mockk.resources } returns resources
            }
            val restore = RestoreWalletUseCase(repository, context)
            val seed = SeedPhrase.new(WalletFixture.Alice.seedPhrase)
            val original = WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet)
            val corrected = BlockHeight.new(original.value + 1)

            coEvery { harness.restoringProvider.store(any()) } throws IOException("Synthetic setup failure")
            assertFailsWith<IllegalStateException> { restore(seed, false, original) }
            coEvery { harness.restoringProvider.store(any()) } returns Unit

            assertFailsWith<WalletInitializationConflictException> { restore(seed, false, corrected) }
            assertFailsWith<WalletInitializationConflictException> { restore(seed, true, original) }
            repository.walletInitialization.first { it == WalletInitializationState.FAILED }
            assertEquals(original, harness.walletProvider.requirePersistableWallet().birthday)

            restore(seed, false, original)
            repository.walletInitialization.first { it == WalletInitializationState.IDLE }
            assertEquals(original, harness.walletProvider.requirePersistableWallet().birthday)
        }

    private fun WalletRepositoryImpl.restoreFixture() =
        restoreWallet(
            ZcashNetwork.Testnet,
            SeedPhrase.new(WalletFixture.Alice.seedPhrase),
            WalletFixture.Alice.getBirthday(ZcashNetwork.Testnet)
        )

    @Test
    fun initResetsOnboardingAndErasesSdkDataWhenWalletIsMissing() =
        runTest {
            val (repository, harness) = newRepository(onboardingState = OnboardingState.READY, initialWallet = null)
            repository.useTestScope(testScheduler)

            repository.init()
            advanceUntilIdle()

            assertEquals(listOf("onboarding=0", "sdkRepair", "erase"), harness.events)
        }

    @Test
    fun initKeepsOnboardingWhenWalletIsPresent() =
        runTest {
            val (repository, harness) =
                newRepository(onboardingState = OnboardingState.READY, initialWallet = mockk(relaxed = true))
            repository.useTestScope(testScheduler)

            repository.init()
            advanceUntilIdle()

            assertEquals("3", harness.fakePrefs.getString(StandardPreferenceKeys.ONBOARDING_STATE.key))
            coVerify(exactly = 0) { Synchronizer.erase(any(), any(), any()) }
        }

    @Test
    fun initSkipsRepairAndEraseWhenFlagIsNotReady() =
        runTest {
            val (repository, harness) = newRepository(onboardingState = OnboardingState.NONE, initialWallet = null)
            repository.useTestScope(testScheduler)

            repository.init()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), harness.events)
            coVerify(exactly = 0) { Synchronizer.erase(any(), any(), any()) }
        }

    @Test
    fun initSkipsEraseWhenAWalletIsStoredWhileSelfHealing() =
        runTest {
            val (repository, harness) =
                newRepository(
                    onboardingState = OnboardingState.READY,
                    initialWallet = null,
                    persistableWalletProviderOverride =
                        WalletStoredAfterFirstReadProvider(mockk(relaxed = true)),
                )
            repository.useTestScope(testScheduler)

            repository.init()
            advanceUntilIdle()

            assertEquals(listOf("onboarding=0", "sdkRepair"), harness.events)
            coVerify(exactly = 0) { Synchronizer.erase(any(), any(), any()) }
        }

    /**
     * The fail-safe hinge of the whole self-heal: "no wallet" must only ever mean "the store said
     * there is no wallet", never "reading the store failed". Nothing in
     * `PersistableWalletProviderImpl.getPersistableWallet` catches, so a read failure propagates
     * out of [WalletRepositoryImpl.init] today — this pins that against a future well-meaning
     * `runCatching` turning an unreadable store into a reset to onboarding and an erase.
     */
    @Test
    fun initNeitherResetsOnboardingNorErasesWhenTheWalletReadFails() =
        runTest {
            val failingWalletProvider =
                mockk<PersistableWalletProvider> {
                    every { persistableWallet } returns MutableSharedFlow()
                    every { writeVersion } returns 0L
                }
            coEvery { failingWalletProvider.getPersistableWallet() } throws
                IllegalStateException("encrypted preferences unreadable")

            val (repository, harness) =
                newRepository(
                    onboardingState = OnboardingState.READY,
                    initialWallet = null,
                    persistableWalletProviderOverride = failingWalletProvider,
                )
            repository.useTestScope(testScheduler)

            repository.init()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), harness.events)
            assertEquals("3", harness.fakePrefs.getString(StandardPreferenceKeys.ONBOARDING_STATE.key))
            coVerify(exactly = 0) { Synchronizer.erase(any(), any(), any()) }
        }

    @Test
    fun aDelayedEndpointChangeCannotRestoreAClearedWallet() = runTest {
        val (repository, harness) = newRepository(OnboardingState.READY, fixtureWallet())
        val walletProvider = harness.walletProvider as FakePersistableWalletProvider
        val currentEndpoint = LightWalletEndpoint("current.example.invalid", 443, true)
        repository.updateWalletEndpoint(currentEndpoint)
        assertEquals(currentEndpoint, walletProvider.requirePersistableWallet().endpoint)
        val walletRead = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        walletProvider.beforeReadReturns = {
            walletRead.complete(Unit)
            releaseRead.await()
        }
        val stale = launch { repository.updateWalletEndpoint(LightWalletEndpoint("stale.example.invalid", 443, true)) }
        walletRead.await()
        walletProvider.clearForReset()
        releaseRead.complete(Unit)
        stale.join()

        assertNull(walletProvider.getPersistableWallet())
        assertEquals(listOf("wallet"), harness.events)
    }

    /**
     * "The erase failed and we carried on anyway" is the half-reset state MOB-1836 was reported
     * for, so it has to reach crash reporting rather than only a log line on the user's device.
     */
    @Test
    fun initReportsAFailedEraseToCrashReporting() =
        runTest {
            mockkObject(GlobalCrashReporter)
            every { GlobalCrashReporter.reportCaughtException(any()) } returns Unit

            val (repository, harness) = newRepository(onboardingState = OnboardingState.READY, initialWallet = null)
            val eraseFailure = RuntimeException("erasing the SDK databases failed")
            coEvery { Synchronizer.erase(any(), any(), any()) } throws eraseFailure
            repository.useTestScope(testScheduler)

            repository.init()
            advanceUntilIdle()

            assertEquals(listOf("onboarding=0", "sdkRepair"), harness.events)
            verify(exactly = 1) { GlobalCrashReporter.reportCaughtException(eraseFailure) }
        }

    /**
     * Replaces the repository's production scope with one driven by the test's scheduler, and
     * registers both for cancellation in [tearDown] so no test leaves a live scope behind.
     */
    private fun WalletRepositoryImpl.useTestScope(scheduler: TestCoroutineScheduler) {
        scope = CoroutineScope(StandardTestDispatcher(scheduler)).also { scopes += it }
    }

    private suspend fun newRepository(
        onboardingState: OnboardingState,
        initialWallet: PersistableWallet?,
        persistableWalletProviderOverride: PersistableWalletProvider? = null,
    ): Pair<WalletRepositoryImpl, TestHarness> {
        val events = mutableListOf<String>()

        coEvery { Synchronizer.erase(any(), any(), any()) } coAnswers {
            events += "erase"
            true
        }

        val configurationRepository =
            mockk<ConfigurationRepository> {
                every { configurationFlow } returns MutableStateFlow<Configuration?>(mockk())
            }

        val application = mockk<Application>(relaxed = true)

        val lightWalletEndpointProvider =
            mockk<LightWalletEndpointProvider> {
                every { getDefaultEndpoint() } returns LightWalletEndpoint("zec.rocks", 443, true)
                every { getDecommissionedHosts() } returns emptySet()
            }

        val persistableWalletProvider =
            persistableWalletProviderOverride ?: FakePersistableWalletProvider(initialWallet, events)

        val synchronizerProvider =
            mockk<SynchronizerProvider>(relaxed = true) {
                every { synchronizer } returns MutableStateFlow<Synchronizer?>(null)
            }

        val fakePrefs = RecordingPreferenceProvider(events)
        fakePrefs.seed(StandardPreferenceKeys.ONBOARDING_STATE.key, onboardingState.toNumber().toString())
        val standardPreferenceProvider = mockk<StandardPreferenceProvider>()
        coEvery { standardPreferenceProvider() } returns fakePrefs

        val restoreTimestampDataSource = mockk<RestoreTimestampDataSource>(relaxed = true)
        val walletBackupFlagStorageProvider = mockk<WalletBackupFlagStorageProvider>(relaxed = true)

        val walletRestoringStateProvider = mockk<WalletRestoringStateProvider>(relaxed = true)
        coEvery { walletRestoringStateProvider.store(any()) } coAnswers {
            events += "restoring=${firstArg<WalletRestoringState>()}"
        }

        val isIronwoodAnnouncementShownStorageProvider =
            mockk<IsIronwoodAnnouncementShownStorageProvider> {
                every { observe() } returns flowOf(null)
            }

        val sdkEncryptedPreferenceRecoveryProvider = mockk<SdkEncryptedPreferenceRecoveryProvider>()
        coEvery { sdkEncryptedPreferenceRecoveryProvider.ensureReadable() } coAnswers { events += "sdkRepair" }

        val repository =
            WalletRepositoryImpl(
                configurationRepository = configurationRepository,
                application = application,
                lightWalletEndpointProvider = lightWalletEndpointProvider,
                persistableWalletProvider = persistableWalletProvider,
                synchronizerProvider = synchronizerProvider,
                standardPreferenceProvider = standardPreferenceProvider,
                restoreTimestampDataSource = restoreTimestampDataSource,
                walletRestoringStateProvider = walletRestoringStateProvider,
                walletBackupFlagStorageProvider = walletBackupFlagStorageProvider,
                isIronwoodAnnouncementShownStorageProvider = isIronwoodAnnouncementShownStorageProvider,
                sdkEncryptedPreferenceRecoveryProvider = sdkEncryptedPreferenceRecoveryProvider,
                isTorEnabledStorageProvider = mockk(relaxed = true),
            )

        scopes += repository.scope
        scopes += repository.sharingScope

        return repository to TestHarness(
            events, fakePrefs, persistableWalletProvider, walletRestoringStateProvider,
            sdkEncryptedPreferenceRecoveryProvider, walletBackupFlagStorageProvider
        )
    }

    private data class TestHarness(
        val events: List<String>,
        val fakePrefs: RecordingPreferenceProvider,
        val walletProvider: PersistableWalletProvider,
        val restoringProvider: WalletRestoringStateProvider,
        val recoveryProvider: SdkEncryptedPreferenceRecoveryProvider,
        val backupProvider: WalletBackupFlagStorageProvider,
    )
}

/**
 * A hand-written [PersistableWalletProvider] stand-in: MockK cannot mock a suspend member whose
 * parameter is the [PersistableWallet] value class in this codebase's setup, so [store] is
 * implemented directly, backed by a [MutableStateFlow] the way the real
 * `PersistableWalletProviderImpl` observes its storage provider.
 */
private class FakePersistableWalletProvider(
    initial: PersistableWallet?,
    private val events: MutableList<String>
) : PersistableWalletProvider {
    private val walletFlow = MutableStateFlow(initial)
    private val writeAccess = WalletWriteAccess()
    var failWrites = false
    var beforeReadReturns: suspend () -> Unit = {}

    override val persistableWallet: Flow<PersistableWallet?> = walletFlow
    override val writeVersion get() = writeAccess.token()

    override suspend fun store(persistableWallet: PersistableWallet) =
        writeAccess.replaceWallet { write(persistableWallet) }

    override suspend fun storeIfCurrent(
        expected: PersistableWallet,
        replacement: PersistableWallet,
        version: Long,
    ): Boolean = writeAccess.write(version) {
        val wallet = walletFlow.value
        if (wallet?.copy(walletInitMode = expected.walletInitMode) != expected) {
            false
        } else {
            write(replacement)
            true
        }
    } ?: false

    override suspend fun getPersistableWallet(): PersistableWallet? =
        walletFlow.value.also { beforeReadReturns() }

    override suspend fun requirePersistableWallet(): PersistableWallet = checkNotNull(walletFlow.value)

    suspend fun clearForReset() {
        writeAccess.beginReset()
        walletFlow.value = null
        writeAccess.finishReset()
    }

    private fun write(wallet: PersistableWallet) {
        walletFlow.value = wallet
        // Android may update its memory cache even when commit reports a disk failure.
        if (failWrites) throw IOException("Synthetic persistence failure")
        events += "wallet"
    }
}

/**
 * Reports no wallet on the first read and a stored wallet on every read after it, which is the
 * shape of the self-heal race: [WalletRepositoryImpl.init] sees an empty encrypted store, and by
 * the time the erase would run a wallet has been stored under it. The erase must not happen.
 */
private class WalletStoredAfterFirstReadProvider(
    private val storedLater: PersistableWallet
) : PersistableWalletProvider {
    private val walletFlow = MutableStateFlow<PersistableWallet?>(null)

    override val persistableWallet: Flow<PersistableWallet?> = walletFlow

    override suspend fun store(persistableWallet: PersistableWallet) {
        walletFlow.value = persistableWallet
    }

    override suspend fun storeIfCurrent(
        expected: PersistableWallet,
        replacement: PersistableWallet,
        version: Long,
    ): Boolean = error("This wallet setup fixture does not run seed-mismatch recovery")

    override suspend fun getPersistableWallet(): PersistableWallet? =
        walletFlow.value.also { walletFlow.value = storedLater }

    override suspend fun requirePersistableWallet(): PersistableWallet = checkNotNull(walletFlow.value)
}

/**
 * A hand-written [PreferenceProvider] stand-in, not a MockK proxy: [PreferenceProvider]'s methods
 * take the [PreferenceKey] value class, and MockK's reflection-based call recorder throws on that
 * combination for suspend members. [observe] re-emits after every [putString] the way the real
 * `AndroidPreferenceProvider` re-emits on every preference change, so
 * `StandardPreferenceKeys.ONBOARDING_STATE.observe(...)` reacts to writes made through this fake.
 */
private class RecordingPreferenceProvider(
    private val events: MutableList<String>
) : PreferenceProvider {
    private val values = mutableMapOf<String, String?>()
    private val changes =
        MutableSharedFlow<Unit>(replay = 1).apply {
            tryEmit(Unit)
        }

    /**
     * Sets [key]'s stored value directly, without recording it as an event or notifying
     * [observe] collectors — test setup, not an action under test.
     */
    fun seed(
        key: PreferenceKey,
        value: String?
    ) {
        values[key.key] = value
    }

    override suspend fun hasKey(key: PreferenceKey) = values.containsKey(key.key)

    override suspend fun putString(
        key: PreferenceKey,
        value: String?
    ) {
        values[key.key] = value
        if (key == StandardPreferenceKeys.ONBOARDING_STATE.key) {
            events += "onboarding=$value"
        }
        changes.emit(Unit)
    }

    override suspend fun putStringSet(
        key: PreferenceKey,
        value: Set<String>?
    ) = Unit

    override suspend fun putLong(
        key: PreferenceKey,
        value: Long?
    ) = Unit

    override suspend fun getLong(key: PreferenceKey): Long? = null

    override suspend fun getString(key: PreferenceKey): String? = values[key.key]

    override suspend fun getStringSet(key: PreferenceKey): Set<String>? = null

    override fun observe(key: PreferenceKey): Flow<String?> = changes.map { getString(key) }

    override suspend fun remove(key: PreferenceKey) {
        values.remove(key.key)
    }

    override suspend fun clearPreferences(): Boolean {
        values.clear()
        return true
    }
}
