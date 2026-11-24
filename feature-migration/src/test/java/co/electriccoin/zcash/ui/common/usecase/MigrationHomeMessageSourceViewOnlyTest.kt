package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.toStorageKeyId
import co.electriccoin.zcash.ui.common.repository.MigrationHomeMessageData
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPczts
import co.electriccoin.zcash.ui.common.repository.PendingKeystoneMigrationPcztsRepositoryImpl
import co.electriccoin.zcash.ui.screen.home.migration.MigrationMessageState
import co.electriccoin.zcash.ui.screen.migration.scheduled.MigrationScheduledArgs
import kotlinx.coroutines.flow.first
import kotlin.test.assertIs
import kotlin.test.assertTrue
import cash.z.ecc.android.sdk.model.Account
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
        assertIs<MigrationMessageState>(home.createMessageState(message)).onClick()
        verify { router.forward(MigrationScheduledArgs(ownerKey)) }
        verify(exactly = 0) { observeMigrationLiveReadout(any()) }
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
    ) =
        MigrationHomeMessageSourceImpl(
            context = mockk(relaxed = true),
            accountDataSource =
                mockk<AccountDataSource> {
                    every { selectedAccount } returns MutableStateFlow(account)
                },
            getOrchardMigrationSdk = mockk(relaxed = true),
            observeMigrationLiveReadout = observeMigrationLiveReadout,
            getOrchardBalance = mockk(relaxed = true),
            walletSnapshotDataSource = mockk(relaxed = true),
            hasSeenMigrationCompleteStorageProvider = mockk(relaxed = true),
            isBackgroundExecutionAvailableProvider = mockk(relaxed = true),
            navigationRouter = router,
            migrationUpgradeNoticeStorageProvider = mockk(relaxed = true),
            pendingKeystone = pending,
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
