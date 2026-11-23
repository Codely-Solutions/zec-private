package co.electriccoin.zcash.ui.common.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.exception.InitializeException
import cash.z.ecc.android.sdk.ledger.LedgerAccountPairing
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.preference.AndroidPreferenceProvider
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.AccountDataSourceImpl
import co.electriccoin.zcash.ui.common.model.LedgerPairingMissingException
import co.electriccoin.zcash.ui.common.provider.LedgerAccountBindingProvider
import co.electriccoin.zcash.ui.common.provider.SelectedAccountUUIDProviderImpl
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.LedgerPairingRepository
import co.electriccoin.zcash.ui.common.repository.LedgerPairingRepositoryImpl
import co.electriccoin.zcash.ui.screen.connectledger.connected.LedgerConnectedArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenArgs
import co.electriccoin.zcash.ui.screen.keepopen.KeepOpenFlow
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A pairing with no birthday imports from the chain tip and goes straight to the success screen; a
 * pairing with one has history to scan and detours through the "keep Zodl open" screen first.
 */
class CreateLedgerAccountUseCaseTest {
    private val createdAccount = Account.new(AccountUuid.new(ByteArray(16) { it.toByte() }))

    @Test
    fun aNewDeviceImportsFromTheTipAndGoesStraightToTheSuccessScreen() =
        runTest {
            val navigationRouter = mockk<NavigationRouter>(relaxed = true)
            val accountDataSource = accountDataSource()
            val ledgerPairingRepository = pairingRepository()
            val useCase = useCase(accountDataSource, ledgerPairingRepository, navigationRouter)

            useCase(birthday = null)

            coVerify(exactly = 1) { accountDataSource.importLedgerAccount(any(), null) }
            coVerify(exactly = 1) { accountDataSource.selectAccount(createdAccount) }
            verify(exactly = 1) { ledgerPairingRepository.clear(any()) }
            verify(exactly = 1) { navigationRouter.forward(LedgerConnectedArgs) }
        }

    @Test
    fun anActiveDeviceImportsFromItsBirthdayAndDetoursThroughKeepOpen() =
        runTest {
            val navigationRouter = mockk<NavigationRouter>(relaxed = true)
            val accountDataSource = accountDataSource()
            val birthday = BlockHeight.new(2_500_000L)
            val useCase = useCase(accountDataSource, pairingRepository(), navigationRouter)

            useCase(birthday = birthday)

            coVerify(exactly = 1) { accountDataSource.importLedgerAccount(any(), birthday) }
            verify(exactly = 1) { navigationRouter.forward(KeepOpenArgs(KeepOpenFlow.LEDGER)) }
        }

    @Test
    fun aMissingPairingFailsInsteadOfImportingNothing() =
        runTest {
            val accountDataSource = accountDataSource()
            val ledgerPairingRepository =
                mockk<LedgerPairingRepository>(relaxed = true) {
                    every { get() } returns null
                }
            val useCase = useCase(accountDataSource, ledgerPairingRepository, mockk(relaxed = true))

            val thrown = assertFailsWith<LedgerPairingMissingException> { useCase(birthday = null) }

            assertTrue(thrown.message.orEmpty().contains("paired again"))
            coVerify(exactly = 0) { accountDataSource.importLedgerAccount(any(), any()) }
        }

    @Test
    fun aReImportCollisionPropagatesWithoutSelectingOrMovingOn() =
        runTest {
            val navigationRouter = mockk<NavigationRouter>(relaxed = true)
            val accountDataSource =
                mockk<AccountDataSource>(relaxed = true) {
                    coEvery { importLedgerAccount(any(), any()) } throws
                        InitializeException.ImportAccountCollisionException(null)
                }
            val ledgerPairingRepository = pairingRepository()
            val useCase = useCase(accountDataSource, ledgerPairingRepository, navigationRouter)

            assertFailsWith<InitializeException.ImportAccountCollisionException> { useCase(birthday = null) }

            coVerify(exactly = 0) { accountDataSource.selectAccount(any<Account>()) }
            verify(exactly = 0) { ledgerPairingRepository.clear(any()) }
            verify(exactly = 0) { navigationRouter.forward(*anyVararg()) }
        }

    @Test
    fun selectionRetryUsesTheImportedAccountAndKeepsItsLedgerBinding() = runBlocking {
        val pairing = mockk<LedgerAccountPairing>(relaxed = true) {
            every { binding } returns mockk {
                every { deviceIdentity } returns mockk { every { encoding } returns "tpk0-synthetic" }
                every { zip32AccountIndex } returns Zip32AccountIndex.new(4L)
            }
        }
        val pairings = LedgerPairingRepositoryImpl().apply { set(pairing) }
        val nativeSync = mockk<Synchronizer>(relaxed = true) {
            coEvery { importAccountByUfvk(any()) } returns createdAccount
        }
        val synchronizerProvider = mockk<SynchronizerProvider>(relaxed = true) {
            every { synchronizer } returns MutableStateFlow(null)
            coEvery { getSynchronizer() } returns nativeSync
        }
        val binding = mockk<LedgerAccountBindingProvider>(relaxed = true)
        val previous = AccountUuid.new(ByteArray(16) { 9 })
        val previousEncoding = Base64.getEncoder().encodeToString(previous.value)
        val storage = PublishingPreferenceFixture(previousEncoding)
        val androidPreferences = AndroidPreferenceProvider(storage.preferences, Dispatchers.Unconfined)
        val selected = SelectedAccountUUIDProviderImpl(mockk<EncryptedPreferenceProvider> {
            coEvery { this@mockk.invoke() } returns androidPreferences
        })
        val accountDataSource = AccountDataSourceImpl(
            synchronizerProvider = synchronizerProvider,
            selectedAccountUUIDProvider = selected,
            persistableWalletProvider = mockk {
                every { persistableWallet } returns MutableStateFlow(null)
            },
            ledgerAccountBindingProvider = binding,
            viewOnlyContent = mockk(relaxed = true),
            context = mockk(relaxed = true),
        )
        val router = mockk<NavigationRouter>(relaxed = true)
        val useCase = CreateLedgerAccountUseCase(accountDataSource, pairings, synchronizerProvider, router)
        val approvedBirthday = BlockHeight.new(2_500_000L)

        storage.failNextCommit = true
        assertFailsWith<IOException> { useCase(approvedBirthday) }
        assertEquals(previousEncoding, storage.durable)
        assertEquals(previous, selected.getUUID())
        assertSame(pairing, pairings.get())
        coVerify(exactly = 1) { binding.save(createdAccount.accountUuid, "tpk0-synthetic", 4L) }
        verify(exactly = 0) { synchronizerProvider.resetSynchronizer() }
        verify(exactly = 0) { router.forward(*anyVararg()) }

        useCase(birthday = null)
        useCase(BlockHeight.new(2_000_000L))
        assertSame(pairing, pairings.get())
        verify(exactly = 0) { synchronizerProvider.resetSynchronizer() }

        useCase(approvedBirthday)

        assertEquals(createdAccount.accountUuid, selected.getUUID())
        assertEquals(Base64.getEncoder().encodeToString(createdAccount.accountUuid.value), storage.durable)
        assertNull(pairings.get())
        coVerify(exactly = 1) { nativeSync.importAccountByUfvk(any()) }
        coVerify(exactly = 1) { binding.save(createdAccount.accountUuid, "tpk0-synthetic", 4L) }
        coVerify(exactly = 0) { binding.clear(any()) }
        coVerify(exactly = 0) { nativeSync.deleteAccount(any()) }
        verify(exactly = 1) { synchronizerProvider.resetSynchronizer() }
        verify(exactly = 1) { router.forward(KeepOpenArgs(KeepOpenFlow.LEDGER)) }
    }

    @Test
    fun retryingARestartFailureFromTheTipKeepsTheCommittedBirthday() = runTest {
        val accounts = accountDataSource()
        val pairings = LedgerPairingRepositoryImpl().apply { set(mockk()) }
        val synchronizer = mockk<SynchronizerProvider>(relaxed = true) {
            every { resetSynchronizer() } throws IOException() andThen Unit
        }
        val router = mockk<NavigationRouter>(relaxed = true)
        val useCase = CreateLedgerAccountUseCase(accounts, pairings, synchronizer, router)
        val historicalHeight = BlockHeight.new(2_000_000L)

        assertFailsWith<IOException> { useCase() }
        useCase(historicalHeight)
        assertSame(createdAccount, pairings.imported.value?.account)
        assertNull(pairings.imported.value?.birthday)
        verify(exactly = 0) { router.forward(*anyVararg()) }

        useCase()

        coVerify(exactly = 1) { accounts.importLedgerAccount(any(), null) }
        coVerify(exactly = 0) { accounts.importLedgerAccount(any(), historicalHeight) }
        coVerify(exactly = 2) { accounts.selectAccount(createdAccount) }
        verify(exactly = 2) { synchronizer.resetSynchronizer() }
        verify(exactly = 1) { router.forward(LedgerConnectedArgs) }
    }

    private fun accountDataSource() =
        mockk<AccountDataSource>(relaxed = true) {
            coEvery { importLedgerAccount(any(), any()) } returns createdAccount
        }

    private fun pairingRepository() =
        mockk<LedgerPairingRepository>(relaxed = true) {
            every { get() } returns mockk<LedgerAccountPairing>(relaxed = true)
            every { importedAccount(any()) } returns null
            every { recordImport(any(), any()) } returns true
            every { clear(any()) } returns true
        }

    private fun useCase(
        accountDataSource: AccountDataSource,
        ledgerPairingRepository: LedgerPairingRepository,
        navigationRouter: NavigationRouter,
    ) = CreateLedgerAccountUseCase(
        accountDataSource = accountDataSource,
        ledgerPairingRepository = ledgerPairingRepository,
        synchronizerProvider = mockk<SynchronizerProvider>(relaxed = true),
        navigationRouter = navigationRouter,
    )
}
