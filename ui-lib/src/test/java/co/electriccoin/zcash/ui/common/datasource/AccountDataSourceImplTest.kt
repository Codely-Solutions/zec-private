package co.electriccoin.zcash.ui.common.datasource

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.ledger.LedgerAccountPairing
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.preference.api.PreferenceProvider
import co.electriccoin.zcash.preference.model.entry.PreferenceKey
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccountBindingData
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.provider.LedgerAccountBindingProvider
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProvider
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProviderImpl
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.provider.WalletTransportAccess
import co.electriccoin.zcash.ui.common.usecase.NavigateToReceiveUseCase
import co.electriccoin.zcash.ui.common.viewonly.ViewOnlyContent
import co.electriccoin.zcash.ui.screen.receive.ReceiveArgs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MOB-1723: a null balances snapshot from the synchronizer must propagate as null through every
 * account's balance fields — never suppressed (a fresh wallet's accounts stay invisible) and never
 * defaulted to zero (a truth claim the wallet hasn't made yet).
 */
class AccountDataSourceImplTest {
    private val accountUuid = AccountUuid.new(ByteArray(16) { it.toByte() })
    private val account = Account.new(accountUuid)

    private val viewOnlyContent = mockk<ViewOnlyContent>(relaxed = true)

    private fun zeroBalance() = WalletBalance(Zatoshi(0), Zatoshi(0), Zatoshi(0))

    private fun accountBalance(
        available: Long,
        unshielded: Long,
    ): Map<AccountUuid, AccountBalance> =
        mapOf(
            accountUuid to
                AccountBalance(
                    sapling = zeroBalance(),
                    orchard = zeroBalance().copy(available = Zatoshi(available)),
                    ironwood = zeroBalance(),
                    unshielded = Zatoshi(unshielded),
                )
        )

    private fun dataSource(
        walletBalances: MutableStateFlow<Map<AccountUuid, AccountBalance>?>,
        sdkAccount: Account = account,
        ledgerBinding: LedgerAccountBindingData? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        storedSelection: Flow<AccountUuid?> = flowOf(null),
        accountPreferenceReads: StateFlow<Long> = MutableStateFlow(0L),
        synchronizerFlow: MutableStateFlow<Synchronizer?>? = null,
        deriveAddress: suspend () -> String = { "unified-address" },
    ): AccountDataSourceImpl {
        val synchronizer =
            mockk<Synchronizer> {
                every { accountsFlow } returns MutableStateFlow(listOf(sdkAccount))
                every { this@mockk.walletBalances } returns walletBalances
                coEvery { getCustomUnifiedAddress(any(), any()) } coAnswers { deriveAddress() }
                coEvery { getTransparentAddress(any()) } returns "transparent-address"
                coEvery { getSaplingAddress(any()) } returns "sapling-address"
            }
        val activeSynchronizer = synchronizerFlow?.also { it.value = synchronizer } ?: MutableStateFlow(synchronizer)
        val synchronizerProvider =
            mockk<SynchronizerProvider> {
                every { this@mockk.synchronizer } returns activeSynchronizer
            }
        val selectedAccountUUIDProvider =
            mockk<SelectedAccountUUIDProvider> {
                every { uuid } returns storedSelection
            }
        val persistableWalletProvider =
            mockk<PersistableWalletProvider> {
                every { persistableWallet } returns MutableStateFlow(mockk(relaxed = true))
            }
        val ledgerAccountBindingProvider =
            mockk<LedgerAccountBindingProvider> {
                every { observe(any()) } returns MutableStateFlow(ledgerBinding)
            }
        return AccountDataSourceImpl(
            walletTransport = WalletTransportAccess(),
            synchronizerProvider = synchronizerProvider,
            selectedAccountUUIDProvider = selectedAccountUUIDProvider,
            persistableWalletProvider = persistableWalletProvider,
            ledgerAccountBindingProvider = ledgerAccountBindingProvider,
            viewOnlyContent = viewOnlyContent,
            context = mockk(relaxed = true),
            scope = scope,
            accountPreferenceReads = accountPreferenceReads,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryWithholdsPreviousSelectionWhileTheCurrentReadIsPendingOrFails() = runTest {
        val readGeneration = MutableStateFlow(0L)
        val liveSynchronizer = MutableStateFlow<Synchronizer?>(null)
        var mayRead: CompletableDeferred<Unit>? = null
        var isReadable = true
        val source = dataSource(
            MutableStateFlow(null),
            scope = backgroundScope,
            storedSelection = flow {
                mayRead?.await()
                check(isReadable) { "Synthetic selection read failure" }
                emit(accountUuid)
            },
            accountPreferenceReads = readGeneration,
            synchronizerFlow = liveSynchronizer,
        )
        runCurrent()
        assertTrue(assertNotNull(source.allAccounts.value).single().isSelected)

        val pending = CompletableDeferred<Unit>()
        mayRead = pending
        val synchronizer = assertNotNull(liveSynchronizer.value)
        liveSynchronizer.value = null
        readGeneration.value++
        runCurrent()
        assertEquals(AccountSelectionReadState.CHECKING, source.accountSelectionReadState.first().state)
        assertFalse(assertNotNull(source.allAccounts.value).single().isSelected)
        assertNull(source.selectedAccount.first())

        isReadable = false
        pending.complete(Unit)
        runCurrent()
        assertEquals(AccountSelectionReadState.UNAVAILABLE, source.accountSelectionReadState.first().state)
        assertNull(source.selectedAccount.first())

        isReadable = true
        mayRead = null
        readGeneration.value++
        liveSynchronizer.value = synchronizer
        runCurrent()
        assertEquals(AccountSelectionReadState.VERIFIED, source.accountSelectionReadState.first().state)
        assertEquals(accountUuid, assertNotNull(source.selectedAccount.first()).sdkAccount.accountUuid)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun receiveOpensWhileTheFirstAddressIsStillLoading() = runTest {
        val initialAddress = CompletableDeferred<String>()
        val source = dataSource(MutableStateFlow(null), scope = backgroundScope) { initialAddress.await() }
        val navigation = mockk<NavigationRouter>(relaxed = true)
        runCurrent()
        assertNull(source.allAccounts.value)

        val receive = launch { NavigateToReceiveUseCase(navigation, source)() }
        runCurrent()
        assertTrue(receive.isCompleted)
        verify(exactly = 1) { navigation.forward(ReceiveArgs) }
        assertFailsWith<FreshShieldedAddressUnavailableException> {
            source.requestNextShieldedAddress(requireFresh = true)
        }

        initialAddress.complete("initial-address")
        runCurrent()
        assertEquals("initial-address", assertNotNull(source.allAccounts.value).single().unifiedAddress)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pendingRotationCannotExtendTheNextRequestsDeadline() = runTest {
        var calls = 0
        val rotationStarted = CompletableDeferred<Unit>()
        val releaseRotation = CompletableDeferred<Unit>()
        val source = dataSource(MutableStateFlow(null), scope = backgroundScope) {
            when (++calls) {
                1 -> "initial-address"
                2 -> {
                    rotationStarted.complete(Unit)
                    releaseRotation.await()
                    "rotated-address"
                }
                else -> "new-address"
            }
        }
        runCurrent()
        val account = assertNotNull(source.allAccounts.value).single()
        val first = launch { source.requestNextShieldedAddress(requireFresh = true, account = account) }
        runCurrent()
        assertTrue(rotationStarted.isCompleted)
        first.cancelAndJoin()

        val receive = async { source.requestNextShieldedAddress(account = account) }
        val swap = async { runCatching { source.requestNextShieldedAddress(requireFresh = true, account = account) } }
        runCurrent()
        advanceTimeBy(14_999)
        assertFalse(receive.isCompleted)
        assertFalse(swap.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(receive.isCompleted)
        assertEquals("initial-address", receive.await())
        assertTrue(swap.isCompleted)
        assertTrue(swap.await().exceptionOrNull() is FreshShieldedAddressUnavailableException)
        assertEquals(2, calls)

        releaseRotation.complete(Unit)
        runCurrent()
        assertEquals("rotated-address", assertNotNull(source.allAccounts.value).single().unifiedAddress)
        assertEquals("new-address", source.requestNextShieldedAddress(requireFresh = true, account = account))
    }

    @Test
    fun replacementAndUnavailableSnapshotsDoNotAccumulatePreviousBalances() = runBlocking {
        val walletBalances = MutableStateFlow<Map<AccountUuid, AccountBalance>?>(null)
        val source = dataSource(walletBalances)
        val observed = source.allAccounts.filterNotNull()
        val states = listOf(
            accountBalance(available = 100L, unshielded = 5L) to 105L,
            accountBalance(available = 40L, unshielded = 0L) to 40L,
            null to null,
            accountBalance(available = 40L, unshielded = 0L) to 40L,
            accountBalance(available = 47L, unshielded = 0L) to 47L,
        )

        for ((snapshot, expected) in states) {
            walletBalances.value = snapshot
            val account = withTimeout(5_000) {
                observed.first { accounts -> accounts.single().totalBalance?.value == expected }.single()
            }
            assertEquals(expected, account.totalBalance?.value)
        }
    }

    @Test
    fun nullBalancesMapEmitsAccountWithNullBalancesRatherThanNoEmissionOrZeros() =
        runBlocking {
            val walletBalances = MutableStateFlow<Map<AccountUuid, AccountBalance>?>(null)
            val dataSource = dataSource(walletBalances)

            val accounts = withTimeout(5_000) { dataSource.allAccounts.filterNotNull().first() }

            assertEquals(1, accounts.size)
            val zashiAccount = accounts.single() as ZodlAccount
            assertNull(zashiAccount.unifiedBalance)
            assertNull(zashiAccount.transparentBalance)
            assertNull(zashiAccount.saplingBalance)
            assertNull(zashiAccount.ironwoodBalance)
            assertNull(zashiAccount.totalBalance)
            assertNull(zashiAccount.totalShieldedBalance)
            assertNull(zashiAccount.totalTransparentBalance)
            assertNull(zashiAccount.spendableShieldedBalance)
            assertNull(zashiAccount.pendingShieldedBalance)
            assertNull(zashiAccount.isShieldedPending)
            assertNull(zashiAccount.isShieldingAvailable)
            assertNull(zashiAccount.isAllShielded)
            assertNull(zashiAccount.canSpend(Zatoshi(0)))
        }

    @Test
    fun realBalancesSnapshotPopulatesRealValues() =
        runBlocking {
            val walletBalances =
                MutableStateFlow<Map<AccountUuid, AccountBalance>?>(
                    accountBalance(available = 500_000L, unshielded = 250_000L)
                )
            val dataSource = dataSource(walletBalances)

            val accounts = withTimeout(5_000) { dataSource.allAccounts.filterNotNull().first() }

            assertEquals(1, accounts.size)
            val zashiAccount = accounts.single() as ZodlAccount
            val unifiedBalance = zashiAccount.unifiedBalance
            assertNotNull(unifiedBalance)
            assertEquals(Zatoshi(500_000L), unifiedBalance.available)
            assertEquals(Zatoshi(250_000L), zashiAccount.transparentBalance)
            assertEquals(Zatoshi(0), zashiAccount.saplingBalance?.available)
            assertEquals(Zatoshi(0), zashiAccount.ironwoodBalance?.available)
            assertEquals(Zatoshi(750_000L), zashiAccount.totalBalance)
            assertEquals(Zatoshi(500_000L), zashiAccount.spendableShieldedBalance)
        }

    @Test
    fun ledgerKeySourceMapsToLedgerAccountCarryingItsStoredBinding() =
        runBlocking {
            val ledgerAccount =
                mockk<Account> {
                    every { accountUuid } returns this@AccountDataSourceImplTest.accountUuid
                    every { keySource } returns "ledger"
                    every { ufvk } returns null
                    every { hdAccountIndex } returns null
                }
            val dataSource =
                dataSource(
                    walletBalances =
                        MutableStateFlow(accountBalance(available = 100L, unshielded = 0L)),
                    sdkAccount = ledgerAccount,
                    ledgerBinding =
                        LedgerAccountBindingData(
                            deviceIdentityEncoding = "tpk0-deadbeef",
                            zip32AccountIndex = Zip32AccountIndex.new(3L),
                        ),
                )

            val accounts = withTimeout(5_000) { dataSource.allAccounts.filterNotNull().first() }

            val account = accounts.single() as LedgerAccount
            assertEquals("tpk0-deadbeef", account.deviceIdentity)
            assertEquals(Zip32AccountIndex.new(3L), account.hdAccountIndex)
            assertNull(account.saplingAddress)
            assertNull(account.saplingBalance)
            assertEquals(Zatoshi(100L), account.spendableShieldedBalance)
        }

    @Test
    fun ledgerAccountWithoutStoredBindingStillDisplaysButHasNoIndex() =
        runBlocking {
            val ledgerAccount =
                mockk<Account> {
                    every { accountUuid } returns this@AccountDataSourceImplTest.accountUuid
                    every { keySource } returns "ledger"
                    every { ufvk } returns null
                    every { hdAccountIndex } returns null
                }
            val dataSource =
                dataSource(
                    walletBalances = MutableStateFlow(null),
                    sdkAccount = ledgerAccount,
                    ledgerBinding = null,
                )

            val accounts = withTimeout(5_000) { dataSource.allAccounts.filterNotNull().first() }

            val account = accounts.single() as LedgerAccount
            assertNull(account.deviceIdentity)
            assertNull(account.zip32AccountIndex)
            assertNull(account.hdAccountIndex)
            assertFalse(account.isBound)
        }

    @Test
    fun accountsSortDescendingAsZodlThenKeystoneThenLedger() {
        val zashi =
            ZodlAccount(
                sdkAccount = account,
                unifiedAddress = "u",
                transparentAddress = "t",
                saplingAddress = "s",
                orchardBalance = null,
                saplingBalance = null,
                ironwoodBalance = null,
                transparentBalance = null,
                isSelected = true,
            )
        val keystone =
            KeystoneAccount(
                sdkAccount = account,
                unifiedAddress = "u",
                transparentAddress = "t",
                orchardBalance = null,
                ironwoodBalance = null,
                transparentBalance = null,
                isSelected = false,
            )
        val ledger =
            LedgerAccount(
                sdkAccount = account,
                unifiedAddress = "u",
                transparentAddress = "t",
                orchardBalance = null,
                ironwoodBalance = null,
                transparentBalance = null,
                isSelected = false,
                deviceIdentity = null,
                zip32AccountIndex = Zip32AccountIndex.new(0L),
            )

        val sorted = listOf<WalletAccount>(ledger, keystone, zashi).sortedDescending()

        assertEquals(listOf(zashi, keystone, ledger), sorted)
    }

    @Test
    fun ledgerAccountToStringDoesNotLeakTheDeviceIdentity() {
        val ledger =
            LedgerAccount(
                sdkAccount = account,
                unifiedAddress = "u1secretaddress",
                transparentAddress = "t1secretaddress",
                orchardBalance = null,
                ironwoodBalance = null,
                transparentBalance = null,
                isSelected = false,
                deviceIdentity = "tpk0-deadbeef",
                zip32AccountIndex = Zip32AccountIndex.new(0L),
            )

        val printed = ledger.toString()

        assertFalse(printed.contains("tpk0-deadbeef"))
        assertFalse(printed.contains("u1secretaddress"))
        assertFalse(printed.contains("t1secretaddress"))
    }

    @Test
    fun aBindingThatCannotBeStoredRollsBackTheImportInsteadOfKeepingAnUnsignableAccount() =
        runBlocking {
            val importedUuid = AccountUuid.new(ByteArray(16) { (it + 1).toByte() })
            val imported =
                mockk<Account> {
                    every { accountUuid } returns importedUuid
                }
            val synchronizer =
                mockk<Synchronizer>(relaxed = true) {
                    every { accountsFlow } returns MutableStateFlow(listOf(account))
                    every { walletBalances } returns MutableStateFlow(null)
                    coEvery { importAccountByUfvk(any()) } returns imported
                    coEvery { deleteAccount(importedUuid) } returns true
                }
            val bindingFailure = RuntimeException("keystore unavailable")
            val ledgerAccountBindingProvider =
                mockk<LedgerAccountBindingProvider> {
                    every { observe(any()) } returns MutableStateFlow(null)
                    coEvery { save(any(), any(), any()) } throws bindingFailure
                }
            val dataSource = dataSource(synchronizer, ledgerAccountBindingProvider)

            val thrown =
                assertFailsWith<RuntimeException> {
                    dataSource.importLedgerAccount(synchronizer, pairing = mockk(relaxed = true), birthday = null)
                }

            assertEquals(bindingFailure.message, thrown.message)
            coVerify(exactly = 1) { synchronizer.deleteAccount(importedUuid) }
        }

    @Test
    fun anImportCancelledWhileStoringTheBindingStillRollsBackToTheEnd() =
        runBlocking {
            val importedUuid = AccountUuid.new(ByteArray(16) { (it + 1).toByte() })
            val imported =
                mockk<Account> {
                    every { accountUuid } returns importedUuid
                }
            var isRolledBack = false
            val synchronizer =
                mockk<Synchronizer>(relaxed = true) {
                    every { accountsFlow } returns MutableStateFlow(listOf(account))
                    every { walletBalances } returns MutableStateFlow(null)
                    coEvery { importAccountByUfvk(any()) } returns imported
                    coEvery { deleteAccount(importedUuid) } coAnswers {
                        delay(1)
                        isRolledBack = true
                        true
                    }
                }
            val saving = CompletableDeferred<Unit>()
            val ledgerAccountBindingProvider =
                mockk<LedgerAccountBindingProvider> {
                    every { observe(any()) } returns MutableStateFlow(null)
                    coEvery { save(any(), any(), any()) } coAnswers {
                        saving.complete(Unit)
                        awaitCancellation()
                    }
                }
            val dataSource = dataSource(synchronizer, ledgerAccountBindingProvider)

            val import = launch {
                dataSource.importLedgerAccount(synchronizer, pairing = mockk(relaxed = true), birthday = null)
            }
            saving.await()
            import.cancel()
            import.join()

            assertTrue(isRolledBack)
        }

    @Test
    fun aRollbackThatAlsoFailsStillReportsTheOriginalBindingFailure() =
        runBlocking {
            val importedUuid = AccountUuid.new(ByteArray(16) { (it + 1).toByte() })
            val imported =
                mockk<Account> {
                    every { accountUuid } returns importedUuid
                }
            val synchronizer =
                mockk<Synchronizer>(relaxed = true) {
                    every { accountsFlow } returns MutableStateFlow(listOf(account))
                    every { walletBalances } returns MutableStateFlow(null)
                    coEvery { importAccountByUfvk(any()) } returns imported
                    coEvery { deleteAccount(importedUuid) } throws RuntimeException("delete failed too")
                }
            val bindingFailure = RuntimeException("keystore unavailable")
            val ledgerAccountBindingProvider =
                mockk<LedgerAccountBindingProvider> {
                    every { observe(any()) } returns MutableStateFlow(null)
                    coEvery { save(any(), any(), any()) } throws bindingFailure
                }
            val dataSource = dataSource(synchronizer, ledgerAccountBindingProvider)

            val thrown =
                assertFailsWith<RuntimeException> {
                    dataSource.importLedgerAccount(synchronizer, pairing = mockk(relaxed = true), birthday = null)
                }

            assertEquals(bindingFailure.message, thrown.message)
        }

    @Test
    fun aSuccessfulImportStoresTheBindingForTheNewAccount() =
        runBlocking {
            val importedUuid = AccountUuid.new(ByteArray(16) { (it + 1).toByte() })
            val imported = mockk<Account> { every { accountUuid } returns importedUuid }
            val synchronizer =
                mockk<Synchronizer>(relaxed = true) {
                    every { accountsFlow } returns MutableStateFlow(listOf(account))
                    every { walletBalances } returns MutableStateFlow(null)
                    coEvery { importAccountByUfvk(any()) } returns imported
                }
            val ledgerAccountBindingProvider =
                mockk<LedgerAccountBindingProvider>(relaxed = true) {
                    every { observe(any()) } returns MutableStateFlow(null)
                }
            val pairing =
                mockk<LedgerAccountPairing>(relaxed = true) {
                    every { binding } returns
                        mockk(relaxed = true) {
                            every { deviceIdentity } returns mockk { every { encoding } returns "tpk0-deadbeef" }
                            every { zip32AccountIndex } returns Zip32AccountIndex.new(4L)
                        }
                }

            dataSource(synchronizer, ledgerAccountBindingProvider)
                .importLedgerAccount(synchronizer, pairing = pairing, birthday = null)

            coVerify(exactly = 1) {
                ledgerAccountBindingProvider.save(importedUuid, "tpk0-deadbeef", 4L)
            }
            coVerify(exactly = 0) { synchronizer.deleteAccount(any()) }
        }

    @Test
    fun deletingALedgerAccountAlsoDropsItsStoredBinding() =
        runBlocking {
            val synchronizer =
                mockk<Synchronizer>(relaxed = true) {
                    every { accountsFlow } returns MutableStateFlow(listOf(account))
                    every { walletBalances } returns MutableStateFlow(null)
                    coEvery { deleteAccount(any()) } returns true
                }
            val ledgerAccountBindingProvider =
                mockk<LedgerAccountBindingProvider>(relaxed = true) {
                    every { observe(any()) } returns MutableStateFlow(null)
                }
            val ledger = ledgerAccount()

            dataSource(synchronizer, ledgerAccountBindingProvider).deleteAccount(ledger)

            coVerify(exactly = 1) { ledgerAccountBindingProvider.clear(accountUuid) }
        }

    @Test
    fun aBindingThatCannotBeClearedDoesNotFailAnAlreadyCompletedDeletion() =
        runBlocking {
            val synchronizer =
                mockk<Synchronizer>(relaxed = true) {
                    every { accountsFlow } returns MutableStateFlow(listOf(account))
                    every { walletBalances } returns MutableStateFlow(null)
                    coEvery { deleteAccount(any()) } returns true
                }
            val ledgerAccountBindingProvider =
                mockk<LedgerAccountBindingProvider>(relaxed = true) {
                    every { observe(any()) } returns MutableStateFlow(null)
                    coEvery { clear(any()) } throws RuntimeException("keystore unavailable")
                }

            dataSource(synchronizer, ledgerAccountBindingProvider).deleteAccount(ledgerAccount())

            coVerify(exactly = 1) { synchronizer.deleteAccount(accountUuid) }
        }

    @Test
    fun deletingASoftwareAccountNeverTouchesTheLedgerBindingStore() =
        runBlocking {
            val synchronizer =
                mockk<Synchronizer>(relaxed = true) {
                    every { accountsFlow } returns MutableStateFlow(listOf(account))
                    every { walletBalances } returns MutableStateFlow(null)
                    coEvery { deleteAccount(any()) } returns true
                }
            val ledgerAccountBindingProvider =
                mockk<LedgerAccountBindingProvider>(relaxed = true) {
                    every { observe(any()) } returns MutableStateFlow(null)
                }
            val zashi =
                ZodlAccount(
                    sdkAccount = account,
                    unifiedAddress = "u",
                    transparentAddress = "t",
                    saplingAddress = "s",
                    orchardBalance = null,
                    saplingBalance = null,
                    ironwoodBalance = null,
                    transparentBalance = null,
                    isSelected = false,
                )

            dataSource(synchronizer, ledgerAccountBindingProvider).deleteAccount(zashi)

            coVerify(exactly = 0) { ledgerAccountBindingProvider.clear(any()) }
        }

    @Test
    fun aPersistedSelectionOverridesStaleAccountRowsAtApprovalAndRevokesExistingApproval() = runTest {
        val second = Account.new(AccountUuid.new(ByteArray(16) { 7 }))
        val synchronizer = mockk<Synchronizer>(relaxed = true) {
            every { accountsFlow } returns MutableStateFlow(listOf(account))
            every { walletBalances } returns MutableStateFlow(null)
            coEvery { getAccounts() } returns listOf(account, second)
        }
        var stored: String? = null
        val key = PreferenceKey("selected_account_uuid")
        val preferences = mockk<PreferenceProvider>(relaxed = true) {
            coEvery { getString(key) } coAnswers { stored }
            coEvery { putStringWithRollback(key, any()) } coAnswers { stored = secondArg<String?>() }
        }
        val selection = SelectedAccountUUIDProviderImpl(
            mockk<EncryptedPreferenceProvider> { coEvery { this@mockk() } returns preferences }
        )
        selection.setUUID(accountUuid)
        val source = dataSource(synchronizer, mockk(relaxed = true), selection)
        val stale = ledgerAccount().copy(isSelected = true)
        val guard = assertNotNull(source.confirmSelection(stale))
        assertTrue(guard())

        selection.setUUID(second.accountUuid)

        assertFalse(guard())
        assertNull(source.confirmSelection(stale))
        assertTrue(assertNotNull(source.confirmSelection(stale.copy(sdkAccount = second)))())
    }

    private fun ledgerAccount() =
        LedgerAccount(
            sdkAccount = account,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = false,
            deviceIdentity = "tpk0-deadbeef",
            zip32AccountIndex = Zip32AccountIndex.new(0L),
        )

    private fun dataSource(
        synchronizer: Synchronizer,
        ledgerAccountBindingProvider: LedgerAccountBindingProvider,
        selection: SelectedAccountUUIDProvider = mockk {
            every { uuid } returns MutableStateFlow(null)
            coEvery { getUUID() } returns null
        },
    ): AccountDataSourceImpl {
        val synchronizerProvider =
            mockk<SynchronizerProvider> {
                every { this@mockk.synchronizer } returns MutableStateFlow(synchronizer)
                coEvery { getSynchronizer() } returns synchronizer
            }
        return AccountDataSourceImpl(
            walletTransport = WalletTransportAccess(),
            synchronizerProvider = synchronizerProvider,
            selectedAccountUUIDProvider = selection,
            persistableWalletProvider =
                mockk {
                    every { persistableWallet } returns MutableStateFlow(mockk(relaxed = true))
                },
            ledgerAccountBindingProvider = ledgerAccountBindingProvider,
            viewOnlyContent = viewOnlyContent,
            context = mockk(relaxed = true),
        )
    }
}
