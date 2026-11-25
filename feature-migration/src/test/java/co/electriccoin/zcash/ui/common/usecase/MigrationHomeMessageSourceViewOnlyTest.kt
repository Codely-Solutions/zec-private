package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.MigrationHomeMessageData
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPczts
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepositoryImpl
import co.electriccoin.zcash.ui.common.repository.PendingMigrationScheduleRepositoryImpl
import co.electriccoin.zcash.ui.screen.migration.restart.MigrationRestartArgs
import co.electriccoin.zcash.ui.screen.home.migration.MigrationMessageState
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledArgs
import kotlinx.coroutines.flow.first
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import cash.z.ecc.android.sdk.OrchardMigrationSdk
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.WalletSnapshotDataSource
import co.electriccoin.zcash.ui.common.model.WalletSnapshot
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_DUST_THRESHOLD_ZATOSHI
import co.electriccoin.zcash.ui.common.model.migration.MIGRATION_RESIDUAL_MIN_ZATOSHI
import co.electriccoin.zcash.ui.common.provider.HasSeenMigrationCompleteStorageProvider
import co.electriccoin.zcash.ui.common.provider.MigrationUpgradeNoticeStorageProvider
import io.mockk.coEvery
import kotlinx.coroutines.flow.flowOf
import kotlin.test.assertNull
import cash.z.ecc.android.sdk.model.AccountUuid
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.ViewOnlyAccount
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZodlAccount
import co.electriccoin.zcash.ui.common.repository.MigrationHomeMessage
import co.electriccoin.zcash.ui.design.util.stringRes
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A view-only account cannot migrate its funds, so the migration home banner stays empty for it and
 * the source never reads the engine's migration state on its behalf.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationHomeMessageSourceViewOnlyTest {
    private val sdkAccount = Account.new(AccountUuid.new(ByteArray(16) { it.toByte() }))

    private val observeMigrationLiveReadout = mockk<ObserveMigrationLiveReadoutUseCase>(relaxed = true)

    @Test
    fun aViewOnlyAccountGetsNoMigrationBanner() =
        runTest {
            val emissions = collectEmissions(source(viewOnly()))

            assertEquals(listOf(null), emissions)
            verify(exactly = 0) { observeMigrationLiveReadout(any()) }
        }

    @Test
    fun aSpendableAccountStillHasItsMigrationStateRead() =
        runTest {
            collectEmissions(source(zodl()))

            verify(exactly = 1) { observeMigrationLiveReadout(any()) }
        }

    @Test
    fun unavailableEligibilityDoesNotBecomeAnInvitationFromThePublishedBalance() = runTest {
        val native = mockk<OrchardMigrationSdk> {
            coEvery { migrationDustThresholdZatoshi() } returns MIGRATION_DUST_THRESHOLD_ZATOSHI
        }
        val sdk = mockk<GetOrchardMigrationSdkUseCase>()
        every { observeMigrationLiveReadout(any()) } returns flowOf(null)

        for (balance in listOf(MIGRATION_RESIDUAL_MIN_ZATOSHI + 1, MIGRATION_RESIDUAL_MIN_ZATOSHI - 1)) {
            val home = source(zodl(), sdk = sdk, balance = balance)
            coEvery { sdk(any<String>()) } returns null
            assertNull(home.observe().first())
            coEvery { sdk(any<String>()) } returns native
            coEvery { native.migratableOrchardTotal() } throws IllegalStateException("synthetic unavailable read")
            assertNull(home.observe().first())
            coEvery { native.migratableOrchardTotal() } returns balance
            val current = assertIs<MigrationHomeMessageData>(home.observe().first())
            assertEquals(balance < MIGRATION_RESIDUAL_MIN_ZATOSHI, current.isResidueOnly)
        }
    }

    @Test
    fun homeResumesASignedHandoffForItsOwnerWithoutReopeningAScanner() = runTest {
        val owner = zodl()
        val ownerKey = owner.sdkAccount.accountUuid.toStorageKeyId()
        val pending = PendingKeystoneMigrationPcztsRepositoryImpl()
        val batch = PendingKeystoneMigrationPczts(
            requestId = byteArrayOf(1),
            splitUnsignedPczt = null,
            transferUnsignedPczts = emptyList(),
            isFullySigned = true,
        )
        pending.set(ownerKey, batch)
        pending.set("different-owner", batch.copy(requestId = byteArrayOf(2)))
        val router = mockk<NavigationRouter>(relaxed = true)
        val home = source(owner, pending, router)

        val message = assertIs<MigrationHomeMessageData>(home.observe().first())
        assertEquals(ownerKey, message.accountKeyId)
        assertTrue(message.isAwaitingFinalization)
        val banner = assertIs<MigrationMessageState>(home.createMessageState(message))
        assertNotNull(banner.onClick).invoke()
        verify { router.forward(MigrationScheduledArgs(ownerKey)) }
        verify(exactly = 0) { observeMigrationLiveReadout(any()) }
    }

    @Test
    fun coldUnsignedMigrationOffersExplicitRecoveryForTheObservedOwner() {
        val owner = zodl()
        val ownerKey = owner.sdkAccount.accountUuid.toStorageKeyId()
        val router = mockk<NavigationRouter>(relaxed = true)
        val home = source(owner, router = router)
        val unsigned = MigrationHomeMessageData(
            isRunActive = true, accountKeyId = ownerKey, isAwaitingSignatures = true
        )

        val message = assertIs<MigrationMessageState>(home.createMessageState(unsigned))
        assertNotNull(message.onClick).invoke()

        verify(exactly = 1) { router.forward(MigrationRestartArgs(ownerKey)) }
    }

    @Test
    fun readyTransferNumberUsesItsIndexWhileEarlierTransfersStillConfirm() {
        val state = MigrationHomeMessageData(
            isRunActive = true, completedCount = 0, totalCount = 2,
            isReadyToSend = true, nextTransferIndex = 1,
        )
        val message = assertIs<MigrationMessageState>(source(zodl()).createMessageState(state))
        assertEquals("Transfer 2 is ready to send", message.progressLabel)
    }

    private fun TestScope.collectEmissions(source: MigrationHomeMessageSourceImpl): List<MigrationHomeMessage?> {
        val emissions = mutableListOf<MigrationHomeMessage?>()
        backgroundScope.launch { source.observe().toList(emissions) }
        runCurrent()
        return emissions
    }

    private fun source(
        account: WalletAccount,
        pending: PendingKeystoneMigrationPcztsRepositoryImpl = PendingKeystoneMigrationPcztsRepositoryImpl(),
        router: NavigationRouter = mockk(relaxed = true),
        sdk: GetOrchardMigrationSdkUseCase = mockk(relaxed = true),
        balance: Long = 0L,
    ) =
        MigrationHomeMessageSourceImpl(
            context = mockk(relaxed = true),
            accountDataSource =
                mockk<AccountDataSource> {
                    every { selectedAccount } returns MutableStateFlow(account)
                },
            getOrchardMigrationSdk = sdk,
            observeMigrationLiveReadout = observeMigrationLiveReadout,
            getOrchardBalance = mockk<GetOrchardBalanceUseCase> {
                every { observe(any()) } returns flowOf(Zatoshi(balance))
            },
            walletSnapshotDataSource = mockk<WalletSnapshotDataSource> {
                every { observe() } returns MutableStateFlow(mockk<WalletSnapshot> {
                    every { status } returns Synchronizer.Status.SYNCED
                })
            },
            hasSeenMigrationCompleteStorageProvider = mockk<HasSeenMigrationCompleteStorageProvider> {
                every { observe(any()) } returns flowOf(false)
            },
            isBackgroundExecutionAvailableProvider = mockk(relaxed = true),
            navigationRouter = router,
            migrationUpgradeNoticeStorageProvider = mockk<MigrationUpgradeNoticeStorageProvider> {
                every { observePendingForSelected() } returns flowOf(false)
            },
            pendingKeystone = pending,
            pendingSchedule = PendingMigrationScheduleRepositoryImpl(),
        )

    private fun zodl() =
        ZodlAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            saplingAddress = "s",
            orchardBalance = null,
            saplingBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = true,
        )

    private fun viewOnly() =
        ViewOnlyAccount(
            sdkAccount = sdkAccount,
            unifiedAddress = "u",
            transparentAddress = "t",
            orchardBalance = null,
            saplingBalance = null,
            ironwoodBalance = null,
            transparentBalance = null,
            isSelected = true,
            name = stringRes("Savings"),
            customName = "Savings",
            icon = 0,
        )
}
