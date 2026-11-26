package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.GiftCardRedeemer
import cash.z.ecc.android.sdk.GiftCardRedeemers
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ADDRESS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ALIAS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.AMOUNT
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.FEE
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.LINK
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.OTHER_ALIAS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.TX_ID
import co.electriccoin.zcash.ui.common.model.GiftCardPhase
import co.electriccoin.zcash.ui.common.model.GiftRedemptionDestination
import co.electriccoin.zcash.ui.common.repository.AddressBookRepository
import co.electriccoin.zcash.ui.common.repository.GiftCardLinkStoreImpl
import co.electriccoin.zcash.ui.common.repository.GiftCardRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.MetadataRepository
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.ui.screen.deletewallet.ResetStage
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase
import co.electriccoin.zcash.ui.screen.deletewallet.ResetWalletSdkDataUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Verifies wallet reset against the production gift repository and data source. */
@OptIn(ExperimentalCoroutinesApi::class)
class GiftCardResetTest : GiftCardDataSourceTestFixture() {
    @Test
    fun resetWaitsForTheStartedRedemptionAndNativeCloseBeforeErasingTheWallet() = runTest {
        val submission = CompletableDeferred<Unit>()
        val close = CompletableDeferred<Unit>()
        coEvery { redeemer.redeem(recipient, any(), any()) } coAnswers {
            submission.await()
            GiftCardRedeemer.Redemption(fee = Zatoshi(FEE), results = listOf(success(TX_ID)))
        }
        coEvery { redeemer.close() } coAnswers { close.await() }
        val fixture = resetFixture()
        fixture.repository.redeem(fixture.id) { GiftRedemptionDestination(ADDRESS) { true } }
        runCurrent()
        coVerify(exactly = 1) { redeemer.redeem(recipient, any(), mainSynchronizer) }

        val reset = launch { fixture.reset(keepFiles = true) }
        runCurrent()

        coVerify(exactly = 0) { fixture.sdkData() }
        coVerify(exactly = 0) { redeemer.close() }
        fixture.assertPreparationPending(ResetStage.ERASE_SDK)

        submission.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { redeemer.close() }
        coVerify(exactly = 0) { fixture.sdkData() }
        fixture.assertPreparationPending(ResetStage.ERASE_SDK)

        close.complete(Unit)
        reset.join()

        coVerify(exactly = 1) { fixture.sdkData() }
        verify(exactly = 1) { parsedCard.wipe() }
        coVerify(exactly = 1) { fixture.progressStore.store(ResetStage.ERASE_SDK) }
        assertFalse(fixture.reset.isPending.value)
    }

    @Test
    fun resetDuringRecipientPreparationCancelsBeforeEnteringSdkRedeem() = runTest {
        val recipientRequested = CompletableDeferred<Unit>()
        val recipientReady = CompletableDeferred<RecipientAddress>()
        coEvery { RecipientAddress.new(ADDRESS, ZcashNetwork.Mainnet) } coAnswers {
            recipientRequested.complete(Unit)
            recipientReady.await()
        }
        val fixture = resetFixture()
        fixture.repository.redeem(fixture.id) { GiftRedemptionDestination(ADDRESS) { true } }
        recipientRequested.await()

        fixture.reset(keepFiles = true)
        recipientReady.complete(recipient)
        runCurrent()

        coVerify(exactly = 0) { redeemer.redeem(any(), any(), any()) }
        coVerify(exactly = 1) { redeemer.close() }
        coVerify(exactly = 1) { fixture.sdkData() }
    }

    @Test
    fun resetWaitsForTheExactCardWalletFilesAfterCloseReturns() = runTest {
        val fixture = resetFixture()
        var aliases = setOf(ALIAS, OTHER_ALIAS)
        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Mainnet) } answers { aliases }
        coEvery { GiftCardRedeemers.erase(any(), ZcashNetwork.Mainnet, OTHER_ALIAS) } coAnswers {
            aliases -= OTHER_ALIAS
            true
        }
        val reset = launch { fixture.reset(keepFiles = true) }
        runCurrent()

        coVerify(exactly = 1) { redeemer.close() }
        coVerify(exactly = 0) { GiftCardRedeemers.erase(any(), any(), any()) }
        coVerify(exactly = 0) { fixture.sdkData() }
        aliases -= ALIAS
        reset.join()

        coVerify(exactly = 1) { GiftCardRedeemers.erase(any(), ZcashNetwork.Mainnet, OTHER_ALIAS) }
        coVerify(exactly = 1) { fixture.sdkData() }
    }

    @Test
    fun aCardWalletThatRemainsStoredBoundsClosureWaitingAndCanBeCheckedAgain() = runTest {
        val dataSource = dataSource(this)
        dataSource.close(dataSource.parse(LINK).summary.handle)
        var lookups = 0
        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Mainnet) } coAnswers {
            lookups++
            setOf(ALIAS)
        }
        val waiting = async { runCatching { dataSource.awaitPendingCloses() } }

        advanceTimeBy(31.seconds)
        assertEquals(
            "Timed out waiting for gift card wallet closure",
            assertIs<IllegalStateException>(waiting.await().exceptionOrNull()).message,
        )
        val stoppedAt = lookups
        advanceTimeBy(31.seconds)
        assertEquals(stoppedAt, lookups)

        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Mainnet) } coAnswers {
            lookups++
            emptySet()
        }
        dataSource.awaitPendingCloses()

        assertEquals(stoppedAt + 1, lookups)
        coVerify(exactly = 1) { redeemer.close() }
    }

    @Test
    fun resetTimeoutStopsCheckingBeforeRetryAfterTheCardWalletDisappears() = runTest {
        val fixture = resetFixture()
        var lookups = 0
        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Mainnet) } coAnswers {
            lookups++
            setOf(ALIAS)
        }
        val first = async { runCatching { fixture.reset(keepFiles = false) } }

        advanceTimeBy(31.seconds)
        assertIs<IllegalStateException>(first.await().exceptionOrNull())
        coVerify(exactly = 0) { fixture.sdkData() }
        fixture.assertPreparationPending(ResetStage.DELETE_LOCAL_FILES)
        val stoppedAt = lookups
        advanceTimeBy(31.seconds)
        assertEquals(stoppedAt, lookups)

        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Mainnet) } coAnswers {
            lookups++
            emptySet()
        }
        fixture.reset(keepFiles = false)

        assertEquals(stoppedAt + 2, lookups)
        coVerify(exactly = 1) { redeemer.close() }
        coVerify(exactly = 0) { redeemer.redeem(any(), any(), any()) }
        coVerify(exactly = 1) { fixture.sdkData() }
    }

    @Test
    fun aFailedNativeCloseBlocksRepeatedResetBeforeErasure() = runTest {
        val fixture = resetFixture()
        coEvery { redeemer.close() } throws IllegalStateException("database is locked")

        repeat(2) { assertFailsWith<GiftWalletRestartRequiredException> { fixture.reset(keepFiles = false) } }

        coVerify(exactly = 1) { redeemer.close() }
        verify(exactly = 1) { parsedCard.wipe() }
        fixture.assertPreparationPending(ResetStage.DELETE_LOCAL_FILES, attempts = 2)
        coVerify(exactly = 0) { fixture.sdkData() }
    }

    @Test
    fun resetCanBeRetriedAfterAnInFlightRedemptionExceedsItsWaitBudget() = runTest {
        val submission = CompletableDeferred<Unit>()
        coEvery { redeemer.redeem(recipient, any(), any()) } coAnswers {
            submission.await()
            error("Submission outcome unknown")
        }
        val fixture = resetFixture()
        fixture.repository.redeem(fixture.id) { GiftRedemptionDestination(ADDRESS) { true } }
        runCurrent()

        val first = async { runCatching { fixture.reset(keepFiles = true) } }
        advanceTimeBy(31.seconds)
        assertEquals(
            "Timed out preparing wallet reset",
            assertIs<IllegalStateException>(first.await().exceptionOrNull()).message,
        )
        coVerify(exactly = 0) { fixture.sdkData() }
        fixture.assertPreparationPending(ResetStage.ERASE_SDK)
        coVerify(exactly = 0) { redeemer.close() }

        val blocked = fixture.repository.observeSession(fixture.store.stash(LINK)).first()
        assertIs<GiftCardPhase.Failed>(blocked.phase)
        coVerify(exactly = 1) { GiftCard.parse(LINK) }

        val retry = launch { fixture.reset(keepFiles = true) }
        runCurrent()
        coVerify(exactly = 0) { fixture.sdkData() }
        submission.complete(Unit)
        retry.join()

        coVerify(exactly = 1) { redeemer.redeem(recipient, any(), any()) }
        coVerify(exactly = 0) { replacementRedeemer.redeem(any(), any(), any()) }
        coVerify(exactly = 1) { fixture.sdkData() }
    }

    @Test
    fun resetRetriesAndVerifiesAnOrphanLeftByFailedStartupSweep() = runTest {
        val store = GiftCardLinkStoreImpl()
        val dataSource = dataSource(this).also { it.scope = backgroundScope }
        val repository = GiftCardRepositoryImpl(dataSource, store).also { it.scope = backgroundScope }
        val fixture = ResetFixture(repository, store, "unused-session")
        val network = ZcashNetwork.Mainnet
        var aliases = setOf(ALIAS)
        var eraseCalls = 0
        coEvery { GiftCardRedeemers.storedAliases(any(), network) } answers { aliases }
        coEvery { GiftCardRedeemers.erase(any(), network, ALIAS) } coAnswers {
            when (++eraseCalls) {
                1 -> throw java.io.IOException("Startup cleanup failure")
                2 -> false
                else -> {
                    aliases = emptySet()
                    true
                }
            }
        }

        repository.sweepOrphanedCardWallets()
        assertEquals(1, eraseCalls)
        assertEquals(listOf(StoredCardWallet(network, ALIAS)), dataSource.findStoredCardWallets())

        assertFailsWith<IllegalStateException> { fixture.reset(keepFiles = false) }
        fixture.assertPreparationPending(ResetStage.DELETE_LOCAL_FILES)
        coVerify(exactly = 0) { fixture.sdkData() }
        assertEquals(2, eraseCalls)
        assertEquals(listOf(StoredCardWallet(network, ALIAS)), dataSource.findStoredCardWallets())

        fixture.reset(keepFiles = false)
        assertFalse(fixture.reset.isPending.value)
        coVerify(exactly = 1) { fixture.sdkData() }
        assertEquals(3, eraseCalls)
        assertTrue(dataSource.findStoredCardWallets().isEmpty())
    }

    @Test
    fun resetRetriesOrphanEnumerationBeforeClearingTheWallet() = runTest {
        val store = GiftCardLinkStoreImpl()
        val dataSource = dataSource(this).also { it.scope = backgroundScope }
        val repository = GiftCardRepositoryImpl(dataSource, store).also { it.scope = backgroundScope }
        val fixture = ResetFixture(repository, store, "unused-session")
        var aliases = setOf(ALIAS)
        var lookups = 0
        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Testnet) } coAnswers {
            if (++lookups <= 2) throw java.io.IOException("Cannot list helper wallets")
            aliases
        }
        coEvery { GiftCardRedeemers.erase(any(), ZcashNetwork.Testnet, ALIAS) } coAnswers {
            aliases = emptySet()
            true
        }

        repository.sweepOrphanedCardWallets()
        assertFailsWith<java.io.IOException> { fixture.reset(keepFiles = false) }
        fixture.assertPreparationPending(ResetStage.DELETE_LOCAL_FILES)
        coVerify(exactly = 0) { fixture.sdkData() }

        fixture.reset(keepFiles = false)
        coVerify(exactly = 1) { GiftCardRedeemers.erase(any(), ZcashNetwork.Testnet, ALIAS) }
        coVerify(exactly = 1) { fixture.sdkData() }
        assertFalse(fixture.reset.isPending.value)
    }

    private suspend fun TestScope.resetFixture(): ResetFixture {
        val store = GiftCardLinkStoreImpl()
        val dataSource = dataSource(this).also { it.scope = backgroundScope }
        val repository = GiftCardRepositoryImpl(dataSource, store).also { it.scope = backgroundScope }
        val balance = GiftCardRedeemer.Balance(
            total = Zatoshi(AMOUNT),
            spendable = Zatoshi(AMOUNT),
            pending = Zatoshi(0)
        )
        coEvery { redeemer.check() } returns GiftCardRedeemer.Status.Ready(balance, fee = Zatoshi(FEE))
        val id = store.stash(LINK)
        repository.observeSession(id).first { it.phase is GiftCardPhase.Ready }
        return ResetFixture(repository, store, id)
    }

    private class ResetFixture(
        val repository: GiftCardRepositoryImpl,
        val store: GiftCardLinkStoreImpl,
        val id: String,
    ) {
        val sdkData = mockk<ResetWalletSdkDataUseCase>(relaxUnitFun = true)
        private val stage = MutableStateFlow<ResetStage?>(null)
        private val pending = MutableStateFlow(false)
        private val addressBook = mockk<AddressBookRepository>(relaxed = true)
        private val metadata = mockk<MetadataRepository>(relaxed = true)
        private val standardPrefs = mockk<PreferenceProvider> { coEvery { clearPreferences() } returns true }
        private val encryptedPrefs = mockk<PreferenceProvider> { coEvery { clearPreferences() } returns true }
        val progressStore = mockk<ResetProgressStore>(relaxed = true) {
            every { isPending } returns pending
            coEvery { getStage() } coAnswers { stage.value }
            coEvery { store(any()) } coAnswers { recordStage(firstArg()) }
            coEvery { store(null) } coAnswers { recordStage(null) }
        }
        val reset = ResetZashiUseCase(
            resetWalletSdkData = sdkData,
            standardPreferenceProvider = mockk<StandardPreferenceProvider> {
                coEvery { this@mockk.invoke() } returns standardPrefs
            },
            encryptedPreferenceProvider = mockk<EncryptedPreferenceProvider> {
                coEvery { this@mockk.invoke() } returns encryptedPrefs
                coEvery { purgeQuarantine() } returns true
            },
            homeMessageCacheRepository = mockk(relaxed = true),
            biometricRepository = mockk(relaxed = true),
            addressBookRepository = addressBook,
            metadataRepository = metadata,
            migrationAppHooks = mockk(relaxed = true),
            giftCardRepository = repository,
            progressStore = progressStore,
            deleteSharedQRImages = mockk(relaxed = true),
            deleteLegacySharedExports = mockk(relaxed = true),
        )

        fun assertPreparationPending(expected: ResetStage, attempts: Int = 1) {
            assertEquals(expected, stage.value)
            assertTrue(reset.isPending.value)
            coVerify(exactly = attempts) { progressStore.store(expected) }
            coVerify(exactly = 0) { progressStore.store(ResetStage.CLEAR_PREFERENCES) }
            coVerify(exactly = 0) { progressStore.store(null) }
            coVerify(exactly = 0) { addressBook.delete() }
            coVerify(exactly = 0) { metadata.delete() }
            coVerify(exactly = 0) { standardPrefs.clearPreferences() }
            coVerify(exactly = 0) { encryptedPrefs.clearPreferences() }
        }

        private fun recordStage(value: ResetStage?) {
            stage.value = value
            pending.value = value != null
        }
    }
}
