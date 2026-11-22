package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.GiftCardRedeemer
import cash.z.ecc.android.sdk.GiftCardRedeemers
import cash.z.ecc.android.sdk.WalletCoordinator
import cash.z.ecc.android.sdk.model.GiftCard
import cash.z.ecc.android.sdk.model.RecipientAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.StandardPreferenceProvider
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ADDRESS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.ALIAS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.AMOUNT
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.FEE
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.LINK
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.OTHER_ALIAS
import co.electriccoin.zcash.ui.common.datasource.GiftCardDataSourceTestValues.TX_ID
import co.electriccoin.zcash.ui.common.model.GiftCardPhase
import co.electriccoin.zcash.ui.common.repository.GiftCardLinkStoreImpl
import co.electriccoin.zcash.ui.common.repository.GiftCardRepositoryImpl
import co.electriccoin.zcash.ui.screen.deletewallet.ResetProgressStore
import co.electriccoin.zcash.ui.screen.deletewallet.ResetStage
import co.electriccoin.zcash.ui.screen.deletewallet.ResetZashiUseCase
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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
        fixture.repository.redeem(fixture.id) { ADDRESS }
        runCurrent()
        coVerify(exactly = 1) { redeemer.redeem(recipient, any(), mainSynchronizer) }

        val reset = launch { fixture.reset(keepFiles = true) }
        runCurrent()

        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { redeemer.close() }
        assertFalse(fixture.reset.isPending.value)

        submission.complete(Unit)
        runCurrent()

        coVerify(exactly = 1) { redeemer.close() }
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { fixture.progressStore.store(any()) }

        close.complete(Unit)
        reset.join()

        verify(exactly = 1) { fixture.walletCoordinator.deleteSdkDataFlow() }
        verify(exactly = 1) { parsedCard.wipe() }
        coVerify(exactly = 1) { fixture.progressStore.store(ResetStage.ERASE_SDK) }
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
        fixture.repository.redeem(fixture.id) { ADDRESS }
        recipientRequested.await()

        fixture.reset(keepFiles = true)
        recipientReady.complete(recipient)
        runCurrent()

        coVerify(exactly = 0) { redeemer.redeem(any(), any(), any()) }
        coVerify(exactly = 1) { redeemer.close() }
        verify(exactly = 1) { fixture.walletCoordinator.deleteSdkDataFlow() }
    }

    @Test
    fun resetWaitsForTheExactCardWalletFilesAfterCloseReturns() = runTest {
        val fixture = resetFixture()
        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Mainnet) } returnsMany
            listOf(setOf(ALIAS, OTHER_ALIAS), setOf(OTHER_ALIAS))
        val reset = launch { fixture.reset(keepFiles = true) }
        runCurrent()

        coVerify(exactly = 1) { redeemer.close() }
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        reset.join()

        verify(exactly = 1) { fixture.walletCoordinator.deleteSdkDataFlow() }
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
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { fixture.progressStore.store(any()) }
        val stoppedAt = lookups
        advanceTimeBy(31.seconds)
        assertEquals(stoppedAt, lookups)

        coEvery { GiftCardRedeemers.storedAliases(any(), ZcashNetwork.Mainnet) } coAnswers {
            lookups++
            emptySet()
        }
        fixture.reset(keepFiles = false)

        assertEquals(stoppedAt + 1, lookups)
        coVerify(exactly = 1) { redeemer.close() }
        coVerify(exactly = 0) { redeemer.redeem(any(), any(), any()) }
        verify(exactly = 1) { fixture.walletCoordinator.deleteSdkDataFlow() }
    }

    @Test
    fun aFailedNativeCloseBlocksRepeatedResetBeforeErasure() = runTest {
        val fixture = resetFixture()
        coEvery { redeemer.close() } throws IllegalStateException("database is locked")

        repeat(2) { assertFailsWith<IllegalStateException> { fixture.reset(keepFiles = false) } }

        coVerify(exactly = 1) { redeemer.close() }
        verify(exactly = 1) { parsedCard.wipe() }
        coVerify(exactly = 0) { fixture.progressStore.store(any()) }
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
    }

    @Test
    fun resetCanBeRetriedAfterAnInFlightRedemptionExceedsItsWaitBudget() = runTest {
        val submission = CompletableDeferred<Unit>()
        coEvery { redeemer.redeem(recipient, any(), any()) } coAnswers {
            submission.await()
            error("Submission outcome unknown")
        }
        val fixture = resetFixture()
        fixture.repository.redeem(fixture.id) { ADDRESS }
        runCurrent()

        val first = async { runCatching { fixture.reset(keepFiles = true) } }
        advanceTimeBy(31.seconds)
        assertEquals(
            "Timed out preparing wallet reset",
            assertIs<IllegalStateException>(first.await().exceptionOrNull()).message,
        )
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        coVerify(exactly = 0) { fixture.progressStore.store(any()) }
        coVerify(exactly = 0) { redeemer.close() }

        val blocked = fixture.repository.observeSession(fixture.store.stash(LINK)).first()
        assertIs<GiftCardPhase.Failed>(blocked.phase)
        coVerify(exactly = 1) { GiftCard.parse(LINK) }

        val retry = launch { fixture.reset(keepFiles = true) }
        runCurrent()
        verify(exactly = 0) { fixture.walletCoordinator.deleteSdkDataFlow() }
        submission.complete(Unit)
        retry.join()

        coVerify(exactly = 1) { redeemer.redeem(recipient, any(), any()) }
        coVerify(exactly = 0) { replacementRedeemer.redeem(any(), any(), any()) }
        verify(exactly = 1) { fixture.walletCoordinator.deleteSdkDataFlow() }
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
        val walletCoordinator = mockk<WalletCoordinator> {
            every { deleteSdkDataFlow() } returns flowOf(true)
        }
        val progressStore = mockk<ResetProgressStore>(relaxed = true) {
            every { isPending } returns MutableStateFlow(false)
            coEvery { getStage() } returns null
        }
        val reset = ResetZashiUseCase(
            walletCoordinator = walletCoordinator,
            synchronizerProvider = mockk {
                coEvery { getSynchronizer() } returns mockk<CloseableSynchronizer>(relaxed = true)
            },
            standardPreferenceProvider = mockk<StandardPreferenceProvider> {
                coEvery { this@mockk.invoke() } returns mockk {
                    coEvery { clearPreferences() } returns true
                }
            },
            encryptedPreferenceProvider = mockk<EncryptedPreferenceProvider> {
                coEvery { this@mockk.invoke() } returns mockk {
                    coEvery { clearPreferences() } returns true
                }
                coEvery { purgeQuarantine() } returns true
            },
            homeMessageCacheRepository = mockk(relaxed = true),
            biometricRepository = mockk(relaxed = true),
            addressBookRepository = mockk(relaxed = true),
            metadataRepository = mockk(relaxed = true),
            migrationAppHooks = mockk(relaxed = true),
            giftCardRepository = repository,
            progressStore = progressStore,
        )
    }
}
