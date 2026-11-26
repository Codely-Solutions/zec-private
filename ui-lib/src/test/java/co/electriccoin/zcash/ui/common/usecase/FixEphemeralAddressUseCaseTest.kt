package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.MetadataReadUnavailableException
import co.electriccoin.zcash.ui.common.model.SwapQuoteStatus
import co.electriccoin.zcash.ui.common.model.ZcashTransparentSwapAddress
import kotlinx.coroutines.delay
import co.electriccoin.zcash.ui.common.model.AppAccessSession
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.IsTorEnabledStorageProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.TorOnlyRequest
import co.electriccoin.zcash.ui.common.provider.WalletWriteAccess
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import co.electriccoin.zcash.ui.ActiveNavigationEntry
import co.electriccoin.zcash.ui.PendingNavigation
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FixEphemeralAddressUseCaseTest {
    @Test
    fun backgroundedSuccessfulScanKeepsAnOwnerBoundCompletionForTheDialog() = backgroundedScan(false)

    @Test
    fun aLateSuccessfulScanReturningAfterRelockIsSuperseded() = backgroundedScan(true)

    private fun backgroundedScan(relockBeforeCompletion: Boolean) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = mockk<WalletAccount> {
                every { isSelected } returns true
                every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16)))
            }
            val accounts = mockk<AccountDataSource> {
                every { allAccounts } returns MutableStateFlow(listOf(owner))
            }
            val entry = ActiveNavigationEntry("discover", "Discover")
            val active = MutableStateFlow<ActiveNavigationEntry?>(entry)
            val pending = PendingNavigation()
            val navigation = mockk<NavigationRouter>(relaxed = true) {
                every { activeEntry } returns active
                every { pendingNavigation } returns pending
            }
            val scanStarted = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Boolean>()
            val sdk = mockk<Synchronizer> {
                coEvery { validateAddress("synthetic-address") } returns AddressType.Transparent
                coEvery { fetchUtxosByAddress(any(), any()) } coAnswers {
                    scanStarted.complete(Unit)
                    finishScan.await()
                }
            }
            val provider = mockk<SynchronizerProvider> { coEvery { getSynchronizer() } returns sdk }
            val access = AppAccessSession().apply { unlock() }
            val tor = mockk<IsTorEnabledStorageProvider> { coEvery { get() } returns true }
            val recover = FixEphemeralAddressUseCase(
                provider, navigation, accounts, mockk(), mockk(), access, WalletWriteAccess(), tor,
            )
            val completion = async { recover("synthetic-address", entry.id) }
            scanStarted.await()
            active.value = entry.copy(hostResumed = false)
            if (relockBeforeCompletion) {
                access.lock()
                access.unlock()
            }
            finishScan.complete(true)

            val outcome = completion.await()
            verify(exactly = 0) { navigation.back() }
            if (relockBeforeCompletion) {
                assertEquals(DiscoveryOutcome.Superseded, outcome)
                assertTrue(pending.commands.value.isEmpty())
            } else {
                val confirmed = assertIs<DiscoveryOutcome.Confirmed>(outcome)
                val result = assertNotNull(pending.commands.value[entry.id])
                active.value = entry
                assertTrue(confirmed.ownership.first())
                assertTrue(result.isStillAllowed())
                access.lock()
                access.unlock()
                assertFalse(confirmed.ownership.first())
                assertFalse(result.isStillAllowed())
            }
            coVerify(exactly = 1) { sdk.fetchUtxosByAddress(any(), any()) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedOrInconclusiveDiscoveryKeepsItsRecoveryAction() = runTest {
        for (failure in Failure.entries) {
            var reads = 0
            val tor = mockk<IsTorEnabledStorageProvider> {
                coEvery { get() } coAnswers {
                    val read = ++reads
                    check(read != failure.failedRead) { "Synthetic unavailable Tor setting" }
                    true
                }
            }
            val account = mockk<WalletAccount> {
                every { isSelected } returns true
                every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16)))
            }
            val accounts = mockk<AccountDataSource> {
                every { allAccounts } returns MutableStateFlow(listOf(account))
            }
            val synchronizer = mockk<Synchronizer> {
                coEvery { validateAddress("synthetic-address") } returns when (failure) {
                    Failure.STATUS_READ -> mockk<AddressType.Invalid>()
                    else -> AddressType.Transparent
                }
                coEvery { fetchUtxosByAddress(any(), any()) } coAnswers {
                    if (failure == Failure.SDK_THROW) throw IOException("Synthetic failed scan")
                    false
                }
            }
            val status = mockk<GetSwapStatusUseCase> {
                every { observeForAccount("synthetic-address", account, any()) } returns flow {
                    assertNotNull(currentCoroutineContext()[TorOnlyRequest.Key])
                    emit(SwapQuoteStatusData(isLoading = false, error = IOException("Synthetic unavailable status")))
                }
            }
            val provider = mockk<SynchronizerProvider> {
                coEvery { getSynchronizer() } returns synchronizer
            }
            val navigation = mockk<NavigationRouter>(relaxed = true)
            val recovery = FixEphemeralAddressUseCase(
                synchronizerProvider = provider,
                navigationRouter = navigation,
                accountDataSource = accounts,
                getSwapStatus = status,
                context = mockk(),
                appAccessSession = AppAccessSession().apply { unlock() },
                walletWriteAccess = WalletWriteAccess(),
                isTorEnabled = tor,
            )
            assertEquals(DiscoveryOutcome.Unconfirmed, recovery("synthetic-address", sourceEntryId = null))

            coVerify(exactly = failure.expectedReads) { tor.get() }
            val lookups = if (failure == Failure.SDK_FALSE || failure == Failure.SDK_THROW) 1 else 0
            coVerify(exactly = lookups) { synchronizer.fetchUtxosByAddress(any(), any()) }
            val statusRequests = if (failure == Failure.STATUS_READ) 1 else 0
            verify(exactly = statusRequests) { status.observeForAccount("synthetic-address", account, any()) }
            verify(exactly = 0) { navigation.back() }
        }
    }

    @Test
    fun boundedRecoveryKeepsItsTorRouteThroughATemporaryMetadataReadFailure() = runTest {
        val uuid = AccountUuid.new(ByteArray(16))
        val owner = mockk<WalletAccount> {
            every { isSelected } returns true
            every { sdkAccount } returns Account.new(uuid)
        }
        val accounts = mockk<AccountDataSource> {
            every { allAccounts } returns MutableStateFlow(listOf(owner))
        }
        val entry = ActiveNavigationEntry("discover", "Discover")
        val navigation = mockk<NavigationRouter>(relaxed = true) {
            every { activeEntry } returns MutableStateFlow(entry)
        }
        val resolved = mockk<SwapQuoteStatus> {
            every { originAsset } returns mockk()
            every { destinationAsset } returns mockk()
            every { destinationAddress } returns ZcashTransparentSwapAddress("synthetic-resolution")
        }
        val status = mockk<GetSwapStatusUseCase> {
            every { observeForAccount("synthetic-reference", owner, any()) } returns flow {
                assertNotNull(currentCoroutineContext()[TorOnlyRequest.Key])
                emit(SwapQuoteStatusData(isLoading = false, error = MetadataReadUnavailableException()))
                delay(1_000)
                emit(SwapQuoteStatusData(status = resolved, isLoading = false))
            }
        }
        val sdk = mockk<Synchronizer> {
            coEvery { validateAddress("synthetic-reference") } returns mockk<AddressType.Invalid>()
            coEvery { fetchUtxosByAddress(uuid, "synthetic-resolution") } returns false
        }
        val provider = mockk<SynchronizerProvider> { coEvery { getSynchronizer() } returns sdk }
        val tor = mockk<IsTorEnabledStorageProvider> { coEvery { get() } returns true }
        val recover = FixEphemeralAddressUseCase(
            provider, navigation, accounts, status, mockk(),
            AppAccessSession().apply { unlock() }, WalletWriteAccess(), tor,
        )

        assertEquals(DiscoveryOutcome.Unconfirmed, recover("synthetic-reference", entry.id))
        coVerify(exactly = 1) { sdk.fetchUtxosByAddress(uuid, "synthetic-resolution") }
    }

    @Test
    fun backgroundingTheSameDialogCancelsItsSuspendedStatusLookup() = runTest {
        val owner = mockk<WalletAccount> {
            every { isSelected } returns true
            every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16)))
        }
        val accounts = mockk<AccountDataSource> {
            every { allAccounts } returns MutableStateFlow(listOf(owner))
        }
        val entry = ActiveNavigationEntry("discover", "Discover")
        val active = MutableStateFlow<ActiveNavigationEntry?>(entry)
        val navigation = mockk<NavigationRouter>(relaxed = true) { every { activeEntry } returns active }
        val statusStarted = CompletableDeferred<Unit>()
        val statusCancelled = CompletableDeferred<Unit>()
        val status = mockk<GetSwapStatusUseCase> {
            every { observeForAccount("synthetic-reference", owner, any()) } returns flow {
                assertNotNull(currentCoroutineContext()[TorOnlyRequest.Key])
                statusStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    statusCancelled.complete(Unit)
                }
            }
        }
        val sdk = mockk<Synchronizer> {
            coEvery { validateAddress("synthetic-reference") } returns mockk<AddressType.Invalid>()
        }
        val synchronizerProvider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } returns sdk
        }
        val tor = mockk<IsTorEnabledStorageProvider> { coEvery { get() } returns true }
        val recover = FixEphemeralAddressUseCase(
            synchronizerProvider, navigation, accounts, status, mockk(),
            AppAccessSession().apply { unlock() }, WalletWriteAccess(), tor,
        )
        val lookup = async { recover("synthetic-reference", entry.id) }
        statusStarted.await()

        active.value = entry.copy(hostResumed = false)

        assertFailsWith<CancellationException> { lookup.await() }
        statusCancelled.await()
        coVerify(exactly = 0) { sdk.fetchUtxosByAddress(any(), any()) }
    }

    @Test
    fun closingTheDialogCancelsItsLookupAndAnUnansweredLookupStopsWaiting() = runTest {
        val owner = mockk<WalletAccount> {
            every { isSelected } returns true
            every { sdkAccount } returns Account.new(AccountUuid.new(ByteArray(16)))
        }
        val accounts = mockk<AccountDataSource> {
            every { allAccounts } returns MutableStateFlow(listOf(owner))
        }
        val active = MutableStateFlow<ActiveNavigationEntry?>(ActiveNavigationEntry("discover", "Discover"))
        val navigation = mockk<NavigationRouter>(relaxed = true) { every { activeEntry } returns active }
        val status = mockk<GetSwapStatusUseCase>()
        val sdk = CompletableDeferred<Synchronizer>()
        val synchronizerProvider = mockk<SynchronizerProvider> {
            coEvery { getSynchronizer() } coAnswers { sdk.await() }
        }
        val tor = mockk<IsTorEnabledStorageProvider> { coEvery { get() } returns true }
        val recover = FixEphemeralAddressUseCase(
            synchronizerProvider, navigation, accounts, status, mockk(),
            AppAccessSession().apply { unlock() }, WalletWriteAccess(), tor,
        )
        val first = async { recover("synthetic-reference", "discover") }
        runCurrent()
        active.value = ActiveNavigationEntry("home", "Home")
        assertFailsWith<CancellationException> { first.await() }
        verify(exactly = 0) { status.observeForAccount(any(), any(), any()) }

        active.value = ActiveNavigationEntry("reopened", "Discover")
        val second = async { recover("synthetic-reference", "reopened") }
        advanceTimeBy(30_001)
        assertEquals(DiscoveryOutcome.Unconfirmed, second.await())
    }

    private enum class Failure(val expectedReads: Int, val failedRead: Int? = null) {
        INITIAL_TOR(1, 1),
        FINAL_TOR(2, 2),
        STATUS_READ(1),
        SDK_FALSE(2),
        SDK_THROW(2),
    }
}
