package co.electriccoin.zcash.ui.common.usecase

import android.content.SharedPreferences

import cash.z.ecc.android.sdk.ledger.LedgerAccountBinding
import cash.z.ecc.android.sdk.ledger.LedgerAccountPairing
import cash.z.ecc.android.sdk.ledger.LedgerBluetoothDevice
import cash.z.ecc.android.sdk.ledger.LedgerDeviceIdentity
import cash.z.ecc.android.sdk.ledger.LedgerDeviceModel
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import co.electriccoin.zcash.preference.AndroidPreferenceProvider
import co.electriccoin.zcash.preference.EncryptedPreferenceProvider
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.LedgerDeviceDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.LedgerAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.LedgerAccountBindingProvider
import co.electriccoin.zcash.ui.common.provider.LedgerAccountBindingProviderImpl
import co.electriccoin.zcash.ui.common.repository.LedgerPairingRepository
import co.electriccoin.zcash.ui.common.repository.LedgerPairingRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.LedgerRepairTargetRepository
import co.electriccoin.zcash.ui.common.repository.LedgerRepairTargetRepositoryImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pairing stashes the result for the birthday screens, unless the wallet already holds an account
 * for that viewing key — in which case nothing is stashed, and the caller is told to offer the
 * existing account instead, or, when that account has lost its Ledger binding, the pairing's binding
 * is stored with it. Pairing an account again, and pairing a second Ledger, report any other viewing
 * key as the wrong Ledger and import nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairLedgerDeviceUseCaseTest {
    private val enrollment = LedgerEnrollmentFixture()
    private val bindingProvider = mockk<LedgerAccountBindingProvider>(relaxed = true)

    private val firstAccount = Zip32AccountIndex.new(0)

    private val device =
        LedgerBluetoothDevice(
            model = LedgerDeviceModel.NANO_X,
            name = "Ledger Device",
            identifier = "AA:BB",
            rssi = -40,
        )

    @Test
    fun aFreshDeviceHasItsPairingStashedForTheBirthdayScreens() =
        runTest {
            val pairing = pairing(ufvk = "ufvk-new")
            val repository = LedgerPairingRepositoryImpl()

            val result = useCase(pairing, repository, existing = emptyList()).invoke(device, firstAccount)

            assertTrue(result is PairLedgerDeviceResult.Paired)
            assertSame(pairing, repository.get())
        }

    @Test
    fun aDeviceWhoseKeyIsAlreadyImportedReportsTheExistingAccountAndStashesNothing() =
        runTest {
            val pairing = pairing(ufvk = "ufvk-known")
            val alreadyAdded = ledgerAccount(ufvk = "ufvk-known", bound = true)
            val repository = LedgerPairingRepositoryImpl()

            val result = useCase(pairing, repository, existing = listOf(alreadyAdded)).invoke(device, firstAccount)

            assertEquals(PairLedgerDeviceResult.AlreadyAdded(alreadyAdded), result)
            assertNull(repository.get())
            coVerify(exactly = 0) { bindingProvider.save(any(), any(), any()) }
        }

    @Test
    fun anImportedAccountWithoutABindingIsReboundToTheDeviceAndStashesNothing() =
        runTest {
            val pairing = pairing(ufvk = "ufvk-known")
            val unbound = ledgerAccount(ufvk = "ufvk-known", bound = false)
            val repository = LedgerPairingRepositoryImpl()

            val result = useCase(pairing, repository, existing = listOf(unbound)).invoke(device, firstAccount)

            assertEquals(PairLedgerDeviceResult.Rebound(unbound), result)
            assertNull(repository.get())
            val accountUuid = unbound.sdkAccount.accountUuid
            coVerify(exactly = 1) {
                bindingProvider.save(
                    accountUuid = accountUuid,
                    deviceIdentityEncoding = IDENTITY,
                    zip32AccountIndex = 0L,
                )
            }
        }

    @Test
    fun aKeystoneAccountWithTheSameKeyIsNotADuplicateLedger() =
        runTest {
            val pairing = pairing(ufvk = "ufvk-shared")
            val keystone =
                KeystoneAccount(
                    sdkAccount = sdkAccount(ufvk = "ufvk-shared"),
                    unifiedAddress = "u",
                    transparentAddress = "t",
                    orchardBalance = null,
                    ironwoodBalance = null,
                    transparentBalance = null,
                    isSelected = false,
                )
            val repository = LedgerPairingRepositoryImpl()

            val result = useCase(pairing, repository, existing = listOf(keystone)).invoke(device, firstAccount)

            assertTrue(result is PairLedgerDeviceResult.Paired)
        }

    @Test
    fun aSecondLedgerIsTheWrongLedgerAndStashesNothing() =
        runTest {
            val pairing = pairing(ufvk = "ufvk-second")
            val first = ledgerAccount(ufvk = "ufvk-first", bound = true)
            val repository = LedgerPairingRepositoryImpl()

            val result = useCase(pairing, repository, existing = listOf(first)).invoke(device, firstAccount)

            assertEquals(PairLedgerDeviceResult.WrongLedger, result)
            assertNull(repository.get())
            coVerify(exactly = 0) { bindingProvider.save(any(), any(), any()) }
        }

    @Test
    fun pairingTheTargetAgainWithItsOwnLedgerRebindsItAndClearsTheTarget() =
        runTest {
            val pairing = pairing(ufvk = "ufvk-known")
            val target = ledgerAccount(ufvk = "ufvk-known", bound = false)
            val repository = LedgerPairingRepositoryImpl()
            val repairTarget = repairTarget(target)

            val result =
                useCase(pairing, repository, existing = listOf(target), repairTarget = repairTarget)
                    .invoke(device, firstAccount)

            assertEquals(PairLedgerDeviceResult.Rebound(target), result)
            assertNull(repository.get())
            assertNull(repairTarget.get())
            val accountUuid = target.sdkAccount.accountUuid
            coVerify(exactly = 1) {
                bindingProvider.save(
                    accountUuid = accountUuid,
                    deviceIdentityEncoding = IDENTITY,
                    zip32AccountIndex = 0L,
                )
            }
        }

    @Test
    fun pairingABoundTargetAgainReplacesItsBindingBecauseTheStoredOneMayBeUnusable() =
        runTest {
            val pairing = pairing(ufvk = "ufvk-known")
            val target = ledgerAccount(ufvk = "ufvk-known", bound = true)
            val repository = LedgerPairingRepositoryImpl()

            val result =
                useCase(pairing, repository, existing = listOf(target), repairTarget = repairTarget(target))
                    .invoke(device, firstAccount)

            assertEquals(PairLedgerDeviceResult.Rebound(target), result)
            assertNull(repository.get())
            val accountUuid = target.sdkAccount.accountUuid
            coVerify(exactly = 1) {
                bindingProvider.save(
                    accountUuid = accountUuid,
                    deviceIdentityEncoding = IDENTITY,
                    zip32AccountIndex = 0L,
                )
            }
        }

    @Test
    fun aFailedWriteToAnAlreadyBoundAccountCanBeRetried() = runTest {
        val pairing = pairing(ufvk = "ufvk-known")
        val target = ledgerAccount(ufvk = "ufvk-known", bound = true)
        val accountUuid = target.sdkAccount.accountUuid
        val repository = LedgerPairingRepositoryImpl()
        val repairTarget = repairTarget(target)
        val useCase = useCase(pairing, repository, existing = listOf(target), repairTarget = repairTarget)
        coEvery { bindingProvider.save(any(), any(), any()) } throws IOException() andThen Unit

        assertFailsWith<IOException> { useCase(device, firstAccount) }

        assertEquals(accountUuid, repairTarget.get())
        assertEquals(PairLedgerDeviceResult.Rebound(target), useCase(device, firstAccount))
        assertNull(repairTarget.get())
        coVerify(exactly = 2) { bindingProvider.save(accountUuid, IDENTITY, 0L) }
    }

    @Test
    fun aFailedAndroidCommitKeepsThePreviousBindingInMemoryAndOnDiskUntilRetry() = runTest {
        val storage = PublishingPreferenceFixture("tpk0-previous|3")
        val android = AndroidPreferenceProvider(storage.preferences, StandardTestDispatcher(testScheduler))
        val provider = LedgerAccountBindingProviderImpl(mockk<EncryptedPreferenceProvider> {
            coEvery { this@mockk.invoke() } returns android
        })
        val target = ledgerAccount(ufvk = "ufvk-known", bound = true)
        val accountUuid = target.sdkAccount.accountUuid
        val pairing = pairing(ufvk = "ufvk-known", zip32AccountIndex = 4L)
        val repair = repairTarget(target)
        val useCase = useCase(dataSource(pairing), LedgerPairingRepositoryImpl(), listOf(target), repair, provider)
        val observed = mutableListOf<String>()
        backgroundScope.launch {
            provider.observe(accountUuid).collect { binding ->
                binding?.let { observed.add(it.deviceIdentityEncoding) }
            }
        }
        runCurrent()

        storage.failNextCommit = true
        assertFailsWith<IOException> { useCase(device, firstAccount) }
        runCurrent()

        assertEquals(accountUuid, repair.get())
        assertEquals("tpk0-previous|3", storage.durable)
        assertEquals("tpk0-previous", provider.observe(accountUuid).first()?.deviceIdentityEncoding)
        assertEquals(listOf("tpk0-previous"), observed.distinct())

        assertEquals(PairLedgerDeviceResult.Rebound(target), useCase(device, firstAccount))
        runCurrent()
        assertEquals("$IDENTITY|4", storage.durable)
        assertEquals(IDENTITY, provider.observe(accountUuid).first()?.deviceIdentityEncoding)
        assertEquals(listOf("tpk0-previous", IDENTITY), observed.distinct())
        assertNull(repair.get())
    }

    @Test
    fun anEnteredRepairCannotRetireANewerRepairOfTheSameAccount() = runTest {
        val pairing = pairing(ufvk = "ufvk-known")
        val target = ledgerAccount(ufvk = "ufvk-known", bound = true)
        val repair = repairTarget(target)
        val useCase = useCase(pairing, LedgerPairingRepositoryImpl(), listOf(target), repair)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { bindingProvider.save(any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }

        val oldAttempt = async { useCase(device, firstAccount) }
        entered.await()
        repair.set(target.sdkAccount.accountUuid, target.zip32AccountIndex, enrollment.owner)
        val newer = assertNotNull(repair.current())
        release.complete(Unit)

        assertEquals(PairLedgerDeviceResult.Superseded, oldAttempt.await())
        assertSame(newer, repair.current())
        assertEquals(PairLedgerDeviceResult.Rebound(target), useCase(device, firstAccount))
        assertNull(repair.get())
    }

    @Test
    fun aSupersededBindingFailureLeavesTheNewRepairInControl() = runTest {
        val pairing = pairing(ufvk = "ufvk-known")
        val target = ledgerAccount(ufvk = "ufvk-known", bound = true)
        val repair = repairTarget(target)
        val useCase = useCase(pairing, LedgerPairingRepositoryImpl(), listOf(target), repair)
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { bindingProvider.save(any(), any(), any()) } coAnswers {
            writing.complete(Unit)
            release.await()
            throw IOException("Binding write failed")
        }

        val superseded = async { useCase(device, firstAccount) }
        writing.await()
        repair.set(target.sdkAccount.accountUuid, target.zip32AccountIndex, enrollment.owner)
        val newer = assertNotNull(repair.current())
        release.complete(Unit)

        assertEquals(PairLedgerDeviceResult.Superseded, superseded.await())
        assertSame(newer, repair.current())

        coEvery { bindingProvider.save(any(), any(), any()) } returns Unit
        assertEquals(PairLedgerDeviceResult.Rebound(target), useCase(device, firstAccount))
        assertNull(repair.get())
    }

    @Test
    fun pairingTheTargetAgainWithAnotherLedgerIsTheWrongLedgerAndImportsNothing() =
        runTest {
            val target = ledgerAccount(ufvk = "ufvk-known", bound = false)
            listOf(listOf(target), emptyList()).forEach { existing ->
                val repository = LedgerPairingRepositoryImpl()
                val repairTarget = repairTarget(target)

                val result =
                    useCase(pairing(ufvk = "ufvk-other"), repository, existing, repairTarget)
                        .invoke(device, firstAccount)

                assertEquals(PairLedgerDeviceResult.WrongLedger, result, "existing=${existing.size}")
                assertNull(repository.get())
                assertEquals(target.sdkAccount.accountUuid, repairTarget.get(), "Try again keeps the target")
            }
            coVerify(exactly = 0) { bindingProvider.save(any(), any(), any()) }
        }

    @Test
    fun theAccountTheUserChoseIsTheOneExported() =
        runTest {
            val dataSource = dataSource(pairing(ufvk = "ufvk-new"))

            useCase(dataSource, LedgerPairingRepositoryImpl(), existing = emptyList())
                .invoke(device, Zip32AccountIndex.new(7))

            coVerify(exactly = 1) { dataSource.pair(device, Zip32AccountIndex.new(7)) }
        }

    /**
     * Pairing an account again exports whichever account the user chose; an index whose viewing key
     * is not the target's is the wrong Ledger, as for any other device.
     */
    @Test
    fun pairingAnAccountAgainExportsTheChosenAccountAndAMismatchIsTheWrongLedger() =
        runTest {
            val target = ledgerAccount(ufvk = "ufvk-known", bound = false, zip32AccountIndex = null)
            val dataSource = dataSource(pairing(ufvk = "ufvk-index-4"))
            val repository = LedgerPairingRepositoryImpl()

            val result =
                useCase(dataSource, repository, listOf(target), repairTarget(target))
                    .invoke(device, Zip32AccountIndex.new(4))

            coVerify(exactly = 1) { dataSource.pair(device, Zip32AccountIndex.new(4)) }
            assertEquals(PairLedgerDeviceResult.WrongLedger, result)
            assertNull(repository.get())
            coVerify(exactly = 0) { bindingProvider.save(any(), any(), any()) }
        }

    /**
     * The binding stored when an account is paired again carries the index the device exported, not
     * the one the account had before.
     */
    @Test
    fun pairingAnAccountAgainStoresTheIndexItWasPairedAt() =
        runTest {
            val target = ledgerAccount(ufvk = "ufvk-known", bound = false, zip32AccountIndex = null)
            val dataSource = dataSource(pairing(ufvk = "ufvk-known", zip32AccountIndex = 4L))

            val result =
                useCase(dataSource, LedgerPairingRepositoryImpl(), listOf(target), repairTarget(target))
                    .invoke(device, Zip32AccountIndex.new(4))

            assertEquals(PairLedgerDeviceResult.Rebound(target), result)
            val accountUuid = target.sdkAccount.accountUuid
            coVerify(exactly = 1) {
                bindingProvider.save(
                    accountUuid = accountUuid,
                    deviceIdentityEncoding = IDENTITY,
                    zip32AccountIndex = 4L,
                )
            }
        }

    /**
     * A viewing key that belongs to a Ledger account other than the one being paired again is still
     * the wrong Ledger; neither account's binding is touched.
     */
    @Test
    fun pairingAnAccountAgainWithAnotherAccountsKeyIsTheWrongLedger() =
        runTest {
            val target = ledgerAccount(ufvk = "ufvk-target", bound = false)
            val other = ledgerAccount(ufvk = "ufvk-other", bound = true)
            val repairTarget = repairTarget(target)
            val existing = listOf(target, other)

            val result =
                useCase(pairing(ufvk = "ufvk-other"), LedgerPairingRepositoryImpl(), existing, repairTarget)
                    .invoke(device, Zip32AccountIndex.new(1))

            assertEquals(PairLedgerDeviceResult.WrongLedger, result)
            assertEquals(target.sdkAccount.accountUuid, repairTarget.get())
            coVerify(exactly = 0) { bindingProvider.save(any(), any(), any()) }
        }

    @Test
    fun aFreshPairingIsStashedWithTheIndexItWasPairedAt() =
        runTest {
            val pairing = pairing(ufvk = "ufvk-new", zip32AccountIndex = 7L)
            val repository = LedgerPairingRepositoryImpl()

            useCase(pairing, repository, existing = emptyList()).invoke(device, Zip32AccountIndex.new(7))

            assertEquals(Zip32AccountIndex.new(7), repository.get()?.binding?.zip32AccountIndex)
        }

    @Test
    fun anEarlierPairingIsReplacedRatherThanKept() =
        runTest {
            val repository = LedgerPairingRepositoryImpl()
            repository.set(pairing(ufvk = "ufvk-stale"), enrollment.owner)
            val fresh = pairing(ufvk = "ufvk-fresh")

            useCase(fresh, repository, existing = emptyList()).invoke(device, firstAccount)

            assertSame(fresh, repository.get())
        }

    @Test
    fun aDelayedRepairCannotBindAnAccountAfterWalletReplacement() = runTest {
        val remaining = CompletableDeferred<LedgerAccountPairing>()
        val dataSource = mockk<LedgerDeviceDataSource> {
            coEvery { pair(any(), any()) } coAnswers { remaining.await() }
        }
        val existing = ledgerAccount("ufvk-existing", bound = true)
        val repository = LedgerPairingRepositoryImpl()
        val result = async {
            useCase(dataSource, repository, listOf(existing), repairTarget(existing))(device, firstAccount)
        }
        runCurrent()
        enrollment.replaceWallet()
        remaining.complete(pairing("ufvk-existing"))

        assertEquals(PairLedgerDeviceResult.Superseded, result.await())
        assertNull(repository.get())
        coVerify(exactly = 0) { bindingProvider.save(any(), any(), any()) }
    }

    private fun useCase(
        pairing: LedgerAccountPairing,
        repository: LedgerPairingRepository,
        existing: List<WalletAccount>,
        repairTarget: LedgerRepairTargetRepository = LedgerRepairTargetRepositoryImpl(),
    ) = useCase(dataSource(pairing), repository, existing, repairTarget)

    private fun useCase(
        dataSource: LedgerDeviceDataSource,
        repository: LedgerPairingRepository,
        existing: List<WalletAccount>,
        repairTarget: LedgerRepairTargetRepository = LedgerRepairTargetRepositoryImpl(),
        provider: LedgerAccountBindingProvider = bindingProvider,
    ): PairLedgerDeviceUseCase {
        coEvery { enrollment.sdk.getAccounts() } returns existing.map { it.sdkAccount }
        return PairLedgerDeviceUseCase(
            ledgerDeviceDataSource = dataSource,
            ledgerPairingRepository = repository,
            ledgerRepairTargetRepository = repairTarget,
            accountDataSource = mockk<AccountDataSource> {
                coEvery { getAllAccounts() } returns existing
            },
            ledgerAccountBindingProvider = provider,
            walletEnrollment = enrollment.access,
        )
    }

    private fun dataSource(pairing: LedgerAccountPairing) =
        mockk<LedgerDeviceDataSource> {
            coEvery { pair(any(), any()) } returns pairing
        }

    private fun pairing(
        ufvk: String,
        zip32AccountIndex: Long = 0L,
    ) =
        mockk<LedgerAccountPairing> {
            every { this@mockk.ufvk } returns UnifiedFullViewingKey(ufvk)
            every { binding } returns
                LedgerAccountBinding(
                    deviceIdentity = mockk<LedgerDeviceIdentity> { every { encoding } returns IDENTITY },
                    zip32AccountIndex = Zip32AccountIndex.new(zip32AccountIndex),
                )
        }

    private fun repairTarget(account: LedgerAccount) =
        LedgerRepairTargetRepositoryImpl().apply {
            set(account.sdkAccount.accountUuid, account.zip32AccountIndex, enrollment.owner)
        }

    /**
     * Each viewing key gets its own UUID, so accounts with different keys are different accounts.
     */
    private fun sdkAccount(ufvk: String) =
        mockk<Account> {
            every { accountUuid } returns AccountUuid.new(ByteArray(16) { (it + ufvk.hashCode()).toByte() })
            every { this@mockk.ufvk } returns ufvk
        }

    private fun ledgerAccount(
        ufvk: String,
        bound: Boolean,
        zip32AccountIndex: Zip32AccountIndex? = Zip32AccountIndex.new(0L),
    ) =
        LedgerAccount(
            sdkAccount = sdkAccount(ufvk),
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = false,
            deviceIdentity = if (bound) IDENTITY else null,
            zip32AccountIndex = zip32AccountIndex,
        )
}

private const val IDENTITY = "tpk0-identity"

internal class PublishingPreferenceFixture(initial: String) {
    var durable: String? = initial
        private set
    var failNextCommit = false
    private var cached: String? = initial
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    val preferences = mockk<SharedPreferences> {
        every { getString(any(), null) } answers { cached }
        every { registerOnSharedPreferenceChangeListener(any()) } answers { listeners.add(firstArg()) }
        every { unregisterOnSharedPreferenceChangeListener(any()) } answers { listeners.remove(firstArg()) }
    }

    init {
        var key: String? = null
        var staged: String? = null
        val editor = mockk<SharedPreferences.Editor> {
            every { putString(any(), any()) } answers {
                key = firstArg()
                staged = secondArg()
                this@mockk
            }
            every { commit() } answers {
                cached = staged
                listeners.forEach { it.onSharedPreferenceChanged(preferences, checkNotNull(key)) }
                val fail = failNextCommit
                failNextCommit = false
                if (!fail) durable = staged
                !fail
            }
        }
        every { preferences.edit() } returns editor
    }
}
